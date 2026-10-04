package ai.localstudio.app.log

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * [TombstoneDecoder] has no Android dependency; it lives in androidTest only
 * because that is the source set CI runs (see LazyMemoryEmbedderTest).
 * The tombstone here is built field by field with the numbers of AOSP's
 * tombstone.proto -- the same layout build #445's capture showed (a 66-byte
 * fingerprint after field 2, a 32-byte build id after field 8, ...).
 */
@RunWith(AndroidJUnit4::class)
class TombstoneDecoderTest {

    /** Minimal protobuf writer: varints and length-delimited fields. */
    private class Proto {
        private val out = ByteArrayOutputStream()
        private fun raw(v: Long) {
            var x = v
            while (x and 0x7F.inv().toLong() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        fun varint(field: Int, value: Long) = apply { raw((field shl 3).toLong()); raw(value) }
        fun bytes(field: Int, value: ByteArray) = apply { raw(((field shl 3) or 2).toLong()); raw(value.size.toLong()); out.write(value) }
        fun string(field: Int, value: String) = bytes(field, value.toByteArray())
        fun message(field: Int, build: Proto.() -> Unit) = bytes(field, Proto().apply(build).toBytes())
        fun toBytes(): ByteArray = out.toByteArray()
    }

    private fun Proto.frame(relPc: Long, function: String, offset: Long, file: String) = message(4) {
        varint(1, relPc); varint(2, relPc + 0x7000_0000); string(4, function); varint(5, offset); string(6, file); string(8, "a".repeat(32))
    }

    private fun Proto.thread(id: Int, name: String, frames: Proto.() -> Unit) = message(16) {
        varint(1, id.toLong())
        message(2) { varint(1, id.toLong()); string(2, name); frames() }
    }

    private val tombstone = Proto()
        .varint(1, 1) // arch
        .string(2, "google/blazer/blazer:17/CP2A.260805.005/15828068:user/release-keys")
        .varint(5, 4242)
        .varint(6, 4301) // the crashing tid
        .string(8, "u:r:untrusted_app_34:s0:c197,c257,c512,c768")
        .string(9, "ai.localstudio.app")
        .message(10) { varint(1, 11); string(2, "SIGSEGV"); varint(3, 1); string(4, "SEGV_MAPERR"); varint(8, 1); varint(9, 0) }
        .message(15) { string(1, "null pointer dereference") }
        // A parked worker first -- what the strings scan latched onto in build #445.
        .thread(4250, "DefaultDispatch") {
            frame(0x1000, "syscall", 32, "/apex/com.android.runtime/lib64/bionic/libc.so")
            frame(0x2000, "art::Thread::Park(bool, long)", 400, "/apex/com.android.art/lib64/libart.so")
        }
        .thread(4301, "DefaultDispatch") {
            frame(0x5a10, "whisper_full_with_state", 1208, "/data/app/ai.localstudio.app/lib/arm64/libwhisper_jni.so")
            frame(0x6b20, "Java_ai_localstudio_whisper_WhisperBridge_nativeTranscribe", 96, "/data/app/ai.localstudio.app/lib/arm64/libwhisper_jni.so")
        }
        .varint(22, 16384) // page_size, after the threads
        .toBytes()

    @Test
    fun the_crashing_thread_is_the_one_the_tombstone_names() {
        val crash = TombstoneDecoder.decode(tombstone)!!
        assertEquals("SIGSEGV", crash.signal)
        assertEquals("SEGV_MAPERR", crash.signalCode)
        assertEquals(0L, crash.faultAddress)
        assertEquals(listOf("null pointer dereference"), crash.causes)
        assertEquals(4301, crash.threadId)
        assertEquals(listOf("whisper_full_with_state", "Java_ai_localstudio_whisper_WhisperBridge_nativeTranscribe"), crash.frames.map { it.functionName })
        assertEquals(0x5a10L, crash.frames.first().relPc)
    }

    @Test
    fun the_log_text_reads_like_a_backtrace() {
        val text = TombstoneDecoder.format(TombstoneDecoder.decode(tombstone)!!)
        assertTrue(text, text.startsWith("signal SIGSEGV (SEGV_MAPERR), fault address 0x0\n"))
        assertTrue(text, "cause: null pointer dereference" in text)
        assertTrue(text, "crashing thread 4301 \"DefaultDispatch\":" in text)
        assertTrue(text, "#00 pc 00005a10  /data/app/ai.localstudio.app/lib/arm64/libwhisper_jni.so (whisper_full_with_state+1208)" in text)
        assertTrue("the parked worker is not in it", "Thread::Park" !in text)
    }

    @Test
    fun anything_else_is_not_a_tombstone() {
        assertNull(TombstoneDecoder.decode("----- pid 4242 at 2026-10-04 -----\nCmd line: ai.localstudio.app\n".toByteArray()))
        assertNull(TombstoneDecoder.decode(ByteArray(0)))
        assertNull(TombstoneDecoder.decode(tombstone.copyOf(tombstone.size / 2)))
    }
}
