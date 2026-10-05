package ai.localstudio.model.install

import kotlinx.serialization.Serializable

/** One file of a model on the Hub, pinned: repository, the commit it was read at, its path there. */
@Serializable
data class ModelFile(
    val repo: String,
    val revision: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)

/** A vision projector (mmproj): the file, and the `clip.projector_type` its own header declares. */
@Serializable
data class ProjectorFile(val file: ModelFile, val type: String)

/**
 * A model as one unit: its main GGUF and, when it can see, the projector
 * that belongs to it. The projector is part of the model -- never a model
 * or a candidate of its own -- and is bound to the same repository and the
 * same commit as the main file, the pair a publisher put side by side, not
 * any mmproj with a similar name found elsewhere. Whether the two actually
 * work together is what a device check proves; this only says what they are.
 */
@Serializable
data class ModelArtifact(val main: ModelFile, val projector: ProjectorFile? = null) {
    init {
        projector?.let {
            require(it.file.repo == main.repo && it.file.revision == main.revision) {
                "a projector belongs to its model's repository and commit: ${it.file.repo}@${it.file.revision} is not ${main.repo}@${main.revision}"
            }
        }
    }

    val totalBytes: Long get() = main.sizeBytes + (projector?.file?.sizeBytes ?: 0L)

    /** Whether this artifact has the parts [capability] needs -- the precondition for checking it, not a result (see [DeviceVerification]). */
    fun canCheck(capability: String): Boolean = CapabilityRequirements.of(capability).all { part ->
        when (part) {
            ArtifactPart.MAIN -> true
            ArtifactPart.PROJECTOR -> projector != null
        }
    }
}

enum class ArtifactPart { MAIN, PROJECTOR }

/** What each [VerifiedCapability] needs of a [ModelArtifact]. Nothing here names a model: any family with the parts qualifies. */
object CapabilityRequirements {
    fun of(capability: String): Set<ArtifactPart> = when (capability) {
        VerifiedCapability.VISION -> setOf(ArtifactPart.MAIN, ArtifactPart.PROJECTOR)
        else -> setOf(ArtifactPart.MAIN)
    }
}
