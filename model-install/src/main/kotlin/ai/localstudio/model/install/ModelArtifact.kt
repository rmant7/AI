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

/**
 * Which bytes a model is: repository, commit, main file, and its projector
 * when it has one. Two models with the same [ArtifactId] are the same model
 * byte for byte; anything a device observed about one applies to the other
 * on that device, and to nothing else. [key] is its stable string form.
 *
 * No projector type: a projector file at a commit has exactly one, read
 * from its own header, so naming the file already names it -- and an id
 * rebuilt from an install manifest (which records files, not headers) must
 * equal the one discovery made.
 */
@Serializable
data class ArtifactId(
    val repository: String,
    val revision: String,
    val mainFile: String,
    val projectorFile: String? = null,
) {
    val key: String get() = listOfNotNull(repository, revision, mainFile, projectorFile).joinToString("|")

    /** False for an [unpinned] id: no repository commit stands behind it, only the files' sizes. */
    val isPinned: Boolean get() = !repository.startsWith(UNPINNED_PREFIX)

    companion object {
        private const val UNPINNED_PREFIX = "local/"

        /**
         * An installed model with no recorded source (a download from before
         * install manifests): named by the app's model id and its files'
         * exact sizes. A check of it holds while those files stay as they are;
         * a different file under the same name is a different id, and its
         * old check is STALE. Never offered to anyone as a repository.
         */
        fun unpinned(modelId: String, mainBytes: Long, projectorBytes: Long?): ArtifactId = ArtifactId(
            repository = UNPINNED_PREFIX + modelId,
            revision = "bytes-$mainBytes" + (projectorBytes?.let { "-$it" } ?: ""),
            mainFile = "model.gguf",
            projectorFile = projectorBytes?.let { "projector.gguf" },
        )
    }
}

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

    val id: ArtifactId get() = ArtifactId(main.repo, main.revision, main.path, projector?.file?.path)

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
