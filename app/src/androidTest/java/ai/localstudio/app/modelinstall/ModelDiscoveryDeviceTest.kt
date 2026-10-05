package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.ModelDiscovery
import ai.localstudio.model.install.ModelSearchQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Discovery end to end over real HTTP: the app's own Hub client searching
 * [LocalHub], resolving each repository to a commit, listing it, and the
 * probe reading each GGUF's header through the same redirecting transport
 * an install uses. The search response's shape here is this code's own
 * assumption about the Hub (`id`/`modelId`, `gated` as boolean, string or
 * null) -- the device's app log (DISCOVERY) is what checks it against the
 * real one.
 */
@RunWith(AndroidJUnit4::class)
class ModelDiscoveryDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hub = LocalHub()
    private val base = File(context.cacheDir, "discovery-${System.nanoTime()}")

    private fun gguf(architecture: String, payload: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(size: Int, put: ByteBuffer.() -> Unit) = out.write(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(put).array())
        fun str(v: String) { val b = v.toByteArray(); le(8) { putLong(b.size.toLong()) }; out.write(b) }
        le(4) { putInt(0x46554747) }; le(4) { putInt(3) }; le(8) { putLong(1) }; le(8) { putLong(2) }
        str("general.architecture"); le(4) { putInt(8) }; str(architecture)
        str("$architecture.context_length"); le(4) { putInt(4) }; le(4) { putInt(32768) }
        out.write(ByteArray(payload))
        return out.toByteArray()
    }

    @After
    fun tearDown() {
        hub.close()
        base.deleteRecursively()
    }

    @Test
    fun the_hub_is_searched_and_each_candidate_judged_by_its_header() {
        hub.publish("acme/qwen-GGUF", "main", "a".repeat(40), "qwen-Q4_K_M.gguf" to gguf("qwen3", 2_000_000), "mmproj-qwen-F16.gguf" to gguf("clip", 100))
        hub.publish("acme/future-GGUF", "main", "b".repeat(40), "future-Q4_K_M.gguf" to gguf("gemma9", 1_000))
        hub.publish("acme/locked-GGUF", "main", "c".repeat(40), "locked-Q4_K_M.gguf" to gguf("llama", 1_000))
        hub.searchResponse = """[
            {"_id":"1","id":"acme/qwen-GGUF","modelId":"acme/qwen-GGUF","downloads":1200,"likes":4,"tags":["gguf","text-generation"],"pipeline_tag":"text-generation","gated":false},
            {"_id":"2","modelId":"acme/future-GGUF","downloads":9000,"tags":["gguf"],"gated":null},
            {"_id":"3","id":"acme/locked-GGUF","downloads":500,"gated":"manual"},
            {"_id":"4","downloads":1}
        ]"""
        val installation = ModelInstallation(
            context,
            root = File(base, ModelInstallation.ROOT_DIR),
            hub = HuggingFaceApiClient(apiBase = hub.base),
            transport = HttpRangeTransport(rewrite = hub.rewrite),
            attemptsPerSource = 1,
            retryDelayMs = 0,
        )

        val report = installation.discovery!!.discover(
            ModelSearchQuery(tags = listOf("gguf"), pipelineTag = "text-generation", limit = 15),
            quantPriority = listOf("Q4_K_M"),
            maxModelBytes = 100L * 1024 * 1024,
        )

        assertEquals(listOf("acme/qwen-GGUF"), report.candidates.map { it.repo.id })
        val qwen = report.candidates.single()
        assertEquals("qwen-Q4_K_M.gguf", qwen.file.name)
        assertEquals("qwen3", qwen.architecture)
        assertEquals(32768L, qwen.contextLength)
        val dropped = report.outcomes.filterIsInstance<ModelDiscovery.Outcome.Dropped>().associate { it.repo.id to it.reason }
        assertTrue(dropped.getValue("acme/future-GGUF").contains("gemma9"))
        assertTrue("null gated is not gated", "gated" !in dropped.getValue("acme/future-GGUF"))
        assertTrue(dropped.getValue("acme/locked-GGUF").contains("gated"))
        assertEquals("an item with no id is skipped", 3, report.outcomes.size)

        val query = URLDecoder.decode(hub.searchQueries.single(), "UTF-8")
        assertTrue(query, "filter=gguf" in query && "pipeline_tag=text-generation" in query && "sort=downloads" in query && "limit=15" in query)
        assertTrue((installation.hub as HuggingFaceApiClient).lastSearchShape!!.contains("4 items"))

        // The mmproj is never a model of its own; it is read as qwen's projector and, with no vision keys in its header, left out.
        assertEquals(null, qwen.projector)
        assertTrue(qwen.notes.toString(), qwen.notes.any { "mmproj-qwen-F16.gguf" in it && "left out" in it })
        val headerBytes = hub.fileRequests().count()
        assertEquals("one header read per probed file: two models and qwen's projector", 3, headerBytes)
    }
}
