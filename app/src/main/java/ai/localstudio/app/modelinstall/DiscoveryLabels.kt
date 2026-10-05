package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.Lineage
import ai.localstudio.model.install.Lineages

/**
 * A stored run's label: "<purpose>:<lineage id>" ("chat:qwen",
 * "translation:hunyuan-mt"). Runs stored before lineages are the bare
 * "chat" / "translation" -- still read, by purpose, until the next sweep
 * replaces them.
 */
object DiscoveryLabels {
    fun of(lineage: Lineage): String = purposeOf(lineage.purpose) + ":" + lineage.id

    fun isTranslation(label: String): Boolean = label.substringBefore(':') == "translation"

    fun lineage(label: String): Lineage? = label.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let(Lineages::byId)

    private fun purposeOf(purpose: Lineage.Purpose) = if (purpose == Lineage.Purpose.TRANSLATION) "translation" else "chat"
}
