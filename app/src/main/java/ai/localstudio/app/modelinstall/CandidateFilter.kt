package ai.localstudio.app.modelinstall

/**
 * What the Candidates screen shows of the last search: a person narrowing
 * dozens of repositories down to the few worth a download -- by what a
 * model is for, how big it is, how many people use it, words in its name or
 * tags, and what this phone already observed. Only ever hides and orders;
 * never changes what was found.
 */
data class CandidateFilter(
    /** Every word must appear in the repository, file, architecture or a tag (case ignored). */
    val text: String = "",
    val purpose: Purpose = Purpose.ANY,
    /** What the repository's own tags say it is for (roleplay, code, math, ...); null = any. */
    val tagged: CandidatePurpose? = null,
    /** Download size of the whole model (main file plus projector); null = any size. */
    val maxBytes: Long? = null,
    val minDownloads: Long = 0,
    val status: Status = Status.ANY,
    val sort: Sort = Sort.SEARCH,
) {
    enum class Purpose { ANY, CHAT, TRANSLATION, VISION }

    enum class Status {
        ANY,
        /** No current result: never tested, or only a STALE one. */
        NOT_TESTED,
        /** At least one capability passes now. */
        WORKS,
        /** Tested now, nothing passes. */
        FAILED,
    }

    enum class Sort { SEARCH, DOWNLOADS, NEWEST, SMALLEST }

    val isDefault: Boolean get() = this == CandidateFilter()

    /**
     * [passesNow]: the capabilities that pass for [candidate] in the current
     * verification context; [testedNow]: whether it has any current
     * (not STALE) result. Both from the caller, which knows the context.
     */
    fun matches(label: String, candidate: DiscoveredCandidate, passesNow: Set<String>, testedNow: Boolean): Boolean {
        val purposeOk = when (purpose) {
            Purpose.ANY -> true
            Purpose.CHAT -> !DiscoveryLabels.isTranslation(label)
            Purpose.TRANSLATION -> DiscoveryLabels.isTranslation(label) ||
                CandidatePurpose.TRANSLATION in CandidateFacts.of(candidate.tags).purposes
            Purpose.VISION -> candidate.projector != null
        }
        if (!purposeOk) return false
        if (tagged != null && tagged !in CandidateFacts.of(candidate.tags).purposes) return false
        if (maxBytes != null && candidate.totalBytes > maxBytes) return false
        if (candidate.downloads < minDownloads) return false
        val statusOk = when (status) {
            Status.ANY -> true
            Status.NOT_TESTED -> !testedNow
            Status.WORKS -> passesNow.isNotEmpty()
            Status.FAILED -> testedNow && passesNow.isEmpty()
        }
        if (!statusOk) return false
        val words = text.lowercase().split(' ', ',').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        // Also what its tags say it is for, by name and every tag that means it: "rp" and "role" find a
        // model tagged "roleplay" or "creative-writing", "code" one tagged "coder".
        val purposes = CandidateFacts.of(candidate.tags).purposes.flatMap { listOf(it.name) + it.tags }
        val haystack = (listOf(candidate.repoId, candidate.filePath, candidate.architecture) + candidate.tags + purposes)
            .joinToString(" ").lowercase()
        return words.all { it in haystack }
    }

    /** [candidates] in [sort] order; SEARCH keeps the order the search returned them in. */
    fun <T> sorted(candidates: List<T>, of: (T) -> DiscoveredCandidate): List<T> = when (sort) {
        Sort.SEARCH -> candidates
        Sort.DOWNLOADS -> candidates.sortedByDescending { of(it).downloads }
        Sort.NEWEST -> candidates.sortedByDescending { of(it).createdAt.orEmpty() }
        Sort.SMALLEST -> candidates.sortedBy { of(it).totalBytes }
    }
}
