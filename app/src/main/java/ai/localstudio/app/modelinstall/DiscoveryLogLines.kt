package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.ModelDiscovery

/**
 * A sweep's log, short enough to read and paste: one line per candidate
 * (with whether it can see, and why not when a projector was left out), and
 * one line per family for everything dropped -- counted by reason, with the
 * uncommon reasons spelled out. A real report: one sweep logged a line per
 * dropped repository, 150+ lines, and the log was cut off before reaching
 * the families that mattered.
 */
object DiscoveryLogLines {

    fun candidate(label: String, c: ModelDiscovery.Outcome.Candidate): String {
        val vision = c.projector?.let { "vision ${it.type} +${it.file.sizeBytes / 1_000_000} MB" }
            ?: c.notes.firstOrNull { "projector" in it }?.let { "no vision: $it" }
        val otherNotes = c.notes.count { "projector" !in it }
        return "$label + ${c.repo.id}: ${c.file.name} ${c.file.sizeBytes / 1_000_000} MB, ${c.architecture}" +
            (vision?.let { ", $it" } ?: "") +
            (if (otherNotes > 0) ", $otherNotes note(s)" else "") +
            ", ${c.repo.downloads} dl, @${c.commit.take(8)}"
    }

    /** Null when nothing was dropped. */
    fun dropped(label: String, dropped: List<ModelDiscovery.Outcome.Dropped>, examples: Int = 5): String? {
        if (dropped.isEmpty()) return null
        val byKind = dropped.groupBy { kind(it.reason) }
        val counts = byKind.entries.sortedByDescending { it.value.size }.joinToString(", ") { (kind, list) -> "$kind ${list.size}" }
        val other = byKind[OTHER].orEmpty()
        val spelled = other.take(examples).joinToString("; ") { "${it.repo.id}: ${it.reason}" } +
            if (other.size > examples) "; …" else ""
        return "$label dropped ${dropped.size} ($counts)" + if (spelled.isNotEmpty()) " -- $spelled" else ""
    }

    private const val OTHER = "other"

    private fun kind(reason: String): String = when {
        reason.startsWith("same model as") -> "duplicate"
        "too large" in reason || "more than this device can hold" in reason -> "too large"
        reason.startsWith("not a text-generation model") -> "not text"
        "gated" in reason -> "gated"
        else -> OTHER
    }
}
