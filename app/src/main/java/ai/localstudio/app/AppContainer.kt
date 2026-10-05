package ai.localstudio.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.google.mlkit.genai.common.FeatureStatus
import ai.localstudio.commercialmemory.AppMemory
import ai.localstudio.commercialmemory.CommercialContextSelector
import ai.localstudio.commercialmemory.ExperimentLogger
import ai.localstudio.commercialmemory.ExperimentMode
import ai.localstudio.commercialmemory.ExperimentRecord
import ai.localstudio.commercialmemory.JsonlExperimentLogger
import ai.localstudio.commercialmemory.MemoryExperimentRunner
import ai.localstudio.commercialmemory.RankingWeights
import ai.localstudio.app.aicore.AiCorePromptClient
import ai.localstudio.app.aicore.AiCoreRuntime
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.context.ContextEngine
import ai.localstudio.core.engine.ModelSelector
import ai.localstudio.core.engine.NodeExecutors
import ai.localstudio.core.engine.Orchestrator
import ai.localstudio.core.engine.SelectedModel
import ai.localstudio.core.engine.UserRequest
import ai.localstudio.core.memory.LlmMemoryExtractor
import ai.localstudio.core.pipeline.NodeValue
import ai.localstudio.core.pipeline.PipelineCodec
import ai.localstudio.core.pipeline.PipelineEngine
import ai.localstudio.memory.FileMemoryStore
import ai.localstudio.memory.FileSemanticIndex
import ai.localstudio.memory.MemoryQuery
import ai.localstudio.memory.MemoryScope
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
import ai.localstudio.core.runtime.DeviceMemoryGatedRuntime
import ai.localstudio.core.runtime.FallbackCandidate
import ai.localstudio.core.runtime.FallbackTextRuntime
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.RuntimeManager
import ai.localstudio.core.runtime.SharedRuntime
import ai.localstudio.app.attach.AttachedDocument
import ai.localstudio.app.attach.DocumentStore
import ai.localstudio.app.benchmark.BenchmarkOrchestrator
import ai.localstudio.app.benchmark.BenchmarkReportStore
import ai.localstudio.app.benchmark.BenchmarkService
import ai.localstudio.app.benchmark.BenchmarkUiState
import ai.localstudio.core.benchmark.BenchmarkRunner
import ai.localstudio.core.benchmark.TranscriptionEngine
import ai.localstudio.app.keys.BundledApiKeyStore
import ai.localstudio.app.keys.BundledApiKeys
import ai.localstudio.app.keys.PrefsApiKeyStore
import ai.localstudio.app.llama.EmbeddingModelSpec
import ai.localstudio.app.llama.ExperimentalDownloadState
import ai.localstudio.app.llama.ExperimentalEmbeddingDownloads
import ai.localstudio.app.llama.ExperimentalEmbeddingModels
import ai.localstudio.app.llama.ExperimentalEmbeddingStore
import ai.localstudio.app.llama.LazyMemoryEmbedder
import ai.localstudio.app.llama.LlamaBridge
import ai.localstudio.app.llama.LlamaCppMemoryEmbedder
import ai.localstudio.app.log.AppLog
import ai.localstudio.app.llama.LlamaCppRuntime
import ai.localstudio.app.llama.MeasuredRamStore
import ai.localstudio.app.llama.RamMeasuringRuntime
import ai.localstudio.app.llama.readMemAvailableBytes
import ai.localstudio.app.modelinstall.ModelInstallation
import ai.localstudio.app.modelinstall.CandidateTrial
import ai.localstudio.app.modelinstall.CandidateTrialState
import ai.localstudio.app.modelinstall.DiscoveredCandidate
import ai.localstudio.app.modelinstall.DiscoveryRun
import ai.localstudio.app.modelinstall.FunctionalProbe
import ai.localstudio.app.modelinstall.TrialRuntime
import ai.localstudio.app.modelinstall.DiscoveryStore
import ai.localstudio.app.modelinstall.HuggingFaceApiClient
import ai.localstudio.core.registry.ArtifactResolver
import ai.localstudio.model.install.CandidateModel
import ai.localstudio.model.install.InstallResult
import ai.localstudio.model.install.ModelDiscovery
import ai.localstudio.model.install.tier
import ai.localstudio.model.install.ModelSearchQuery
import ai.localstudio.app.models.CatalogFreshness
import ai.localstudio.app.models.LocalModelSeed
import ai.localstudio.app.models.LocalModels
import ai.localstudio.app.models.ModelPurpose
import ai.localstudio.app.models.ModelDownloadService
import ai.localstudio.app.models.ModelDownloads
import ai.localstudio.app.models.ModelStore
import ai.localstudio.app.models.TranslationModels
import ai.localstudio.app.routing.ModelCooldownStore
import ai.localstudio.app.vosk.VoskDownloads
import ai.localstudio.app.vosk.VoskFileTranscriber
import ai.localstudio.app.vosk.VoskModels
import ai.localstudio.app.vosk.VoskRegisteredSpeechModel
import ai.localstudio.app.vosk.VoskSpeechRecognizer
import ai.localstudio.app.whisper.WhisperLanguageIdentifier
import ai.localstudio.app.whisper.WhisperRegisteredSpeechModel
import ai.localstudio.core.speech.DefaultStreamingSpeechRouter
import ai.localstudio.core.speech.InMemorySpeechModelRegistry
import ai.localstudio.core.speech.Language
import ai.localstudio.core.speech.RoutingPolicy
import ai.localstudio.core.speech.SpeechModelRegistry
import ai.localstudio.core.speech.StreamingSpeechRouter
import ai.localstudio.app.whisper.WhisperCppMicSession
import ai.localstudio.app.whisper.WhisperCppRuntime
import ai.localstudio.app.whisper.WhisperDownloads
import ai.localstudio.app.whisper.FileTranscriptionRunner
import ai.localstudio.app.whisper.FileTranscriptionService
import ai.localstudio.app.whisper.WhisperEngine
import ai.localstudio.app.whisper.WhisperCppTranscriptionEngine
import ai.localstudio.app.whisper.WhisperFileTranscriber
import ai.localstudio.app.whisper.WhisperModels
import ai.localstudio.app.whisper.WhisperStore
import ai.localstudio.whisper.WhisperBridge
import ai.localstudio.openai.GigaChatTokenProvider
import ai.localstudio.openai.OpenAiConfig
import ai.localstudio.openai.OpenAiRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Errors the app has hit, readable and copyable from Settings → "Журнал ошибок". Declared here, ahead of its usual place below, so memory/memoryExperimentLogger (right after) can already reference it. */
    val appLog = AppLog(context)

    /**
     * Where every on-device LLM actually lives, whichever path asked for it —
     * chat, a fallback chain, a Compare-mode source, translation. Each of
     * those wraps its [LlamaCppRuntime] in a [SharedRuntime] pointed here, so
     * one GGUF can never be resident twice, and loading a different one
     * evicts the previous one first ([RuntimeManager]'s `exclusive` mode —
     * see its own doc comment on why budget arithmetic can't decide
     * coexistence for memory-mapped weights). Real device reports behind
     * this: Qwen 9B hanging for five minutes while Gemma stayed resident in a
     * chain nobody's budget could see, and — when this manager was instead
     * shared by whole orchestrators — every Compare-mode bubble answering
     * with the same chain, because all chains share one registry id.
     *
     * Strict, and budgeted off [DeviceProfile.liveRamBytes], not
     * [DeviceProfile.usableRamBytes]: the latter is a user-set policy
     * ceiling (`max(% of total, live free)`) — raising the RAM percentage
     * in Settings clears it regardless of what's genuinely free, which is
     * exactly right for what the Models screen labels "Recommended" and
     * exactly wrong for a check that gates an actual native allocation.
     * Real device report, same night, same mechanism three times: MADLAD-400
     * 7B cleared a 13 GB policy ceiling (80% of a 16 GB phone) with only
     * ~5 GB genuinely free; the weights loaded, then the OOM killer took the
     * whole process out the moment generation allocated anything more — no
     * Settings percentage can make memory that isn't there. [LlamaCppRuntime]'s
     * own separate, narrower pre-flight check (a live headroom reading a few
     * tens of milliseconds later, never throwing) is untouched — this is
     * only the outer gate deciding whether to attempt the load at all.
     */
    /** Real per-model RAM costs measured on this device — see [RamMeasuringRuntime]. */
    private val measuredRam = MeasuredRamStore(context)

    private val sharedRuntimeManager = RuntimeManager(
        budgetBytes = { device.liveRamBytes },
        runtimes = emptyMap(),
        exclusive = true,
        log = { appLog.record("RAM_MANAGER", it) },
        // A cancelled load keeps running on its detached native worker (see
        // LlamaCppRuntime.load) — the next model's budget must not be read
        // while that one still physically holds memory.
        beforeAdmission = { requiredBytes ->
            if (LlamaCppRuntime.hasPendingNativeWork()) {
                appLog.record("RAM_MANAGER", "waiting for an abandoned native load/free to finish before admitting the next model")
                LlamaCppRuntime.awaitPendingNativeWork()
            }
            freeAuxiliaryModelsForLocalLoad(requiredBytes)
        },
        // Admission against what this model actually cost on this device
        // (variant = the context size it's loaded with), once measured;
        // the file-size × 1.3 guess only until the first real run.
        requiredBytesFor = { binding, variant ->
            measuredRam.measurementFor(binding.artifact, variant as? Int)?.requiredBytes ?: binding.effectiveRequiredRamBytes
        },
    )

    /**
     * Shared between [sharedLlamaRuntime] and [aicoreCandidate] via
     * [DeviceMemoryGatedRuntime] — see that class's own doc comment for the
     * real-device OOM crash this exists to stop: [sharedRuntimeManager]'s
     * budget has no visibility into AICore's own memory use (it isn't this
     * app's own weights), so a Compare-mode batch that fires both at once
     * could pass the local load's budget check and still lose to AICore's
     * concurrent ramp-up. One mutex means at most one of the two is ever
     * actually generating at a time.
     */
    private val deviceMemoryGate = Mutex()

    /** See [HeavyOperations] — chat/translation generation and voice transcription wrap themselves in this. */
    val heavyOperations = HeavyOperations()

    /** The Translation screen's results, kept past that screen's own lifetime — see [TranslationSession]. */
    val translationSession = TranslationSession(context)

    /**
     * The last AICore [FeatureStatus] seen this run — null until the first
     * check (started from this class's last init block) finishes. Updated
     * again every time [AiCoreRuntime] checks it before a real generation.
     */
    private val _aicoreStatus = MutableStateFlow<Int?>(null)
    val aicoreStatus: StateFlow<Int?> = _aicoreStatus

    /**
     * True only when AICore itself answered that this device can't run
     * Gemini Nano — not "not downloaded yet", not "unknown". Real device
     * report: a Samsung Galaxy S20 FE (status=0) still offered Gemini Nano
     * on Models → Translation, with a working "Use" button — and picking it
     * silently removed the local translation source, leaving only cloud
     * answers.
     */
    val aicoreUnsupported: Boolean get() = _aicoreStatus.value == FeatureStatus.UNAVAILABLE

    private fun recordAicoreStatus(status: Int) {
        val previous = _aicoreStatus.value
        _aicoreStatus.value = status
        if (previous != status) appLog.record("AICORE_LOAD", "device status: $status")
        if (status == FeatureStatus.UNAVAILABLE && settings.translationModel == CloudProviders.AICORE.id) {
            settings.translationModel = ""
            appLog.record("MODELS", "Gemini Nano is not available on this device — translation model reset to auto")
        }
    }

    private val aicoreCheckInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var aicoreLastCheckAt = 0L

    /**
     * Re-asks AICore whenever one of this app's screens comes to the front,
     * at most every [AICORE_RECHECK_INTERVAL_MS]. Once status=0 drops Gemini
     * Nano from routing, nothing else ever asks again — real device report:
     * AICore disabled at launch, re-enabled while the app kept running, and
     * Nano still missing from the options 10 minutes later.
     */
    private fun watchAicoreOnResume() {
        val app = context.applicationContext as? android.app.Application ?: return
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                if (System.currentTimeMillis() - aicoreLastCheckAt >= AICORE_RECHECK_INTERVAL_MS) refreshAicoreStatus()
                reloadSemanticMemoryOnResume()
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }

    /**
     * Back in the foreground after a background unload (trim level 40 on
     * every backgrounding, device logs #438/#439): E5 starts loading now,
     * so the next message is recalled with it. Before, only that message
     * itself triggered the reload, and its own reply then held it off
     * until after the answer. Through [LazyMemoryEmbedder.requestReload],
     * so it shares the in-flight guard and the wait for a running
     * generation with every other reload.
     */
    private fun reloadSemanticMemoryOnResume() {
        if (semanticMemoryEmbedder.isReady || !settings.memoryEnabled || !settings.semanticMemoryEnabled) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (experimentalEmbeddingStore.isInstalled(ExperimentalEmbeddingModels.E5_BASE)) semanticMemoryEmbedder.requestReload()
        }
    }

    /** Asks AICore once, in the background; errors leave the status unknown rather than guessing. */
    fun refreshAicoreStatus() {
        if (!aicoreCheckInFlight.compareAndSet(false, true)) return
        aicoreLastCheckAt = System.currentTimeMillis()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val client = AiCorePromptClient()
            try {
                recordAicoreStatus(client.status())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                appLog.record("AICORE_LOAD", "background status check failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { client.close() }
                aicoreCheckInFlight.set(false)
            }
        }
    }

    /**
     * Completed by the very last init block in this class. [init]'s
     * background task starts semantic-memory work while construction is
     * still running, and [embedderBlockedBy] reads properties declared far
     * below it (fileTranscriptionRunner, benchmarkOrchestrator) — read too
     * early, those are still null on the JVM. Everything that consults
     * [embedderBlockedBy] awaits this first.
     */
    private val constructionComplete = CompletableDeferred<Unit>()

    /**
     * Fronts [memory]'s semantic half. Constructing this is cheap and
     * synchronous (no native call) — the actual GGUF load that fills it in
     * happens off the main thread, in [init]'s own background task, which
     * downloads [ExperimentalEmbeddingModels.E5_BASE] automatically the
     * first time memory is enabled (see that task's own doc comment).
     * Every call made to this before that finishes degrades to the same
     * lexical-only behavior [memory] already had with no embedder
     * configured at all — see [LazyMemoryEmbedder]'s own doc comment.
     *
     * `reloadTrigger` routes through the same [ensureEmbedderLoaded] the
     * background task below and the periodic backfill loop already use —
     * fired the instant a real call finds this unloaded (a memory-pressure
     * [LazyMemoryEmbedder.unload], most likely), on its own background
     * coroutine so the call that triggered it still returns its lexical-only
     * fallback immediately rather than waiting on the reload.
     */
    private val semanticMemoryEmbedder = LazyMemoryEmbedder(
        isEnabled = { settings.semanticMemoryEnabled },
        reloadTrigger = { onComplete ->
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    // The call that fires this is usually a chat message whose
                    // own reply is about to be generated -- which blocks the
                    // load (embedderBlockedBy). Loading straight away was
                    // therefore always deferred, with no retry until the next
                    // backfill that had missing vectors: semantic recall stayed
                    // off after every background unload (device log, build
                    // #438). Waiting the generation out here, still within this
                    // one trigger (reloadInFlight), loads E5 right after it.
                    constructionComplete.await()
                    withTimeoutOrNull(RELOAD_WAIT_MAX_MS) {
                        while (embedderBlockedBy() != null) delay(RELOAD_WAIT_POLL_MS)
                    }
                    ensureEmbedderLoaded(ExperimentalEmbeddingModels.E5_BASE)
                } finally {
                    onComplete()
                }
            }
        },
    )

    /** Whether semantic retrieval is actually usable right now — the Memory screen's own status line, and nothing else's, needs this. */
    val semanticEmbedderReady: Boolean get() = semanticMemoryEmbedder.isReady

    /**
     * [FileSemanticIndex] is a small binary sidecar file, cheap to construct
     * regardless of whether [semanticMemoryEmbedder] has finished loading —
     * or whether the model behind it is even installed. A dimension/model
     * mismatch on reopen (a different candidate downloaded later, say) just
     * discards stale vectors; it never touches [memory]'s actual records —
     * see that class's own doc comment on why that split is deliberate.
     */
    private val semanticMemoryIndex = FileSemanticIndex(
        File(context.filesDir, "memory-embeddings.bin"),
        modelId = ExperimentalEmbeddingModels.E5_BASE.id,
        dimension = ExperimentalEmbeddingModels.E5_BASE.dimension,
    )

    /**
     * Memory lives above the models, so it survives switching between
     * runtimes — and, via [FileMemoryStore], the process dying too. The
     * extractor reuses [orchestrator] itself for consolidation's one model
     * call rather than re-deriving "which model should answer this" from
     * scratch — local-vs-cloud, fallback chains and cooldowns already work,
     * and a second, independent selection path here would just be a second
     * place for those to drift out of sync with the one everything else uses.
     * memoryEnabled = false: consolidation must not recursively search or
     * write memory for its own extraction call.
     *
     * [LlmMemoryExtractor]'s own `log` callback is wired to [appLog] — this
     * call is the entire reason a conversation's memory carries over to the
     * next one at all, and until now a failed or empty extraction (the
     * model call throwing, a cooldown, an empty response) failed completely
     * silently: nothing showed up anywhere, "did my memory actually get
     * saved" had no answer except reading memory.json directly.
     */
    val memory = FileMemoryStore(
        File(context.filesDir, "memory.json"),
        extractor = LlmMemoryExtractor(log = { message -> appLog.record("MEMORY_CONSOLIDATE", message) }) { prompt ->
            orchestrator().handle(
                UserRequest(conversationId = "memory-consolidation", text = prompt, memoryEnabled = false),
            ).text
        },
        semanticIndex = semanticMemoryIndex,
        embedder = semanticMemoryEmbedder,
    )

    /**
     * (embedded, total) for the Memory screen's own "Index: N / M embedded"
     * line — total is every item [memory] holds that could ever be
     * embedded, embedded is that count minus whatever [semanticMemoryIndex]
     * itself still reports missing a vector for. Reads both fresh on every
     * call rather than caching: this backs a status line someone opens
     * Memory to check mid-backfill, not a value worth the complexity of
     * keeping incrementally in sync with [semanticMemoryEmbedder]'s own
     * background progress.
     */
    suspend fun semanticIndexStatus(): Pair<Int, Int> {
        val all = memory.all()
        val missing = semanticMemoryIndex.missing(all.map { it.id })
        return (all.size - missing.size) to all.size
    }

    /**
     * Stage 3's measurement harness (see :commercial-memory), wired to the
     * real app for the first time: every turn's retrieve→rank→budget→select
     * pass is logged as one JSON line here, in the same directory chat
     * history and attached documents already live in. This is what actually
     * turns "an architecture that could collect Stage-4 data" into data
     * getting collected, on a real device, from real use — the whole point
     * of shipping this rather than only unit-testing it.
     *
     * Also mirrored, one human-readable line per turn, into [appLog] — the
     * JSONL file needs adb (or root) to actually read off a real device, and
     * wireless debugging is exactly the kind of thing that reliably works
     * right up until the one time you need it. [appLog] is already the
     * existing "get a report off this phone with no cable and no dev tools"
     * path (chat menu → Журнал ошибок → Скопировать), so this rides that
     * same, already-working mechanism instead of asking for a second one.
     */
    private val memoryExperimentLogger = object : ExperimentLogger {
        private val jsonl = JsonlExperimentLogger(File(context.filesDir, "memory-experiments.jsonl"))
        override fun log(record: ExperimentRecord) {
            jsonl.log(record)
            // semanticOnlyCandidateCount is the number this line exists for:
            // candidateCount alone can't say whether a lexical-only search
            // would have found the same things — this is what actually
            // proves semantic retrieval found something vocabulary overlap
            // never would have, as opposed to two conversations just
            // happening to share words.
            val semanticNote = if (record.semanticOnlyCandidateCount > 0) {
                " (${record.semanticOnlyCandidateCount} semantic-only)"
            } else {
                ""
            }
            appLog.record(
                "MEMORY_EXPERIMENT",
                "${record.mode}: ${record.candidateCount} candidates$semanticNote -> ${record.selectedCount} selected " +
                    "(${record.selectedCharacters} chars), ${record.latencyMs}ms",
            )
        }
    }
    /**
     * The first live, non-zero [RankingWeights.semantic] this app has ever
     * used — [RankingWeights]' own doc comment on that field explains why it
     * defaulted to 0.0 until now: no embedder was wired into the app, and
     * SEMANTIC_RETRIEVAL_DESIGN.md's step 9 wanted a real measurement, not a
     * guess, before picking one. That measurement now exists — see
     * [SEMANTIC_RANKING_WEIGHT]'s own doc comment for the hybrid-sweep
     * numbers behind this value: it is the smallest weight that already
     * captures the sweep's entire real gain over lexical-only, with every
     * larger weight's additional gain too small to clear its own bootstrap
     * noise. Still not the last word (no A/B run against this app's own
     * real usage yet, only a synthetic-dataset sweep), and still well under
     * [RankingWeights.taskRelevance]'s 0.30. Safe regardless of tuning:
     * [semanticMemoryEmbedder] not being ready yet — including the moment
     * right after a memory-pressure [unload], now covered by
     * [LazyMemoryEmbedder]'s own `reloadTrigger` (see [ensureEmbedderLoaded])
     * rather than left to wait for the periodic backfill loop — or the
     * embedder having no vector for a given item, both leave
     * [ai.localstudio.commercialmemory.ContextCandidate.semanticScore] null,
     * which [ai.localstudio.commercialmemory.HeuristicContextRanker]
     * already treats as contributing nothing — so this weight is inert
     * until real coverage exists, whatever it's set to.
     */
    val memoryExperimentRunner = MemoryExperimentRunner(
        AppMemory(memory),
        selector = CommercialContextSelector(weights = RankingWeights(semantic = SEMANTIC_RANKING_WEIGHT)),
        logger = memoryExperimentLogger,
    )

    val documents = DocumentStore(context)

    val catalogFreshness = CatalogFreshness(context)

    /** Per-provider pools of API keys — add/delete/validate in ApiKeysActivity. */
    val apiKeyStore = PrefsApiKeyStore(context, settings)

    /** Cooldown state for keys baked into the build itself — see BundledApiKeys. Never shown in ApiKeysActivity. */
    private val bundledApiKeyStore = BundledApiKeyStore(context)

    /** One rotator per provider, so cooldown state for Gemini and Mistral never mixes. */
    fun apiKeyRotator(providerId: String): ApiKeyRotator =
        ApiKeyRotator(apiKeyStore, providerId, bundledStore = bundledApiKeyStore)

    /** Which cloud models are sitting out an overload — see cloudCandidates(). */
    val modelCooldowns = ModelCooldownStore(context)

    // InMemoryMemoryProvider, as the name says, does not survive the process
    // being killed — routine on Android the moment the app is backgrounded.
    // [documents] does survive it (it's a file), so on every fresh start its
    // chunks are re-remembered here; this map is what lets a later delete
    // remove exactly the memory items *this* document put there, this run,
    // rather than guess by matching text.
    private val documentMemoryIds = mutableMapOf<String, List<String>>()

    /**
     * The new install chain (model-store/): semantic memory's embedding model
     * and the chat/translation GGUF models install through it (Phase 3b.3,
     * 3c.1); one instance, so the two never run two installers over the same
     * directory unaware of each other. Declared before [init] for the same
     * reason as [experimentalEmbeddingStore] below.
     */
    val modelInstallation = ModelInstallation(context, token = { settings.huggingFaceToken.ifBlank { null } })
        // VoskModelStore is an object reached with only a Context; it learns the chain here, first thing.
        .also { ai.localstudio.app.vosk.VoskModelStore.installation = it }

    /** The last discovery sweep's results, surviving the screen or the app closing before it finishes — see [startDiscovery]. */
    val discoveryStore = DiscoveryStore(context)

    /** Whether [startDiscovery] has a sweep running right now — [ModelDownloadService] watches this to stay alive for it. */
    val discoveryRunning = MutableStateFlow(false)

    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoveryJob: Job? = null

    /**
     * Searches Hugging Face for GGUF chat and translation candidates this
     * device could load (see [ai.localstudio.model.install.ModelDiscovery])
     * -- in [discoveryScope], an application-scoped coroutine, not whatever
     * screen's `lifecycleScope` happened to start it. Examining one
     * repository is up to three sequential, blocking HTTP round trips with
     * no concurrency, so a sweep over both labels can run close to two
     * minutes -- real enough that the person starting it is not guaranteed
     * to still be on the Models screen, or to still have the app open at
     * all, by the time it finishes. [discoveryStore.record] persists each
     * label's run the moment it finishes, independent of the other label and
     * of whatever UI is or isn't still around to show it.
     *
     * A no-op while a sweep is already running (checked via [discoveryJob],
     * not [discoveryRunning] — the latter is for outside observers).
     */
    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        val discovery = modelInstallation.discovery ?: return
        discoveryJob = discoveryScope.launch {
            discoveryRunning.value = true
            try {
                val known = (LocalModels.SEEDS + TranslationModels.SEEDS + allCustomSeeds())
                    .flatMap { it.repoIds }.toSet()
                // The same 1.3x file-size-to-RAM estimate DeviceProfile.fitsBudget uses.
                val maxModelBytes = device.usableRamBytes * 10 / 13
                val queries = listOf(
                    "chat" to ModelSearchQuery(tags = listOf("gguf"), pipelineTag = "text-generation", limit = 15),
                    "translation" to ModelSearchQuery(tags = listOf("gguf"), pipelineTag = "translation", limit = 15),
                )
                for ((label, query) in queries) {
                    appLog.record("DISCOVERY", "$label: searching Hugging Face…")
                    val outcomes = mutableListOf<ModelDiscovery.Outcome>()
                    val result = runCatching {
                        discovery.discover(
                            query,
                            ArtifactResolver.DEFAULT_QUANT_PRIORITY,
                            maxModelBytes,
                            known,
                            onOutcome = { outcome ->
                                outcomes += outcome
                                appLog.record(
                                    "DISCOVERY",
                                    when (outcome) {
                                        is ModelDiscovery.Outcome.Candidate ->
                                            "$label CANDIDATE ${outcome.repo.id}: ${outcome.file.name} (${outcome.file.sizeBytes / 1_000_000} MB, " +
                                                "${outcome.architecture}, context ${outcome.contextLength ?: "?"}${outcome.notes.joinToString("") { "; $it" }}) " +
                                                "@${outcome.commit.take(8)}, ${outcome.repo.downloads} downloads"
                                        is ModelDiscovery.Outcome.Dropped -> "$label dropped ${outcome.repo.id}: ${outcome.reason}"
                                    },
                                )
                            },
                        )
                    }
                    (modelInstallation.hub as? HuggingFaceApiClient)?.lastSearchShape
                        ?.let { appLog.record("DISCOVERY", "$label search: $it") }
                    val run = result.fold(
                        onSuccess = { r ->
                            DiscoveryRun(
                                label = label,
                                finishedAtEpochMs = System.currentTimeMillis(),
                                checked = r.outcomes.size,
                                candidates = r.candidates.map(DiscoveryStore::candidateOf),
                            )
                        },
                        onFailure = { e ->
                            appLog.record("DISCOVERY", "$label search FAILED: ${e.javaClass.simpleName}: ${e.message}")
                            DiscoveryRun(
                                label = label,
                                finishedAtEpochMs = System.currentTimeMillis(),
                                checked = outcomes.size,
                                candidates = outcomes.filterIsInstance<ModelDiscovery.Outcome.Candidate>()
                                    .map(DiscoveryStore::candidateOf),
                                failure = "${e.javaClass.simpleName}: ${e.message}",
                            )
                        },
                    )
                    discoveryStore.record(run)
                }
                appLog.record("DISCOVERY", "sweep finished")
            } finally {
                discoveryRunning.value = false
            }
        }
    }

    /** What [startCandidateTrial] is doing right now, for the notification and the candidates screen; null when idle. */
    val candidateTrialStatus = MutableStateFlow<CandidateTrialState?>(null)

    private var candidateTrialJob: Job? = null

    /**
     * Download & Test for one discovered candidate: installs the exact file
     * discovery probed (see [CandidateModel]), loads it through
     * [sharedRuntimeManager] like any local model (so the resident chat
     * model is evicted first, and the RAM admission applies), asks it the
     * [FunctionalProbe]s for its label and records the [DeviceVerification]
     * that run produced. An install that fails records nothing -- no load
     * was attempted, so there is nothing to say about this device -- and the
     * candidate stays UNVERIFIED either way unless the runtime itself
     * answered. False when a trial is already running.
     */
    fun startCandidateTrial(label: String, candidate: DiscoveredCandidate): Boolean {
        if (candidateTrialJob?.isActive == true) return false
        candidateTrialJob = discoveryScope.launch {
            val tag = "CANDIDATE_TEST"
            val name = candidate.repoId
            try {
                candidateTrialStatus.value = CandidateTrialState(name, CandidateTrialState.Phase.DOWNLOADING, 0, candidate.sizeBytes)
                appLog.record(tag, "$name: installing ${candidate.filePath}@${candidate.commit.take(8)} (${candidate.sizeBytes / 1_000_000} MB)")
                var loggedQuarter = 0
                val weights = installCandidate(candidate) { done, total ->
                    val state = CandidateTrialState(name, CandidateTrialState.Phase.DOWNLOADING, done, total)
                    candidateTrialStatus.value = state
                    val quarter = (state.percent ?: 0) / 25
                    if (quarter > loggedQuarter && quarter < 4) {
                        loggedQuarter = quarter
                        appLog.record(tag, "$name: downloaded ${done / 1_000_000}/${total / 1_000_000} MB")
                    }
                } ?: return@launch

                candidateTrialStatus.value = CandidateTrialState(name, CandidateTrialState.Phase.LOADING)
                appLog.record(tag, "$name: loading with llama.cpp")
                val profile = "${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT} / " +
                    String.format(
                        java.util.Locale.ROOT,
                        "%.1f GB / llama.cpp %s (%s)",
                        device.totalRamBytes / 1e9,
                        ai.localstudio.model.install.LlamaCppArchitectures.LLAMA_CPP_TAG,
                        LlamaBridge.loadedLibrary ?: "unavailable",
                    )
                val probes = FunctionalProbe.forLabel(label)
                val runtime = candidateRuntime(candidate, weights)
                var asked = 0
                val verification = CandidateTrial().run(
                    deviceProfile = profile,
                    runtimeId = RuntimeKind.LLAMA_CPP.id,
                    probes = probes,
                    runtime = TrialRuntime { prompt, onLoaded, onChunk ->
                        val probe = ++asked
                        runtime.answer(
                            prompt,
                            onLoaded = {
                                onLoaded()
                                if (candidateTrialStatus.value?.phase != CandidateTrialState.Phase.ANSWERING) {
                                    appLog.record(tag, "$name: loaded; asking ${probes.size} question(s)")
                                }
                                candidateTrialStatus.value = CandidateTrialState(name, CandidateTrialState.Phase.ANSWERING, probe = probe, probes = probes.size)
                            },
                            onChunk = onChunk,
                        )
                    },
                )
                val tier = verification.tier()
                appLog.record(
                    tag,
                    "$name: $tier -- loaded=${verification.loaded}, answered=${verification.inferenceOk}" +
                        (verification.tokensPerSecond?.let { String.format(java.util.Locale.ROOT, ", %.1f tok/s", it) } ?: "") +
                        (verification.error?.let { ", $it" } ?: "") +
                        (verification.sampleOutput?.let { " -- said: $it" } ?: ""),
                )
                if (!discoveryStore.recordVerification(label, name, verification)) {
                    appLog.record(tag, "$name: no longer in the last $label sweep; result logged only")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                appLog.record(tag, "$name: cancelled")
                throw e
            } catch (e: Exception) {
                appLog.record(tag, "$name: FAILED: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                // The candidate is not a model anything else uses: free its RAM now rather than when the next load evicts it.
                runCatching { withContext(kotlinx.coroutines.NonCancellable) { sharedRuntimeManager.evictIdle() } }
                candidateTrialStatus.value = null
            }
        }
        return true
    }

    private fun candidateVariantId(candidate: DiscoveredCandidate) =
        ai.localstudio.model.VariantId("discovered-" + candidate.repoId.replace('/', '_').lowercase() + "-" + candidate.commit.take(12))

    /** Bytes a Download & Test left on disk for [candidate]; null when nothing is installed for it. */
    fun candidateInstalledBytes(candidate: DiscoveredCandidate): Long? =
        modelInstallation.installed.manifest(candidateVariantId(candidate))?.let { m -> m.artifacts.sumOf { it.unpackedBytes ?: it.sizeBytes } }

    /** Removes what Download & Test installed for [candidate]; its recorded verification stays, it is still what was observed. */
    fun deleteCandidateInstall(candidate: DiscoveredCandidate): Boolean {
        if (candidateTrialJob?.isActive == true) return false
        val removed = modelInstallation.installed.uninstall(candidateVariantId(candidate))
        appLog.record("CANDIDATE_TEST", "${candidate.repoId}: install ${if (removed) "deleted" else "not found"}")
        return removed
    }

    /** The installed weights file, or null (logged) when the install did not complete. */
    private fun installCandidate(candidate: DiscoveredCandidate, progress: (done: Long, total: Long) -> Unit): File? {
        val model = CandidateModel.of(
            repoId = candidate.repoId,
            commit = candidate.commit,
            filePath = candidate.filePath,
            sizeBytes = candidate.sizeBytes,
            sha256 = candidate.sha256,
            variantId = candidateVariantId(candidate).id,
            capability = ai.localstudio.model.Capabilities.TEXT_GENERATION,
            capabilityFacet = ai.localstudio.model.GenericFacet(),
            runtime = ai.localstudio.model.Runtimes.LLAMA_CPP,
        )
        val variant = model.variants.single()
        var lastReportedMb = -1L
        val result = modelInstallation.installer.install(
            CANDIDATE_CATALOG_ID,
            candidate.commit,
            model,
            variant,
            modelInstallation::freeBytes,
        ) { p ->
            val mb = p.transfer.bytesDone / 1_000_000
            if (mb / 10 != lastReportedMb / 10) {
                lastReportedMb = mb
                progress(p.transfer.bytesDone, p.transfer.bytesTotal ?: candidate.sizeBytes)
            }
        }
        val tag = "CANDIDATE_TEST"
        val manifest = when (result) {
            is InstallResult.Installed -> result.manifest
            is InstallResult.AlreadyInstalled -> result.manifest.also { appLog.record(tag, "${candidate.repoId}: already installed, testing it as is") }
            is InstallResult.InsufficientStorage -> {
                appLog.record(tag, "${candidate.repoId}: not installed -- need ${result.neededBytes / 1_000_000} MB, ${result.freeBytes / 1_000_000} MB free")
                return null
            }
            is InstallResult.Refused -> {
                appLog.record(tag, "${candidate.repoId}: not installed -- refused (${result.status})")
                return null
            }
            is InstallResult.Failed -> {
                appLog.record(tag, "${candidate.repoId}: not installed -- ${result.fileName}: ${result.failures.joinToString("; ")}")
                return null
            }
        }
        val artifact = manifest.artifacts.single { it.role == ai.localstudio.model.ArtifactRoles.WEIGHTS }
        appLog.record(tag, "${candidate.repoId}: installed (${artifact.sizeBytes / 1_000_000} MB, ${artifact.integrity})")
        return modelInstallation.installed.pathOf(manifest, artifact)
    }

    /**
     * The trial's [TrialRuntime]: the same llama.cpp stack a chat model loads
     * through ([sharedRuntimeManager], [deviceMemoryGate], RAM measuring),
     * with a short context (the probes are a few dozen tokens) and greedy
     * sampling so a rerun asks the same question the same way. A probe that
     * does not finish within [CANDIDATE_PROBE_TIMEOUT_MS] -- the first one
     * includes the load -- fails as a timeout instead of hanging the trial.
     */
    private fun candidateRuntime(candidate: DiscoveredCandidate, weights: File): TrialRuntime {
        val contextTokens = SMALL_CONTEXT_TOKENS
        val runtime = RamMeasuringRuntime(
            inner = LlamaCppRuntime(
                contextTokens = contextTokens,
                log = appLog::record,
                availableRamBytes = { currentAvailableRamBytes(context) },
                memoryDiagnostics = { currentMemoryDiagnostics(context) },
            ),
            contextTokens = contextTokens,
            store = measuredRam,
            log = appLog::record,
        )
        val descriptor = ModelDescriptor(
            id = "candidate:${candidate.repoId}@${candidate.commit.take(12)}",
            family = "discovered",
            version = candidate.commit,
            parameterCount = 0,
            contextLength = candidate.contextLength?.toInt() ?: 0,
            capabilities = setOf(Capability.TEXT_GENERATION),
            sourceUrl = "https://huggingface.co/${candidate.repoId}",
            bindings = listOf(RuntimeBinding(RuntimeKind.LLAMA_CPP, weights.absolutePath, weights.length())),
        )
        val binding = descriptor.bindings.single()
        return TrialRuntime { prompt, onLoaded, onChunk ->
            try {
                withTimeout(CANDIDATE_PROBE_TIMEOUT_MS) {
                    deviceMemoryGate.withLock {
                        sharedRuntimeManager.withModel(descriptor, binding, runtime, contextTokens) { loaded ->
                            onLoaded()
                            val handle = loaded as? ai.localstudio.core.runtime.TextModelHandle
                                ?: throw IllegalStateException("${descriptor.id} did not load as a text model")
                            handle.generate(
                                ai.localstudio.core.runtime.GenerationRequest(
                                    prompt = prompt,
                                    maxTokens = CANDIDATE_MAX_TOKENS,
                                    temperature = 0.0,
                                    repeatPenalty = 1.0,
                                ),
                            ).collect { onChunk(it) }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Not a cancellation of the trial itself: a probe that never finished is an observed failure.
                throw IllegalStateException("no complete answer within ${CANDIDATE_PROBE_TIMEOUT_MS / 60_000} min")
            }
        }
    }

    /**
     * Where semantic memory's embedding model ([ExperimentalEmbeddingModels.E5_BASE])
     * lives: the model store first, the legacy directory as fallback (see
     * [ExperimentalEmbeddingStore]). Entirely separate from [downloads]/[modelStore]:
     * nothing here ever feeds [LocalModels] or the chat-model registry.
     *
     * Declared here, before [init] rather than in its more natural spot
     * further down near [modelStore] — [init]'s own background task reads
     * this from a coroutine dispatched on Dispatchers.IO, which can start
     * running concurrently with the rest of this very constructor, on a
     * different thread, before construction finishes. A property declared
     * *after* [init] in the source is not guaranteed initialized by the time
     * such a coroutine runs; this crashed with a real
     * NullPointerException on `experimentalEmbeddingStore.isInstalled(...)`
     * for exactly that reason before this property moved up here.
     */
    val experimentalEmbeddingStore = ExperimentalEmbeddingStore(
        context,
        installation = modelInstallation,
        log = { appLog.record("SEMANTIC_MEMORY", it) },
    )

    init {
        // Must run before sync() below: a cooldown sync() would otherwise
        // preserve as "unchanged" is exactly what this clears. One-time
        // self-heal for installs that hit a bundled key's daily-limit
        // message before the short-cooldown fix shipped — see this
        // method's own doc comment for why clearing unconditionally is safe.
        bundledApiKeyStore.clearStaleCooldownsOnce()

        // Reconciled on every launch, not just the first: cheap when it's
        // already a no-op, and it's how a bundled key added or rotated in a
        // later build ever reaches an existing install.
        BundledApiKeys.sync(bundledApiKeyStore, "groq")
        BundledApiKeys.sync(bundledApiKeyStore, "gemini")
        BundledApiKeys.sync(bundledApiKeyStore, "gigachat")

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
        //
        // Gated on memoryEnabled: with memory off, nothing ever queries
        // `memory`, so replaying every document's chunks into RAM on every
        // single launch — regardless of whether the user ever opens a chat —
        // was pure standing RAM cost for a feature not in use, competing with
        // whatever local model loads next for exactly the budget that model
        // needs. Toggling memory back on picks the documents back up the next
        // time the app starts.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (!settings.memoryEnabled) return@launch
            documents.list().forEach { doc ->
                val ids = doc.chunks.map { chunk ->
                    memory.remember(
                        chunk,
                        MemoryScope.SEMANTIC,
                        mapOf("source" to doc.name, "conversationId" to doc.conversationId),
                    )
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

        // multilingual-e5-base is this app's production embedding model as of
        // this task — see ExperimentalEmbeddingModels.E5_BASE's own doc
        // comment for the verification history (on-device dimension/cosine
        // check, plus the standalone Mobile_mem0 benchmark) that promoted it
        // out of "download it yourself on the Experimental screen first."
        // This task loads it automatically, downloading it first if it
        // isn't on disk yet, and keeps semanticMemoryIndex caught up with
        // memory afterward — off the main thread (both the download and the
        // GGUF load are real, blocking-if-synchronous work) and off the hot
        // path (embedPending() is never called from remember() or
        // consolidate() themselves; see SEMANTIC_RETRIEVAL_DESIGN.md's own
        // invariant that embedding coverage may lag, but a memory record
        // must never be lost or hidden because of it).
        //
        // Gated on memoryEnabled for the same reason the document-replay
        // task above is: with memory off, nothing ever queries `memory`, so
        // there is no "semantic memory" to initialize at all — downloading
        // ~180 MB nobody's retrieval will ever use would be pure waste.
        // Every other failure mode (no network, download failed, unsupported
        // device, the GGUF fails to load) degrades to memory staying exactly
        // as lexical-only as it always was — never a crash, never a blocked
        // launch, and never a retry loop within one run; a failed attempt
        // simply tries again fresh on the next cold start, the same way this
        // app already backfills a missing chat-model mmproj file.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Both early returns below log before bailing — without it, a
            // memory-off or unsupported-ABI device produces a completely
            // silent "why did Base never download" with nothing in the log
            // to tell it apart from every other skip reason further down,
            // which already do log. This is exactly what made a real report
            // of that question take a live device log to even start
            // narrowing down.
            if (!settings.memoryEnabled) {
                appLog.record("SEMANTIC_MEMORY", "auto-download of Multilingual E5 Base skipped: long-term memory is off")
                return@launch
            }
            experimentalEmbeddingStore.deleteRemovedModels()
            val spec = ExperimentalEmbeddingModels.E5_BASE
            // Reading isAvailable, not just checking it: this is what
            // actually triggers its lazy System.loadLibrary() call. Skipping
            // straight to LlamaCppMemoryEmbedder.load() below without ever
            // reading this property would call an external fun before the
            // native library is loaded at all, on every device — not only
            // ones this build genuinely doesn't support.
            if (!LlamaBridge.isAvailable) {
                appLog.record("SEMANTIC_MEMORY", "auto-download of ${spec.title} skipped: llama_jni did not load for this device/ABI")
                return@launch
            }

            if (!experimentalEmbeddingStore.isInstalled(spec)) {
                // settings.autoDownloadEnabled/downloadPolicy: this is the
                // one download in the app nobody explicitly asked for on
                // this run — a background task starting it on its own,
                // rather than a Download button someone just tapped. Both
                // gates are checked fresh here, not once at app start, so a
                // policy changed in Settings after this task launched but
                // before it reaches this line still takes effect.
                if (!settings.autoDownloadEnabled ||
                    !NetworkPolicy.autoDownloadAllowed(settings.downloadPolicy, NetworkPolicy.isUnmetered(context))
                ) {
                    appLog.record("SEMANTIC_MEMORY", "auto-download of ${spec.title} skipped by download settings; memory stays lexical-only for now")
                    return@launch
                }
                experimentalEmbeddingDownloads.start(spec)
                experimentalEmbeddingDownloads.state.first { states ->
                    val s = states[spec.id]
                    s is ExperimentalDownloadState.Installed || s is ExperimentalDownloadState.Failed
                }
                if (!experimentalEmbeddingStore.isInstalled(spec)) {
                    appLog.record("SEMANTIC_MEMORY", "auto-download of ${spec.title} did not complete this run; memory stays lexical-only for now")
                    return@launch
                }
            }

            if (!ensureEmbedderLoaded(spec)) return@launch

            // Periodic, not one-shot: consolidate() keeps adding new durable
            // memories for as long as the app runs, so this has to keep
            // checking back rather than running once. ensureEmbedderLoaded
            // here too, not just once above: onTrimMemory (see init{} below)
            // can unload the model between iterations under real memory
            // pressure, and this is what reloads it once pressure passes,
            // from the same on-disk file, no re-download — but only when
            // there is real work to reload it *for*, per the check below.
            //
            // Skipped while settings.semanticMemoryEnabled is off, not just
            // "allowed to run but pointless": semanticMemoryEmbedder's own
            // embedForStorage() degrades to an empty list while disabled
            // (see LazyMemoryEmbedder's isEnabled), and SemanticRetrieval.
            // embedPending() indexes that list back onto its input by
            // position — an empty list against a non-empty backlog is an
            // out-of-bounds read there, not a graceful no-op. Checking here
            // is what keeps that contract intact without weakening it on
            // the library side.
            while (true) {
                // Real device report: a benchmark run's own multi-GB Whisper
                // model and this task's own E5 embedder were repeatedly
                // fighting over the same limited RAM — every benchmark log
                // this feature has produced shows SEMANTIC_MEMORY unloading
                // and reloading itself every ~5 minutes throughout the run,
                // and one model load that should take seconds took 138s
                // right in the middle of that cycling, with no new memories
                // written in between to justify it. embedPending() itself is
                // a cheap no-op whenever nothing is missing a vector (it
                // starts with the same semanticMemoryIndex.missing(...) check
                // below), but that check happening *inside* embedPending()
                // was too late — reaching it still required
                // ensureEmbedderLoaded() to reload E5's native weights first,
                // which is the actual RAM cost this loop was causing on every
                // single tick, missing work or not. Checking missing() here,
                // before ever touching the embedder, is what actually skips
                // the reload rather than just skipping the (already-cheap)
                // embedding call after paying for it.
                if (settings.semanticMemoryEnabled) {
                    val missing = semanticMemoryIndex.missing(memory.all().map { it.id })
                    if (missing.isEmpty()) {
                        // Nothing to backfill — the embedder never gets
                        // touched this tick, so a quiet app produces zero
                        // E5 load/unload cycles, not one every interval.
                    } else {
                        // Gated on a direct RAM reading, not "is a benchmark
                        // running": a benchmark is just the one feature that
                        // happened to produce a log detailed enough to catch
                        // this, but chat generation, a live mic session, and
                        // a one-off file transcription all hold their own
                        // multi-GB models resident too, and this loop has no
                        // way to enumerate every feature that might be busy
                        // right now — nor should it need to, since a low
                        // reading already means *something* needs the room
                        // regardless of what. Same [currentAvailableRamBytes]
                        // "soft, best-effort" reading [LlamaCppRuntime]
                        // already uses to skip loading a vision projector,
                        // not routed through [DeviceProfile] for the same
                        // reason that one isn't (see that function's own doc
                        // comment). Threshold picked off observed failures:
                        // 2GB+ free ran fine, everything under that showed
                        // real symptoms (slow loads, decode failures).
                        val freeRamBytes = currentAvailableRamBytes(context)
                        constructionComplete.await()
                        val blockedBy = embedderBlockedBy()
                        if (blockedBy != null) {
                            // Checked before the RAM reading, not instead of
                            // it: even an already-loaded E5 embeds under the
                            // shared nativeOpMutex, stalling a local
                            // generation's native calls behind its batches.
                            appLog.record("SEMANTIC_MEMORY", "backfill skipped: $blockedBy")
                        } else if (freeRamBytes >= SEMANTIC_BACKFILL_MIN_FREE_RAM_BYTES) {
                            ensureEmbedderLoaded(spec)
                            runCatching { memory.embedPending(SEMANTIC_BACKFILL_BATCH) }
                                .onFailure { appLog.record("SEMANTIC_MEMORY", "embedPending failed: ${it.message}") }
                        } else {
                            appLog.record("SEMANTIC_MEMORY", "backfill skipped: only ${freeRamBytes / (1024 * 1024)} MB free")
                        }
                    }
                }
                delay(SEMANTIC_BACKFILL_INTERVAL_MS)
            }
        }

        // Releases the embedding model's native memory under real system
        // pressure — see this task's own reasoning: a device OOM-killed this
        // app's process once already with the model resident but idle (see
        // PROCESS_EXIT logging), and until now nothing ever freed it once
        // loaded. TRIM_MEMORY_RUNNING_LOW and up covers real pressure, both
        // foreground (RUNNING_LOW/RUNNING_CRITICAL — the exact levels a
        // still-visible, still-in-use app gets before being killed outright)
        // and background (BACKGROUND and up — the system actually starting
        // to reclaim from backgrounded processes in LRU order). Two levels
        // are deliberately excluded from that range, both because they fire
        // routinely rather than signalling real pressure: RUNNING_MODERATE
        // (below the threshold already) and UI_HIDDEN (20, inside the
        // range but skipped explicitly) — UI_HIDDEN fires the instant the
        // app is merely not visible, e.g. switching away for a second, with
        // nothing to do with how much RAM is actually free; a live device
        // log showed this firing (and reloading the model right after) on
        // completely routine backgrounding, far more often than the RAM it
        // saved was worth. onLowMemory() is the older, still-called-on-
        // every-API-level fallback for the same "this is serious" signal.
        // Reloading afterward is the background task above's own job, the
        // next time it wakes up — this callback only ever frees, never loads.
        context.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                    level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                ) {
                    return
                }
                CoroutineScope(Dispatchers.IO).launch { releaseMemoryUnderPressure("trim level $level", level) }
            }

            override fun onLowMemory() {
                CoroutineScope(Dispatchers.IO).launch { releaseMemoryUnderPressure("onLowMemory", level = Int.MAX_VALUE) }
            }

            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
        })
    }

    /**
     * The actual release both real memory-pressure callbacks above and
     * [simulateMemoryPressureForTesting] run — suspending, unlike
     * [android.content.ComponentCallbacks2.onTrimMemory]/`onLowMemory`
     * themselves, which launch this on a background coroutine rather than
     * calling it directly since neither is itself a suspend function.
     *
     * Frees [releaseLocalModels] too, not just [semanticMemoryEmbedder] —
     * this app's own log evidence is why: the embedding model is a few
     * hundred MB, while an idle local chat model plus its KV cache is
     * several GB, and until this call existed here nothing ever freed that
     * under memory pressure at all (its only other caller sits behind the
     * mic button, which this build hides — see [releaseLocalModels]'s own
     * doc comment). A real device log showed a `PROCESS_EXIT` from an
     * outright OOM kill with the embedding model already correctly
     * unloading on trim signals but the multi-GB chat model still fully
     * resident throughout — freeing only the smaller of the two was never
     * going to be enough to actually avoid that.
     *
     * Also frees whichever whisper.cpp engine(s) are currently resident —
     * the same gap, unaddressed by the commit above: it freed the LLM side
     * of the RAM budget but not the ASR side, even though a large whisper
     * model (up to ~1.1 GB for large-v3) is not that far off the embedding
     * model this function already treats as worth freeing. Three separate
     * engines, not one, because none of them share state: [whisperEngine]/
     * [whisperPreviewEngine] (the ad hoc mic path — see their own doc
     * comments) and [whisperFileTranscriber] ([TranscribeActivity]'s
     * vertical-slice test harness, docs/13-asr-pipeline-migration.md) each
     * load independently and are each just as capable of sitting resident
     * and uncounted through a real OOM as the LLM was.
     */
    /**
     * Frees the embedder and whisper engines for a local model about to load
     * (real device report: the embedder staying resident starved a new chat
     * load) -- at the load itself, not when the orchestrator is built.
     * Freeing it at build time ran before the request's memory recall, so
     * every message with a local model in its route was recalled without
     * semantic search (device log, build #439: E5 unloaded for "chat model
     * load" in the same second as each COMMERCIAL_MEMORY selection).
     *
     * Kept resident when freeing cannot make the load fit: a 9.8 GB model
     * against 4.7 GB available is refused either way, and unloading E5 for it
     * only cost a reload afterwards.
     */
    private suspend fun freeAuxiliaryModelsForLocalLoad(requiredBytes: Long) {
        val available = device.liveRamBytes
        if (requiredBytes > available + AUXILIARY_MODELS_MAX_BYTES) {
            appLog.record(
                "RAM_MANAGER",
                "not freeing semantic memory/whisper for a ${requiredBytes / MB} MB load: " +
                    "${available / MB} MB available, it cannot fit either way",
            )
            return
        }
        releaseMemoryUnderPressure("chat model load", includeLocalModels = false)
    }

    /**
     * Serializes [releaseMemoryUnderPressure] against itself: onTrimMemory
     * and onLowMemory each launch it in their own coroutine, and devices fire
     * trim callbacks in bursts. None of the engines' release() methods is
     * reentrant on its own (each reads a plain var, closes what it read,
     * then nulls it), so two overlapping calls could both close the same
     * native handle. Hardening, not a confirmed crash fix: the native crash
     * that prompted it (build #445, ~22s after one "file-transcriber engine
     * unloaded" line) logged that line once, so two overlapping releases
     * did not happen there.
     */
    private val memoryReleaseMutex = Mutex()

    private suspend fun releaseMemoryUnderPressure(
        reason: String,
        level: Int = Int.MAX_VALUE,
        includeLocalModels: Boolean = true,
    ) {
        memoryReleaseMutex.withLock {
            if (semanticMemoryEmbedder.isReady) {
                semanticMemoryEmbedder.unload()
                appLog.record("SEMANTIC_MEMORY", "unloaded under memory pressure ($reason); will reload once pressure passes")
            }
            releaseWhisperEngines(reason, level)
        }
        // Outside memoryReleaseMutex, never inside it: evictIdle takes
        // sharedRuntimeManager's own lock, and a local load calls this very
        // function (freeAuxiliaryModelsForLocalLoad, from beforeAdmission)
        // while *holding* that lock. Nesting the two here, in the opposite
        // order, would deadlock a trim callback against a model load.
        // evictIdle is already serialized by that lock on its own.
        if (includeLocalModels) releaseLocalModels()
    }

    /**
     * [level] is the raw ComponentCallbacks2 trim level (Int.MAX_VALUE for
     * onLowMemory/[simulateMemoryPressureForTesting], always the most
     * severe). Two different engines tolerate different tiers of it, because
     * they mean different things while "actively in use":
     *
     * - The router's four models ([whisperFallbackModel]/[whisperLidModel]/
     *   [voskRuSpecialist]/[voskEnSpecialist]) skip eviction below TRIM_MEMORY_RUNNING_CRITICAL
     *   while [routerSessionActive] — a foreground, interactive feature that
     *   already stops itself on backgrounding (TranscribeActivity.onPause),
     *   so it only needs protecting from the *routine* signal (RUNNING_LOW,
     *   fired constantly on a real device at ~1.4GB free) that was never
     *   close to an actual OOM. See this fix's own commit for the original
     *   report: an ~18s reload forced mid-conversation.
     * - [whisperFileTranscriber] skips eviction below TRIM_MEMORY_COMPLETE
     *   while [fileTranscriptionRunner]'s own `running` says a batch is
     *   active — a task that is *expected*
     *   to keep running while the app is backgrounded (the user switches
     *   away and comes back later), so it needs to tolerate BACKGROUND/
     *   MODERATE, not just RUNNING_LOW. Real device report: a file finished
     *   as "Done" with an empty transcript, saved as an empty .txt, because
     *   [WhisperFileTranscriber.release] fired mid-transcription (BACKGROUND,
     *   trim level 40) — its `requestCancel()` makes the transcription loop
     *   quietly `break` and return whatever it has so far (nothing, if the
     *   very first 30s window hadn't finished yet) rather than throwing, so
     *   nothing in TranscribeActivity's own error handling ever saw this as
     *   a failure. TRIM_MEMORY_COMPLETE (about to be killed regardless) and
     *   onLowMemory still evict it — this is not a way to keep a file
     *   transcription alive through an actual OOM, only through routine
     *   backgrounding.
     *
     * Every other engine here has no "in use" concept to protect and is
     * released on any qualifying level, exactly as before.
     */
    private fun releaseWhisperEngines(reason: String, level: Int = Int.MAX_VALUE) {
        if (whisperEngine.isLoaded) {
            whisperEngine.release()
            appLog.record("WHISPER", "main engine unloaded under memory pressure ($reason)")
        }
        if (whisperPreviewEngine.isLoaded) {
            whisperPreviewEngine.release()
            appLog.record("WHISPER", "preview engine unloaded under memory pressure ($reason)")
        }
        val skipFileTranscriber = fileTranscriptionRunner.running.value && level < android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (skipFileTranscriber) {
            appLog.record("WHISPER", "file-transcriber kept resident through non-critical pressure ($reason); transcription is active")
        }
        if (whisperFileTranscriber.isLoaded && !skipFileTranscriber) {
            whisperFileTranscriber.release()
            appLog.record("WHISPER", "file-transcriber engine unloaded under memory pressure ($reason)")
        }
        if (voskFileTranscriber.isLoaded && !skipFileTranscriber) {
            voskFileTranscriber.release()
            appLog.record("VOSK", "file-transcriber engine unloaded under memory pressure ($reason)")
        }
        if (whisperMicSession.isLoaded) {
            whisperMicSession.release()
            appLog.record("WHISPER", "mic session unloaded under memory pressure ($reason)")
        }
        if (voskRecognizer.isLoaded) {
            voskRecognizer.release()
            appLog.record("VOSK", "recognizer unloaded under memory pressure ($reason)")
        }
        val skipRouterModels = routerSessionActive && level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (skipRouterModels) {
            appLog.record("ROUTER", "router models kept resident through non-critical pressure ($reason); session is active")
        }
        if (whisperFallbackModel.isLoaded && !skipRouterModels) {
            whisperFallbackModel.release()
            appLog.record("WHISPER", "router fallback model unloaded under memory pressure ($reason)")
        }
        if (whisperLidModel.isLoaded && !skipRouterModels) {
            whisperLidModel.release()
            appLog.record("WHISPER", "router LID model unloaded under memory pressure ($reason)")
        }
        if (voskRuSpecialist.isLoaded && !skipRouterModels) {
            voskRuSpecialist.release()
            appLog.record("VOSK", "router RU specialist unloaded under memory pressure ($reason)")
        }
        if (voskEnSpecialist.isLoaded && !skipRouterModels) {
            voskEnSpecialist.release()
            appLog.record("VOSK", "router EN specialist unloaded under memory pressure ($reason)")
        }
    }

    /**
     * Test/diagnostic-only entry point: runs the exact release
     * [onTrimMemory][android.content.ComponentCallbacks2.onTrimMemory]
     * would under real system memory pressure (see that function's own
     * doc comment for exactly which levels qualify) — suspending, so an
     * instrumentation test can await it
     * deterministically instead of racing the fire-and-forget coroutine the
     * real callback launches. Nothing in this app can force Android to
     * actually deliver TRIM_MEMORY_RUNNING_LOW from a test, so this is the
     * only practical way to exercise the full downloaded → loaded →
     * pressure → lexical-fallback → async-reload → semantic-again lifecycle
     * end to end without a device that happens to be genuinely low on
     * memory mid-test-run.
     */
    suspend fun simulateMemoryPressureForTesting() {
        releaseMemoryUnderPressure("simulated for test")
    }

    /**
     * Serializes every actual native load [ensureEmbedderLoaded] attempts.
     * Before [LazyMemoryEmbedder]'s own `reloadTrigger` existed, this
     * function only ever ran from one place at a time — the initial
     * background task, then the periodic backfill loop, always
     * sequentially. `reloadTrigger` adds a second, independent caller that
     * can fire around the same moment the periodic loop's own tick does;
     * this is what keeps that overlap from becoming two concurrent
     * [LlamaCppMemoryEmbedder.load] calls racing over the same GGUF file. A
     * caller that arrives while another is already loading simply waits for
     * it to finish and reads its result, rather than starting a second,
     * redundant load of its own.
     */
    private val embedderReloadMutex = Mutex()

    /**
     * Why semantic-memory work (loading E5, embedding batches) should wait
     * right now, or null when nothing heavier is running. Only after this
     * says null does the free-RAM threshold even matter.
     */
    private fun embedderBlockedBy(): String? = when {
        heavyOperations.isActive -> "a chat/translation reply or a voice transcription is running"
        deviceMemoryGate.isLocked -> "a local or AICore generation holds the device-memory gate"
        sharedRuntimeManager.hasModelInUse -> "a local model is in use"
        LlamaCppRuntime.hasPendingNativeWork() -> "an abandoned native model load is still finishing"
        routerSessionActive -> "a live mic session is running"
        fileTranscriptionRunner.running.value -> "a file transcription is running"
        benchmarkOrchestrator.state.value is BenchmarkUiState.Running -> "a benchmark is running"
        else -> null
    }

    /**
     * Loads [spec] and hands it to [semanticMemoryEmbedder] if it isn't
     * already resident — shared by the initial load above, the periodic
     * reload-after-[LazyMemoryEmbedder.unload] check, and
     * [LazyMemoryEmbedder]'s own `reloadTrigger`, so all three go through
     * the exact same native-load, error-logging, and no-parallel-loads path
     * (see [embedderReloadMutex]). Returns whether the embedder is ready by
     * the time this returns (already-ready counts). Never re-downloads:
     * a spec not yet installed on disk is reported not-ready rather than
     * started here, exactly as before.
     */
    private suspend fun ensureEmbedderLoaded(spec: EmbeddingModelSpec): Boolean = embedderReloadMutex.withLock {
        constructionComplete.await()
        if (semanticMemoryEmbedder.isReady) return@withLock true
        if (!experimentalEmbeddingStore.isInstalled(spec)) return@withLock false
        // E5 is the lowest-priority native model this app has: semantic
        // recall degrades to lexical search without it, while a local
        // generation or transcription that loses its headroom to it can be
        // OOM-killed. All three callers (initial load, periodic backfill,
        // reloadTrigger — which fires mid-chat, right as a local model is
        // loading) go through this, so all three wait for a quiet moment;
        // the periodic loop retries later.
        embedderBlockedBy()?.let { reason ->
            appLog.record("SEMANTIC_MEMORY", "E5 load deferred: $reason")
            return@withLock false
        }
        // A legacy download moves into the model store only once proven
        // byte-identical to the catalogue's file; anything short of that
        // leaves it where it is and fileFor() keeps pointing at it.
        withContext(Dispatchers.IO) { runCatching { experimentalEmbeddingStore.adoptLegacy(spec) } }
            .exceptionOrNull()?.let { appLog.record("SEMANTIC_MEMORY", "${spec.title}: adoption failed, legacy file used: ${it.message}") }

        val embedder = runCatching {
            LlamaCppMemoryEmbedder.load(
                bridge = LlamaBridge(),
                modelPath = experimentalEmbeddingStore.fileFor(spec).absolutePath,
                modelId = spec.id,
                pooling = spec.pooling,
                queryPrefix = spec.queryPrefix,
                passagePrefix = spec.passagePrefix,
            )
        }.getOrElse { error ->
            appLog.record("SEMANTIC_MEMORY", "failed to load ${spec.title}: ${error.message}")
            null
        } ?: return@withLock false

        semanticMemoryEmbedder.set(embedder) { embedder.close() }
        appLog.record(
            "SEMANTIC_MEMORY",
            "${spec.title} ready (dimension=${embedder.dimension}) — backfilling existing memory",
        )
        true
    }

    /**
     * [conversationId] is tagged onto every chunk's memory metadata, not just
     * onto the stored [AttachedDocument] record — [NodeExecutors.contextBuild]
     * filters on it when force-including an attached document's content, so
     * two different chats attaching files that happen to share a name can't
     * cross-contaminate each other's context.
     */
    suspend fun rememberDocument(name: String, chunks: List<String>, conversationId: String): AttachedDocument =
        withContext(Dispatchers.IO) {
            val ids = chunks.map { chunk ->
                memory.remember(chunk, MemoryScope.SEMANTIC, mapOf("source" to name, "conversationId" to conversationId))
            }
            val doc = AttachedDocument(
                id = "doc-${System.currentTimeMillis()}",
                name = name,
                chunks = chunks,
                addedAt = System.currentTimeMillis(),
                conversationId = conversationId,
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

    /**
     * Deleting a conversation from history only ever removed its transcript
     * file (see ChatHistoryStore.delete) — anything already consolidated
     * into durable memory (LlmMemoryExtractor tags every fact it produces
     * with this same conversationId) survived that deletion untouched and
     * kept surfacing in later, unrelated chats. Every scope is searched, not
     * just EPISODIC/SEMANTIC: an un-consolidated conversation's raw WORKING
     * turns should go with it too, not linger forever as an unreachable
     * orphan (search()'s default scopes already exclude WORKING, so it was
     * never visible, just never cleaned up either).
     */
    suspend fun forgetConversationMemory(conversationId: String) = withContext(Dispatchers.IO) {
        memory.search(
            MemoryQuery(
                text = "",
                scopes = MemoryScope.entries.toSet(),
                metadataFilter = mapOf(FileMemoryStore.CONVERSATION_KEY to conversationId),
                limit = CONVERSATION_MEMORY_FORGET_LIMIT,
            ),
        ).forEach { item -> memory.forget(item.id) }
    }

    /** Recomputed on demand: free memory moves, and the budget is user-settable. */
    val device: DeviceProfile get() = profileOf(context, settings.ramBudgetFraction, sharedRuntimeManager.residentBytes)

    val modelStore = ModelStore(context, modelInstallation)

    val downloads = ModelDownloads(
        modelStore,
        tokenProvider = { settings.huggingFaceToken.ifBlank { null } },
        onDownloadStarted = { ModelDownloadService.ensureStarted(context) },
        log = appLog::record,
    )

    // No auto-download of Tiny on first launch: voice input's mic button and
    // Voice model category are both hidden (see activity_chat.xml and
    // activity_models.xml) because whisper.cpp transcription still isn't
    // reliable enough on-device — CPU contention with a local LLM can push a
    // single transcription past a minute and derail the silence-based
    // auto-stop entirely. Nothing reachable from the UI calls transcribe()
    // while the mic is hidden, so fetching a model in the background would
    // just be wasted disk space until this comes back properly.
    val whisperStore = WhisperStore(context, modelInstallation)
    val whisperDownloads = WhisperDownloads(
        whisperStore,
        onDownloadStarted = { ModelDownloadService.ensureStarted(context) },
        log = { appLog.record("WHISPER_DOWNLOAD", it) },
    )

    /**
     * One [ai.localstudio.app.whisper.WhisperModelSeed] per URL in
     * [Settings.customWhisperUrls] — same pattern as [customSeeds] for chat
     * models: rebuilt fresh on every read, so a model added via "Custom
     * model" on the Voice tab survives an Activity recreation or a full
     * app restart.
     */
    fun customWhisperSeeds(): List<ai.localstudio.app.whisper.WhisperModelSeed> =
        settings.customWhisperUrls.map { ai.localstudio.app.whisper.WhisperModels.custom(it) }

    fun addCustomWhisperModel(url: String): ai.localstudio.app.whisper.WhisperModelSeed {
        settings.customWhisperUrls += url
        return ai.localstudio.app.whisper.WhisperModels.custom(url)
    }

    /** Forgets a custom Whisper model entirely, same reasoning as [removeCustomModel]. */
    fun removeCustomWhisperModel(url: String) {
        val seed = ai.localstudio.app.whisper.WhisperModels.custom(url)
        if (settings.whisperModelId == seed.id) settings.whisperModelId = ""
        settings.customWhisperUrls -= url
        whisperDownloads.delete(seed)
    }

    /** [WhisperStore.installedSeed], but able to resolve a custom model too — see [customWhisperSeeds]. */
    fun installedWhisperSeed(preferredId: String? = null) =
        whisperStore.installedSeed(preferredId, WhisperModels.SEEDS + customWhisperSeeds())

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

    /**
     * [ai.localstudio.core.runtime.SpeechModelHandle] for `whisper_cpp`, wired
     * into [RuntimeManager] (see buildOrchestrator) so a pipeline's
     * SPEECH_TO_TEXT node resolves to a real engine instead of throwing "no
     * runtime registered" — see docs/13-asr-pipeline-migration.md. A single
     * shared instance, unlike [LlamaCppRuntime] which is rebuilt per call:
     * nothing about it varies per request the way llama's context size does.
     */
    val whisperCppRuntime: ModelRuntime = WhisperCppRuntime(context = context, log = appLog::record)

    /**
     * Shared across the app rather than owned by [TranscribeActivity] so
     * [releaseMemoryUnderPressure] can free it too — the same model-lifecycle
     * gap that commit fixed for the LLM applied here just as much: a large
     * whisper.cpp model (up to ~1.1 GB for large-v3) left resident behind
     * [TranscribeActivity] while the user goes do something else in
     * [ChatActivity] would otherwise sit uncounted through the exact memory
     * pressure that unloads everything else.
     */
    val whisperFileTranscriber = WhisperFileTranscriber(whisperCppRuntime as WhisperCppRuntime, whisperStore)

    /**
     * [VoskFileTranscriber]'s own loaded-model slot for file transcription —
     * deliberately its own [VoskSpeechRecognizer] instance, separate from
     * [voskRecognizer] (the live-mic one) below, for the exact same reason
     * [whisperFileTranscriber] doesn't share a handle with [whisperMicSession]:
     * a file transcription running in the background must not silently
     * `cancel()` an unrelated live-mic session.
     */
    private val voskFileRecognizer = VoskSpeechRecognizer(context, appLog)
    val voskFileTranscriber = VoskFileTranscriber(context, voskFileRecognizer)

    /**
     * Owns [TranscribeActivity]'s batch file/folder transcription for the
     * whole app — see this class's own doc comment for the real device
     * report that made this necessary (Activity recreation under memory
     * pressure silently wiping an in-progress transcription). Wired with
     * [FileTranscriptionService.ensureStarted] the same way [downloads]/
     * [whisperDownloads] wire [ModelDownloadService] — a foreground service
     * so the process itself survives backgrounding, not just the Activity.
     */
    val fileTranscriptionRunner = FileTranscriptionRunner(
        whisperTranscriber = whisperFileTranscriber,
        voskTranscriber = voskFileTranscriber,
        context = context,
        onTranscriptionStarted = { FileTranscriptionService.ensureStarted(context) },
    )

    /**
     * STT Benchmark (docs/16-stt-benchmark.md): every backend currently
     * available to compare, resolved live rather than cached — a model can
     * be downloaded or deleted between one read and the next, same
     * reasoning [installedSeeds] already follows. Empty (never a crash)
     * when no whisper model is installed; [BenchmarkActivity] surfaces that
     * as [ai.localstudio.app.R.string.benchmark_no_engines] before a run is
     * even started.
     *
     * A dedicated [WhisperCppTranscriptionEngine] instance, not
     * [whisperFileTranscriber]/`whisperFallbackModel`/etc — see that
     * class's own doc comment for why sharing a resident handle would
     * corrupt the one number a benchmark exists to measure honestly
     * (model_load_time).
     */
    /**
     * Every *installed* Whisper size, not just [settings.whisperModelId]'s
     * one pick — comparing sizes/quantizations against each other is the
     * whole point once CTranslate2 is off the table as a second backend
     * (see docs/16-stt-benchmark.md's own note on this). Order is
     * deliberate, not scan order: Tiny first (cheapest, fastest way to
     * confirm the whole run works at all), then largest-to-smallest
     * through the rest — this run's biggest, riskiest load happens early,
     * right after a known-good baseline, rather than last after whatever
     * memory pressure the smaller models already added.
     */
    val transcriptionEngines: List<TranscriptionEngine>
        get() {
            val installed = WhisperModels.SEEDS.filter { whisperStore.isInstalled(it) }
            val tiny = installed.filter { it.id == WhisperModels.TINY_ID }
            val restLargestFirst = installed.filterNot { it.id == WhisperModels.TINY_ID }.sortedByDescending { it.approxSizeBytes }
            return (tiny + restLargestFirst).map { seed ->
                WhisperCppTranscriptionEngine(whisperCppRuntime as WhisperCppRuntime, whisperStore, seed)
            }
        }

    private val benchmarkReportStore = BenchmarkReportStore(context, appLog)

    /**
     * Owns the STT Benchmark run for the whole app, not for
     * [BenchmarkActivity] — same reasoning [fileTranscriptionRunner]
     * already follows: a run over hundreds of files across several engines
     * can run far longer than the screen watching it is guaranteed to stay
     * alive for.
     */
    val benchmarkOrchestrator = BenchmarkOrchestrator(
        runner = BenchmarkRunner(),
        reportStore = benchmarkReportStore,
        engineProvider = { transcriptionEngines },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        appLog = appLog,
        context = context,
        onBenchmarkStarted = { BenchmarkService.ensureStarted(context) },
    )

    /**
     * Phase 3 (docs/13-asr-pipeline-migration.md): a live-mic
     * [ai.localstudio.core.runtime.StreamingSpeechSession] driver, new and
     * not yet wired into any screen's UI — [TranscribeActivity] exercises
     * it as a test harness the same way it already does for files. Shared
     * for the same reason [whisperFileTranscriber] is: so
     * [releaseMemoryUnderPressure] can free it too.
     */
    val whisperMicSession = WhisperCppMicSession(whisperCppRuntime as WhisperCppRuntime, whisperStore)

    /**
     * Vosk ASR spike (docs/14-vosk-spike.md): a second, independent live-mic
     * path, tried as a candidate for replacing [whisperMicSession]'s
     * re-transcribe-the-growing-buffer approach. Shared the same way
     * [whisperMicSession] is, so [releaseMemoryUnderPressure] can free its
     * model too, and so [ai.localstudio.app.TranscribeActivity] can reuse
     * one instance across recordings instead of reloading the model each time.
     */
    val voskRecognizer = VoskSpeechRecognizer(context, appLog)

    /** In-app downloader for [ai.localstudio.app.vosk.VoskModels.SEEDS] — same role for the Voice tab's Vosk rows as [whisperDownloads] has for Whisper's. */
    val voskDownloads = VoskDownloads(
        context,
        onDownloadStarted = { ModelDownloadService.ensureStarted(context) },
        log = { appLog.record("VOSK_DOWNLOAD", it) },
    )

    /**
     * Whisper registered as the router's multilingual fallback — see
     * [WhisperRegisteredSpeechModel]'s own doc comment. Held separately
     * (not just inside [speechModelRegistry]) so callers with a loaded
     * handle already in hand (there were none left once
     * [speechLanguageIdentifier] stopped being one of them — see
     * [whisperLidModel]'s own doc comment for why) can reuse it.
     */
    private val whisperFallbackModel = WhisperRegisteredSpeechModel(
        runtime = whisperCppRuntime as WhisperCppRuntime,
        whisperStore = whisperStore,
        seedProvider = { installedWhisperSeed(settings.whisperModelId) },
    )

    /**
     * A dedicated, always-tiny model for the router's own language
     * identification — deliberately NOT [whisperFallbackModel]/
     * `settings.whisperModelId` (the user's actual transcription-quality
     * pick). LID runs a full whisper pass every few seconds regardless of
     * which language is active, and doing that with whatever heavy model
     * the user picked for real transcription (large-turbo: 574MB, several
     * seconds a pass) competed for the same global native mutex (see
     * [ai.localstudio.whisper.WhisperBridge.nativeOpMutex]'s own doc
     * comment) with both the router's own ASR and any concurrent file
     * transcription. A real device report showed exactly this: a
     * large-turbo file transcription "stuck" for minutes while the
     * router's own LID, hammering that same heavy model every few
     * seconds, starved everything else waiting on that one lock.
     * [WhisperModels.TINY_ID] is this app's own auto-downloaded
     * first-launch default — normally installed with no extra download UI
     * needed, and cheap enough that a full pass costs a small fraction of
     * what a heavier model would.
     */
    private val whisperLidModel = WhisperRegisteredSpeechModel(
        runtime = whisperCppRuntime as WhisperCppRuntime,
        whisperStore = whisperStore,
        seedProvider = { whisperStore.installedSeed(WhisperModels.TINY_ID) },
        id = "whisper-lid",
    )

    private val voskRuSpecialist = VoskRegisteredSpeechModel(context, VoskModels.byId("vosk-small-ru")!!, setOf(Language.RU))
    private val voskEnSpecialist = VoskRegisteredSpeechModel(context, VoskModels.byId("vosk-small-en")!!, setOf(Language.EN))

    /**
     * docs/15-speech-routing.md's first configuration: RU/EN handled by
     * their small Vosk specialists (reusing the exact seeds the Vosk spike
     * — docs/14-vosk-spike.md — already downloads/tests), everything else
     * (Hebrew, unknown, mixed, or either specialist failing/not installed)
     * falling back to multilingual Whisper. Deliberately temporary — see
     * that doc's own "the router should be able to switch ... simply by
     * configuration/registration, no router code should change" note.
     */
    val speechModelRegistry: SpeechModelRegistry = InMemorySpeechModelRegistry().apply {
        register(voskRuSpecialist)
        register(voskEnSpecialist)
        register(whisperFallbackModel)
    }

    private val speechLanguageIdentifier = WhisperLanguageIdentifier { whisperLidModel.loadedWhisperModel() }

    /**
     * Shared router instance for the experimental routing demo (see
     * [ai.localstudio.app.TranscribeActivity]'s own "LIVE MIC — ROUTER"
     * section) — a long-lived [CoroutineScope] of its own, not tied to any
     * one screen, the same reasoning [whisperMicSession]/[voskRecognizer]
     * already follow. `lidStrideMs` is several seconds, not the
     * sub-second default: [WhisperLanguageIdentifier] runs a full whisper
     * pass per evaluation, real CPU cost that must not compete with the
     * active ASR model too often.
     */
    val speechRouter: StreamingSpeechRouter = DefaultStreamingSpeechRouter(
        registry = speechModelRegistry,
        languageIdentifier = speechLanguageIdentifier,
        policy = RoutingPolicy(fallbackModelId = "whisper-fallback"),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        lidWindowMs = 4_000,
        lidStrideMs = 3_000,
    )

    /**
     * Set by [ai.localstudio.app.TranscribeActivity] around a router
     * session's lifetime — read by [releaseWhisperEngines] so a routine,
     * non-critical trim signal (TRIM_MEMORY_RUNNING_LOW, the one a real
     * device log showed firing constantly at ~1.4GB free) does not evict
     * the exact models an active router session is using every few seconds
     * for LID and ASR, forcing an expensive reload mid-session. Real
     * pressure (TRIM_MEMORY_RUNNING_CRITICAL and up, onLowMemory) still
     * evicts them regardless — see [releaseWhisperEngines]'s own doc
     * comment for why that distinction matters: this must never become a
     * way to silently reintroduce the OOM kill [releaseMemoryUnderPressure]
     * exists to prevent.
     */
    @Volatile
    var routerSessionActive: Boolean = false

    /**
     * One [LocalModelSeed] per repo id in [Settings.customModelRepoIds],
     * rebuilt fresh on every call rather than cached — this is the single
     * source of truth [ai.localstudio.app.ModelsActivity] and every real
     * generation path ([installedSeeds], [localRegistry]) both read, so a
     * model added via "Custom model from Hugging Face" is visible and
     * actually usable from the moment it's added, survives an Activity
     * recreation or a full app restart, and is resolved the same way
     * whichever screen is asking. See [Settings.customModelRepoIds]'s own
     * doc comment for the real-device bug this replaces.
     */
    fun customSeeds(purpose: ModelPurpose): List<LocalModelSeed> = when (purpose) {
        ModelPurpose.CHAT -> settings.customChatRepoIds.map { LocalModels.custom(it) }
        ModelPurpose.TRANSLATION -> settings.customTranslationRepoIds.map { LocalModels.customTranslation(it) }
    }

    /**
     * Custom models whose purpose is unknown: added before the chat /
     * translation split, or recovered from a file on disk no setting points
     * to. Not used anywhere until the user assigns one on the Models screen
     * ([assignCustomModel]) — guessing chat vs. translation from a repo name
     * is exactly the mix-up this split exists to stop.
     */
    fun unassignedCustomSeeds(): List<LocalModelSeed> = settings.customModelRepoIds.map { LocalModels.custom(it) }

    /** Every custom seed in any state — what counts as "known" when looking for orphaned files. */
    fun allCustomSeeds(): List<LocalModelSeed> =
        customSeeds(ModelPurpose.CHAT) + customSeeds(ModelPurpose.TRANSLATION) + unassignedCustomSeeds()

    fun addCustomModel(repoId: String, purpose: ModelPurpose): LocalModelSeed = when (purpose) {
        ModelPurpose.CHAT -> {
            settings.customChatRepoIds += repoId
            LocalModels.custom(repoId)
        }
        ModelPurpose.TRANSLATION -> {
            settings.customTranslationRepoIds += repoId
            LocalModels.customTranslation(repoId)
        }
    }.also { settings.customModelRepoIds -= repoId }

    /** Moves an unassigned custom model into [purpose]'s list; its file (if any) stays as it is. */
    fun assignCustomModel(repoId: String, purpose: ModelPurpose) {
        addCustomModel(repoId, purpose)
        appLog.record("MODELS", "$repoId: assigned to ${purpose.name.lowercase()}")
    }

    /**
     * Forgets [repoId] for [purpose] (null = the unassigned list). The file
     * is deleted only once no list refers to it any more — the same repo
     * added on both tabs shares one download.
     */
    fun removeCustomModel(repoId: String, purpose: ModelPurpose?) {
        when (purpose) {
            ModelPurpose.CHAT -> settings.customChatRepoIds -= repoId
            ModelPurpose.TRANSLATION -> settings.customTranslationRepoIds -= repoId
            null -> settings.customModelRepoIds -= repoId
        }
        val stillUsed = repoId in settings.customChatRepoIds ||
            repoId in settings.customTranslationRepoIds ||
            repoId in settings.customModelRepoIds
        if (!stillUsed) downloads.delete(LocalModels.custom(repoId))
    }

    /**
     * Once: a pre-split custom entry that is currently selected for chat or
     * translation goes to that list, so the selection keeps working; the
     * rest stay unassigned for the user to place. Every launch: a
     * `custom-*.gguf` file no list knows (a download from a build that
     * didn't persist custom models at all — real device report: shown as an
     * unknown file under chat) is recovered into the unassigned list rather
     * than offered only for deletion.
     */
    private fun migrateCustomModels() {
        if (!settings.customModelsMigrated) {
            for (repo in settings.customModelRepoIds) {
                val id = LocalModels.custom(repo).id
                if (settings.chatModelFor(CloudProviders.LOCAL.id) == id) addCustomModel(repo, ModelPurpose.CHAT)
                if (settings.translationModel == id) addCustomModel(repo, ModelPurpose.TRANSLATION)
            }
            settings.customModelsMigrated = true
        }
        val known = (LocalModels.SEEDS + TranslationModels.SEEDS + allCustomSeeds()).map { modelStore.legacyFileFor(it).name }.toSet()
        modelStore.directory().listFiles().orEmpty()
            .filter { it.isFile && it.name !in known }
            .mapNotNull { LocalModels.repoIdFromCustomFileName(it.name) }
            .forEach { repo ->
                settings.customModelRepoIds += repo
                appLog.record("MODELS", "$repo: found on disk with no list entry — recovered as unassigned")
            }
    }

    /** Local models chat may use: the chat catalog and custom chat models — never a translation-only model. */
    private fun chatSeeds(): List<LocalModelSeed> = LocalModels.SEEDS + customSeeds(ModelPurpose.CHAT)

    /**
     * Local models translation may use: the translation catalog, the chat
     * catalog (general instruct models, prompted for the task) and custom
     * translation models. Custom *chat* models are not included — nothing
     * says an arbitrary repo can translate.
     */
    private fun translationSeeds(): List<LocalModelSeed> =
        TranslationModels.SEEDS + LocalModels.SEEDS + customSeeds(ModelPurpose.TRANSLATION)

    /**
     * Seeds that are on disk right now, newest state each time it is asked —
     * [LocalModels.SEEDS] (chat GGUFs), [TranslationModels.SEEDS]
     * (specialized encoder-decoder translation GGUFs) and [customSeeds]
     * (anything added via "Custom model from Hugging Face") alike, since all
     * three are fetched and stored the same way and [localRegistry] needs to
     * resolve any of them by id.
     */
    fun installedSeeds(purpose: ModelPurpose): List<LocalModelSeed> =
        (if (purpose == ModelPurpose.CHAT) chatSeeds() else translationSeeds())
            .distinctBy { it.id }
            .filter { modelStore.isInstalled(it) }

    val experimentalEmbeddingDownloads = ExperimentalEmbeddingDownloads(
        experimentalEmbeddingStore,
        tokenProvider = { settings.huggingFaceToken.ifBlank { null } },
        log = { appLog.record("SEMANTIC_MEMORY", it) },
    )

    private var cachedOrchestrator: Orchestrator? = null
    private var cachedSignature: String? = null

    /**
     * Labels of whatever [orchestrator] most recently wired up, refreshed on
     * every call regardless of the cache — this is what lets Settings and
     * the chat screen show what is actually configured *right now*, rather
     * than what was configured the last time the orchestrator happened to be
     * rebuilt.
     */
    private var lastCandidates: List<FallbackCandidate> = emptyList()

    /**
     * Non-null only when exactly one candidate is configured — a pure
     * local-only setup, since every cloud provider contributes several
     * candidates via [cloudCandidates]' model rotation. That single-candidate
     * case bypasses [FallbackTextRuntime] entirely (see [orchestrator]) to
     * keep RAM-budget tracking, so it never gets the "Ответ от: <label>"
     * line [FallbackTextRuntime] embeds in the answer text — this is what
     * [ChatActivity] uses to show the same attribution for that one
     * remaining case.
     */
    val soleAnswererLabel: String? get() = lastCandidates.singleOrNull()?.label

    /**
     * The model actually in play right now — the first configured
     * candidate's own model id. Deliberately not [Settings.chatModel]: that
     * getter is scoped to whichever provider happens to be selected in the
     * Settings screen's dropdown at the moment, which is completely
     * independent of which providers are actually enabled for routing — the
     * status bar showing a Gemini model name while [runtimeLabel] (correctly)
     * said "Локально на устройстве" was exactly that mismatch, not a routing
     * bug: the router was already using local, only the label lied about it.
     */
    val activeModelName: String
        get() {
            orchestrator() // ensures lastCandidates reflects live settings even before any message is sent
            return lastCandidates.firstOrNull()?.model?.id ?: CloudProviders.DEMO.defaultModel
        }

    /** Rebuilt only when the settings that affect wiring have actually changed. */
    fun orchestrator(): Orchestrator {
        val enabled = enabledProviders()
        // Each cloud provider can contribute several candidates now, not one —
        // see cloudCandidates — so this is a flatMap, not mapNotNull. Order is
        // still what makes the policy real: local first, then each enabled
        // provider's own model list before the next provider's first model.
        val candidates = enabled.flatMap { provider ->
            when (provider.id) {
                CloudProviders.LOCAL.id -> listOfNotNull(localCandidate())
                CloudProviders.AICORE.id -> listOf(aicoreCandidate())
                else -> cloudCandidates(provider)
            }
        }
        lastCandidates = candidates

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
                settings.memoryEnabled,
                // Whether *any* document is attached is what effectiveContextTokens()
                // actually keys on — this is what makes attaching or removing
                // one pick up the right context size on the very next message,
                // not just whenever some unrelated setting also changes.
                documents.list().isNotEmpty(),
            ) +
                enabled.flatMap { listOf(settings.chatModelFor(it.id), settings.apiKeyFor(it.id)) } +
                // Which local model is actually installed can change without
                // any setting changing (download finished, model deleted).
                candidates.map { it.model.id }
            ).joinToString("|")
        cachedOrchestrator?.takeIf { cachedSignature == signature }?.let { return it }

        // Only on an actual rebuild, not every call — this is the answer to
        // "is the provider I just unchecked really gone": if it still shows
        // up here after being disabled, the bug is in enabledProviderIds
        // persistence, not in routing.
        appLog.record(
            "ROUTER_REBUILD",
            "enabled=${enabled.map { it.id }} candidates=${candidates.map { it.label }}",
        )

        val runtime: ModelRuntime = when {
            candidates.isEmpty() -> StubRuntime(context)
            candidates.size == 1 -> candidates.single().runtime
            // CloudProviders.ALL lists Local first, so `enabled` — and
            // therefore `candidates` — already carries that order: this is
            // what makes "local first, cloud as the fallback" true.
            else -> FallbackTextRuntime(candidates)
        }
        val isLocalOnly = candidates.singleOrNull()?.binding?.runtime == RuntimeKind.LLAMA_CPP
        return buildOrchestrator(runtime, isLocalOnly, candidates).also {
            cachedOrchestrator = it
            cachedSignature = signature
        }
    }

    /**
     * Frees every locally-loaded model (the LLM, its vision projector). Two
     * callers: [releaseMemoryUnderPressure], under real system memory
     * pressure — a multi-GB resident model with nothing ever freeing it was
     * exactly what a real `PROCESS_EXIT` OOM kill looked like in this app's
     * own log; and (originally) right before starting a voice recording, so
     * Whisper would not compete with an already-resident local model for
     * the same RAM budget — currently unreachable in this build since the
     * mic button is hidden (see `activity_chat.xml`'s own comment), left in
     * place for when it comes back. Uses [RuntimeManager.evictIdle], not the
     * blunter unloadAll: a model still actively mid-generation (refCount > 0)
     * is left alone rather than force-freed out from under whatever is
     * using it.
     */
    suspend fun releaseLocalModels() {
        sharedRuntimeManager.evictIdle()
    }

    /**
     * Whether [modelId] is resident in [sharedRuntimeManager] right now —
     * used by the Models screen to put what's actually warm at the top of
     * its list, ahead of what merely fits the budget but would still need a
     * fresh load. Best-effort, not locked: a model can go resident/idle
     * between this read and the next render, the same way [fitsBudget] is
     * already a snapshot rather than a guarantee — both are advisory
     * ordering, not something anything else depends on being exact.
     */
    fun isModelResident(modelId: String): Boolean =
        sharedRuntimeManager.residentModels().any { it.modelId == modelId }

    /**
     * Whether every candidate here needs the small, on-device-sized context
     * ceiling ([effectiveContextTokens]) instead of [CLOUD_CONTEXT_WINDOW_TOKENS] —
     * deliberately not the same thing [isLocalOnly] means at each of this
     * function's own call sites (output-length capping, whether a source
     * streams): [RuntimeKind.LLAMA_CPP] and [RuntimeKind.AICORE] both need
     * this, real cloud providers don't, and folding the two checks into one
     * flag would have meant getting one of the other two decisions wrong to
     * fix this one.
     */
    private fun needsSmallContextWindow(candidates: List<FallbackCandidate>): Boolean =
        candidates.all { it.binding.runtime == RuntimeKind.LLAMA_CPP || it.binding.runtime == RuntimeKind.AICORE }

    private fun buildOrchestrator(
        runtime: ModelRuntime,
        isLocalOnly: Boolean,
        registryCandidates: List<FallbackCandidate>,
    ): Orchestrator {
        // The semantic-memory embedder and whisper engines are freed right
        // before a local model actually loads -- sharedRuntimeManager's
        // beforeAdmission, see [freeAuxiliaryModelsForLocalLoad] -- not here.
        // The previous LLM is not freed here either: that is
        // sharedRuntimeManager's job at the moment the new one actually
        // loads, so a rebuild for an unrelated setting (temperature, say)
        // no longer throws away a warm multi-GB model for nothing.
        // Its own manager, holding only this orchestrator's handles — the
        // weights of a local model inside them live in sharedRuntimeManager
        // (see SharedRuntime). One manager shared by whole orchestrators
        // cached every Compare-mode chain under the same "fallback-chain" id.
        val runtimeManager = RuntimeManager(
            budgetBytes = { device.usableRamBytes },
            runtimes = mapOf(runtime.kind to runtime, whisperCppRuntime.kind to whisperCppRuntime),
        )
        val executors = NodeExecutors(
            selector = ModelSelector(registry(runtime, registryCandidates), selectionDevice()),
            runtimeManager = runtimeManager,
            contextEngine = ContextEngine(),
            memory = memory,
            memoryExperiment = memoryExperimentRunner,
            memoryExperimentMode = ExperimentMode.COMMERCIAL_MEMORY,
            systemPrompt = settings.systemPrompt.ifBlank { null },
            // The context-window setting exists to keep a local llama.cpp
            // context (and its RAM) small enough for the device — it has
            // nothing to do with what a cloud model can handle. Tuning it
            // down to survive on-device was also quietly capping how much
            // conversation/memory ever reached Gemini, unrelated to the
            // max-tokens leak fixed the same way in OpenAiRuntime.
            //
            // AICore included, not just LLAMA_CPP (isLocalOnly, used a few
            // lines down for output-length capping, means something
            // different and stays narrow) — real device report: a 4590-char
            // Compare-mode prompt, nowhere near CLOUD_CONTEXT_WINDOW_TOKENS
            // (32,000 tokens), made Gemini Nano return a blank response,
            // confirming what was flagged as an unverified hypothesis
            // earlier: AICore has nothing like a real cloud provider's
            // context limit. Reuses effectiveContextTokens() rather than a
            // new guessed number — already the ceiling this app trusts for
            // on-device inference; whether it's small enough specifically
            // for AICore is itself unverified and may need its own,
            // smaller number if this turns out not to be enough.
            contextWindowTokens = if (needsSmallContextWindow(registryCandidates)) effectiveContextTokens() else CLOUD_CONTEXT_WINDOW_TOKENS,
            defaultTemperature = settings.temperature,
            defaultTopP = settings.topP,
            defaultTopK = settings.topK,
            defaultRepeatPenalty = settings.repeatPenalty,
            // Same reasoning as contextWindowTokens just above: maxResponseTokens
            // is one shared setting a user can (reasonably) raise for a cloud
            // model's longer, more capable answers, and until now local paid
            // for that unconditionally too — a longer generation is direct,
            // linear extra wall-clock time on hardware already the
            // bottleneck, not just extra RAM the way context length is.
            defaultMaxTokens = if (isLocalOnly) minOf(settings.maxResponseTokens, LOCAL_MAX_OUTPUT_TOKENS) else settings.maxResponseTokens,
        )
        return Orchestrator(CapabilityRouter(), PipelineEngine(executors.build()))
    }

    /**
     * One [Orchestrator] per enabled provider, for [Settings.compareMode]'s
     * parallel fan-out: every enabled source generates independently rather
     * than sources being tried in fallback order against each other.
     *
     * Each source still gets its provider's whole free-tier rotation as an
     * internal chain. This used to take only the primary model, on the
     * grounds that compare mode is already N simultaneous requests and
     * should not become N × rotation-size of them — but a rotation is tried
     * *sequentially, on failure*, so it never adds a single concurrent
     * request. What the shortcut did instead was leave a source with nothing
     * to fall through to: a Gemini 503 ("high demand") became the visible
     * answer for that bubble, with no attempt at the next free model. Worse,
     * [FallbackCandidate.onFailure] is only ever invoked by
     * [FallbackTextRuntime], so with a lone candidate the 503 cooldown in
     * [cloudCandidates] never fired either and the next message went
     * straight back to the same overloaded model.
     */
    private val compareOrchestrators = mutableMapOf<String, Orchestrator>()
    private val compareSignatures = mutableMapOf<String, String>()

    /**
     * One Compare-mode bubble's source. [isLocal] drives whether
     * [ai.localstudio.app.ChatActivity] streams this source's answer token by
     * token or waits for the full response — a cloud call is fast enough
     * end-to-end that progressive rendering only adds visual noise, while a
     * local model can take minutes and needs the incremental feedback.
     *
     * [hideOnFailure] is for [CloudProviders.AICORE] specifically, in both
     * [compareCandidates] (chat) and [translationCompareCandidates]: Gemini
     * Nano's own readiness is only knowable by actually asking it (see
     * [aicoreCandidate]'s own doc comment) — not installed on this device,
     * AICore's service not bound, a blank response — and unlike every other
     * source here, that is not something explaining to the user actually
     * helps with; a failed bubble/card for it is just noise, so the caller
     * removes it instead of showing the error.
     */
    data class CompareSource(
        val label: String,
        val orchestrator: Orchestrator,
        val isLocal: Boolean,
        val hideOnFailure: Boolean = false,
    )

    fun compareCandidates(): List<CompareSource> =
        enabledProviders().mapNotNull { provider ->
            val candidates = when (provider.id) {
                CloudProviders.LOCAL.id -> listOfNotNull(localCandidate())
                CloudProviders.AICORE.id -> listOf(aicoreCandidate())
                else -> cloudCandidates(provider)
            }
            if (candidates.isEmpty()) return@mapNotNull null
            // Always wrapped, even for a single candidate: this is what gives
            // every Compare-mode bubble the "Ответ от: <model> · <elapsed>"
            // footer, not just chains with a fallback to name.
            val runtime: ModelRuntime = FallbackTextRuntime(candidates)
            // The specific model that answered is still named, by
            // FallbackTextRuntime's own "Ответ от:" footer — this is just the
            // bubble's heading, which should stay the provider for a chain
            // rather than claim whichever model happens to lead the rotation.
            val label = candidates.singleOrNull()?.label ?: context.getString(provider.titleRes)
            val isLocalOnly = candidates.all { it.binding.runtime == RuntimeKind.LLAMA_CPP }

            // Every call used to build a brand new RuntimeManager — meaning a
            // brand new LlamaCppRuntime.load() for the local candidate on
            // every single Compare-mode turn, with nothing ever freeing the
            // *previous* turn's already-loaded model (a fresh RuntimeManager
            // has no record of it, so it never gets to evict it): the old
            // native session — GGUF weights, KV cache, mmproj encoder — just
            // leaked, resident, while a second full copy loaded on top of it.
            // Two turns of that on a multi-GB vision model is exactly what
            // an OOM kill on the second message looks like. Cached the same
            // way orchestrator() already caches the single-provider path:
            // reused for this provider as long as nothing that would change
            // its wiring actually has.
            // The weights themselves are held by sharedRuntimeManager, not
            // this cached orchestrator, so chat's own path and this one reuse
            // one resident copy rather than each loading its own.
            val signature = (
                listOf(
                    settings.customEndpoint,
                    settings.temperature,
                    settings.topP,
                    settings.topK,
                    settings.repeatPenalty,
                    settings.contextTokens,
                    settings.maxResponseTokens,
                    settings.systemPrompt,
                    settings.memoryEnabled,
                    documents.list().isNotEmpty(),
                    settings.chatModelFor(provider.id),
                    settings.apiKeyFor(provider.id),
                ) + candidates.map { it.model.id }
            ).joinToString("|")

            val cached = compareOrchestrators[provider.id]?.takeIf { compareSignatures[provider.id] == signature }
            val orchestrator = cached ?: buildOrchestrator(runtime, isLocalOnly, candidates).also {
                compareOrchestrators[provider.id] = it
                compareSignatures[provider.id] = signature
            }
            CompareSource(label, orchestrator, isLocalOnly, hideOnFailure = provider.id == CloudProviders.AICORE.id)
        }

    /**
     * Providers actually enabled for use, in fallback order — see
     * [Settings.enabledProviderIds]. Gemini Nano drops out once AICore has
     * said this device can't run it ([aicoreUnsupported]), even if still
     * checked: real device log (status=0) had it in every chat route and
     * every Compare batch, failing each time. It's part of [orchestrator]'s
     * signature, so the router rebuilds on the next message after the
     * status arrives.
     */
    private fun enabledProviders(): List<CloudProvider> {
        val ids = settings.enabledProviderIds
        return CloudProviders.ALL.filter { it.id in ids && !(it.id == CloudProviders.AICORE.id && aicoreUnsupported) }
    }

    /**
     * The explicit choice from Models if it's actually installed,
     * [ModelSelector]'s best fit otherwise — factored out so
     * [localVisionAvailable] can ask "which model, specifically" without also
     * building a runtime and a [FallbackCandidate] just to answer that.
     *
     * [chosenId] defaults to the chat model ([localCandidate]'s own use), but
     * takes an explicit id too — [translationLocalCandidate] passes
     * [Settings.translationModel] here so a model picked for translation
     * doesn't have to be the same one chat is currently using.
     */
    private fun effectiveLocalSelection(
        registry: ModelRegistry,
        chosenId: String = settings.chatModelFor(CloudProviders.LOCAL.id),
    ): SelectedModel? {
        val chosen = registry.find(chosenId)
            ?.takeIf { it.state == InstallState.INSTALLED }
            ?.let { entry -> SelectedModel(entry.model, entry.model.bindings.first()) }
        return chosen ?: ModelSelector(registry, device).selectOrNull(Capability.TEXT_GENERATION)
    }

    /**
     * [Settings.translationModel] resolved to a local candidate — shared by
     * every caller in [translationCompareCandidates] that needs the LOCAL
     * entry built the identical way, instead of each resolving it separately
     * and risking drift. Null for the AICore case (its own caller handles
     * that id directly) and when nothing local is chosen or installed.
     */
    private fun translationLocalCandidate(): FallbackCandidate? {
        // A stored AICore pick (from before Gemini Nano left the Models →
        // Translation list) means "auto": Nano is added to every translation
        // batch on its own (see translationCompareCandidates), so treating it
        // as the translation model only ever removed the local source —
        // real device report: picking Nano there switched the local model off.
        val chosenId = settings.translationModel.takeUnless { it == CloudProviders.AICORE.id }.orEmpty()
        val registry = localRegistry(ModelPurpose.TRANSLATION)
        val selected = (if (chosenId.isNotBlank()) effectiveLocalSelection(registry, chosenId) else null)
            ?: effectiveLocalSelection(registry)
            ?: return null
        return FallbackCandidate(
            label = "${context.getString(CloudProviders.LOCAL.titleRes)}: ${selected.model.id}",
            runtime = sharedLlamaRuntime(),
            model = selected.model,
            binding = selected.binding,
        )
    }

    private val translationCompareOrchestrators = mutableMapOf<String, Orchestrator>()
    private val translationCompareSignatures = mutableMapOf<String, String>()

    /**
     * [compareCandidates]'s own idea, for [TranslationActivity]: one
     * [CompareSource] per enabled provider, so a translation shows several
     * drafts side by side instead of committing to whichever single model
     * happens to be picked on the Models screen's Translation tab — real
     * device report: MADLAD-400's own output for a low-resource language
     * (Seychellois Creole) can be outright wrong with no way to tell short
     * of trying another source. [CloudProviders.AICORE] is always included
     * regardless of [Settings.enabledProviderIds]: Gemini Nano costs
     * nothing to try, answers in seconds, and there's no reason to leave it
     * out of a translation's own comparison just because chat happens to be
     * configured without it. The LOCAL entry resolves against
     * [Settings.translationModel] (see [translationLocalCandidate]), not
     * [Settings.chatModel] the way [compareCandidates]'s own LOCAL entry does.
     */
    fun translationCompareCandidates(): List<CompareSource> {
        // LOCAL forced in alongside AICORE, not just gated by
        // enabledProviderIds like the cloud providers below: real device
        // report — MADLAD-400 7B, explicitly picked on Models ->
        // Translation, never even attempted (no LOCAL_LOAD line at all,
        // success or failure) because the general "Local" chat-provider
        // checkbox in Settings happened to be unchecked. That checkbox
        // governs chat's own fallback chain; Settings.translationModel is a
        // separate, dedicated choice this screen has always respected
        // regardless of chat's own provider configuration (see
        // translationLocalCandidate's own doc comment) — conflating the two
        // here silently dropped the one source the user most explicitly
        // asked for.
        // AICore is skipped outright once it has said this device can't run
        // it — hideOnFailure would hide the card anyway, but not the
        // pointless status round-trip on every single translation.
        val providerIds = settings.enabledProviderIds + CloudProviders.LOCAL.id +
            (if (aicoreUnsupported) emptySet() else setOf(CloudProviders.AICORE.id))
        val effectiveProviderIds = if (aicoreUnsupported) providerIds - CloudProviders.AICORE.id else providerIds
        return CloudProviders.ALL.filter { it.id in effectiveProviderIds }.mapNotNull { provider ->
            val candidates = when (provider.id) {
                CloudProviders.LOCAL.id -> listOfNotNull(translationLocalCandidate())
                CloudProviders.AICORE.id -> listOf(aicoreCandidate())
                else -> cloudCandidates(provider)
            }
            if (candidates.isEmpty()) return@mapNotNull null

            val runtime: ModelRuntime = FallbackTextRuntime(candidates)
            val label = candidates.singleOrNull()?.label ?: context.getString(provider.titleRes)
            val isLocalOnly = candidates.all { it.binding.runtime == RuntimeKind.LLAMA_CPP }

            val signature = (
                listOf(
                    settings.customEndpoint,
                    settings.temperature,
                    settings.topP,
                    settings.topK,
                    settings.repeatPenalty,
                    settings.maxResponseTokens,
                    settings.chatModelFor(provider.id),
                    settings.apiKeyFor(provider.id),
                    settings.translationModel,
                ) + candidates.map { it.model.id }
            ).joinToString("|")

            val cached = translationCompareOrchestrators[provider.id]?.takeIf { translationCompareSignatures[provider.id] == signature }
            val orchestrator = cached ?: buildTranslationOrchestrator(runtime, isLocalOnly, candidates).also {
                translationCompareOrchestrators[provider.id] = it
                translationCompareSignatures[provider.id] = signature
            }
            CompareSource(label, orchestrator, isLocalOnly, hideOnFailure = provider.id == CloudProviders.AICORE.id)
        }
    }

    /**
     * The build behind every entry [translationCompareCandidates] returns —
     * one [Orchestrator] for translation given whichever
     * [runtime]/[registryCandidates] the caller already
     * resolved (a single local/AICore candidate, or a cloud provider's own
     * [FallbackTextRuntime] rotation). Never the user's chat persona/house
     * rules ([systemPrompt] null) — a translation prompt is already fully
     * self-contained (see [TranslationActivity.buildPrompt]) — and output is
     * capped far below a chat reply's own ceiling: a real device report
     * showed a general chat model ignore "reply with only the translation"
     * and ramble for 457 tokens before the wall-clock timeout cut it off, for
     * a task that never legitimately needs anywhere near
     * [LOCAL_MAX_OUTPUT_TOKENS]. Capped unconditionally, not just for a local
     * candidate — a cloud/AICore model rambling wastes the same wall-clock
     * time.
     */
    private fun buildTranslationOrchestrator(
        runtime: ModelRuntime,
        isLocalOnly: Boolean,
        registryCandidates: List<FallbackCandidate>,
    ): Orchestrator {
        if (registryCandidates.any { it.binding.runtime == RuntimeKind.LLAMA_CPP }) {
            // [releaseMemoryUnderPressure] otherwise only runs reactively,
            // off Android's own onTrimMemory callback — real device logs
            // showed that callback landing the same second a translation's
            // fresh model load started, with the embedding model (and
            // whatever chat model was still resident) not yet freed by the
            // time llama.cpp's own allocations ran, on a device already
            // down to ~1-2 GB free. A resident chat model itself is freed by
            // sharedRuntimeManager when this load starts, and only if it is
            // a different model — this is only the cross-subsystem piece.
            kotlinx.coroutines.runBlocking { releaseMemoryUnderPressure("translation model load", includeLocalModels = false) }
        }
        val runtimeManager = RuntimeManager(
            budgetBytes = { device.usableRamBytes },
            runtimes = mapOf(runtime.kind to runtime, whisperCppRuntime.kind to whisperCppRuntime),
        )
        val executors = NodeExecutors(
            selector = ModelSelector(registry(runtime, registryCandidates), selectionDevice()),
            runtimeManager = runtimeManager,
            contextEngine = ContextEngine(),
            memory = memory,
            memoryExperiment = memoryExperimentRunner,
            memoryExperimentMode = ExperimentMode.COMMERCIAL_MEMORY,
            systemPrompt = null,
            // See buildOrchestrator's own comment on why AICore is included
            // here too, not just LLAMA_CPP.
            contextWindowTokens = if (needsSmallContextWindow(registryCandidates)) effectiveContextTokens() else CLOUD_CONTEXT_WINDOW_TOKENS,
            defaultTemperature = settings.temperature,
            defaultTopP = settings.topP,
            defaultTopK = settings.topK,
            defaultRepeatPenalty = settings.repeatPenalty,
            defaultMaxTokens = minOf(settings.maxResponseTokens, TRANSLATION_MAX_OUTPUT_TOKENS),
        )
        return Orchestrator(CapabilityRouter(), PipelineEngine(executors.build()))
    }

    /**
     * Whether the local model that would actually be used right now can see
     * an attached image — never a static flag the way [CloudProvider.visionCapable]
     * is for a cloud provider: local vision depends on which specific model
     * is installed and whether its projector actually downloaded (see
     * ModelStore.hasMmproj), not on the llama.cpp runtime as a whole. Gating
     * "can I even attach an image" on [CloudProviders.LOCAL]'s own
     * (necessarily false, for exactly that reason) visionCapable flag meant
     * attaching an image was refused outright — "none of the enabled models
     * understands images" — for a model that, in fact, did.
     */
    fun localVisionAvailable(): Boolean {
        val seedId = effectiveLocalSelection(localRegistry())?.model?.id ?: return false
        val seed = LocalModels.SEEDS.firstOrNull { it.id == seedId } ?: return false
        return modelStore.hasMmproj(seed)
    }

    /**
     * A [LlamaCppRuntime] whose loads go through [sharedRuntimeManager]. The
     * context size is the residency variant: a copy loaded with a smaller
     * window is reloaded, not reused, once a document needs the bigger one.
     */
    private fun sharedLlamaRuntime(): ModelRuntime {
        val contextTokens = effectiveContextTokens()
        return DeviceMemoryGatedRuntime(
            inner = SharedRuntime(
                inner = RamMeasuringRuntime(
                    inner = LlamaCppRuntime(
                        contextTokens = contextTokens,
                        log = appLog::record,
                        availableRamBytes = { currentAvailableRamBytes(context) },
                        memoryDiagnostics = { currentMemoryDiagnostics(context) },
                    ),
                    contextTokens = contextTokens,
                    store = measuredRam,
                    log = appLog::record,
                ),
                manager = sharedRuntimeManager,
                variant = contextTokens,
            ),
            gate = deviceMemoryGate,
        )
    }

    /**
     * The device as [ModelSelector] sees it when choosing among candidates
     * the user already picked: RAM only rules out a model that could never
     * fit on this phone at all. Whether it fits *right now* is decided at
     * load time by [sharedRuntimeManager] — a live-RAM reading taken when an
     * orchestrator happens to be built (and then cached) refused Qwen 9B for
     * translation with 8.5 GB genuinely free.
     */
    private fun selectionDevice(): DeviceProfile = device.let {
        it.copy(availableRamBytes = it.totalRamBytes, ramBudgetFraction = DeviceProfile.MAX_RAM_FRACTION)
    }

    /**
     * The model the user explicitly picked via "Использовать" in Models,
     * if it's actually installed right now — [ModelSelector] otherwise.
     *
     * [ai.localstudio.app.ModelsActivity.switchToLocal] writes the chosen
     * seed's id to `settings.chatModelFor(LOCAL.id)`, but nothing ever read
     * it back: this function used to call [ModelSelector] unconditionally,
     * which ranks every installed local model by device suitability and
     * always returns whatever scores highest — completely ignoring which
     * one the user just switched to. Switching to a heavier model in
     * Models had no effect at all, which is exactly what was reported: the
     * status bar (and the actual route) kept using the old, lighter model
     * no matter what was selected, even across a restart, because nothing
     * about that choice was ever persisted anywhere ModelSelector looks.
     */
    private fun localCandidate(): FallbackCandidate? {
        val registry = localRegistry()
        val selected = effectiveLocalSelection(registry) ?: return null
        return FallbackCandidate(
            // Names the specific installed model, not just "Локально на
            // устройстве" — with several local models to choose from
            // (or a mix of a tiny and a huge one, tried at different times),
            // a generic label in the log and in the answer's own attribution
            // line answered "was it local?" but not "which local model?".
            label = "${context.getString(CloudProviders.LOCAL.titleRes)}: ${selected.model.id}",
            runtime = sharedLlamaRuntime(),
            model = selected.model,
            binding = selected.binding,
        )
    }

    /**
     * Gemini Nano via AICore, as a [FallbackCandidate] — unlike [localCandidate],
     * always contributed when the provider is enabled, since there is no file
     * to check "is it installed" against: readiness is only knowable by
     * actually asking AICore, which [ai.localstudio.app.aicore.AiCoreRuntime.load]
     * does. An unready device (not downloaded, or AICore absent entirely)
     * surfaces as this candidate failing fast and [FallbackTextRuntime] moving
     * on to whichever candidate comes next — same as any other candidate that
     * turns out not to be ready, not a special case.
     *
     * [RuntimeBinding.fileSizeBytes]/[RuntimeBinding.requiredRamBytes] are
     * both nominal ([servedModel]'s usual `1`) rather than measured, same as
     * every other [servedModel]-built candidate — deliberately not the
     * llama.cpp-sized estimate [localCandidate] uses: AICore's own weights
     * live in AICore's system service, not this app's process, so there is
     * nothing here for [RuntimeManager]'s RAM budget to actually plan for.
     * [DeviceMemoryGatedRuntime] is the actual mitigation for that gap —
     * see its own doc comment for the real crash it fixes.
     * See docs/04-runtime.md's "Gemini Nano / AICore feasibility" section.
     */
    private fun aicoreCandidate(): FallbackCandidate {
        val model = servedModel("gemini-nano-aicore", RuntimeKind.AICORE, Capability.TEXT_GENERATION, Capability.REASONING)
        return FallbackCandidate(
            label = context.getString(CloudProviders.AICORE.titleRes),
            runtime = DeviceMemoryGatedRuntime(
                inner = AiCoreRuntime(log = appLog::record, onStatus = ::recordAicoreStatus),
                gate = deviceMemoryGate,
                beforeGenerate = { makeRoomForAicore() },
            ),
            model = model,
            binding = model.bindings.first(),
        )
    }

    /**
     * Runs with [deviceMemoryGate] held, right before AICore generates — so
     * no local generation is in flight and anything [sharedRuntimeManager]
     * holds is idle. The gate alone serializes generation but not
     * residency: MADLAD-400 7B (~5.3 GB measured) stays resident after its
     * own translation, and AICore's own memory — in a system service this
     * app can't measure — would otherwise ramp up right next to it, the
     * same combination that OOM-killed the process when both ran at once.
     *
     * Evicts only when free RAM is below [AICORE_RAM_RESERVE_BYTES], not
     * unconditionally: in Compare-mode translation both run on every
     * request, and evicting every time would mean a full reload (~10 s load
     * + ~9 s encode for MADLAD) on each translation. The reserve is a
     * deliberately conservative safety margin, not an estimate of AICore's
     * footprint — every decision is logged (AICORE_RAM) so it can be tuned
     * from real device logs.
     */
    private suspend fun makeRoomForAicore() {
        val residentBytes = sharedRuntimeManager.residentBytes
        val freeBytes = currentFreeRamBytes(context)
        val mb = { bytes: Long -> bytes / (1024 * 1024) }
        // The three-way branch itself is ai.localstudio.core.resources.decideAicoreRoom
        // — a pure function, tested without a real RuntimeManager or free-RAM
        // reading — this is only the logging/eviction each outcome performs.
        when (ai.localstudio.core.resources.decideAicoreRoom(freeBytes, residentBytes, AICORE_RAM_RESERVE_BYTES)) {
            ai.localstudio.core.resources.AicoreRoomDecision.NO_LOCAL_MODEL ->
                appLog.record("AICORE_RAM", "free ${mb(freeBytes)} MB, no local model resident")
            ai.localstudio.core.resources.AicoreRoomDecision.SUFFICIENT_HEADROOM ->
                appLog.record(
                    "AICORE_RAM",
                    "free ${mb(freeBytes)} MB >= reserve ${mb(AICORE_RAM_RESERVE_BYTES)} MB — keeping local model(s) resident (${mb(residentBytes)} MB)",
                )
            ai.localstudio.core.resources.AicoreRoomDecision.EVICT_IDLE -> {
                appLog.record(
                    "AICORE_RAM",
                    "free ${mb(freeBytes)} MB < reserve ${mb(AICORE_RAM_RESERVE_BYTES)} MB — evicting idle local model(s) (${mb(residentBytes)} MB) first",
                )
                sharedRuntimeManager.evictIdle()
                appLog.record("AICORE_RAM", "after eviction: free ${mb(currentFreeRamBytes(context))} MB")
            }
        }
    }

    /**
     * The user's [Settings.contextTokens] is a ceiling for when it's actually
     * needed — an attached document or memory recall can require the full
     * window — not a size every plain chat should pay KV-cache RAM for. Most
     * conversations are a short prompt and a short answer, where 4096 (or
     * whatever the user raised it to for a big document once) holds roughly
     * twice the KV cache a plain back-and-forth ever uses. llama_jni.cpp
     * already truncates a prompt that doesn't fit rather than failing, so
     * undersizing here degrades gracefully instead of breaking anything.
     */
    private fun effectiveContextTokens(): Int {
        // Even with a document or memory recall in play, local still gets a
        // hard ceiling rather than the raw setting — [settings.contextTokens]
        // is one shared control also used to size CLOUD_CONTEXT_WINDOW_TOKENS
        // usage upstream, so a value the user raised for a big document
        // against Gemini used to also hand a phone's llama.cpp context that
        // same size verbatim. The app log's own OOM kills correlate with
        // exactly that: a local model loading with a large accumulated
        // context, on a device with no headroom for a KV cache anywhere near
        // that big. LOCAL_CONTEXT_TOKENS_CEILING is deliberately still well
        // above SMALL_CONTEXT_TOKENS — real room for an attached document or
        // recalled memory — just not the *raw*, cloud-sized ceiling.
        val ceiling = if (documents.list().isEmpty() && !settings.memoryEnabled) {
            SMALL_CONTEXT_TOKENS
        } else {
            LOCAL_CONTEXT_TOKENS_CEILING
        }
        return minOf(settings.contextTokens, ceiling)
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
     *
     * A model an earlier turn already saw a 503 from is left out of the list
     * for a while — [ModelCooldownStore] escalating from a few minutes up to
     * [ModelCooldownStore.DEFAULT_COOLDOWN_MS] the more times in a row it
     * keeps happening — without this, a model stuck overloaded gets retried
     * (and fails, slowly) on every single message, which is what turned "one
     * model is down" into "every reply takes 20+ seconds".
     */
    /** Shared so its token cache (keyed by authorization key) survives across turns — see its own doc comment. */
    private val gigaChatTokenProvider = GigaChatTokenProvider()

    private fun cloudCandidates(provider: CloudProvider): List<FallbackCandidate> {
        val endpoint = if (provider.editableUrl) settings.customEndpoint else provider.baseUrl
        if (endpoint.isBlank()) return emptyList()
        // Anthropic speaks a genuinely different wire protocol (its own
        // Messages API), not an OpenAI-compatible one — see AnthropicRuntime's
        // own doc comment for why it isn't just another OpenAiConfig, the
        // way GigaChat's differing auth is. Every other provider here still
        // goes through the shared OpenAiRuntime.
        val runtimeKind = if (provider.id == "anthropic") RuntimeKind.REMOTE_ANTHROPIC else RuntimeKind.REMOTE_OPENAI
        val runtime: ModelRuntime = if (provider.id == "anthropic") {
            ai.localstudio.openai.AnthropicRuntime(
                ai.localstudio.openai.AnthropicConfig(
                    baseUrl = endpoint,
                    apiKey = settings.apiKeyFor(provider.id).ifBlank { null },
                    keyRotator = apiKeyRotator(provider.id),
                ),
            )
        } else {
            OpenAiRuntime(
                OpenAiConfig(
                    baseUrl = endpoint,
                    apiKey = settings.apiKeyFor(provider.id).ifBlank { null },
                    keyRotator = apiKeyRotator(provider.id),
                    transformKey = if (provider.id == "gigachat") gigaChatTokenProvider::token else null,
                ),
            )
        }
        val primaryModel = settings.chatModelFor(provider.id)
        val modelNames = (listOf(primaryModel) + provider.freeModels.filterNot { it == primaryModel })
            .filterNot { modelCooldowns.isOnCooldown(provider.id, it) }
        // Set by the first sibling that hits HTTP 413 (request too large) —
        // every other model on this SAME provider almost certainly shares
        // the same context-length limit and will reject the identical
        // oversized prompt too, so there is nothing to learn by actually
        // trying each of them before falling through to a different
        // provider. Scoped to this one cloudCandidates() call (i.e. this one
        // turn's attempt at this one provider) rather than persisted:
        // unlike a 503, a 413 says nothing about whether this provider is
        // healthy — the very next message, with a shorter prompt, is
        // expected to work against the exact same models.
        val requestTooLargeForProvider = java.util.concurrent.atomic.AtomicBoolean(false)
        return modelNames.map { modelName ->
            val model = servedModel(modelName, runtimeKind, Capability.TEXT_GENERATION, Capability.REASONING)
            val providerTitle = context.getString(provider.titleRes)
            FallbackCandidate(
                // Always the specific model, even for the primary one: the
                // attribution footer this label ends up in (see
                // FallbackTextRuntime) is the only place the user can tell
                // *which* of a provider's free-tier models actually
                // answered, and "Groq" alone answers a different question
                // than "which Groq model" does.
                label = "$providerTitle ($modelName)",
                runtime = runtime,
                model = model,
                binding = model.bindings.first(),
                shouldSkip = { requestTooLargeForProvider.get() },
                // null (unverified) defaults to true, same as before this
                // field existed — see CloudProvider.visionModels' own doc
                // comment for which providers have an actual confirmed list.
                supportsImages = provider.visionModels?.contains(modelName) ?: true,
                // AIErrorClassifier/FallbackPolicy, not a raw `error is
                // OpenAiException && error.status == …` check: the same
                // decision (cool this model down vs. skip this provider's
                // other models for the turn) then applies to any provider's
                // failure — GigaChat's own OAuth exception included — not
                // just an OpenAI-compatible endpoint's, see docs/17.
                onFailure = { error ->
                    val classified = ai.localstudio.core.errors.AIErrorClassifier.classify(error)
                    when (ai.localstudio.core.errors.FallbackPolicy.actionFor(classified.code)) {
                        ai.localstudio.core.errors.FallbackAction.COOLDOWN_MODEL -> {
                            modelCooldowns.markOverloaded(provider.id, modelName)
                            // Otherwise the next message reuses the cached
                            // orchestrator — built before this model went on
                            // cooldown — and hits the very same overloaded
                            // model it was just supposed to stop trying.
                            cachedOrchestrator = null
                            val cooldownMs = modelCooldowns.currentCooldownMs(provider.id, modelName)
                            appLog.record(
                                "MODEL_COOLDOWN",
                                "$providerTitle ($modelName): HTTP ${classified.providerStatus}, skipping for ${cooldownMs / 60_000} min",
                            )
                        }
                        // Distinct from the 429/daily-limit path entirely —
                        // this is not a quota problem key rotation or a
                        // cooldown can route around, it means THIS request
                        // (with this conversation's current prompt size, or
                        // shape) is rejected outright, and every sibling
                        // model on the same provider will reject the
                        // identical request too.
                        ai.localstudio.core.errors.FallbackAction.SKIP_PROVIDER_THIS_TURN -> {
                            requestTooLargeForProvider.set(true)
                            appLog.record(
                                "GENERATION_ERROR",
                                "$providerTitle ($modelName): HTTP ${classified.providerStatus}, request rejected — " +
                                    "skipping the rest of this provider's models for this turn",
                            )
                        }
                        ai.localstudio.core.errors.FallbackAction.NONE -> {}
                    }
                },
            )
        }
    }

    private fun registry(runtime: ModelRuntime, candidates: List<FallbackCandidate>): ModelRegistry {
        val entries = mutableListOf<RegistryEntry>()
        when {
            candidates.isEmpty() -> entries += RegistryEntry(
                servedModel(settings.chatModel, RuntimeKind.STUB, Capability.TEXT_GENERATION, Capability.REASONING),
                InstallState.INSTALLED,
            )

            // Branches on the RUNTIME actually being handed to RuntimeManager
            // below, not on candidates.size — those used to always agree
            // (a lone candidate's own runtime, a chain's own FALLBACK_CHAIN
            // kind), but compareCandidates() now wraps even a single
            // candidate in FallbackTextRuntime unconditionally (for the
            // attribution+latency footer every Compare-mode source gets).
            // Registering `only.model`'s ORIGINAL binding (llama.cpp, say)
            // while RuntimeManager only has FALLBACK_CHAIN registered — the
            // old candidates.size == 1 branch's mistake — sent ModelSelector
            // to pick a runtime kind nothing in this orchestrator's own
            // RuntimeManager knew how to serve: "No runtime registered for
            // llama_cpp" on every single Compare-mode local turn.
            runtime.kind == RuntimeKind.FALLBACK_CHAIN ->
                entries += RegistryEntry(fallbackChainDescriptor(candidates), InstallState.INSTALLED)

            else -> {
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
        }
        // Independent of the branch above (which is about text generation):
        // whenever a whisper.cpp model is actually installed on disk, it's
        // registered too, so SPEECH_TO_TEXT resolves to it via WhisperCppRuntime
        // — see sharedRuntimeManager/buildOrchestrator for why that runtime
        // is always in RuntimeManager's map regardless of which one this
        // orchestrator's own `runtime` argument is. Still not reachable from
        // any screen in this app yet (see whisperEngine's own comment above,
        // used directly by ChatActivity's mic button instead) — this is what
        // lets a saved pipeline, or a future file-transcription screen, find
        // and use it. See docs/13-asr-pipeline-migration.md.
        whisperStore.installedSeed()?.let { seed ->
            val file = whisperStore.modelFile(seed)
            entries += RegistryEntry(
                ModelDescriptor(
                    id = seed.id,
                    family = "whisper",
                    version = "1",
                    parameterCount = 1,
                    capabilities = setOf(Capability.SPEECH_TO_TEXT),
                    bindings = listOf(
                        RuntimeBinding(
                            runtime = RuntimeKind.WHISPER_CPP,
                            artifact = file.absolutePath,
                            fileSizeBytes = file.length().coerceAtLeast(1),
                        ),
                    ),
                ),
                InstallState.INSTALLED,
                installedPath = file.absolutePath,
            )
        }
        return ModelRegistry(entries)
    }

    /**
     * Downloaded models, described from what is actually on disk: the binding's
     * artifact is the file path, and its size is the file's real size, so the
     * scorer and the runtime agree about what exists.
     */
    private fun localRegistry(purpose: ModelPurpose = ModelPurpose.CHAT): ModelRegistry = ModelRegistry(
        installedSeeds(purpose).map { seed ->
            // Best-effort backfill for a model that was already installed
            // before it declared a projector, or whose projector fetch
            // failed the first time — see ModelDownloads.start()'s own
            // comment on why this can't wait for the user to notice and
            // re-download the whole model. Cheap to call on every registry
            // build: start() no-ops while a fetch for this seed is already
            // running, and stops matching this condition entirely once
            // hasMmproj(seed) actually becomes true.
            if (seed.mmprojFileName != null && !modelStore.hasMmproj(seed)) {
                downloads.start(seed)
            }
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
                            // Left null here: a real measurement (MeasuredRamStore)
                            // is applied live at admission time through
                            // sharedRuntimeManager's requiredBytesFor, so a cached
                            // orchestrator's binding never goes stale.
                            requiredRamBytes = null,
                            mmprojArtifact = modelStore.mmprojFileFor(seed).absolutePath
                                .takeIf { modelStore.hasMmproj(seed) },
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

    /**
     * Real device report (a fresh Samsung install, LOCAL enabled but no GGUF
     * downloaded yet): this used to list every *enabled* provider regardless
     * of whether it actually contributed anything to try, so the very same
     * turn's own `ROUTER_REBUILD` log line showed `candidates=[Gemini Nano
     * (AICore), ...]` — no local entry at all, since [localCandidate] returns
     * null with nothing installed — right above a `SEND` line still claiming
     * `route=Локально на устройстве (llama.cpp) → Gemini Nano (AICore) →
     * ...`, as if llama.cpp genuinely led the route it was never even
     * attempted in. [CloudProviders.LOCAL] is the one entry in
     * [enabledProviders] that can be "on" with nothing to show for it this
     * way (a fresh install, or the installed model deleted) — every other
     * provider here either has a fixed endpoint or (AICore) is already
     * filtered out there when unsupported, so
     * only LOCAL needs this check.
     */
    val runtimeLabel: String
        get() = enabledProviders()
            .filter { it.id != CloudProviders.LOCAL.id || localCandidate() != null }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" → ") { context.getString(it.titleRes) }
            ?: context.getString(CloudProviders.DEMO.titleRes)

    /**
     * Whether the route [orchestrator] last built is local-only — every
     * candidate in it runs on-device, with no cloud fallback configured.
     * [ChatActivity] uses this to decide whether a turn's answer should
     * stream in as it's produced: worth it for a local model that can take
     * minutes, but a cloud candidate answers in seconds, so live partial
     * renders there add redraw churn without buying anything. Reads
     * [lastCandidates] rather than rebuilding — call [orchestrator] first so
     * this reflects the route about to actually run.
     */
    val isLocalOnlyRoute: Boolean
        get() = lastCandidates.isNotEmpty() && lastCandidates.all { it.binding.runtime == RuntimeKind.LLAMA_CPP }

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

    // Must stay the last initializer in this class — see constructionComplete.
    init {
        constructionComplete.complete(Unit)
        migrateCustomModels()
        refreshAicoreStatus()
        watchAicoreOnResume()
    }

    companion object {
        /** See [watchAicoreOnResume]. An IPC to AICore, cheap, but not worth doing on every screen switch. */
        private const val AICORE_RECHECK_INTERVAL_MS = 15_000L

        private const val CATALOG_ASSET = "catalog.example.json"

        // Not a real ceiling, just "large enough that this app's own context
        // engine is never the reason a cloud model didn't get enough
        // conversation/memory" — actual providers support far more than
        // this, and the request itself grows or shrinks with what's
        // actually assembled, not with this number.
        private const val CLOUD_CONTEXT_WINDOW_TOKENS = 32_000

        private const val LIVE_FREE_RAM_SAFETY_FACTOR = 0.95

        // Ceiling for a local context when nothing in this conversation
        // needs the user's full configured window — see effectiveContextTokens().
        private const val SMALL_CONTEXT_TOKENS = 2048

        /** Install manifests of discovery candidates name this catalog; their catalog version is the commit. */
        private const val CANDIDATE_CATALOG_ID = "discovery"

        /** Per probe; the first includes the load, which has taken minutes for a large model on this class of device. */
        private const val CANDIDATE_PROBE_TIMEOUT_MS = 10 * 60_000L

        /** Enough for a reasoning model's short thinking block before its one-word answer. */
        private const val CANDIDATE_MAX_TOKENS = 256

        // Ceiling for a local context even when a document or memory recall
        // IS in play — see effectiveContextTokens(). Room for real context,
        // just not the raw, cloud-sized settings.contextTokens value verbatim.
        private const val LOCAL_CONTEXT_TOKENS_CEILING = 4096

        // A local model's own output length, capped independently of
        // settings.maxResponseTokens — see its call site in buildOrchestrator().
        private const val LOCAL_MAX_OUTPUT_TOKENS = 512

        // A translated phrase's own output length — see its call site in
        // buildTranslationOrchestrator(). Deliberately far below LOCAL_MAX_OUTPUT_TOKENS:
        // that ceiling is sized for a chat reply, not a single translation.
        private const val TRANSLATION_MAX_OUTPUT_TOKENS = 200

        // Not a real ceiling, just "large enough that a single conversation's
        // worth of memory items is never left behind" — see
        // forgetConversationMemory().
        private const val CONVERSATION_MEMORY_FORGET_LIMIT = 10_000

        // Hybrid-sweep-tuned, not a guess and not the earlier 0.20/0.30
        // placeholders. Source: benchmark/run_hybrid_sweep.py in Mobile_mem0
        // (multilingual-e5-base Q4_K_M, mean pooling, query:/passage:
        // prefixes), same 92-positive/10-negative dataset as before.
        //
        // Part A re-confirmed lexical vs semantic (base) is unchanged from
        // the original benchmark:
        //   Overall  Recall@1  Recall@5  Recall@10  MRR
        //   lexical     0.500     0.598      0.620   0.543
        //   semantic    0.848     0.946      0.946   0.892
        //
        // Part B swept this exact hybrid formula (lexical*0.45 + semantic*w,
        // matching HeuristicContextRanker's own lexical weight) across
        // w = 0.00..0.50, with a deterministic bootstrap (200 resamples) per
        // weight for stability:
        //   weight  Recall@1  Recall@5  Recall@10    MRR   MRR std
        //     0.00     0.500     0.620      0.674    0.565   0.047
        //     0.10     0.663     0.913      0.924    0.775   0.035
        //     0.20     0.663     0.924      0.924    0.781   0.034
        //     0.25     0.663     0.924      0.924    0.781   0.034
        //     0.30     0.663     0.924      0.924    0.781   0.034
        //     0.35-0.50: identical to 0.30 — the ranking itself stops
        //       changing past this point (a linear weighted sum saturates
        //       once one term dominates every comparison that will ever
        //       flip), not a measurement error.
        // 0.00->0.10 is the entire real gain (MRR +0.21, far outside any
        // weight's own bootstrap noise); every larger weight's additional
        // gain over 0.10 (+0.006 MRR at most) is smaller than that weight's
        // own bootstrap std (0.034-0.047) — not distinguishable from noise.
        // No category regresses at 0.10 vs lexical-only, identifier included
        // (both 1.000): the smallest weight that captures the real gain and
        // the best-performing weight are the same value, so there is no
        // trade-off to make. This supersedes the earlier 0.30 (itself set
        // from Part A alone, before this hybrid formula existed to measure
        // against) and the original 0.20 placeholder.
        // Still open, unchanged from before: an A/B run against this app's
        // own real ExperimentLogger data (SEMANTIC_RETRIEVAL_DESIGN.md's
        // step 9) — this hybrid sweep is a synthetic-dataset measurement,
        // the best one available without that.
        private const val SEMANTIC_RANKING_WEIGHT = 0.10

        // How many memory records embedPending() backfills per pass — see the
        // semantic-memory background task in init{}. One pass at this size is
        // one native call per missing item; kept well below
        // CONVERSATION_MEMORY_FORGET_LIMIT so a large backlog spreads across
        // several passes instead of blocking the first one for a long time.
        private const val SEMANTIC_BACKFILL_BATCH = 64

        // How often that background task rechecks for newly-consolidated
        // memories once the embedder is ready — frequent enough that a fact
        // saved this session is searchable semantically well within the same
        // session, infrequent enough that it costs nothing noticeable while
        // (the common case) there is nothing new to embed.
        private const val SEMANTIC_BACKFILL_INTERVAL_MS = 5 * 60 * 1000L

        // How long a memory-pressure reload waits for a running generation
        // (or anything else embedderBlockedBy names) before trying anyway --
        // ensureEmbedderLoaded then defers once more and the backfill loop
        // takes over, as before.
        private const val RELOAD_WAIT_POLL_MS = 2_000L

        // Upper bound on what freeing the embedder and whisper engines can
        // give back: E5 Base (~0.3 GB) plus whisper large-v3 (~1.1 GB) with
        // headroom. Above available + this, a local load is refused anyway.
        private const val AUXILIARY_MODELS_MAX_BYTES = 2L * 1024 * 1024 * 1024
        private const val MB = 1024L * 1024
        private const val RELOAD_WAIT_MAX_MS = 10 * 60 * 1000L

        // Real device report: reloading E5 below this much free RAM
        // measurably slowed down or broke whatever else happened to be
        // loading a model at the same time (chat, benchmark, file/mic
        // transcription) — see the background task's own doc comment.
        // 2GB+ free ran clean in every observed case; everything under
        // that showed real symptoms.
        private const val SEMANTIC_BACKFILL_MIN_FREE_RAM_BYTES = 2_000L * 1024 * 1024

        /**
         * Free RAM below which an idle local model is evicted before AICore
         * generates — see [makeRoomForAicore]. A conservative safety margin,
         * not a measurement: AICore runs in a system service whose memory
         * this app has no way to read. Meant to be retuned from the
         * AICORE_RAM log lines, not treated as a known quantity.
         */
        private const val AICORE_RAM_RESERVE_BYTES = 4_096L * 1024 * 1024

        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }

        /**
         * A fresh `availMem` read, deliberately not routed through [DeviceProfile]
         * — that class exists to plan a model's admission ahead of loading it,
         * off *total* RAM, on purpose (see its own doc comment on why free memory
         * alone would punish exactly the devices that can run the most). This is
         * a different question, asked at a different moment: how much is
         * genuinely free right *now*, for a soft, best-effort decision (skip
         * loading a vision projector — see [LlamaCppRuntime]) rather than a hard
         * admission gate.
         */
        fun currentAvailableRamBytes(context: Context): Long {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            return info.availMem
        }

        /**
         * Genuinely free RAM right now: the higher of ActivityManager's
         * reading and the kernel's MemAvailable — the same "free" [profileOf]
         * uses, minus its credit for this app's own resident models.
         */
        fun currentFreeRamBytes(context: Context): Long =
            maxOf(currentAvailableRamBytes(context), readMemAvailableBytes() ?: 0L)

        /**
         * Everything else [ActivityManager.MemoryInfo] carries beyond
         * [currentAvailableRamBytes]'s single number — real device report:
         * Settings' own Running services screen showed far more free RAM
         * than [currentAvailableRamBytes] did on the same device moments
         * later, and the only way to tell "this API is genuinely less
         * precise for a non-privileged app on this OS version" apart from
         * "the two screens define 'available' differently" is [lowMemory]:
         * if Android itself doesn't consider the device low on memory right
         * now despite a low [ActivityManager.MemoryInfo.availMem] reading,
         * that reading is the one not to be trusted.
         */
        fun currentMemoryDiagnostics(context: Context): String {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            return "ActivityManager: availMem=${info.availMem / 1_000_000}MB totalMem=${info.totalMem / 1_000_000}MB " +
                "threshold=${info.threshold / 1_000_000}MB lowMemory=${info.lowMemory}"
        }

        /**
         * [ownResidentBytes] is what this app's own local models hold right
         * now — counted as available, since loading a different model evicts
         * them first. Free RAM is the higher of ActivityManager's reading and
         * the kernel's MemAvailable (see [readMemAvailableBytes]), and with
         * that more precise reading most of it is trusted
         * ([LIVE_FREE_RAM_SAFETY_FACTOR]) — real device report: Qwen 9B
         * (~7.4 GB estimated) refused and warned about with 8.1-8.5 GB free,
         * because only 60% of free RAM ever counted.
         */
        fun profileOf(
            context: Context,
            ramBudgetFraction: Double = DeviceProfile.BASE_RAM_FRACTION,
            ownResidentBytes: Long = 0L,
        ): DeviceProfile {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            val free = maxOf(info.availMem, readMemAvailableBytes() ?: 0L)
            return DeviceProfile(
                totalRamBytes = info.totalMem,
                availableRamBytes = (free + ownResidentBytes).coerceIn(0L, info.totalMem),
                availableStorageBytes = context.filesDir.freeSpace,
                cpuCores = Runtime.getRuntime().availableProcessors(),
                androidApiLevel = Build.VERSION.SDK_INT,
                // Only what this build can actually execute on this device:
                // llama.cpp appears once its native library loads for this ABI.
                //
                // AICORE belongs here for the same reason REMOTE_OPENAI does
                // — real availability is only knowable by actually asking it
                // (see AiCoreRuntime), not something DeviceProfile can gate
                // on ahead of time, same as a cloud endpoint's reachability
                // isn't. Missing until a real device report: AICore-only
                // chat happened to always work anyway because every tested
                // device had at least one other provider enabled alongside
                // it, so AICore's candidate was always wrapped in a
                // FALLBACK_CHAIN binding (which IS in this set) rather than
                // standing alone — the single-candidate translation
                // orchestrator that used to exist deliberately never wrapped
                // a candidate that way, which is what first exposed this:
                // picking Gemini Nano for translation made SuitabilityScorer reject
                // its own candidate as NO_SUPPORTED_RUNTIME before
                // AiCoreRuntime ever got a chance to say whether it was
                // actually available.
                supportedRuntimes = buildSet {
                    add(RuntimeKind.REMOTE_OPENAI)
                    add(RuntimeKind.AICORE)
                    add(RuntimeKind.STUB)
                    add(RuntimeKind.FALLBACK_CHAIN)
                    if (LlamaBridge.isAvailable) add(RuntimeKind.LLAMA_CPP)
                    if (WhisperBridge.isAvailable) add(RuntimeKind.WHISPER_CPP)
                },
                hasGpuDelegate = false,
                performanceIndex = 1.0,
                ramBudgetFraction = ramBudgetFraction,
                freeRamSafetyFactor = LIVE_FREE_RAM_SAFETY_FACTOR,
            )
        }
    }
}
