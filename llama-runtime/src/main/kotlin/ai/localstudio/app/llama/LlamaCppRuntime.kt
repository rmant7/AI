package ai.localstudio.app.llama

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.ImageNotSeenException
import ai.localstudio.core.runtime.InsufficientMemoryException
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.ModelMemory
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.TextModelHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * How much free RAM must be visible, relative to the projector *file's*
 * size, before [LlamaCppRuntime.load] will even attempt [LlamaBridge.nativeLoadMmproj].
 * The file is only the vision encoder's weights; encoding an actual image
 * needs activation buffers on top of that — but for a single, already-
 * downscaled image (see ChatActivity.attachImage's 1280px cap) at inference
 * time, not training, that is nowhere near another full copy of the
 * weights. 2.0 was a first, deliberately-cautious guess that turned out to
 * block a real device with genuine headroom to spare (1633MB free against
 * a ~990MB projector); 1.4 still leaves real margin above the bare weight
 * size without being the reason vision never gets to run at all.
 */
const val MMPROJ_RAM_SAFETY_FACTOR = 1.4

/**
 * Same reasoning as [MMPROJ_RAM_SAFETY_FACTOR], for the main GGUF itself —
 * a real device report: a 5.2GB model's load started with only 3.3GB free,
 * ran the whole process out of memory, and got killed by Android's OOM
 * killer partway through [LlamaBridge.nativeLoad] — not a catchable
 * exception, since the process was gone before any Kotlin code downstream
 * could run. [SuitabilityScorer]'s own admission check runs against a
 * *static*, total-RAM-derived budget upstream of this call and had already
 * let this candidate through; it has no way to see momentary pressure from
 * whatever else happens to be resident right now. Lower than the mmproj
 * factor (1.3 vs 1.4) since the main model's KV cache and compute buffers
 * are proportionally smaller relative to its own weights than an image
 * projector's activation buffers are relative to its — a real device's
 * post-load free-RAM logs bore this out (an ~1.86GB model settling around
 * ~2.3-2.4GB actually held).
 */
private const val MAIN_MODEL_RAM_SAFETY_FACTOR = 1.3

/**
 * The kernel's own answer to "how much could a new allocation actually get
 * without swapping heavily" — unlike a plain MemFree/Cached split, already
 * accounts for how much of Cached/Slab is genuinely reclaimable right now.
 * Exactly the right number for [LlamaBridge.nativeLoad]'s own memory model:
 * `llama_jni.cpp` loads a GGUF with `LLAMA_LOAD_MODE_MMAP`, so the weights
 * are file-backed pages the kernel can evict and re-page on demand, not
 * anonymous heap that needs genuinely free RAM up front.
 *
 * Real device report: Settings -> Running services, split two ways —
 * "cached processes" read ~4-5GB free (matching [android.app.ActivityManager]
 * closely) while "running processes" read ~10GB free, with not one process
 * in the cached list over ~200MB. The ~5-6GB gap between those two views is
 * reclaimable page cache, not memory any process is actually holding — the
 * same category MemAvailable is built to count and
 * [android.app.ActivityManager.MemoryInfo.availMem] is not. See
 * [MAIN_MODEL_RAM_SAFETY_FACTOR]'s own doc comment for how this changes the
 * pre-flight refusal below.
 */
fun readMemAvailableBytes(): Long? = runCatching {
    File("/proc/meminfo").useLines { lines ->
        lines.firstOrNull { it.startsWith("MemAvailable:") }
            ?.removePrefix("MemAvailable:")?.trim()?.removeSuffix("kB")?.trim()?.toLongOrNull()
            ?.let { it * 1024 }
    }
}.getOrNull()

/**
 * The kernel's own memory accounting, straight from the same source
 * Android's Settings app reads for its Running services screen — unlike
 * [android.app.ActivityManager.MemoryInfo.availMem], not (as far as this
 * app can tell) subject to the reduced precision a non-privileged app's
 * [android.app.ActivityManager.getMemoryInfo] call is known to get.
 * Best-effort: some devices' SELinux policy denies a regular app read
 * access to `/proc/meminfo` outright, in which case this returns null and
 * callers fall back to whatever [android.app.ActivityManager] already gave
 * them.
 *
 * Every field a real device report asked for, not just MemAvailable: a
 * single number rules ActivityManager's own precision in or out, but
 * Cached/SReclaimable/Buffers (what the kernel considers reclaimable, vs
 * what Settings' own more liberal estimate might count) and SwapTotal/
 * SwapFree are what actually explains *why* two "available" figures
 * disagree, once they do.
 */
private fun readProcMeminfo(): String? = runCatching {
    val wanted = listOf(
        "MemTotal", "MemFree", "MemAvailable", "Buffers", "Cached", "SwapCached", "SReclaimable", "SUnreclaim",
        "Shmem", "AnonPages", "Mapped", "Slab", "SwapTotal", "SwapFree",
    )
    val values = File("/proc/meminfo").useLines { lines ->
        lines.mapNotNull { line ->
            val name = wanted.firstOrNull { line.startsWith("$it:") } ?: return@mapNotNull null
            val kb = line.removePrefix("$name:").trim().removeSuffix("kB").trim().toLongOrNull()
                ?: return@mapNotNull null
            name to kb / 1024
        }.toMap()
    }
    if (values.isEmpty()) null else wanted.mapNotNull { name -> values[name]?.let { "$name=${it}MB" } }.joinToString(" ")
}.getOrNull()

/**
 * This process's resident set size (VmRSS) in bytes — anonymous memory plus
 * every file-backed page actually paged in, so memory-mapped model weights
 * count as soon as they're touched. Null when /proc/self/status can't be read.
 */
/**
 * The resident set split by what backs it: anonymous memory (KV cache,
 * compute buffers, weights llama.cpp copied out of the file, e.g. repacked
 * for i8mm) versus pages of mapped files (the GGUF's own pages, which the
 * kernel can drop and read back -- slower, not fatal). VmRSS alone is their
 * sum; deciding whether a model fits needs to know which part grew.
 */
internal data class RssParts(val totalBytes: Long, val anonBytes: Long?, val fileBytes: Long?)

internal fun readSelfRssParts(): RssParts? = runCatching {
    var total: Long? = null
    var anon: Long? = null
    var file: Long? = null
    File("/proc/self/status").useLines { lines ->
        for (line in lines) {
            fun kb(prefix: String) = line.removePrefix(prefix).trim().removeSuffix("kB").trim().toLongOrNull()?.times(1024)
            when {
                line.startsWith("VmRSS:") -> total = kb("VmRSS:")
                line.startsWith("RssAnon:") -> anon = kb("RssAnon:")
                line.startsWith("RssFile:") -> file = kb("RssFile:")
            }
        }
    }
    total?.let { RssParts(it, anon, file) }
}.getOrNull()

/**
 * What slows a turn down besides the model itself, read before and after
 * it: major page faults (pages read back from storage -- mapped weights
 * the kernel dropped, or anything swapped out) and how much of this process
 * is in swap (zram). A 2B model whose image turn takes minutes is either
 * reading its weights back from flash, swapping, or throttled; these and
 * the thermal status (see [LlamaCppRuntime]'s deviceConditions) tell which.
 */
internal data class TurnConditions(val majorFaults: Long?, val swapBytes: Long?) {
    fun since(before: TurnConditions): String = listOfNotNull(
        majorFaults?.let { now -> before.majorFaults?.let { "major page faults +${now - it}" } },
        swapBytes?.let { now -> "swap ${now / 1_000_000} MB" + (before.swapBytes?.let { String.format(java.util.Locale.ROOT, " (%+d)", (now - it) / 1_000_000) } ?: "") },
    ).joinToString(", ")

    companion object {
        fun read(): TurnConditions {
            // Field 12 of /proc/self/stat, counted after the command name's closing parenthesis (which may contain spaces).
            val majflt = runCatching {
                File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')[9].toLong()
            }.getOrNull()
            val swap = runCatching {
                File("/proc/self/status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("VmSwap:") }?.removePrefix("VmSwap:")?.trim()?.removeSuffix("kB")?.trim()?.toLongOrNull()?.times(1024)
                }
            }.getOrNull()
            return TurnConditions(majflt, swap)
        }
    }
}

internal fun readSelfRssBytes(): Long? = runCatching {
    File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("VmRSS:") }
            ?.removePrefix("VmRSS:")?.trim()?.removeSuffix("kB")?.trim()?.toLongOrNull()
            ?.let { it * 1024 }
    }
}.getOrNull()

/**
 * This process's own memory footprint at the moment of a refusal — a real
 * device report asked for this specifically: whether the app's *own*
 * resident set (semantic memory not actually freed, a previous model's
 * allocation lingering, ...) already accounts for some of the gap between
 * what [readProcMeminfo] and [android.app.ActivityManager] each report,
 * rather than something external. Same best-effort shape as
 * [readProcMeminfo].
 */
private fun readProcSelfStatus(): String? = runCatching {
    val wanted = listOf("VmRSS", "VmSize", "RssAnon", "RssFile", "RssShmem")
    val values = File("/proc/self/status").useLines { lines ->
        lines.mapNotNull { line ->
            val name = wanted.firstOrNull { line.startsWith("$it:") } ?: return@mapNotNull null
            val kb = line.removePrefix("$name:").trim().removeSuffix("kB").trim().toLongOrNull()
                ?: return@mapNotNull null
            name to kb / 1024
        }.toMap()
    }
    if (values.isEmpty()) null else wanted.mapNotNull { name -> values[name]?.let { "$name=${it}MB" } }.joinToString(" ")
}.getOrNull()

/**
 * On-device inference. The same [ModelRuntime] contract as the remote runtime,
 * which is what lets the router, pipelines, context engine and memory stay
 * untouched: only the registration changes.
 */
class LlamaCppRuntime(
    private val contextTokens: Int = LlamaBridge.DEFAULT_CONTEXT_TOKENS,
    private val threads: Int = LlamaBridge.defaultThreads(),
    /**
     * Every stage worth timing gets a line here — load, first token, done or
     * cancelled — because a hang with no crash and no exception (the native
     * call is simply slow, or genuinely stuck) previously left nothing to
     * look at afterward beyond "no response after 180s". Defaults to a no-op
     * so tests and any other caller don't need a real [AppLog][ai.localstudio.app.log.AppLog].
     */
    private val log: (tag: String, message: String) -> Unit = { _, _ -> },
    /**
     * Free RAM right now, read fresh whenever [load] needs it — never cached,
     * since it changes constantly and the whole point is to catch the device
     * being tighter *now* than [binding]'s own admission check assumed.
     *
     * That check ([ai.localstudio.core.runtime.RuntimeManager]'s budget
     * comparison) is sized off the main GGUF alone, deliberately: folding a
     * vision projector's cost into the *same* gate would risk rejecting a
     * model outright — text and all — on a device where only the vision
     * *add-on* doesn't fit, for a model that worked fine as text-only before
     * mmproj existed. The projector is admitted separately, when an image
     * first needs it -- through [memory] when there is one, by this reading
     * alone otherwise -- and an image it cannot get memory for is refused
     * as unseen, never answered as text. Defaults to "assume plenty" so
     * tests and any other caller don't need a real device.
     */
    private val availableRamBytes: () -> Long = { Long.MAX_VALUE },
    /**
     * Everything else [android.app.ActivityManager.MemoryInfo] carries
     * beyond the single number [availableRamBytes] reads — totalMem,
     * threshold, lowMemory — read fresh alongside [availableRamBytes]
     * whenever the pre-flight RAM refusal below actually fires. Defaults to
     * empty so tests and any other caller don't need a real device.
     */
    private val memoryDiagnostics: () -> String = { "" },
    /**
     * Where a vision projector's RAM is admitted when the first image turn
     * needs it: the same manager, the same budget as the model's own load
     * (see [ModelMemory]) -- resident means weights + projector, and a
     * projector that does not fit is refused there, out loud. Null (tests,
     * a caller with no manager): the live free-RAM reading decides alone.
     */
    private val memory: ModelMemory? = null,
    /** Decided at every load, per model file: map its weights or read them into memory -- see [ai.localstudio.core.runtime.WeightsLoadPolicy]. */
    private val weightsLoading: (File) -> ai.localstudio.core.runtime.WeightsLoadDecision = {
        ai.localstudio.core.runtime.WeightsLoadDecision(true, "mapped (default)")
    },
    /** The device's own state, for each turn's log line (the thermal status); empty when unknown -- see [TurnConditions]. */
    private val deviceConditions: () -> String = { "" },
) : ModelRuntime {

    override val kind: RuntimeKind = RuntimeKind.LLAMA_CPP

    /**
     * The higher of [availableRamBytes] (ActivityManager) and
     * [readMemAvailableBytes] (the kernel's own MemAvailable) — see
     * [readMemAvailableBytes]'s own doc comment for why ActivityManager
     * alone under-counts headroom this app's mmap-based load can actually
     * use. `maxOf`, not a straight replacement: [readMemAvailableBytes] can
     * read null (SELinux-restricted devices), and even where it reads a
     * real number, trusting whichever source is more generous is strictly
     * safer than trusting whichever happens to run first — this can only
     * ever let through a load [availableRamBytes] alone would have refused,
     * never the reverse.
     */
    private fun effectiveHeadroomBytes(): Long = maxOf(availableRamBytes(), readMemAvailableBytes() ?: 0L)

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean =
        binding.runtime == RuntimeKind.LLAMA_CPP &&
            LlamaBridge.isAvailable &&
            File(binding.artifact).isFile

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
        if (!LlamaBridge.isAvailable) {
            throw ModelLoadException("The llama.cpp library is not available for this device's ABI")
        }
        val file = File(binding.artifact)
        if (!file.isFile) {
            throw ModelLoadException("Model file is missing: ${binding.artifact}")
        }
        run {
            val fileBytes = file.length()
            val headroom = effectiveHeadroomBytes()
            val wantBytes = (fileBytes * MAIN_MODEL_RAM_SAFETY_FACTOR).toLong()
            if (fileBytes > 0 && headroom < wantBytes) {
                // Explicit product decision, not a bug: this used to throw
                // ModelLoadException here and never attempt the native load
                // at all. A real device report pushed back on that —
                // refusing pre-emptively means every "not enough RAM" report
                // is this heuristic's own guess (file size * 1.3 against a
                // headroom estimate that has itself been wrong twice this
                // same day), never a confirmed fact. Logged, then let
                // through: if the device really can't do it, Android's own
                // OOM killer firing produces a REASON_LOW_MEMORY exit this
                // app already captures on next launch (AppLog.
                // recordProcessExitIfNotable) — real evidence instead of a
                // second-hand estimate, at the cost of losing the current
                // turn if the guess turns out right. Once that evidence
                // exists either way, the actual next step this was blocking
                // (splitting a load into a required text stage and a
                // skippable mmproj stage) is already how mmproj itself
                // works below — this main-model load has no such split of
                // its own to fall back to.
                log(
                    "LOCAL_LOAD",
                    "${file.name}: LOW ON RAM — only ${headroom / 1_000_000}MB free " +
                        "(max of ActivityManager.availMem and /proc/meminfo MemAvailable), " +
                        "want ~${wantBytes / 1_000_000}MB — attempting anyway" +
                        " — ${memoryDiagnostics()}" +
                        (readProcMeminfo()?.let { " — /proc/meminfo: $it" } ?: " — /proc/meminfo unreadable") +
                        (readProcSelfStatus()?.let { " — /proc/self/status: $it" } ?: " — /proc/self/status unreadable"),
                )
            }
        }

        val bridge = LlamaBridge()
        val loadStart = System.currentTimeMillis()
        // Free RAM before/after, same reasoning and same before/after-only
        // shape as WHISPER_LOAD's own logging (see that log line's doc
        // comment): a load that takes far longer than its size class
        // predicts is otherwise invisible here until someone asks "was
        // something else holding memory at the time" and has no log line to
        // check.
        val weights = weightsLoading(file)
        val mapped = weights.mapped
        log(
            "LOCAL_LOAD",
            "${file.name}: starting (ctx=$contextTokens, threads=$threads, weights ${if (mapped) "mapped" else "read into memory"} -- ${weights.reason}, " +
                "free RAM: ${effectiveHeadroomBytes() / (1024 * 1024)} MB)",
        )

        // nativeLoad() is a single blocking JNI call — llama_model_load_from_file()
        // and llama_init_from_model() have no cancellation hook of their own,
        // unlike generate()'s per-chunk checks. Called directly inside this
        // suspend function, a Stop tap or a timeout during a slow load (a
        // 12B model has taken over 10 minutes on this device) would have no
        // effect until the call finally returned — a coroutine cancellation
        // can only be observed at a suspension point, and there wasn't one.
        // Running it on a detached worker (its own SupervisorJob, not a
        // child of the caller) means load() itself still responds to
        // cancellation immediately, while the worker keeps running to
        // completion in the background.
        //
        // Ownership of the produced handle is decided exactly once, through
        // [result]: the worker's complete() and the caller's
        // completeExceptionally() race on the same CompletableDeferred and
        // only one of them wins. The worker frees a handle nobody will ever
        // receive while still holding nativeOpMutex — so the next native op,
        // possibly the next model's load, can't start until it's gone. The
        // previous version freed it from invokeOnCompletion instead, outside
        // nativeOpMutex, concurrently with whatever native op ran next — and
        // RuntimeManager, which had already released its own lock, could
        // admit and start loading another model while this one was still
        // resident: two multi-GB models at once, the exact OOM this class is
        // built to avoid.
        val result = CompletableDeferred<Long>()
        // Written before complete(): read back only when the caller's
        // completeExceptionally() lost the race, i.e. complete() already
        // happened — visibility comes from the deferred's own atomic state.
        val producedRef = java.util.concurrent.atomic.AtomicLong(0L)
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            LlamaBridge.nativeOpMutex.withLock {
                // Only the caller's completeExceptionally() can complete
                // [result] before this point: it gave up while this worker
                // was still queued behind another native op, so there's
                // nothing to load for anyone — skip the (possibly minutes
                // long) load entirely instead of loading just to free it.
                if (result.isCompleted) {
                    log("LOCAL_LOAD", "${file.name}: abandoned before the load started — skipped")
                    return@withLock
                }
                val produced = runCatching { bridge.nativeLoad(file.absolutePath, contextTokens, threads, mapped) }.getOrDefault(0L)
                producedRef.set(produced)
                if (!result.complete(produced) && produced != 0L) {
                    bridge.nativeFree(produced)
                    log("LOCAL_LOAD", "${file.name}: abandoned load finished — native model freed")
                }
            }
        }
        val handle = try {
            result.await()
        } catch (e: CancellationException) {
            log("LOCAL_LOAD", "${file.name}: abandoned after ${System.currentTimeMillis() - loadStart}ms, still loading in the background")
            // The next admission (RuntimeManager's beforeAdmission, see
            // awaitPendingNativeWork) waits for this worker, not just for
            // nativeOpMutex, so its budget is read only after this model is
            // physically gone.
            trackPendingNativeWork(worker)
            if (!result.completeExceptionally(e)) {
                // The worker won the race: the handle already exists and,
                // since this caller is giving up, belongs to nobody else.
                val produced = producedRef.get()
                if (produced != 0L) trackPendingNativeWork(releaseInBackground(bridge, produced, file.name))
            }
            throw e
        }
        val loadMs = System.currentTimeMillis() - loadStart
        if (handle == 0L) {
            log("LOCAL_LOAD", "${file.name}: FAILED after ${loadMs}ms")
            throw ModelLoadException("llama.cpp could not load ${file.name}")
        }
        // From here until the LlamaTextModel below is returned, this function
        // is the handle's only owner — and there are still suspension points
        // (nativeOpMutex.withLock for the projector and the encoder check)
        // where a cancellation, or any other throw, would otherwise leave a
        // multi-GB native model with no reference to it at all, never freed
        // for the life of the process.
        try {
            log("LOCAL_LOAD", "${file.name}: ready in ${loadMs}ms (free RAM: ${effectiveHeadroomBytes() / (1024 * 1024)} MB)")

            // Not loaded here: the projector (~1 GB for Gemma's) is only
            // needed for a turn that actually carries an image, and loading
            // it eagerly cost that much RAM on every text-only chat (real
            // device log: gemma-4-e4b-it-q4, "mmproj loaded" on a plain text
            // turn). [LlamaTextModel.generate] admits and loads it the first
            // time an image arrives.
            //
            // Only if a projector was actually downloaded for this model — a
            // model with none stays text-only, and an image sent to it is
            // refused as unseen. Admission goes through [memory] (the same
            // budget as every load) and refuses when the projector does not
            // fit — loading it anyway was observed on a real device to
            // reliably run the whole process out of memory a turn or two
            // later (Android's OOM killer, not a catchable Kotlin exception).
            // The margin is deliberately generous: the projector's *file*
            // size is only its weights, and encoding an image needs
            // activation buffers on top that scale with the same size.
            val vision: ProjectorLoader? = binding.mmprojArtifact
                ?.let(::File)
                ?.takeIf { it.length() > 0 }
                ?.let { projector ->
                    val needBytes = (projector.length() * MMPROJ_RAM_SAFETY_FACTOR).toLong()
                    ProjectorLoader(
                        file = projector,
                        admit = {
                            if (memory != null) {
                                try {
                                    memory.reserve(model.id, needBytes, "vision projector ${projector.name}")
                                    null
                                } catch (e: InsufficientMemoryException) {
                                    e.message
                                }
                            } else {
                                val headroom = availableRamBytes()
                                if (headroom < needBytes) "only ${headroom / 1_000_000}MB free, want ~${needBytes / 1_000_000}MB" else null
                            }
                        },
                        giveBack = { memory?.unreserve(model.id, needBytes) },
                        load = { runCatching { bridge.nativeLoadMmproj(handle, projector.path, threads) }.getOrDefault(false) },
                    )
                }

            // Read once here rather than on every generate() call — it's a read
            // of static model metadata (llama_model_has_encoder), unchanging for
            // the life of this handle.
            val hasEncoder = LlamaBridge.nativeOpMutex.withLock {
                runCatching { bridge.nativeHasEncoder(handle) }.getOrDefault(false)
            }
            if (hasEncoder) log("LOCAL_LOAD", "${file.name}: encoder-decoder model — routing generate() through nativeGenerateT5")

            return LlamaTextModel(model.id, binding.effectiveRequiredRamBytes, bridge, handle, vision, hasEncoder, log, deviceConditions)
        } catch (t: Throwable) {
            log("LOCAL_LOAD", "${file.name}: abandoned after load (${t.javaClass.simpleName}) — freeing native model")
            trackPendingNativeWork(releaseInBackground(bridge, handle, file.name))
            throw t
        }
    }

    /** Frees [handle] under nativeOpMutex, off the caller — who may already be cancelled and can't suspend on the lock itself. */
    private fun releaseInBackground(bridge: LlamaBridge, handle: Long, fileName: String): Job =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            LlamaBridge.nativeOpMutex.withLock { bridge.nativeFree(handle) }
            log("LOCAL_LOAD", "$fileName: abandoned native model freed")
        }

    companion object {
        /**
         * Native loads/frees that outlived the caller who started them —
         * see load()'s own comments. Only abandoned work goes here; a load
         * someone is still waiting for is already serialized by
         * RuntimeManager's own lock.
         */
        private val pendingNativeWork = java.util.Collections.synchronizedSet(mutableSetOf<Job>())

        private fun trackPendingNativeWork(job: Job) {
            // Added before the completion handler is registered: an
            // already-finished job fires its handler immediately, which must
            // find it in the set to remove it.
            pendingNativeWork += job
            job.invokeOnCompletion { pendingNativeWork -= job }
        }

        /**
         * Suspends until every abandoned native load/free has physically
         * finished. RuntimeManager calls this before reading its budget, so
         * a model still loading (or being freed) in the background is never
         * mistaken for free memory by the next admission check.
         */
        suspend fun awaitPendingNativeWork() {
            while (true) {
                val snapshot = synchronized(pendingNativeWork) { pendingNativeWork.toList() }
                if (snapshot.isEmpty()) return
                snapshot.forEach { it.join() }
            }
        }

        fun hasPendingNativeWork(): Boolean = pendingNativeWork.isNotEmpty()
    }
}

/**
 * A model's vision projector, not yet loaded: [admit] asks for its RAM
 * (null = admitted, otherwise why not), [load] loads it -- under
 * nativeOpMutex, which [admit] must never be called under -- and
 * [giveBack] returns an admission whose load failed.
 */
private class ProjectorLoader(
    val file: File,
    val admit: suspend () -> String?,
    val giveBack: suspend () -> Unit,
    val load: () -> Boolean,
)

/** Not a native code: an image turn refused because the model could not see the image. */
private const val IMAGE_NOT_SEEN = -100

private class LlamaTextModel(
    override val modelId: String,
    override val ramBytes: Long,
    private val bridge: LlamaBridge,
    private val handle: Long,
    /** This model's projector, loaded on the first image turn; null when it has none on disk. */
    private val vision: ProjectorLoader?,
    /** Whether this handle is an encoder-decoder (T5-family) model — see [generate]. */
    private val hasEncoder: Boolean,
    private val log: (tag: String, message: String) -> Unit,
    private val deviceConditions: () -> String,
) : TextModelHandle {

    // nativeCancel()/Job.cancel() only ask a blocking native call to stop at
    // its next checkpoint — they cannot interrupt it, so "the Job was
    // cancelled" does not mean "the native call already returned". close()
    // waits on this before freeing the context (see close() below) so a
    // model eviction can never free memory a still-running llama_decode()
    // call is using out from under it.
    private val activeWorker = AtomicReference<Job?>(null)

    /** Set once [vision] loads; a refused or failed attempt is retried on the next image turn. */
    @Volatile
    private var visionLoaded = false

    /** So the chat-template line lands in the log once per model, not once per turn. */
    private val templateLogged = AtomicBoolean(false)

    override fun generate(request: GenerationRequest): Flow<String> = callbackFlow {
        val start = System.currentTimeMillis()
        val conditionsBefore = TurnConditions.read()
        var tokenCount = 0
        var firstTokenLogged = false
        val completed = AtomicBoolean(false)
        val image = request.images.firstOrNull()
        log(
            "LOCAL_GENERATE",
            "$modelId: starting (prompt=${request.prompt.length} chars, maxTokens=${request.maxTokens}" +
                (if (image != null) ", with ${request.images.size} image(s)" else "") + ")",
        )

        val sink = object : LlamaBridge.TokenSink {
            override fun onToken(text: String) {
                if (!firstTokenLogged) {
                    firstTokenLogged = true
                    log("LOCAL_GENERATE", "$modelId: first token after ${System.currentTimeMillis() - start}ms")
                    // Once per model, and here rather than at load time:
                    // asked before the first turn it can only report "not
                    // attempted yet", which is precisely the part worth
                    // knowing — whether this model's own turn markers were
                    // really used, or a fallback scaffold stood in for them.
                    if (templateLogged.compareAndSet(false, true)) {
                        runCatching { bridge.nativeChatTemplateInfo(handle) }
                            .onSuccess { log("LOCAL_GENERATE", "$modelId: chat template $it") }
                    }
                }
                tokenCount++
                trySend(text)
            }
        }

        val worker = CoroutineScope(Dispatchers.IO).launch {
            // The user asked for the model, not for a background chore: tell
            // Android this thread matters before the native pool inherits it.
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            }
            // A real device report: this whole launch body used to have
            // nothing catching a thrown exception (as opposed to the
            // negative-return-code failure path just below, which was
            // already handled) — bridge.nativeGenerateT5 throwing anything
            // (an OutOfMemoryError allocating a large buffer is the obvious
            // candidate on a memory-constrained device) had nothing to stop
            // it propagating out of this coroutine and killing the whole
            // process, logged by Android as a plain "unhandled exception"
            // with no indication which exception or where. Caught and
            // logged here instead: one translation fails cleanly, and the
            // exception's own message/stack finally reaches the same
            // user-copyable log everything else in this app does.
            var notSeen: String? = null
            val produced = try {
                // Admitted before nativeOpMutex, never under it: RuntimeManager
                // loads models under its own lock and then takes nativeOpMutex,
                // so asking it for memory from inside would invert that order.
                var admitted = false
                if (image != null && !visionLoaded) {
                    notSeen = when (vision) {
                        null -> "this model has no vision projector"
                        else -> vision.admit()?.let { "no memory for its projector -- $it" }
                    }
                    admitted = vision != null && notSeen == null
                }
                LlamaBridge.nativeOpMutex.withLock {
                    if (admitted && vision != null) {
                        visionLoaded = vision.load()
                        log("LOCAL_LOAD", "$modelId: mmproj ${if (visionLoaded) "loaded" else "FAILED to load"} on first image from ${vision.file.path}")
                        if (!visionLoaded) notSeen = "its projector did not load"
                    }
                    if (image != null && !visionLoaded) {
                        // Never answered as text instead: a reply to a question about an image the
                        // model never saw reads like an answer and is not one.
                        log("LOCAL_GENERATE", "$modelId: image NOT seen -- $notSeen")
                        IMAGE_NOT_SEEN
                    } else if (hasEncoder) {
                    // T5-family (MADLAD-400): request.prompt is already the
                    // model's own expected input (`<2xx> source text`, built
                    // by TranslationActivity) — there is no chat template, no
                    // system prompt, and no vision support for this
                    // architecture, so none of that applies here.
                    bridge.nativeGenerateT5(
                        handle = handle,
                        sourceText = request.prompt,
                        maxTokens = request.maxTokens,
                        temperature = request.temperature.toFloat(),
                        topP = request.topP.toFloat(),
                        topK = request.topK,
                        repeatPenalty = request.repeatPenalty.toFloat(),
                        callback = sink,
                    )
                } else if (image != null && visionLoaded) {
                    // ImageRef.uri is always a "data:<mime>;base64,<payload>" string
                    // here, never a content:// or file path — ChatActivity.attachImage()
                    // builds it that way specifically because core/openai are plain JVM
                    // modules with no Android Context to resolve a real URI against.
                    // Every attached image, in order -- one that will not decode fails the turn rather than being dropped.
                    val decoded = request.images.map { ref ->
                        runCatching {
                            android.util.Base64.decode(ref.uri.substringAfter(",", ""), android.util.Base64.NO_WRAP)
                        }.getOrNull()?.takeIf { it.isNotEmpty() }
                    }
                    if (decoded.any { it == null }) {
                        -1
                    } else {
                        bridge.nativeGenerateWithImages(
                            handle = handle,
                            systemPrompt = request.systemPrompt,
                            userPrompt = request.prompt,
                            images = decoded.filterNotNull().toTypedArray(),
                            maxTokens = request.maxTokens,
                            temperature = request.temperature.toFloat(),
                            topP = request.topP.toFloat(),
                            topK = request.topK,
                            repeatPenalty = request.repeatPenalty.toFloat(),
                            callback = sink,
                        )
                    }
                } else {
                    bridge.nativeGenerate(
                        handle = handle,
                        systemPrompt = request.systemPrompt,
                        userPrompt = request.prompt,
                        maxTokens = request.maxTokens,
                        temperature = request.temperature.toFloat(),
                        topP = request.topP.toFloat(),
                        topK = request.topK,
                        repeatPenalty = request.repeatPenalty.toFloat(),
                        callback = sink,
                    )
                }
                }.also {
                    // Admitted, then failed to load: the budget gets its bytes back.
                    if (admitted && !visionLoaded) vision?.giveBack()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                completed.set(true)
                val elapsedMs = System.currentTimeMillis() - start
                log(
                    "LOCAL_GENERATE",
                    "$modelId: THREW ${e::class.java.simpleName}: ${e.message} after ${elapsedMs}ms, " +
                        "$tokenCount tokens\n${e.stackTraceToString().take(4000)}",
                )
                close(e)
                return@launch
            }
            completed.set(true)
            val elapsedMs = System.currentTimeMillis() - start
            if (produced < 0) {
                // An image turn's native cause (what llama.cpp/mtmd logged, and which chunk failed) -- logcat is out of reach on a phone.
                val cause = if (image != null && produced != IMAGE_NOT_SEEN) {
                    runCatching { bridge.nativeLastLoadError() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.replace('\n', ' ')?.takeLast(600)
                } else {
                    null
                }
                log("LOCAL_GENERATE", "$modelId: FAILED code=$produced after ${elapsedMs}ms, $tokenCount tokens" + (cause?.let { " -- native: $it" } ?: ""))
                close(
                    if (produced == IMAGE_NOT_SEEN) {
                        ImageNotSeenException(notSeen ?: "unknown")
                    } else {
                        IllegalStateException("Generation failed with code $produced")
                    },
                )
            } else {
                log("LOCAL_GENERATE", "$modelId: done in ${elapsedMs}ms, $produced tokens")
                runCatching { bridge.nativeLastTurnStats(handle) }
                    .onSuccess { log("LOCAL_GENERATE", "$modelId: $it") }
                listOf(TurnConditions.read().since(conditionsBefore), runCatching(deviceConditions).getOrDefault(""))
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { log("LOCAL_GENERATE", "$modelId: during the turn: ${it.joinToString(", ")}") }
                close()
            }
        }
        activeWorker.set(worker)

        awaitClose {
            // Native generation blocks in C++; cancelling the coroutine alone
            // would leave it running to completion on a background thread.
            if (!completed.get()) {
                log(
                    "LOCAL_GENERATE",
                    "$modelId: cancelled/timed out after ${System.currentTimeMillis() - start}ms, $tokenCount tokens so far",
                )
            }
            bridge.nativeCancel(handle)
            worker.cancel()
        }
        // Tokens arrive faster than a RecyclerView can render them; an unbounded
        // buffer keeps the generating thread from stalling on the UI.
    }.buffer(Channel.UNLIMITED)

    override fun requestCancel() {
        bridge.nativeCancel(handle)
    }

    override fun close() {
        // Blocks (bounded now that prompt processing is chunked and checks
        // cancellation between batches, see llama_jni.cpp — plus however
        // long nativeOpMutex makes this worker wait its turn behind some
        // other model's load or generate, if one happens to be running)
        // until any in-flight native call has genuinely returned — freeing
        // the context while a background thread is still inside
        // llama_decode() using it is a use-after-free, not a graceful stop.
        runBlocking { activeWorker.get()?.join() }
        bridge.nativeFree(handle)
    }
}
