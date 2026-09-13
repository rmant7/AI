package ai.localstudio.app.llama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Same check [ExperimentalEmbeddingModelTest] runs, but callable from inside
 * the running app instead of through `connectedDebugAndroidTest` — for a
 * phone-only workflow with no adb, that gradle command (and the logcat it
 * would otherwise report through) isn't reachable at all, so this is now the
 * only way to actually see whether a downloaded candidate embeds sensibly.
 *
 * This is a coarse sanity check, not a quality bar for any specific
 * candidate: a near-duplicate sentence should score higher than an unrelated
 * one. If it doesn't, the likely cause is a wrong [EmbeddingModelSpec.pooling]
 * or missing/wrong query-passage prefixes for that specific model, not that
 * the file itself is bad.
 */
object ExperimentalEmbeddingTester {

    data class Result(
        val dimension: Int,
        val queryVectorL2Norm: Double,
        val cosineSimilar: Double,
        val cosineDissimilar: Double,
    ) {
        val looksSensible: Boolean get() = cosineSimilar > cosineDissimilar
    }

    private const val QUERY = "рецепты низкокалорийных десертов"
    private const val SIMILAR_PASSAGE = "Пользователь искал рецепты низкокалорийных десертов"
    private const val DISSIMILAR_PASSAGE = "Решили использовать Kotlin для нового модуля"

    /** Loads [modelFile], runs the check, and always releases the native context before returning. */
    suspend fun run(bridge: LlamaBridge, modelFile: java.io.File, spec: EmbeddingModelSpec): Result =
        withContext(Dispatchers.Default) {
            val embedder = LlamaCppMemoryEmbedder.load(
                bridge = bridge,
                modelPath = modelFile.absolutePath,
                modelId = spec.id,
                pooling = spec.pooling,
                queryPrefix = spec.queryPrefix,
                passagePrefix = spec.passagePrefix,
            ) ?: throw IllegalStateException("model failed to load — check it is a valid embedding GGUF")

            try {
                val queryVector = embedder.embedForQuery(QUERY)
                val (similarVector, dissimilarVector) = embedder.embedForStorage(listOf(SIMILAR_PASSAGE, DISSIMILAR_PASSAGE))
                    .let { it[0] to it[1] }

                Result(
                    dimension = embedder.dimension,
                    queryVectorL2Norm = sqrt(queryVector.sumOf { (it * it).toDouble() }),
                    cosineSimilar = cosine(queryVector, similarVector),
                    cosineDissimilar = cosine(queryVector, dissimilarVector),
                )
            } finally {
                embedder.close()
            }
        }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        return if (normA == 0.0 || normB == 0.0) 0.0 else dot / (sqrt(normA) * sqrt(normB))
    }
}
