package ai.localstudio.model.install

import ai.localstudio.model.FileSelector

/**
 * Applies a [FileSelector] to one repository listing — the install-time
 * half of an [ai.localstudio.model.ArtifactSource.HuggingFaceSelection].
 *
 * [FileSelector.ByQuantization] reproduces the legacy
 * `ArtifactResolver.pickBest` exactly (proven by test): candidates are the
 * files ending in the extension (case-insensitive) that are not one part of
 * a multi-part file; the first quantization in priority order that any
 * candidate's path contains wins, in listing order; with no match at all,
 * the smallest candidate.
 */
object FileSelection {

    private val SPLIT_SUFFIX = Regex("""-\d{5}-of-\d{5}\.[a-z0-9]+$""", RegexOption.IGNORE_CASE)

    fun select(selector: FileSelector, files: List<RepoFile>): RepoFile? = when (selector) {
        is FileSelector.ByQuantization -> byQuantization(selector, files)
        is FileSelector.ExactName -> files.firstOrNull { it.name == selector.fileName }
    }

    private fun byQuantization(selector: FileSelector.ByQuantization, files: List<RepoFile>): RepoFile? {
        val usable = files.filter {
            it.path.endsWith(selector.extension, ignoreCase = true) && !SPLIT_SUFFIX.containsMatchIn(it.path)
        }
        if (usable.isEmpty()) return null
        for (quant in selector.quantPriority) {
            usable.firstOrNull { it.path.contains(quant, ignoreCase = true) }?.let { return it }
        }
        return usable.minByOrNull { it.sizeBytes }
    }
}
