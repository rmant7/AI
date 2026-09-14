package ai.localstudio.app

import android.content.Context
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.Transcript
import ai.localstudio.core.model.TranscriptSegment
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.SpeechModelHandle
import ai.localstudio.core.runtime.StreamingSpeechSession
import ai.localstudio.core.runtime.TextModelHandle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A runtime with no weights and no network.
 *
 * It exists so a freshly installed app does something real before any model is
 * configured: the request still goes through the router, the pipeline builder,
 * the validator, model selection, memory and the context engine — only the
 * final token generation is replaced by a description of what the model would
 * have received. That makes the architecture visible instead of a blank screen
 * with a connection error.
 */
class StubRuntime(private val context: Context) : ModelRuntime {

    override val kind: RuntimeKind = RuntimeKind.STUB

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean =
        binding.runtime == RuntimeKind.STUB

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel = when {
        Capability.SPEECH_TO_TEXT in model.capabilities -> StubSpeechModel(model.id, context)
        Capability.TEXT_GENERATION in model.capabilities -> StubTextModel(model.id, context)
        else -> throw ModelLoadException("The stub runtime has nothing for ${model.id}")
    }

    private class StubTextModel(override val modelId: String, private val context: Context) : TextModelHandle {

        override val ramBytes: Long = 0

        override fun generate(request: GenerationRequest): Flow<String> = flow {
            val sections = request.prompt.lines()
                .filter { it.startsWith("## ") }
                .map { it.removePrefix("## ") }

            emit(context.getString(R.string.stub_demo_intro))
            emit(context.getString(R.string.stub_demo_sections, sections.size))
            if (sections.isNotEmpty()) emit(" (" + sections.joinToString(", ") + ")")
            emit(".\n\n")
            emit(context.getString(R.string.stub_demo_hint))
        }

        override fun requestCancel() = Unit

        override fun close() = Unit
    }

    private class StubSpeechModel(override val modelId: String, private val context: Context) : SpeechModelHandle {

        override val ramBytes: Long = 0

        override suspend fun transcribe(audio: AudioRef, language: String?): Transcript =
            Transcript(text = context.getString(R.string.stub_demo_transcript), language = language ?: "en", confidence = 0.0)

        override fun startStreaming(language: String?): StreamingSpeechSession =
            object : StreamingSpeechSession {
                private val channel = Channel<TranscriptSegment>(Channel.UNLIMITED)
                override val segments: Flow<TranscriptSegment> = channel.receiveAsFlow()

                override fun acceptAudio(pcm: ShortArray) = Unit

                override fun finish() {
                    channel.trySend(
                        TranscriptSegment(text = context.getString(R.string.stub_demo_transcript), startMs = 0, endMs = 0),
                    )
                    channel.close()
                }

                override fun cancel() {
                    channel.close()
                }
            }

        override fun requestCancel() = Unit

        override fun close() = Unit
    }
}
