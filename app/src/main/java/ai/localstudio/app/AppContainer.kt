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
import ai.localstudio.core.memory.MemoryScope
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
import ai.localstudio.app.attach.AttachedDocument
import ai.localstudio.app.attach.DocumentStore
import ai.localstudio.app.llama.LlamaBridge
import ai.localstudio.app.llama.LlamaCppRuntime
import ai.localstudio.app.models.CatalogFreshness
import ai.localstudio.app.models.LocalModelSeed
import ai.localstudio.app.models.LocalModels
import ai.localstudio.app.models.ModelDownloadService
import ai.localstudio.app.models.ModelDownloads
import ai.localstudio.app.models.ModelStore
import ai.localstudio.app.whisper.WhisperDownloads
import ai.localstudio.app.whisper.WhisperEngine
import ai.localstudio.app.whisper.WhisperStore
import ai.localstudio.openai.OpenAiConfig
import ai.localstudio.openai.OpenAiRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    val documents = DocumentStore(context)

    val catalogFreshness = CatalogFreshness(context)

    // InMemoryMemoryProvider, as the name says, does not survive the process
    // being killed — routine on Android the moment the app is backgrounded.
    // [documents] does survive it (it's a file), so on every fresh start its
    // chunks are re-remembered here; this map is what lets a later delete
    // remove exactly the memory items *this* document put there, this run,
    // rather than guess by matching text.
    private val documentMemoryIds = mutableMapOf<String, List<String>>()

    init {
        // Off the main thread: this reads a file and re-inserts every chunk of
        // every attached document, and it runs during the first
        // AppContainer.get() — which happens in Activity.onCreate. As
        // runBlocking there, a few large PDFs was a visible freeze on launch
        // at best and an ANR at worst. Memory is safe to fill in late: a
        // search that lands before the replay finishes simply sees fewer
        // fragments, and the provider itself is now synchronized.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            documents.list().forEach { doc ->
                val ids = doc.chunks.map { chunk ->
                    memory.remember(chunk, MemoryScope.SEMANTIC, mapOf("source" to doc.name))
                }
                synchronized(documentMemoryIds) { documentMemoryIds[doc.id] = ids }
            }
        }

        // Re-checks every catalogue entry against Hugging Face, throttled to
        // once an hour so relaunching the app repeatedly does not repeat it.
        // Best-effort and silent: a stale or offline check just means the
        // Models screen shows whatever it showed last, not a crash or a
        // blocked launch.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                catalogFreshness.refreshIfStale(LocalModels.SEEDS, settings.huggingFaceToken.ifBlank { null })
            }
        }
    }

    suspend fun rememberDocument(name: String, chunks: List<String>): AttachedDocument = withContext(Dispatchers.IO) {
        val ids = chunks.map { chunk -> memory.remember(chunk, MemoryScope.SEMANTIC, mapOf("source" to name)) }
        val doc = AttachedDocument(
            id = "doc-${System.currentTimeMillis()}",
            name = name,
            chunks = chunks,
            addedAt = System.currentTimeMillis(),
        )
        documents.add(doc)
        synchronized(documentMemoryIds) { documentMemoryIds[doc.id] = ids }
        doc
    }

    suspend fun forgetDocument(id: String) = withContext(Dispatchers.IO) {
        val ids = synchronized(documentMemoryIds) { documentMemoryIds.remove(id) }
        ids?.forEach { memoryId -> memory.forget(memoryId) }
        documents.remove(id)
    }

    /** Recomputed on demand: free memory moves, and the budget is user-settable. */
    val device: DeviceProfile get() = profileOf(context, settings.ramBudgetFraction)

    val modelStore = ModelStore(context)

    val downloads = ModelDownloads(
        modelStore,
        tokenProvider = { settings.huggingFaceToken.ifBlank { null } },
        onDownloadStarted = { ModelDownloadService.ensureStarted(context) },
    )

    val whisperStore = WhisperStore(context)
    val whisperDownloads = WhisperDownloads(
        whisperStore,
        onDownloadStarted = { ModelDownloadService.ensureStarted(context) },
    )
    val whisperEngine = WhisperEngine(whisperStore)

    /** Seeds that are on disk right now, newest state each time it is asked. */
    fun installedSeeds(): List<LocalModelSeed> = LocalModels.SEEDS.filter { modelStore.isInstalled(it) }

    private var cachedOrchestrator: Orchestrator? = null
    private var cachedSignature: String? = null

    /** Rebuilt only when the settings that affect wiring have actually changed. */
    fun orchestrator(): Orchestrator {
        val signature = listOf(
            settings.providerId,
            settings.endpoint,
            settings.apiKey,
            settings.chatModel,
            settings.speechModel,
            settings.temperature,
            settings.topP,
            settings.topK,
            settings.repeatPenalty,
            settings.contextTokens,
            settings.maxResponseTokens,
            settings.systemPrompt,
        ).joinToString("|")
        cachedOrchestrator?.takeIf { cachedSignature == signature }?.let { return it }

        val runtime: ModelRuntime = when {
            settings.providerId == CloudProviders.LOCAL.id -> LlamaCppRuntime(contextTokens = settings.contextTokens)
            settings.hasEndpoint ->
                OpenAiRuntime(OpenAiConfig(baseUrl = settings.endpoint, apiKey = settings.apiKey.ifBlank { null }))

            else -> StubRuntime()
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
            systemPrompt = settings.systemPrompt,
            contextWindowTokens = settings.contextTokens,
            defaultTemperature = settings.temperature,
            defaultTopP = settings.topP,
            defaultTopK = settings.topK,
            defaultRepeatPenalty = settings.repeatPenalty,
            defaultMaxTokens = settings.maxResponseTokens,
        )
        return Orchestrator(CapabilityRouter(), executors).also {
            cachedOrchestrator = it
            cachedSignature = signature
        }
    }

    fun registry(): ModelRegistry {
        if (settings.providerId == CloudProviders.LOCAL.id) return localRegistry()
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

    /**
     * Downloaded models, described from what is actually on disk: the binding's
     * artifact is the file path, and its size is the file's real size, so the
     * scorer and the runtime agree about what exists.
     */
    private fun localRegistry(): ModelRegistry = ModelRegistry(
        installedSeeds().map { seed ->
            val file: File = modelStore.fileFor(seed)
            RegistryEntry(
                ModelDescriptor(
                    id = seed.id,
                    family = seed.id.substringBefore('-'),
                    version = "1",
                    parameterCount = 1,
                    // What the runtime will actually allocate is the user's
                    // setting, not the seed's own default — the two are the
                    // same value system-wide, since LlamaCppRuntime loads
                    // every model with one context size regardless of which
                    // model it is.
                    contextLength = settings.contextTokens,
                    capabilities = seed.capabilities,
                    bindings = listOf(
                        RuntimeBinding(
                            runtime = RuntimeKind.LLAMA_CPP,
                            artifact = file.absolutePath,
                            fileSizeBytes = file.length().coerceAtLeast(1),
                            // Left unmeasured on purpose: effectiveRequiredRamBytes
                            // then errs high, which is the safe direction here.
                            requiredRamBytes = null,
                        ),
                    ),
                ),
                InstallState.INSTALLED,
                installedPath = file.absolutePath,
            )
        },
    )

    /** The catalog shipped in `registry/`, used by the Models screen to rank against this device. */
    fun catalog(): ModelCatalog = runCatching {
        context.assets.open(CATALOG_ASSET).bufferedReader().use { PipelineCodec.decodeCatalog(it.readText()) }
    }.getOrElse { ModelCatalog(models = emptyList()) }

    val runtimeLabel: String
        get() = settings.provider.title

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

        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }

        fun profileOf(context: Context, ramBudgetFraction: Double = DeviceProfile.BASE_RAM_FRACTION): DeviceProfile {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            return DeviceProfile(
                totalRamBytes = info.totalMem,
                availableRamBytes = info.availMem,
                availableStorageBytes = context.filesDir.freeSpace,
                cpuCores = Runtime.getRuntime().availableProcessors(),
                androidApiLevel = Build.VERSION.SDK_INT,
                // Only what this build can actually execute on this device:
                // llama.cpp appears once its native library loads for this ABI.
                supportedRuntimes = buildSet {
                    add(RuntimeKind.REMOTE_OPENAI)
                    add(RuntimeKind.STUB)
                    if (LlamaBridge.isAvailable) add(RuntimeKind.LLAMA_CPP)
                },
                hasGpuDelegate = false,
                performanceIndex = 1.0,
                ramBudgetFraction = ramBudgetFraction,
            )
        }
    }
}
