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
import ai.localstudio.core.keys.ApiKeyRotator
import ai.localstudio.core.router.CapabilityRouter
import ai.localstudio.core.runtime.FallbackCandidate
import ai.localstudio.core.runtime.FallbackTextRuntime
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.RuntimeManager
import ai.localstudio.app.attach.AttachedDocument
import ai.localstudio.app.attach.DocumentStore
import ai.localstudio.app.keys.PrefsApiKeyStore
import ai.localstudio.app.llama.LlamaBridge
import ai.localstudio.app.log.AppLog
import ai.localstudio.app.llama.LlamaCppRuntime
import ai.localstudio.app.models.CatalogFreshness
import ai.localstudio.app.models.LocalModelSeed
import ai.localstudio.app.models.LocalModels
import ai.localstudio.app.models.ModelDownloadService
import ai.localstudio.app.models.ModelDownloads
import ai.localstudio.app.models.ModelStore
import ai.localstudio.app.whisper.WhisperDownloads
import ai.localstudio.app.whisper.WhisperEngine
import ai.localstudio.app.whisper.WhisperModels
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

    /** Per-provider pools of API keys — add/delete/validate in ApiKeysActivity. */
    val apiKeyStore = PrefsApiKeyStore(context, settings)

    /** One rotator per provider, so cooldown state for Gemini and Mistral never mixes. */
    fun apiKeyRotator(providerId: String): ApiKeyRotator = ApiKeyRotator(apiKeyStore, providerId)

    /** Errors the app has hit, readable and copyable from Settings → "Журнал ошибок". */
    val appLog = AppLog(context)

    // InMemoryMemoryProvider, as the name says, does not survive the process
    // being killed — routine on Android the moment the app is backgrounded.
    // [documents] does survive it (it's a file), so on every fresh start its
    // chunks are re-remembered here; this map is what lets a later delete
    // remove exactly the memory items *this* document put there, this run,
    // rather than guess by matching text.
    private val documentMemoryIds = mutableMapOf<String, List<String>>()

    init {
        // Checked once per process, before anything else has a chance to
        // throw: this is the one place a *native* crash (a segfault in
        // llama.cpp, say) becomes visible after the fact at all — the crash
        // itself kills the process before any of our own code can write
        // anything, but Android remembers why the previous instance died.
        appLog.recordProcessExitIfNotable()

        // Any exception that reaches here slipped past every runCatching in
        // the app — logging it before Android's own crash handling takes
        // over is what makes "Журнал ошибок" useful for those too, not just
        // the errors already caught and shown as a chat bubble.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { appLog.record("UNCAUGHT", "[${thread.name}] ${throwable.stackTraceToString()}") }
            previousHandler?.uncaughtException(thread, throwable)
        }

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

    /** The model the user picked (or the biggest installed one) — used for the one accurate final transcript. */
    val whisperEngine = WhisperEngine(whisperStore)

    /**
     * A second, independent engine dedicated to Whisper Tiny, kept loaded on
     * its own so a live preview during recording never fights the main
     * engine over which model is currently loaded. [WhisperEngine] only
     * holds one model at a time and reloads on every seed change, so sharing
     * one engine between "tiny for live preview" and "whatever the user
     * picked for the final pass" would thrash between the two on every
     * recording instead of ever having either warm.
     */
    val whisperPreviewEngine = WhisperEngine(whisperStore)

    init {
        // First launch, voice input should just work: without this, a user
        // has to already know Models → Голос exists before the mic button
        // does anything at all. Tiny is 75 MB — small enough to fetch
        // without asking, and it doubles as the live-preview model.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (whisperStore.installedSeed() == null) {
                WhisperModels.byId(WhisperModels.TINY_ID)?.let { whisperDownloads.start(it) }
            }
        }
    }

    /** Seeds that are on disk right now, newest state each time it is asked. */
    fun installedSeeds(): List<LocalModelSeed> = LocalModels.SEEDS.filter { modelStore.isInstalled(it) }

    private var cachedOrchestrator: Orchestrator? = null
    private var cachedSignature: String? = null

    /** Rebuilt only when the settings that affect wiring have actually changed. */
    fun orchestrator(): Orchestrator {
        val enabled = enabledProviders()
        // Each cloud provider can contribute several candidates now, not one —
        // see cloudCandidates — so this is a flatMap, not mapNotNull. Order is
        // still what makes the policy real: local first, then each enabled
        // provider's own model list before the next provider's first model.
        val candidates = enabled.flatMap { provider ->
            if (provider.id == CloudProviders.LOCAL.id) listOfNotNull(localCandidate()) else cloudCandidates(provider)
        }

        val signature = (
            listOf(
                enabled.map { it.id }.sorted().joinToString(","),
                settings.customEndpoint,
                settings.speechModel,
                settings.temperature,
                settings.topP,
                settings.topK,
                settings.repeatPenalty,
                settings.contextTokens,
                settings.maxResponseTokens,
                settings.systemPrompt,
            ) +
                enabled.flatMap { listOf(settings.chatModelFor(it.id), settings.apiKeyFor(it.id)) } +
                // Which local model is actually installed can change without
                // any setting changing (download finished, model deleted).
                candidates.map { it.model.id }
            ).joinToString("|")
        cachedOrchestrator?.takeIf { cachedSignature == signature }?.let { return it }

        val runtime: ModelRuntime = when {
            candidates.isEmpty() -> StubRuntime()
            candidates.size == 1 -> candidates.single().runtime
            // CloudProviders.ALL lists Local first, so `enabled` — and
            // therefore `candidates` — already carries that order: this is
            // what makes "local first, cloud as the fallback" true.
            else -> FallbackTextRuntime(candidates)
        }
        val isLocalOnly = candidates.singleOrNull()?.binding?.runtime == RuntimeKind.LLAMA_CPP

        val manager = RuntimeManager(
            // Remote and stub models hold no local weights; the budget starts
            // mattering the moment an on-device runtime is added. Note this
            // budget does not see inside a fallback chain: FallbackTextModel
            // loads each wrapped candidate directly rather than through this
            // manager, so a local model loaded as part of a chain is not
            // tracked or evicted the way a standalone local model is.
            budgetBytes = device.usableRamBytes,
            runtimes = mapOf(runtime.kind to runtime),
        )
        val executors = NodeExecutors(
            selector = ModelSelector(registry(candidates), device),
            runtimeManager = manager,
            contextEngine = ContextEngine(),
            memory = memory,
            systemPrompt = settings.systemPrompt.ifBlank { null },
            // The context-window setting exists to keep a local llama.cpp
            // context (and its RAM) small enough for the device — it has
            // nothing to do with what a cloud model can handle. Tuning it
            // down to survive on-device was also quietly capping how much
            // conversation/memory ever reached Gemini, unrelated to the
            // max-tokens leak fixed the same way in OpenAiRuntime.
            contextWindowTokens = if (isLocalOnly) settings.contextTokens else CLOUD_CONTEXT_WINDOW_TOKENS,
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

    /** Providers actually enabled for use, in fallback order — see [Settings.enabledProviderIds]. */
    private fun enabledProviders(): List<CloudProvider> {
        val ids = settings.enabledProviderIds
        return CloudProviders.ALL.filter { it.id in ids }
    }

    /** The best-fit installed local model as a fallback candidate, or null when nothing is installed. */
    private fun localCandidate(): FallbackCandidate? {
        val selected = ModelSelector(localRegistry(), device).selectOrNull(Capability.TEXT_GENERATION) ?: return null
        return FallbackCandidate(
            label = CloudProviders.LOCAL.title,
            runtime = LlamaCppRuntime(contextTokens = settings.contextTokens),
            model = selected.model,
            binding = selected.binding,
        )
    }

    /**
     * A configured cloud provider as a run of fallback candidates — one per
     * model — or empty when it has no usable endpoint.
     *
     * The provider's own configured model is tried first, then every other
     * model [CloudProvider.freeModels] lists, all sharing one [OpenAiRuntime]
     * (and so one key pool/rotator) for this provider. This is what turns an
     * error specific to one model — "HTTP 503: This model is currently
     * experiencing high demand", a 404 for a retired model name, and the
     * like — into a switch to a sibling model on the same provider instead of
     * jumping straight to a different provider (or failing outright, if this
     * is the only one enabled). [FallbackTextRuntime] already moves on to the
     * next candidate on any thrown exception or empty response — nothing
     * about that needed to change to make this work; only how many
     * candidates one provider contributes did.
     */
    private fun cloudCandidates(provider: CloudProvider): List<FallbackCandidate> {
        val endpoint = if (provider.editableUrl) settings.customEndpoint else provider.baseUrl
        if (endpoint.isBlank()) return emptyList()
        val runtime = OpenAiRuntime(
            OpenAiConfig(
                baseUrl = endpoint,
                apiKey = settings.apiKeyFor(provider.id).ifBlank { null },
                keyRotator = apiKeyRotator(provider.id),
            ),
        )
        val primaryModel = settings.chatModelFor(provider.id)
        val modelNames = listOf(primaryModel) + provider.freeModels.filterNot { it == primaryModel }
        return modelNames.map { modelName ->
            val model = servedModel(modelName, RuntimeKind.REMOTE_OPENAI, Capability.TEXT_GENERATION, Capability.REASONING)
            FallbackCandidate(
                label = if (modelName == primaryModel) provider.title else "${provider.title} ($modelName)",
                runtime = runtime,
                model = model,
                binding = model.bindings.first(),
            )
        }
    }

    private fun registry(candidates: List<FallbackCandidate>): ModelRegistry {
        val entries = mutableListOf<RegistryEntry>()
        when {
            candidates.isEmpty() -> entries += RegistryEntry(
                servedModel(settings.chatModel, RuntimeKind.STUB, Capability.TEXT_GENERATION, Capability.REASONING),
                InstallState.INSTALLED,
            )

            candidates.size == 1 -> {
                val only = candidates.single()
                entries += RegistryEntry(only.model, InstallState.INSTALLED)
                // Voice input never actually goes through the pipeline in
                // this app (ChatActivity talks to WhisperEngine directly) —
                // this exists only so a saved pipeline that asks for
                // SPEECH_TO_TEXT has something to select, using the same
                // runtime already registered rather than a second one.
                if (only.binding.runtime == RuntimeKind.REMOTE_OPENAI) {
                    entries += RegistryEntry(
                        servedModel(settings.speechModel, RuntimeKind.REMOTE_OPENAI, Capability.SPEECH_TO_TEXT),
                        InstallState.INSTALLED,
                    )
                }
            }

            else -> entries += RegistryEntry(fallbackChainDescriptor(candidates), InstallState.INSTALLED)
        }
        return ModelRegistry(entries)
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

    private fun fallbackChainDescriptor(candidates: List<FallbackCandidate>) = ModelDescriptor(
        id = "fallback-chain",
        family = "fallback",
        version = "1",
        parameterCount = 1,
        contextLength = candidates.maxOf { it.model.contextLength },
        capabilities = setOf(Capability.TEXT_GENERATION, Capability.REASONING),
        bindings = listOf(
            RuntimeBinding(
                runtime = RuntimeKind.FALLBACK_CHAIN,
                artifact = candidates.joinToString(" → ") { it.label },
                fileSizeBytes = 1,
                requiredRamBytes = 1,
            ),
        ),
    )

    /** The catalog shipped in `registry/`, used by the Models screen to rank against this device. */
    fun catalog(): ModelCatalog = runCatching {
        context.assets.open(CATALOG_ASSET).bufferedReader().use { PipelineCodec.decodeCatalog(it.readText()) }
    }.getOrElse { ModelCatalog(models = emptyList()) }

    val runtimeLabel: String
        get() = enabledProviders().takeIf { it.isNotEmpty() }
            ?.joinToString(" → ") { it.title }
            ?: CloudProviders.DEMO.title

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

        // Not a real ceiling, just "large enough that this app's own context
        // engine is never the reason a cloud model didn't get enough
        // conversation/memory" — actual providers support far more than
        // this, and the request itself grows or shrinks with what's
        // actually assembled, not with this number.
        private const val CLOUD_CONTEXT_WINDOW_TOKENS = 32_000

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
                    add(RuntimeKind.FALLBACK_CHAIN)
                    if (LlamaBridge.isAvailable) add(RuntimeKind.LLAMA_CPP)
                },
                hasGpuDelegate = false,
                performanceIndex = 1.0,
                ramBudgetFraction = ramBudgetFraction,
            )
        }
    }
}
