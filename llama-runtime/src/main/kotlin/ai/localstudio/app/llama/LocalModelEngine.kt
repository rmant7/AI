package ai.localstudio.app.llama

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.RuntimeManager
import ai.localstudio.core.runtime.TextModelHandle
import ai.localstudio.core.runtime.WeightsLoadDecision
import ai.localstudio.core.runtime.WeightsLoadPolicy
import ai.localstudio.core.runtime.WeightsLoading
import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The on-device model engine, assembled once: one [RuntimeManager] that
 * admits every load against this phone's free RAM and what each model was
 * measured to cost here ([measuredRam]), the weights load mode per file
 * (mapped or read into memory -- [weightsLoadDecision]), and llama.cpp
 * runtimes that measure every load ([llamaRuntime]). Any app that runs
 * local models -- rmant7/AI's own, IntelliVerse -- goes through one of
 * these, so admission, eviction, the vision projector's reservation and the
 * RAM figures are the same everywhere.
 *
 * [runtimeVersion] names the native runtime (llama.cpp build, JNI
 * revision): a RAM figure measured by another one is not reused.
 * [weightsLoading] is the user's setting (AUTO by default).
 * [beforeAdmission] frees whatever else the app holds (an embedding model,
 * caches) before a model is admitted. [budgetBytes] is what a load may
 * take; by default this phone's live free RAM ([liveBudgetBytes]).
 */
class LocalModelEngine(
    private val context: Context,
    private val runtimeVersion: String,
    private val log: (tag: String, message: String) -> Unit = { _, _ -> },
    private val weightsLoading: () -> WeightsLoading = { WeightsLoading.AUTO },
    private val beforeAdmission: suspend (requiredBytes: Long) -> Unit = {},
    budgetBytes: (() -> Long)? = null,
    private val memoryDiagnostics: () -> String = { "" },
    private val deviceConditions: () -> String = { "" },
) {
    /** What every model was measured to cost on this phone, by file, context size and load mode. */
    val measuredRam: MeasuredRamStore = MeasuredRamStore(
        context,
        weightsMapped = { path -> weightsLoadDecision(File(path)).mapped },
        runtimeVersion = { runtimeVersion },
    )

    /** How [file]'s weights load: the setting, and for AUTO this file's own measured profile. */
    fun weightsLoadDecision(file: File): WeightsLoadDecision =
        WeightsLoadPolicy.decide(weightsLoading(), measuredRam.mappedAnonymousBytes(file.path), file.length())

    val manager: RuntimeManager = RuntimeManager(
        budgetBytes = budgetBytes ?: { liveBudgetBytes(context, residentBytesNow()) },
        runtimes = emptyMap(),
        exclusive = true,
        log = { log("RAM_MANAGER", it) },
        // A cancelled load keeps running on its detached native worker (see
        // LlamaCppRuntime.load) -- the next model's budget must not be read
        // while that one still physically holds memory.
        beforeAdmission = { requiredBytes ->
            if (LlamaCppRuntime.hasPendingNativeWork()) {
                log("RAM_MANAGER", "waiting for an abandoned native load/free to finish before admitting the next model")
                LlamaCppRuntime.awaitPendingNativeWork()
            }
            beforeAdmission(requiredBytes)
        },
        // Admission against what this model actually cost on this device
        // (variant = the context size it's loaded with), once measured;
        // the file-size × 1.3 guess only until the first real run.
        requiredBytesFor = { binding, variant ->
            measuredRam.measurementFor(binding.artifact, variant as? Int)?.requiredBytes ?: binding.effectiveRequiredRamBytes
        },
    )

    private fun residentBytesNow(): Long = runCatching { manager.residentBytes }.getOrDefault(0L)

    private val runtimes = ConcurrentHashMap<Int, ModelRuntime>()

    /**
     * The llama.cpp runtime for [contextTokens] -- one per context size,
     * RAM-measuring, admitted and evicted through [manager]. A caller that
     * needs its own wrapping (a device-wide gate, a shared session) wraps it.
     */
    fun llamaRuntime(contextTokens: Int): ModelRuntime = runtimes.getOrPut(contextTokens) {
        RamMeasuringRuntime(
            inner = LlamaCppRuntime(
                contextTokens = contextTokens,
                log = log,
                availableRamBytes = { availableRamBytes(context) },
                memoryDiagnostics = memoryDiagnostics,
                memory = manager,
                weightsLoading = ::weightsLoadDecision,
                deviceConditions = deviceConditions,
            ),
            contextTokens = contextTokens,
            store = measuredRam,
            log = log,
        )
    }

    /** What loading [weights] with [contextTokens] is admitted against: its measurement here once there is one, else size × 1.3. The projector is reserved on top, on the first image turn. */
    fun admissionBytes(weights: File, contextTokens: Int): Long =
        measuredRam.measurementFor(weights.absolutePath, contextTokens)?.requiredBytes ?: weights.length() * 13 / 10

    /**
     * Runs [block] with [model] loaded -- through [manager] (other idle
     * models are evicted first; a model that does not fit fails with
     * InsufficientMemoryException) -- with [contextTokens] of context.
     */
    suspend fun <T> withModel(model: EngineModel, contextTokens: Int, block: suspend (TextModelHandle) -> T): T {
        val descriptor = model.descriptor()
        val binding = descriptor.bindings.single()
        return manager.withModel(descriptor, binding, llamaRuntime(contextTokens), contextTokens) { loaded ->
            val handle = loaded as? TextModelHandle ?: throw IllegalStateException("${model.id} did not load as a text model")
            block(handle)
        }
    }

    /** Unloads every model not in use right now. */
    suspend fun evictIdle() = manager.evictIdle()

    companion object {
        /** Of genuinely free RAM, the share a load may take -- MemAvailable is a precise reading, most of it is trusted. */
        const val LIVE_FREE_RAM_SAFETY_FACTOR = 0.95

        fun availableRamBytes(context: Context): Long {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }.availMem
        }

        /**
         * What a load may take right now: genuinely free RAM (the higher of
         * ActivityManager's reading and the kernel's MemAvailable) plus what
         * this app's own models hold ([ownResidentBytes] -- loading another
         * evicts them), [LIVE_FREE_RAM_SAFETY_FACTOR] of it.
         */
        fun liveBudgetBytes(context: Context, ownResidentBytes: Long): Long {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            val free = maxOf(info.availMem, readMemAvailableBytes() ?: 0L)
            val available = (free + ownResidentBytes).coerceIn(0L, info.totalMem)
            return (available * LIVE_FREE_RAM_SAFETY_FACTOR).toLong()
        }
    }
}

/** One installed model as the engine loads it: its weights, its vision projector when it has one, and its context length. */
data class EngineModel(
    val id: String,
    val weights: File,
    val projector: File? = null,
    val contextLength: Int = 0,
    val sourceUrl: String? = null,
) {
    fun descriptor(): ModelDescriptor = ModelDescriptor(
        id = id,
        family = "local",
        version = weights.lastModified().toString(),
        parameterCount = 0,
        contextLength = contextLength,
        capabilities = if (projector != null) setOf(Capability.TEXT_GENERATION, Capability.VISION) else setOf(Capability.TEXT_GENERATION),
        sourceUrl = sourceUrl ?: "",
        bindings = listOf(RuntimeBinding(RuntimeKind.LLAMA_CPP, weights.absolutePath, weights.length(), mmprojArtifact = projector?.absolutePath)),
    )
}
