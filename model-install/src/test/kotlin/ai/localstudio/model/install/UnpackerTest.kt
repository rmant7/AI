package ai.localstudio.model.install

import ai.localstudio.model.UnpackSpec
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnpackerTest {

    private val dir: File = Files.createTempDirectory("unpack").toFile()

    fun zip(vararg entries: Pair<String, String>): File = File(dir, "a.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun `strip components drops the wrapper folder like the legacy Vosk extractor`() {
        val archive = zip("vosk-model-small-ru-0.22/" to "", "vosk-model-small-ru-0.22/am/final.mdl" to "AM", "vosk-model-small-ru-0.22/conf/model.conf" to "C")
        val out = File(dir, "model")
        val written = Unpacker.unpack(archive, UnpackSpec("zip", stripComponents = 1), out)
        assertEquals("AM", File(out, "am/final.mdl").readText())
        assertEquals("C", File(out, "conf/model.conf").readText())
        assertFalse(File(out, "vosk-model-small-ru-0.22").exists())
        assertEquals(3, written)
    }

    @Test
    fun `an entry escaping the destination is rejected`() {
        val archive = zip("wrap/../../evil.txt" to "x")
        assertFailsWith<IOException> { Unpacker.unpack(archive, UnpackSpec("zip", stripComponents = 1), File(dir, "model")) }
        assertFalse(File(dir, "evil.txt").exists())
    }

    @Test
    fun `tar archives are declared but not supported yet`() {
        assertFailsWith<Unpacker.UnsupportedArchiveException> { Unpacker.unpack(zip(), UnpackSpec("tar.bz2"), File(dir, "x")) }
    }

    @Test
    fun `the unpack directory is the archive name without its extension`() {
        assertEquals("model", Unpacker.directoryNameFor("model.zip"))
        assertEquals("voice", Unpacker.directoryNameFor("voice.tar.bz2"))
        assertTrue(Unpacker.directoryNameFor("blob").endsWith(".d"))
    }
}
