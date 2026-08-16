package ai.localstudio.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.context.ContextEngine
import ai.localstudio.core.engine.ModelSelector
import ai.localstudio.core.engine.NodeExecutors
import ai.localstudio.core.engine.Orchestrator
import ai.localstudio.core.memory.InMemoryMemoryProvider
import ai.localstudio.core.pipeline.PipelineCodec
import ai.localstudio.core.registry.DeviceProfile
import ai.localstudio.core.registry.InstallState
import ai.localstudio.core.registry.ModelCatalog
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.ModelRegistry
import ai.localstudio.core.registry.RegistryEntry
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.router.CapabilityRouter
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.RuntimeManager
import ai.localstudio.openai.OpenAiConfig
import ai.localstudio.openai.OpenAiRuntime

/**
 * Wires the architecture for this device.
 *
 * The only thing that changes between the stub runtime and a real endpoint is
 * which [ModelRuntime] is registered and which bindings the registry carries —
 * router, pipelines, memory and context assembly are identical either way.
 */
class AppContainer private constructor(private val context: Context) {

    val settings = Settings(context)

    /** Memory lives above the models, so it survives switching between runtimes. */
    val memory = InMemoryMemoryProvider()

    val device: DeviceProfile by lazy { profileOf(context) }

    private var cachedOrchestrator: Orchestrator? = null
    private var cachedSignature: String? = null

    /** Rebuilt only when the settings that affect wiring have actually changed. */
    fun orchestrator(): Orchestrator {
        val signature = "${settings.endpoint}|${settings.apiKey}|${settings.chatModel}|${settings.speechModel}"
        cachedOrchestrator?.takeIf { cachedSignature == signature }?.let { return it }

        val runtime: ModelRuntime = if (settings.hasEndpoint) {
            OpenAiRuntime(OpenAiConfig(baseUrl = settings.endpoint, apiKey = settings.apiKey.ifBlank { null }))
        } else {
            StubRuntime()
        }

        val manager = RuntimeManager(
            // Remote and stub models hold no local weights; the budget starts
            // mattering the moment an on-device runtime is added.
            budgetBytes = device.usableRamBytes,
            runtimes = mapOf(runtime.kind to runtime),
        )
        val executors = NodeExecutors(
            selector = ModelSelector(registry(), device),
            runtimeManager = manager,
            contextEngine = ContextEngine(),
            memory = memory,
            systemPrompt = SYSTEM_PROMPT,
        )
        return Orchestrator(CapabilityRouter(), executors).also {
            cachedOrchestrator = it
            cachedSignature = signature
        }
    }

    fun registry(): ModelRegistry {
        val kind = if (settings.hasEndpoint) RuntimeKind.REMOTE_OPENAI else RuntimeKind.STUB
        return ModelRegistry(
            listOf(
                RegistryEntry(
                    servedModel(settings.chatModel, kind, Capability.TEXT_GENERATION, Capability.REASONING),
                    InstallState.INSTALLED,
                ),
                RegistryEntry(
                    servedModel(settings.speechModel, kind, Capability.SPEECH_TO_TEXT),
                    InstallState.INSTALLED,
                ),
            ),
        )
    }

    /** The catalog shipped in `registry/`, used by the Models screen to rank against this device. */
    fun catalog(): ModelCatalog = runCatching {
        context.assets.open(CATALOG_ASSET).bufferedReader().use { PipelineCodec.decodeCatalog(it.readText()) }
    }.getOrElse { ModelCatalog(models = emptyList()) }

    val runtimeLabel: String
        get() = if (settings.hasEndpoint) settings.endpoint else "встроенный демо-runtime"

    private fun servedModel(
        id: String,
        kind: RuntimeKind,
        vararg capabilities: Capability,
    ) = ModelDescriptor(
        id = id,
        family = id.substringBefore(':').substringBefore('-'),
        version = "1",
        parameterCount = 1,
        contextLength = 8_192,
        capabilities = capabilities.toSet(),
        bindings = listOf(
            RuntimeBinding(
                runtime = kind,
                artifact = id,
                fileSizeBytes = 1,
                requiredRamBytes = 1,
                referenceTokensPerSecond = 30.0,
            ),
        ),
    )

    companion object {
        private const val CATALOG_ASSET = "catalog.example.json"
        private const val SYSTEM_PROMPT =
            "Ты локальный ассистент Local AI Studio. Отвечай кратко и по делу, на языке пользователя."

        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }

        fun profileOf(context: Context): DeviceProfile {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            return DeviceProfile(
                totalRamBytes = info.totalMem,
                availableRamBytes = info.availMem,
                availableStorageBytes = context.filesDir.freeSpace,
                cpuCores = Runtime.getRuntime().availableProcessors(),
                androidApiLevel = Build.VERSION.SDK_INT,
                // Only what this build can actually execute. llama.cpp and
                // MediaPipe join the set when their runtimes are implemented.
                supportedRuntimes = setOf(RuntimeKind.REMOTE_OPENAI, RuntimeKind.STUB),
                hasGpuDelegate = false,
                performanceIndex = 1.0,
            )
        }
    }
}
