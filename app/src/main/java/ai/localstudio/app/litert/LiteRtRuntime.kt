package ai.localstudio.app.litert

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.TextModelHandle
import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** Which compute unit to ask the Tensor SDK to run on. */
enum class LiteRtBackend { CPU, GPU, NPU }

/**
 * On-device inference via Google's LiteRT-LM, through the Google Tensor SDK —
 * the only runtime in this app that can hand generation to a Pixel's TPU/NPU
 * instead of the CPU. A separate runtime from [ai.localstudio.app.llama.LlamaCppRuntime]
 * end to end: different native libraries, a different model format
 * (`.litertlm`, not GGUF), a different API shape entirely.
 *
 * This is new, unverified against real Tensor hardware from this environment
 * (no device, no NPU in CI's emulator): it is written directly against the
 * documented Kotlin API, but has not been confirmed to actually accelerate
 * anything on a real Pixel. Treat it as a starting point to test on-device,
 * not as a finished, benchmarked feature the way [LlamaCppRuntime] is.
 */
class LiteRtRuntime(
    private val context: Context,
    // CPU, not NPU: see Settings.liteRtBackend's doc comment — NPU has
    // failed to load every catalog entry tested so far and its failed
    // attempt plus automatic CPU retry (loading the same model twice) has
    // produced a real ANR.
    private val backend: LiteRtBackend = LiteRtBackend.CPU,
    private val log: (tag: String, message: String) -> Unit = { _, _ -> },
) : ModelRuntime {

    override val kind: RuntimeKind = RuntimeKind.LITERT

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean =
        binding.runtime == RuntimeKind.LITERT && isAvailable && File(binding.artifact).isFile

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
        if (!isAvailable) throw ModelLoadException("Google Tensor SDK (LiteRT-LM) is not available in this build")
        val file = File(binding.artifact)
        if (!file.isFile) throw ModelLoadException("Model file is missing: ${binding.artifact}")

        val start = System.currentTimeMillis()
        log("LITERT_LOAD", "${file.name}: starting (backend=$backend)")
        val engine = try {
            loadWith(file, backend)
        } catch (e: Exception) {
            // NPU is the whole point of this runtime, but it is also the
            // narrowest path — Tensor G5 (Pixel 10) plus the matching native
            // dispatch library actually bundled for this ABI. Falling back
            // to CPU here is what keeps a device without that support from
            // being simply unable to use this runtime at all, matching how
            // the rest of this app degrades (a model that doesn't fit RAM,
            // an ABI llama.cpp doesn't support) rather than refusing outright.
            if (backend == LiteRtBackend.CPU) {
                log("LITERT_LOAD", "${file.name}: FAILED on CPU: ${e.message}")
                throw ModelLoadException("LiteRT-LM could not load ${file.name}: ${e.message}")
            }
            // The failed engine above is already closed (see loadWith), but
            // this SDK's native cleanup timing isn't documented — nothing
            // here guarantees it released its allocation before this check
            // runs. A live headroom check right before committing to a
            // second full load of a multi-gigabyte model is the difference
            // between a clean fallback and repeating the exact OOM kill this
            // app already hit on a real device (gemma-4-e4b-it, ~3.6GB):
            // the NPU attempt's footprint plus a fresh CPU copy, resident at
            // the same time, on a phone that only budgeted RAM for one.
            val requiredBytes = binding.effectiveRequiredRamBytes
            val availableBytes = availableRamBytes()
            if (availableBytes < requiredBytes) {
                log(
                    "LITERT_LOAD",
                    "${file.name}: $backend failed (${e.message}); skipping automatic CPU retry — " +
                        "only ${availableBytes / MB}MB free, need ~${requiredBytes / MB}MB",
                )
                throw ModelLoadException(
                    "LiteRT-LM: $backend failed to load ${file.name} (${e.message}). Automatic CPU " +
                        "retry was skipped: only ~${availableBytes / MB}MB RAM is free right now and " +
                        "this model needs ~${requiredBytes / MB}MB — retrying could crash the app. " +
                        "Free up memory, or select CPU as the backend in Settings and try again.",
                )
            }
            log("LITERT_LOAD", "${file.name}: $backend failed (${e.message}), retrying on CPU")
            try {
                loadWith(file, LiteRtBackend.CPU)
            } catch (cpuFailure: Exception) {
                log("LITERT_LOAD", "${file.name}: FAILED on CPU too: ${cpuFailure.message}")
                throw ModelLoadException("LiteRT-LM could not load ${file.name}: ${cpuFailure.message}")
            }
        }
        val elapsed = System.currentTimeMillis() - start
        log("LITERT_LOAD", "${file.name}: ready in ${elapsed}ms")
        return LiteRtTextModel(model.id, binding.effectiveRequiredRamBytes, engine, log)
    }

    private suspend fun loadWith(file: File, backend: LiteRtBackend): Engine {
        val resolvedBackend = when (backend) {
            LiteRtBackend.NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
            LiteRtBackend.GPU -> Backend.GPU()
            LiteRtBackend.CPU -> Backend.CPU()
        }
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = resolvedBackend,
            cacheDir = context.cacheDir.path,
        )
        val engine = Engine(config)
        try {
            engine.initialize()
        } catch (e: Exception) {
            // Engine(config) can itself allocate real native memory for the
            // model (observed: several GB) before initialize() ever reports
            // whether that backend actually works. Leaving a failed engine
            // unclosed here meant an NPU attempt's native allocation could
            // still be resident — with nothing left holding a Kotlin
            // reference to free it deterministically — while the very next
            // line starts loading an entirely separate CPU copy of the same
            // multi-gigabyte model. On a device already tight on memory,
            // that transient double-residency is a plausible way to get
            // OOM-killed during what should be a clean fallback, which is
            // exactly what happened on a real device loading a ~3.6GB model.
            runCatching { engine.close() }
            throw e
        }
        return engine
    }

    /** Free memory this instant — deliberately *not* [ai.localstudio.core.registry.DeviceProfile.usableRamBytes]'s budget, which is sized off total RAM for exactly this reason (see its doc comment); the retry check above needs "would this fit right now", not "is this device generally suitable". */
    private fun availableRamBytes(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Long.MAX_VALUE
        val info = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        return info.availMem
    }

    companion object {
        private const val MB = 1024L * 1024L

        /**
         * Whether the Tensor SDK's classes are actually on the classpath —
         * always true once the Gradle dependency is present, but checked
         * defensively rather than assumed, the same reasoning as
         * [ai.localstudio.app.llama.LlamaBridge.isAvailable] guarding against
         * a native library that failed to load for this ABI.
         */
        val isAvailable: Boolean =
            runCatching { Class.forName("com.google.ai.edge.litertlm.Engine") }.isSuccess
    }
}

private class LiteRtTextModel(
    override val modelId: String,
    override val ramBytes: Long,
    private val engine: Engine,
    private val log: (tag: String, message: String) -> Unit,
) : TextModelHandle {

    override fun generate(request: GenerationRequest): Flow<String> = flow {
        val start = System.currentTimeMillis()
        var tokenCount = 0
        log("LITERT_GENERATE", "$modelId: starting (prompt=${request.prompt.length} chars)")

        // The Tensor SDK configures a system instruction and sampling on the
        // Conversation, not per message — but this app already resends the
        // whole assembled context as one prompt string every turn (same
        // design LlamaCppRuntime relies on), so folding the system prompt
        // into that one string and creating a fresh conversation per turn
        // keeps this runtime's behaviour consistent with the rest of the app
        // instead of trying to keep a stateful Conversation in sync with it.
        val fullPrompt = if (request.systemPrompt.isNullOrBlank()) {
            request.prompt
        } else {
            request.systemPrompt + "\n\n" + request.prompt
        }

        val conversation = engine.createConversation()
        try {
            conversation.sendMessageAsync(fullPrompt).collect { chunk ->
                val text = chunk.toString()
                if (text.isNotEmpty()) {
                    tokenCount++
                    emit(text)
                }
            }
            log("LITERT_GENERATE", "$modelId: done in ${System.currentTimeMillis() - start}ms, $tokenCount chunks")
        } catch (e: Exception) {
            log("LITERT_GENERATE", "$modelId: FAILED after ${System.currentTimeMillis() - start}ms: ${e.message}")
            throw e
        } finally {
            runCatching { conversation.close() }
        }
    }

    override fun requestCancel() {
        // No documented explicit cancel on Conversation/Engine — cancelling
        // the coroutine collecting generate()'s Flow is what this currently
        // relies on, unlike LlamaCppRuntime's flag checked from native code
        // between decode steps. Whether the Tensor SDK actually stops
        // underlying work when its Flow's collector is cancelled, rather
        // than continuing in the background, is untested from here.
        log("LITERT_GENERATE", "$modelId: cancel requested (best-effort — see requestCancel() comment)")
    }

    override fun close() {
        runCatching { engine.close() }
    }
}
