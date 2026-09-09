package ai.localstudio.commercialmemory

import ai.localstudio.memory.MemoryItem

/** What a [ContextSelector] decided to actually inject into the prompt, and why. */
data class ContextSelection(
    val items: List<MemoryItem>,
    /** This candidate's rank score, by memory id — for logging and debugging, not for re-ranking. */
    val scores: Map<String, Double>,
    val estimatedCharacters: Int,
)
