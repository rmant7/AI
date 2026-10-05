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

/**
 * What a projector (mmproj) file's own header says about whether the bundled
 * mtmd can use it for images -- decided the way llama.cpp b10448's clip.cpp
 * decides at load: `general.architecture` "clip"; `clip.has_vision_encoder`
 * true (absent means no vision -- an audio or speech projector); the type
 * from `clip.projector_type`, or for a mixed vision+audio file
 * `clip.vision.projector_type`, one [MtmdProjectors.LOADABLE] knows (no type
 * at all is unknown there too). Necessary, not sufficient: whether it fits
 * the main model it ships with only loading both on a device shows.
 */
sealed interface ProjectorCompatibility {
    data class Vision(val projectorType: String) : ProjectorCompatibility

    data class NotUsable(val reason: String) : ProjectorCompatibility

    companion object {
        const val KEY_PROJECTOR_TYPE = "clip.projector_type"
        const val KEY_VISION_PROJECTOR_TYPE = "clip.vision.projector_type"
        const val KEY_HAS_VISION_ENCODER = "clip.has_vision_encoder"

        fun of(metadata: GgufMetadata, loadable: Set<String> = MtmdProjectors.LOADABLE): ProjectorCompatibility {
            val architecture = metadata.architecture
            if (architecture != "clip") return NotUsable("not a projector (general.architecture ${architecture ?: "missing"})")
            if (metadata.values[KEY_HAS_VISION_ENCODER] != true) return NotUsable("no vision encoder (an audio or speech projector)")
            val type = (metadata.values[KEY_PROJECTOR_TYPE] as? String)?.takeIf { it.isNotEmpty() }
                ?: (metadata.values[KEY_VISION_PROJECTOR_TYPE] as? String)?.takeIf { it.isNotEmpty() }
                ?: return NotUsable("no projector type in the header")
            if (type !in loadable) return NotUsable("llama.cpp ${MtmdProjectors.LLAMA_CPP_TAG} cannot load projector type \"$type\"")
            return Vision(type)
        }

        /** Enough of the header read to decide: the vision flag and a type (or the header's end, when one is missing). */
        fun hasVerdictKeys(values: Map<String, Any>): Boolean =
            values.containsKey(KEY_HAS_VISION_ENCODER) && (values.containsKey(KEY_PROJECTOR_TYPE) || values.containsKey(KEY_VISION_PROJECTOR_TYPE))
    }
}
