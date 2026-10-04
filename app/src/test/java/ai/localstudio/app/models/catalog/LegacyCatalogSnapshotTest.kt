package ai.localstudio.app.models.catalog

import ai.localstudio.model.CatalogCodec
import ai.localstudio.model.CatalogDocument
import ai.localstudio.model.CatalogLoader
import ai.localstudio.model.CatalogTrust
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Snapshot: the exported catalogue file (model-catalog/local-models.json) is
 * exactly what the current mapping produces, and loads cleanly. Any change
 * to a seed, the mapping or the serialized shape shows up as a reviewable
 * diff of that file — regenerate with
 * `./gradlew :app:testDebugUnitTest -PupdateCatalogSnapshot=true`.
 *
 * Whether the content is *right* is [LegacyCatalogGoldenTest]'s question,
 * not this one's.
 */
class LegacyCatalogSnapshotTest {

    private val pretty = Json(CatalogCodec.json) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private fun render(document: CatalogDocument): String =
        pretty.encodeToString(CatalogDocument.serializer(), document) + "\n"

    @Test
    fun `the committed catalogue matches the current mapping`() {
        val expected = render(LegacyCatalog.document)
        val file = LegacyCatalog.snapshotFile
        if (LegacyCatalog.updateSnapshot) {
            file.parentFile.mkdirs()
            file.writeText(expected)
            return
        }
        if (!file.isFile) fail("${file.path} is missing — regenerate with -PupdateCatalogSnapshot=true")
        assertEquals(expected, file.readText(), "${file.name} is stale — regenerate with -PupdateCatalogSnapshot=true and review the diff")
    }

    @Test
    fun `the committed catalogue loads cleanly and is a fixed point of the codec`() {
        val file = LegacyCatalog.snapshotFile
        if (LegacyCatalog.updateSnapshot) return
        val text = file.readText()
        val loaded = CatalogLoader.load(text, CatalogTrust.Bundled)
        assertEquals(emptyList(), loaded.violations)
        assertTrue(loaded.isUsable)
        // Nothing in the file is dropped or reshaped by a decode/encode cycle —
        // a future reader sees exactly what is committed.
        val (decoded, _) = CatalogCodec.decode(text)
        assertEquals(text, render(decoded!!))
    }
}
