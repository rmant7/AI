package ai.localstudio.app.localai

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.runtime.GenerationRequest

/**
 * What a caller hands a local model, in the shape the SDK will take it:
 * text, and any number of images in the order the model should see them.
 * Nothing in it names a runtime, a file or llama.cpp -- a caller builds one
 * without knowing what will run it.
 */
data class LocalAiInput(
    val text: String,
    val images: List<ImageRef> = emptyList(),
    val systemPrompt: String? = null,
) {
    /** What a model needs to take this input: VISION as soon as one image is attached. */
    val requiredCapabilities: Set<Capability>
        get() = if (images.isEmpty()) setOf(Capability.TEXT_GENERATION) else setOf(Capability.TEXT_GENERATION, Capability.VISION)

    /** Whether the installed model behind [binding] can take this input: images need its projector on disk, whatever the model is called. */
    fun canRunOn(binding: RuntimeBinding): Boolean = images.isEmpty() || binding.mmprojArtifact != null

    fun toRequest(
        maxTokens: Int,
        temperature: Double,
        repeatPenalty: Double = GenerationRequest(prompt = "").repeatPenalty,
    ): GenerationRequest = GenerationRequest(
        prompt = text,
        systemPrompt = systemPrompt,
        maxTokens = maxTokens,
        temperature = temperature,
        repeatPenalty = repeatPenalty,
        images = images,
    )
}
