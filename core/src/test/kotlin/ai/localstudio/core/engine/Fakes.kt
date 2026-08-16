package ai.localstudio.core.engine

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.model.Transcript
import ai.localstudio.core.model.VisionResult
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.EmbeddingModelHandle
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.SpeechModelHandle
import ai.localstudio.core.runtime.TextModelHandle
import ai.localstudio.core.runtime.VisionModelHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A runtime that loads the fake counterpart of whatever the model claims to be.
 *
 * Everything above the runtime interface is exercised for real: registry,
 * scorer, selector, runtime manager, context engine, pipeline engine.
 */
class FakeRuntime(
    override val kind: RuntimeKind = RuntimeKind.LLAMA_CPP,
    private val transcriptText: String = "найди мне лучшие локальные модели",
    private val visionDescription: String = "На изображении схема архитектуры",
) : ModelRuntime {

    val loaded = mutableListOf<String>()
    val prompts = mutableListOf<String>()

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding) = true

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
        loaded += model.id
        val ram = binding.effectiveRequiredRamBytes
        return when {
            Capability.SPEECH_TO_TEXT in model.capabilities -> FakeSpeechModel(model.id, ram, transcriptText)
            Capability.EMBEDDING in model.capabilities -> FakeEmbeddingModel(model.id, ram)
            Capability.IMAGE_UNDERSTANDING in model.capabilities ||
                Capability.OCR in model.capabilities -> FakeVisionModel(model.id, ram, visionDescription)

            Capability.TEXT_GENERATION in model.capabilities -> FakeTextModel(model.id, ram) { prompts += it }
            else -> throw ModelLoadException("Nothing to load for ${model.id}")
        }
    }
}

class FakeTextModel(
    override val modelId: String,
    override val ramBytes: Long,
    private val onPrompt: (String) -> Unit,
) : TextModelHandle {
    var cancelled = false
        private set

    override fun generate(request: GenerationRequest): Flow<String> {
        onPrompt(request.prompt)
        val sections = request.prompt.lines().count { it.startsWith("[") }
        return flowOf("ответ ", "по ", "$sections ", "секциям")
    }

    override fun requestCancel() {
        cancelled = true
    }

    override fun close() = Unit
}

class FakeSpeechModel(
    override val modelId: String,
    override val ramBytes: Long,
    private val text: String,
) : SpeechModelHandle {
    override suspend fun transcribe(audio: AudioRef, language: String?): Transcript =
        Transcript(text = text, language = language ?: "ru", confidence = 0.94)

    override fun requestCancel() = Unit
    override fun close() = Unit
}

class FakeVisionModel(
    override val modelId: String,
    override val ramBytes: Long,
    private val description: String,
) : VisionModelHandle {
    override suspend fun analyze(image: ImageRef, prompt: String?): VisionResult =
        VisionResult(description = description, ocrText = "CONTEXT ENGINE", confidence = 0.8)

    override fun close() = Unit
}

class FakeEmbeddingModel(
    override val modelId: String,
    override val ramBytes: Long,
) : EmbeddingModelHandle {
    override val dimensions = 8
    override suspend fun embed(texts: List<String>): List<FloatArray> =
        texts.map { text -> FloatArray(dimensions) { i -> ((text.hashCode() shr i) and 1).toFloat() } }

    override fun close() = Unit
}
