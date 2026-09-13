package ai.localstudio.app.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * Manual, on-device verification for a candidate embedding model — see
 * `Mobile_mem0/SEMANTIC_RETRIEVAL_DESIGN.md`'s "Choosing the concrete
 * model" section, and [ExperimentalEmbeddingModels] for the candidates this
 * was written against. This is not wired into the normal CI smoke-test
 * expectation: [assumeTrue] skips it (not fails it) whenever no model file
 * has been manually placed at [MODEL_PATH], which is the case for every CI
 * run and every real device that has not deliberately been used for this.
 *
 * To actually run this against a candidate:
 * 1. Download the GGUF from the candidate's Hugging Face repo (confirm the
 *    exact file name from that repo's own listing — do not guess it).
 * 2. `adb push downloaded.gguf /sdcard/Android/data/<applicationId>/files/experimental/embedder.gguf`
 *    (create the `experimental` directory first if `adb push` doesn't).
 * 3. Run this test class from Android Studio or
 *    `./gradlew connectedDebugAndroidTest --tests "*ExperimentalEmbeddingModelTest*"`.
 * 4. Read the logcat tag [TAG] for the actual dimension and cosine scores —
 *    this test's own assertions are a coarse sanity check (a near-duplicate
 *    sentence must score higher than an unrelated one), not a quality bar
 *    for any specific candidate.
 */
@RunWith(AndroidJUnit4::class)
class ExperimentalEmbeddingModelTest {

    @Test
    fun embeddings_are_well_formed_and_similarity_ranks_sensibly() {
        val modelFile = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "experimental/embedder.gguf",
        )
        assumeTrue(
            "no experimental embedding model at ${modelFile.absolutePath} - see this class's own doc comment for how to place one",
            modelFile.isFile,
        )

        val bridge = LlamaBridge()
        // MEAN + e5's own query/passage prefixes: the default assumption for
        // ExperimentalEmbeddingModels' current candidates. Change these two
        // arguments directly when testing a model that expects something
        // else - this is a manual harness, not a catalog lookup.
        val embedder = LlamaCppMemoryEmbedder.load(
            bridge = bridge,
            modelPath = modelFile.absolutePath,
            modelId = "experimental",
            pooling = EmbeddingPooling.MEAN,
            queryPrefix = "query: ",
            passagePrefix = "passage: ",
        )
        assertTrue("model failed to load from ${modelFile.absolutePath} - check it is a valid embedding GGUF", embedder != null)
        requireNotNull(embedder)

        try {
            android.util.Log.i(TAG, "loaded ${modelFile.name}, dimension=${embedder.dimension}")
            assertTrue("dimension should be positive, was ${embedder.dimension}", embedder.dimension > 0)

            val query = "рецепты низкокалорийных десертов"
            val similarPassage = "Пользователь искал рецепты низкокалорийных десертов"
            val dissimilarPassage = "Решили использовать Kotlin для нового модуля"

            val queryVector = runBlocking { embedder.embedForQuery(query) }
            val (similarVector, dissimilarVector) = runBlocking {
                embedder.embedForStorage(listOf(similarPassage, dissimilarPassage))
            }.let { it[0] to it[1] }

            assertTrue("query vector should have the model's own dimension", queryVector.size == embedder.dimension)

            val simSimilar = cosine(queryVector, similarVector)
            val simDissimilar = cosine(queryVector, dissimilarVector)
            android.util.Log.i(TAG, "cosine(query, similar passage)=$simSimilar")
            android.util.Log.i(TAG, "cosine(query, dissimilar passage)=$simDissimilar")

            val norm = sqrt(queryVector.sumOf { (it * it).toDouble() })
            android.util.Log.i(TAG, "query vector L2 norm=$norm (should be close to 1.0 - nativeEmbed normalizes)")

            assertTrue(
                "a near-duplicate sentence should score higher than an unrelated one " +
                    "(similar=$simSimilar, dissimilar=$simDissimilar) - if this fails, check the pooling " +
                    "mode and query/passage prefixes for this specific model, not just that it loaded",
                simSimilar > simDissimilar,
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

    private companion object {
        const val TAG = "ExperimentalEmbeddingModel"
    }
}
