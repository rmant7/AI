package ai.localstudio.openai

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Minimal `multipart/form-data` builder for the audio transcription endpoint —
 * the one call in the OpenAI-compatible API that is not JSON.
 *
 * Written by hand rather than pulled in with an HTTP library: the format is a
 * dozen lines, and the whole point of this module is that it adds no
 * dependency beyond the JDK's own client.
 */
internal class Multipart(private val boundary: String) {

    private val buffer = ByteArrayOutputStream()

    fun field(name: String, value: String) = apply {
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        write("$value\r\n")
    }

    fun file(name: String, file: File, contentType: String = "application/octet-stream") = apply {
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n")
        write("Content-Type: $contentType\r\n\r\n")
        buffer.write(file.readBytes())
        write("\r\n")
    }

    fun build(): ByteArray {
        write("--$boundary--\r\n")
        return buffer.toByteArray()
    }

    private fun write(text: String) = buffer.write(text.toByteArray(StandardCharsets.UTF_8))
}
