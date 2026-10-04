package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.ArtifactSpec
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransferTest {

    private val dir: File = Files.createTempDirectory("transfer").toFile()
    private val target = File(dir, "model.gguf")
    private val part = File(dir, "model.gguf.part")
    private val transport = FakeTransport()
    private val engine = TransferEngine(transport, attemptsPerCandidate = 3, sleep = noSleep)

    private val data = bytesOf(200_000)
    private val other = bytesOf(200_000, seed = 2)

    private fun candidate(url: String, sha: String? = sha256(data), size: Long? = data.size.toLong()) =
        DownloadCandidate(url, size, sha, SourceRecord(url))

    private fun artifact(vararg candidates: DownloadCandidate, catalogSha: String? = null) = ResolvedArtifact(
        ArtifactSpec(ArtifactRoles.WEIGHTS, "model.gguf", 0, catalogSha, ArtifactSource.DirectUrl(candidates.first().url)),
        candidates.toList(),
    )

    @Test
    fun `a plain download is verified and recorded with its hash`() {
        transport.bodies["https://a/m"] = data
        val done = assertIs<TransferOutcome.Done>(engine.download(artifact(candidate("https://a/m"), catalogSha = sha256(data)), target))
        assertContentEquals(data, target.readBytes())
        assertEquals(sha256(data), done.file.sha256)
        assertEquals(IntegrityBasis.CATALOG_SHA256, done.file.integrity)
        assertFalse(part.exists())
    }

    @Test
    fun `a dropped connection resumes with a range request instead of starting over`() {
        transport.bodies["https://a/m"] = data
        transport.cutAfter["https://a/m"] = ArrayDeque(listOf(70_000, 50_000))
        assertIs<TransferOutcome.Done>(engine.download(artifact(candidate("https://a/m")), target))
        assertContentEquals(data, target.readBytes())
        assertEquals(listOf(0L, 70_000L, 120_000L), transport.opens.map { it.second })
    }

    @Test
    fun `a server that ignores the range request is restarted from zero, not spliced`() {
        transport.bodies["https://a/m"] = data
        transport.cutAfter["https://a/m"] = ArrayDeque(listOf(70_000))
        transport.ignoreRange += "https://a/m"
        assertIs<TransferOutcome.Done>(engine.download(artifact(candidate("https://a/m")), target))
        assertContentEquals(data, target.readBytes())
    }

    @Test
    fun `wrong bytes are rejected and the next candidate starts from an empty part`() {
        transport.bodies["https://bad/m"] = other
        transport.bodies["https://good/m"] = data
        val done = assertIs<TransferOutcome.Done>(engine.download(artifact(candidate("https://bad/m"), candidate("https://good/m")), target))
        assertContentEquals(data, target.readBytes())
        assertEquals("https://good/m", done.file.candidate.url)
        assertTrue(done.failures.single().message.startsWith("sha256"))
        assertEquals(0L, transport.opens.last().second, "the second source must not resume the first one's bytes")
    }

    @Test
    fun `not found and access denied are not retried`() {
        transport.errors["https://gated/m"] = ArrayDeque(listOf(SourceException(SourceException.Kind.ACCESS_DENIED, "403")))
        transport.bodies["https://good/m"] = data
        assertIs<TransferOutcome.Done>(engine.download(artifact(candidate("https://gated/m"), candidate("https://good/m")), target))
        assertEquals(1, transport.opens.count { it.first == "https://gated/m" })
    }

    @Test
    fun `more bytes than expected fail the candidate without retrying`() {
        transport.bodies["https://a/m"] = data + byteArrayOf(1, 2, 3)
        val failed = assertIs<TransferOutcome.Failed>(engine.download(artifact(candidate("https://a/m")), target))
        assertTrue(failed.failures.single().message.contains("expected"))
        assertEquals(1, transport.opens.size)
        assertFalse(part.exists() || target.exists())
    }

    @Test
    fun `persistent network errors give up after the attempts and report them`() {
        transport.errors["https://a/m"] = ArrayDeque(List(5) { java.io.IOException("timeout") })
        val failed = assertIs<TransferOutcome.Failed>(engine.download(artifact(candidate("https://a/m")), target))
        assertEquals(SourceException.Kind.NETWORK, failed.failures.single().kind)
        assertEquals(3, transport.opens.size)
    }

    @Test
    fun `cancellation keeps the part and a later run resumes it, for the same candidate only`() {
        transport.bodies["https://a/m"] = data
        var chunks = 0
        assertFailsWith<TransferCancelledException> {
            engine.download(artifact(candidate("https://a/m")), target, cancel = { chunks > 1 }) { chunks++ }
        }
        val kept = part.length()
        assertTrue(kept in 1 until data.size)

        transport.opens.clear()
        assertIs<TransferOutcome.Done>(engine.download(artifact(candidate("https://a/m")), target))
        assertEquals(kept, transport.opens.single().second, "resumed where the cancelled run stopped")

        // A part left by a different candidate (other expected hash) is discarded, not resumed.
        target.delete()
        part.writeBytes(other.copyOf(1000))
        File(dir, "model.gguf.part.json").writeText("""{"url":"https://a/m","sha256":"${sha256(other)}","sizeBytes":200000}""")
        transport.opens.clear()
        assertIs<TransferOutcome.Done>(engine.download(artifact(candidate("https://a/m")), target))
        assertEquals(0L, transport.opens.single().second)
        assertContentEquals(data, target.readBytes())
    }

    @Test
    fun `a file already in place is reused only when a hash proves it`() {
        target.writeBytes(data)
        assertIs<TransferOutcome.Done>(engine.download(artifact(candidate("https://a/m")), target))
        assertTrue(transport.opens.isEmpty())

        transport.bodies["https://a/m"] = data
        target.writeBytes(data)
        assertIs<TransferOutcome.Done>(engine.download(artifact(candidate("https://a/m", sha = null)), target))
        assertEquals(1, transport.opens.size, "no hash: a same-size file proves nothing, download again")
    }
}
