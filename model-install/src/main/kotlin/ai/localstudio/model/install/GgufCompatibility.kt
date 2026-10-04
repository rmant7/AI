package ai.localstudio.model.install

/**
 * What a GGUF file's own header says about whether the bundled llama.cpp
 * can run it -- checked before a single weight is downloaded (the header is
 * the first bytes of the file, see [GgufMetadataReader]). A necessary
 * condition, not a sufficient one: an architecture llama.cpp knows can still
 * miss a key it requires (E5 Small's "bert model needs to define token type
 * count"), which only loading it on a device shows.
 */
sealed interface GgufCompatibility {
    /** [notes]: what a chat consumer should know (no chat template, ...); empty when nothing stands out. */
    data class Loadable(val architecture: String, val contextLength: Long?, val notes: List<String>) : GgufCompatibility

    data class NotLoadable(val reason: String) : GgufCompatibility

    companion object {
        fun of(metadata: GgufMetadata, loadable: Set<String> = LlamaCppArchitectures.LOADABLE): GgufCompatibility {
            val architecture = metadata.architecture
                ?: return NotLoadable(
                    if (metadata.complete) "no general.architecture in the header" else "general.architecture not among the keys read",
                )
            if (architecture !in loadable) {
                return NotLoadable("llama.cpp ${LlamaCppArchitectures.LLAMA_CPP_TAG} cannot load architecture \"$architecture\"")
            }
            val notes = buildList {
                if (metadata.contextLength == null) add("no $architecture.context_length")
                if (metadata.complete && !metadata.hasChatTemplate) add("no chat template")
            }
            return Loadable(architecture, metadata.contextLength, notes)
        }
    }
}
