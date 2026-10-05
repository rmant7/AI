package ai.localstudio.app.llama

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.TextModelHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Measures what a local model actually costs this process — its own resident
 * set's peak growth from just before the load through the end of its first
 * complete generation — and records it in [store], where admission
 * ([ai.localstudio.core.runtime.RuntimeManager]'s requiredBytesFor) picks it
 * up in place of the file-size × 1.3 guess.
 *
 * Load *plus* first generation, not the load alone: memory-mapped weights
 * are paged in lazily, and the KV cache and encoder activations only
 * allocate once a prompt runs — MADLAD-400 7B's T5 encoder pass needed
 * headroom well past what "ready" showed. Sampled every
 * [SAMPLE_INTERVAL_MS] rather than read once at the end, since a peak
 * during encoding is freed before generation finishes. Only the first
 * generation of each fresh load is measured; each fresh load is one more
 * sample, merged by maximum.
 *
 * Process RSS, not system free RAM: the latter also moves with every other
 * app and the kernel's own reclaim. Anything else this process loads during
 * the run (another native model) would inflate the number — the safe
 * direction for admission, and visible in the RAM_MEASURE log line.
 */
class RamMeasuringRuntime(
    private val inner: ModelRuntime,
    private val contextTokens: Int,
    private val store: MeasuredRamStore,
    private val log: (tag: String, message: String) -> Unit,
) : ModelRuntime {
    override val kind: RuntimeKind get() = inner.kind

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean = inner.canRun(model, binding)

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
        val loadedMapped = store.loadsMapped(binding.artifact)
        val probe = readSelfRssBytes()?.let { PeakProbe(it) }
        val loaded = try {
            inner.load(model, binding)
        } catch (t: Throwable) {
            probe?.stop()
            throw t
        }
        val handle = loaded as? TextModelHandle ?: run {
            probe?.stop()
            return loaded
        }
        return MeasuredTextHandle(handle, binding.artifact, probe, loadedMapped)
    }

    private inner class MeasuredTextHandle(
        private val handle: TextModelHandle,
        private val artifactPath: String,
        probe: PeakProbe?,
        /** How the weights were loaded, decided before the load (see [MeasuredRamStore.loadsMapped]). */
        private val loadedMapped: Boolean,
    ) : TextModelHandle {
        private val pendingProbe = AtomicReference(probe)

        @Volatile
        private var measuredRequiredBytes: Long? = store.measurementFor(artifactPath, contextTokens)?.requiredBytes

        override val modelId: String get() = handle.modelId

        /** The measured requirement once one exists, so RuntimeManager's resident accounting is real too. */
        override val ramBytes: Long get() = measuredRequiredBytes ?: handle.ramBytes

        override fun generate(request: GenerationRequest): Flow<String> = flow {
            val probe = pendingProbe.getAndSet(null)
            var completed = false
            try {
                emitAll(handle.generate(request))
                completed = true
            } finally {
                probe?.let { finish(it, completed, withImage = request.images.isNotEmpty()) }
            }
        }

        private fun finish(probe: PeakProbe, completed: Boolean, withImage: Boolean) {
            val deltaBytes = probe.stop()
            val split = probe.split()
            val prefix = "$modelId ctx=$contextTokens"
            // The vision projector loads only on an image turn (see
            // LlamaCppRuntime.load), adding ~1 GB for Gemma's. Recording that
            // as the model's cost would make every later text-only chat need
            // it too — real device report: 8.5 GB demanded of a text chat.
            if (withImage) {
                log("RAM_MEASURE", "$prefix: first run carried an image (projector loaded) — not recorded")
                return
            }
            if (!completed) {
                log("RAM_MEASURE", "$prefix: first run didn't complete — not recorded")
                return
            }
            if (deltaBytes <= 0L) {
                log("RAM_MEASURE", "$prefix: no measurable growth (${mb(deltaBytes)} MB) — not recorded")
                return
            }
            // Stored under the way it was loaded -- before the profile below can change that way for the next load.
            val merged = store.record(artifactPath, contextTokens, deltaBytes)
            // What decides how this file loads from now on: only a mapped load's anonymous growth says
            // whether llama.cpp copied the weights out of the file.
            if (loadedMapped) probe.anonymousGrowth()?.takeIf { it > 0 }?.let { store.recordMappedAnonymous(artifactPath, it) }
            if (merged == null) return
            measuredRequiredBytes = merged.requiredBytes
            log(
                "RAM_MEASURE",
                "$prefix: load + first generation peak +${mb(deltaBytes)} MB$split — stored peak ${mb(merged.peakBytes)} MB " +
                    "over ${merged.sampleCount} run(s), admission now requires ${mb(merged.requiredBytes)} MB",
            )
        }

        override fun requestCancel() = handle.requestCancel()

        override fun close() {
            pendingProbe.getAndSet(null)?.stop()
            handle.close()
        }
    }

    private class PeakProbe(private val baselineBytes: Long) {
        private val peakBytes = AtomicLong(baselineBytes)
        private val baselineParts = readSelfRssParts()

        /** The split at the peak sample -- see [RssParts]: which part of the growth was anonymous, which file pages. */
        @Volatile
        private var peakParts: RssParts? = baselineParts

        private val sampler = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                sample()
                delay(SAMPLE_INTERVAL_MS)
            }
        }

        private fun sample() {
            val parts = readSelfRssParts() ?: return
            if (parts.totalBytes > peakBytes.get()) peakParts = parts
            peakBytes.accumulateAndGet(parts.totalBytes) { a, b -> maxOf(a, b) }
        }

        /** Stops sampling; returns the peak growth over the baseline taken just before the load. */
        fun stop(): Long {
            sampler.cancel()
            sample()
            return peakBytes.get() - baselineBytes
        }

        /** Anonymous growth at the peak sample; null when the kernel does not report it. */
        fun anonymousGrowth(): Long? {
            val base = baselineParts?.anonBytes ?: return null
            return peakParts?.anonBytes?.let { it - base }
        }

        /** " (anonymous +A MB, file pages +F MB)" at the peak, or "" when the kernel does not report the split. */
        fun split(): String {
            val base = baselineParts ?: return ""
            val peak = peakParts ?: return ""
            val anon = peak.anonBytes?.let { a -> base.anonBytes?.let { a - it } } ?: return ""
            val file = peak.fileBytes?.let { f -> base.fileBytes?.let { f - it } } ?: return ""
            return " (anonymous +${anon / (1024 * 1024)} MB, file pages +${file / (1024 * 1024)} MB)"
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 200L
        fun mb(bytes: Long): Long = bytes / (1024 * 1024)
    }
}
