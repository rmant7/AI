package ai.localstudio.app.log

/**
 * Reads the crashing thread out of an Android native-crash tombstone -- the
 * protobuf `ApplicationExitInfo.getTraceInputStream()` hands back for a
 * native crash (AOSP system/core/debuggerd/proto/tombstone.proto). The
 * protobuf wire format is simple enough to walk by hand, so no protobuf
 * dependency for the one message this app ever reads.
 *
 * Why not the printable-strings scan [AppLog] used alone before: a tombstone
 * dumps every thread, and the scan cannot tell which one crashed. Build
 * #445's capture showed a parked coroutine worker (`Unsafe_park`) as the
 * "relevant" window -- a thread that was waiting, not the one that faulted.
 * The tombstone names the crashing thread by id; this follows it.
 *
 * Fields used (tombstone.proto): Tombstone.build_fingerprint=2, tid=6,
 * signal_info=10, abort_message=14, causes=15, threads=16 (map<uint32, Thread>);
 * Signal.name=2, code_name=4, has_fault_address=8, fault_address=9;
 * Cause.human_readable=1; Thread.id=1, name=2, current_backtrace=4;
 * BacktraceFrame.rel_pc=1, function_name=4, function_offset=5, file_name=6,
 * build_id=8.
 */
object TombstoneDecoder {

    data class Frame(
        val relPc: Long,
        val functionName: String?,
        val functionOffset: Long,
        val fileName: String?,
        val buildId: String?,
    )

    data class Crash(
        val signal: String?,
        val signalCode: String?,
        val faultAddress: Long?,
        val causes: List<String>,
        val abortMessage: String?,
        val threadId: Int,
        val threadName: String?,
        val frames: List<Frame>,
    )

    /** Null when [bytes] is not a tombstone this reader understands (then the caller falls back to the strings scan). */
    fun decode(bytes: ByteArray): Crash? = runCatching { decodeOrThrow(bytes) }.getOrNull()

    private fun decodeOrThrow(bytes: ByteArray): Crash? {
        val top = fields(bytes, 0, bytes.size)
        val tid = top.firstOrNull { it.number == 6 }?.varint?.toInt() ?: return null
        val signal = top.firstOrNull { it.number == 10 }?.bytes?.let { fields(it, 0, it.size) }
        val causes = top.filter { it.number == 15 }.mapNotNull { cause ->
            cause.bytes?.let { fields(it, 0, it.size) }?.firstOrNull { it.number == 1 }?.string
        }
        val abort = top.firstOrNull { it.number == 14 }?.string?.takeIf { it.isNotBlank() }

        // map<uint32, Thread>: each entry is a message with key=1, value=2.
        val thread = top.filter { it.number == 16 }.mapNotNull { entry ->
            val kv = entry.bytes?.let { fields(it, 0, it.size) } ?: return@mapNotNull null
            val key = kv.firstOrNull { it.number == 1 }?.varint?.toInt()
            val value = kv.firstOrNull { it.number == 2 }?.bytes
            if (key == tid && value != null) fields(value, 0, value.size) else null
        }.firstOrNull() ?: return null

        val frames = thread.filter { it.number == 4 }.mapNotNull { frame ->
            val f = frame.bytes?.let { fields(it, 0, it.size) } ?: return@mapNotNull null
            Frame(
                relPc = f.firstOrNull { it.number == 1 }?.varint ?: 0,
                functionName = f.firstOrNull { it.number == 4 }?.string?.takeIf { it.isNotEmpty() },
                functionOffset = f.firstOrNull { it.number == 5 }?.varint ?: 0,
                fileName = f.firstOrNull { it.number == 6 }?.string?.takeIf { it.isNotEmpty() },
                buildId = f.firstOrNull { it.number == 8 }?.string?.takeIf { it.isNotEmpty() },
            )
        }
        return Crash(
            signal = signal?.firstOrNull { it.number == 2 }?.string,
            signalCode = signal?.firstOrNull { it.number == 4 }?.string,
            faultAddress = signal?.takeIf { s -> s.any { it.number == 8 && it.varint == 1L } }
                ?.firstOrNull { it.number == 9 }?.varint,
            causes = causes,
            abortMessage = abort,
            threadId = tid,
            threadName = thread.firstOrNull { it.number == 2 }?.string,
            frames = frames,
        )
    }

    /** Readable form for the app log: signal, cause, then the crashing thread's frames, one per line. */
    fun format(crash: Crash, maxFrames: Int = 48): String = buildString {
        append("signal ").append(crash.signal ?: "?")
        crash.signalCode?.let { append(" (").append(it).append(')') }
        crash.faultAddress?.let { append(", fault address 0x").append(java.lang.Long.toHexString(it)) }
        append('\n')
        crash.causes.forEach { append("cause: ").append(it).append('\n') }
        crash.abortMessage?.let { append("abort: ").append(it).append('\n') }
        append("crashing thread ").append(crash.threadId)
        crash.threadName?.let { append(" \"").append(it).append('"') }
        append(":\n")
        crash.frames.take(maxFrames).forEachIndexed { i, f ->
            append("  #").append(i.toString().padStart(2, '0'))
            append(" pc ").append(java.lang.Long.toHexString(f.relPc).padStart(8, '0'))
            append("  ").append(f.fileName ?: "?")
            f.functionName?.let { append(" (").append(it); if (f.functionOffset != 0L) append('+').append(f.functionOffset); append(')') }
            f.buildId?.let { append(" [").append(it.take(16)).append(']') }
            append('\n')
        }
        if (crash.frames.size > maxFrames) append("  ... ").append(crash.frames.size - maxFrames).append(" more frames\n")
    }

    private class Field(val number: Int, val varint: Long?, val bytes: ByteArray?) {
        val string: String? get() = bytes?.toString(Charsets.UTF_8)
    }

    private fun fields(buf: ByteArray, start: Int, end: Int): List<Field> {
        val out = mutableListOf<Field>()
        var pos = start
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < end && shift < 64) { "truncated varint" }
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (pos < end) {
            val key = varint()
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += Field(number, varint(), null)
                1 -> { require(pos + 8 <= end); var v = 0L; for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i)); pos += 8; out += Field(number, v, null) }
                2 -> {
                    val length = varint()
                    require(length >= 0 && pos + length <= end) { "length past end" }
                    out += Field(number, null, buf.copyOfRange(pos, pos + length.toInt()))
                    pos += length.toInt()
                }
                5 -> { require(pos + 4 <= end); var v = 0L; for (i in 0 until 4) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i)); pos += 4; out += Field(number, v, null) }
                else -> throw IllegalArgumentException("unsupported wire type")
            }
        }
        return out
    }
}
