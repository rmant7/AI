package ai.localstudio.app

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.Transcript
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.SpeechModelHandle
import ai.localstudio.core.runtime.TextModelHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
class StubRuntime : ModelRuntime {

    override val kind: RuntimeKind = RuntimeKind.STUB

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean =
        binding.runtime == RuntimeKind.STUB

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel = when {
        Capability.SPEECH_TO_TEXT in model.capabilities -> StubSpeechModel(model.id)
        Capability.TEXT_GENERATION in model.capabilities -> StubTextModel(model.id)
        else -> throw ModelLoadException("The stub runtime has nothing for ${model.id}")
    }

    private class StubTextModel(override val modelId: String) : TextModelHandle {

        override val ramBytes: Long = 0

        override fun generate(request: GenerationRequest): Flow<String> = flow {
            val sections = request.prompt.lines()
                .filter { it.startsWith("[") && it.endsWith("]") }
                .map { it.trim('[', ']') }

            emit("Демонстрационный режим: модель не подключена.\n\n")
            emit("Запрос прошёл всю систему, и в контекст попало ${sections.size} секций")
            if (sections.isNotEmpty()) emit(": " + sections.joinToString(", "))
            emit(".\n\n")
            emit("Подключите Ollama или llama-server в настройках — маршрут, ")
            emit("сборка контекста и память останутся ровно теми же, изменится только ")
            emit("то, кто генерирует ответ.")
        }

        override fun requestCancel() = Unit

        override fun close() = Unit
    }

    private class StubSpeechModel(override val modelId: String) : SpeechModelHandle {

        override val ramBytes: Long = 0

        override suspend fun transcribe(audio: AudioRef, language: String?): Transcript =
            Transcript(text = "(демонстрационная расшифровка)", language = language ?: "ru", confidence = 0.0)

        override fun requestCancel() = Unit

        override fun close() = Unit
    }
}
