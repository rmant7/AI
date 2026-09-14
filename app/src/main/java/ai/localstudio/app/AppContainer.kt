package ai.localstudio.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import ai.localstudio.commercialmemory.AppMemory
import ai.localstudio.commercialmemory.CommercialContextSelector
import ai.localstudio.commercialmemory.ExperimentLogger
import ai.localstudio.commercialmemory.ExperimentMode
import ai.localstudio.commercialmemory.ExperimentRecord
import ai.localstudio.commercialmemory.JsonlExperimentLogger
import ai.localstudio.commercialmemory.MemoryExperimentRunner
import ai.localstudio.commercialmemory.RankingWeights
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
import ai.localstudio.core.runtime.FallbackCandidate
import ai.localstudio.core.runtime.FallbackTextRuntime
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.RuntimeManager
import ai.localstudio.app.attach.AttachedDocument
import ai.localstudio.app.attach.DocumentStore
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
import ai.localstudio.app.models.CatalogFreshness
import ai.localstudio.app.models.LocalModelSeed
import ai.localstudio.app.models.LocalModels
import ai.localstudio.app.models.ModelDownloadService
import ai.localstudio.app.models.ModelDownloads
import ai.localstudio.app.models.ModelStore
import ai.localstudio.app.routing.ModelCooldownStore
import ai.localstudio.app.whisper.WhisperDownloads
import ai.localstudio.app.whisper.WhisperEngine
import ai.localstudio.app.whisper.WhisperModels
import ai.localstudio.app.whisper.WhisperStore
import ai.localstudio.openai.GigaChatTokenProvider
import ai.localstudio.openai.OpenAiConfig
import ai.localstudio.openai.OpenAiException
import ai.localstudio.openai.OpenAiRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

    /** Errors the app has hit, readable and copyable from Settings → "Журнал ошибок". Declared here, ahead of its usual place below, so memory/memoryExperimentLogger (right after) can already reference it. */
    val appLog = AppLog(context)

    /**
     * Fronts [memory]'s semantic half. Constructing this is cheap and
     * synchronous (no native call) — the actual GGUF load that fills it in
     * happens off the main thread, in [init]'s own background task, which
     * downloads [ExperimentalEmbeddingModels.E5_BASE] automatically the
     * first time memory is enabled (see that task's own doc comment).
     * Every call made to this before that finishes degrades to the same
     * lexical-only behavior [memory] already had with no embedder
     * configured at all — see [LazyMemoryEmbedder]'s own doc comment.
     */
    private val semanticMemoryEmbedder = LazyMemoryEmbedder(isEnabled = { settings.semanticMemoryEnabled })

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
     * [semanticMemoryEmbedder] not being ready yet, or the embedder having
     * no vector for a given item, both leave
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
     * Backs [ExperimentalEmbeddingsActivity] — a phone-only (no adb) way to
     * download and sanity-check a candidate [ai.localstudio.memory.MemoryEmbedder]
     * model. Entirely separate from [downloads]/[modelStore]: nothing here
     * ever feeds [LocalModels] or the chat-model registry, see
     * ExperimentalEmbeddingModels' own doc comment for why that stays true
     * until a candidate is actually verified.
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
    val experimentalEmbeddingStore = ExperimentalEmbeddingStore(context)

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
            if (!settings.memoryEnabled) return@launch
            val spec = ExperimentalEmbeddingModels.E5_BASE
            // Reading isAvailable, not just checking it: this is what
            // actually triggers its lazy System.loadLibrary() call. Skipping
            // straight to LlamaCppMemoryEmbedder.load() below without ever
            // reading this property would call an external fun before the
            // native library is loaded at all, on every device — not only
            // ones this build genuinely doesn't support.
            if (!LlamaBridge.isAvailable) return@launch

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
            // memories for as long as the app runs, and embedPending() is a
            // cheap no-op whenever nothing is actually missing a vector (it
            // starts by checking semanticMemoryIndex.missing(...) before ever
            // calling the embedder) — this just keeps semantic coverage from
            // permanently falling behind, without needing every write path
            // in the app to remember to call it itself. ensureEmbedderLoaded
            // here too, not just once above: onTrimMemory (see init{} below)
            // can unload the model between iterations under real memory
            // pressure, and this is what reloads it once pressure passes,
            // from the same on-disk file, no re-download.
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
                if (settings.semanticMemoryEnabled) {
                    ensureEmbedderLoaded(spec)
                    runCatching { memory.embedPending(SEMANTIC_BACKFILL_BATCH) }
                        .onFailure { appLog.record("SEMANTIC_MEMORY", "embedPending failed: ${it.message}") }
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
        // and background; TRIM_MEMORY_RUNNING_MODERATE is deliberately
        // excluded — it fires routinely and unloading on every one of those
        // would make semantic search reload far more often than the RAM it
        // actually saves is worth. onLowMemory() is the older, still-called-
        // on-every-API-level fallback for the same "this is serious" signal.
        // Reloading afterward is the background task above's own job, the
        // next time it wakes up — this callback only ever frees, never loads.
        context.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
                CoroutineScope(Dispatchers.IO).launch {
                    if (semanticMemoryEmbedder.isReady) {
                        semanticMemoryEmbedder.unload()
                        appLog.record("SEMANTIC_MEMORY", "unloaded under memory pressure (trim level $level); will reload once pressure passes")
                    }
                }
            }

            override fun onLowMemory() {
                CoroutineScope(Dispatchers.IO).launch {
                    if (semanticMemoryEmbedder.isReady) {
                        semanticMemoryEmbedder.unload()
                        appLog.record("SEMANTIC_MEMORY", "unloaded under memory pressure (onLowMemory); will reload once pressure passes")
                    }
                }
            }

            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
        })
    }

    /**
     * Loads [spec] and hands it to [semanticMemoryEmbedder] if it isn't
     * already resident — shared by the initial load above and the periodic
     * reload-after-[LazyMemoryEmbedder.unload] check, so both go through
     * the exact same native-load and error-logging path. Returns whether
     * the embedder is ready by the time this returns (already-ready counts).
     */
    private suspend fun ensureEmbedderLoaded(spec: EmbeddingModelSpec): Boolean {
        if (semanticMemoryEmbedder.isReady) return true
        if (!experimentalEmbeddingStore.isInstalled(spec)) return false

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
        } ?: return false

        semanticMemoryEmbedder.set(embedder) { embedder.close() }
        appLog.record(
            "SEMANTIC_MEMORY",
            "${spec.title} ready (dimension=${embedder.dimension}) — backfilling existing memory",
        )
        return true
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
    val device: DeviceProfile get() = profileOf(context, settings.ramBudgetFraction)

    val modelStore = ModelStore(context)

    val downloads = ModelDownloads(
        modelStore,
        tokenProvider = { settings.huggingFaceToken.ifBlank { null } },
        onDownloadStarted = { ModelDownloadService.ensureStarted(context) },
        appLogForMmproj = { message -> appLog.record("MMPROJ_DOWNLOAD", message) },
    )

    // No auto-download of Tiny on first launch: voice input's mic button and
    // Voice model category are both hidden (see activity_chat.xml and
    // activity_models.xml) because whisper.cpp transcription still isn't
    // reliable enough on-device — CPU contention with a local LLM can push a
    // single transcription past a minute and derail the silence-based
    // auto-stop entirely. Nothing reachable from the UI calls transcribe()
    // while the mic is hidden, so fetching a model in the background would
    // just be wasted disk space until this comes back properly.
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

    /** Seeds that are on disk right now, newest state each time it is asked. */
    fun installedSeeds(): List<LocalModelSeed> = LocalModels.SEEDS.filter { modelStore.isInstalled(it) }

    val experimentalEmbeddingDownloads = ExperimentalEmbeddingDownloads(
        experimentalEmbeddingStore,
        tokenProvider = { settings.huggingFaceToken.ifBlank { null } },
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
            if (provider.id == CloudProviders.LOCAL.id) listOfNotNull(localCandidate()) else cloudCandidates(provider)
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
     * Every [RuntimeManager] this container has ever built, so
     * [releaseLocalModels] has something to actually reach — buildOrchestrator
     * otherwise hands its manager straight to [NodeExecutors] with no
     * reference kept anywhere else. Never pruned: an old, already-empty
     * manager left in this list costs nothing (no native resources, just a
     * small object), and a manager whose cached [Orchestrator] slot was
     * replaced after a signature change — see compareCandidates' own doc
     * comment on that gap — still gets evicted through here instead of
     * staying orphaned forever.
     */
    private val runtimeManagers = mutableListOf<RuntimeManager>()

    /**
     * Frees every locally-loaded model (the LLM, its vision projector) —
     * called right before starting a voice recording, so Whisper is not
     * competing with an already-resident multi-GB local model for the same
     * RAM budget the same way that local model was competing with Whisper
     * before WhisperEngine started releasing itself after each use. Uses
     * [RuntimeManager.evictIdle], not the blunter unloadAll: a model still
     * actively mid-generation (refCount > 0) is left alone rather than
     * force-freed out from under whatever is using it.
     */
    suspend fun releaseLocalModels() {
        runtimeManagers.forEach { it.evictIdle() }
    }

    private fun buildOrchestrator(
        runtime: ModelRuntime,
        isLocalOnly: Boolean,
        registryCandidates: List<FallbackCandidate>,
    ): Orchestrator {
        // Every previously-built manager's idle models, freed before this one
        // even exists — not just eventually, via releaseLocalModels(). That
        // was written on the assumption something would call it soon after a
        // manager got superseded (a settings change, a 503 cooldown
        // invalidating the cached orchestrator, a Compare-mode signature
        // change); in practice its only caller is the mic button, which this
        // build hides — so nothing ever ran it, and a superseded manager's
        // already-loaded local model just sat resident, uncounted, for the
        // rest of the process's life. The next buildOrchestrator() call then
        // loaded a second, fully separate copy of the same GGUF right
        // alongside it: this is what an OOM kill shortly after a router
        // rebuild looked like in the app log. runBlocking is deliberate, not
        // a shortcut: this runs on the same thread about to build a new
        // RuntimeManager regardless, evictIdle() only touches refCount==0
        // entries (nothing this could contend with is still generating), and
        // freeing an idle llama.cpp context is a bounded, fast native call —
        // unlike loading one.
        runtimeManagers.forEach { existing -> kotlinx.coroutines.runBlocking { existing.evictIdle() } }
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
        runtimeManagers += manager
        val executors = NodeExecutors(
            selector = ModelSelector(registry(runtime, registryCandidates), device),
            runtimeManager = manager,
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
            contextWindowTokens = if (isLocalOnly) effectiveContextTokens() else CLOUD_CONTEXT_WINDOW_TOKENS,
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
        return Orchestrator(CapabilityRouter(), executors)
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
     */
    data class CompareSource(val label: String, val orchestrator: Orchestrator, val isLocal: Boolean)

    fun compareCandidates(): List<CompareSource> =
        enabledProviders().mapNotNull { provider ->
            val candidates = if (provider.id == CloudProviders.LOCAL.id) {
                listOfNotNull(localCandidate())
            } else {
                cloudCandidates(provider)
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
            CompareSource(label, orchestrator, isLocalOnly)
        }

    /** Providers actually enabled for use, in fallback order — see [Settings.enabledProviderIds]. */
    private fun enabledProviders(): List<CloudProvider> {
        val ids = settings.enabledProviderIds
        return CloudProviders.ALL.filter { it.id in ids }
    }

    /**
     * Same resolution [localCandidate] uses — the explicit choice from
     * Models if it's actually installed, [ModelSelector]'s best fit
     * otherwise — factored out so [localVisionAvailable] can ask "which
     * model, specifically" without also building a runtime and a
     * [FallbackCandidate] just to answer that.
     */
    private fun effectiveLocalSelection(registry: ModelRegistry): SelectedModel? {
        val chosenId = settings.chatModelFor(CloudProviders.LOCAL.id)
        val chosen = registry.find(chosenId)
            ?.takeIf { it.state == InstallState.INSTALLED }
            ?.let { entry -> SelectedModel(entry.model, entry.model.bindings.first()) }
        return chosen ?: ModelSelector(registry, device).selectOrNull(Capability.TEXT_GENERATION)
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
            runtime = LlamaCppRuntime(
                contextTokens = effectiveContextTokens(),
                log = appLog::record,
                availableRamBytes = { currentAvailableRamBytes(context) },
            ),
            model = selected.model,
            binding = selected.binding,
        )
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
        val runtime = OpenAiRuntime(
            OpenAiConfig(
                baseUrl = endpoint,
                apiKey = settings.apiKeyFor(provider.id).ifBlank { null },
                keyRotator = apiKeyRotator(provider.id),
                transformKey = if (provider.id == "gigachat") gigaChatTokenProvider::token else null,
            ),
        )
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
            val model = servedModel(modelName, RuntimeKind.REMOTE_OPENAI, Capability.TEXT_GENERATION, Capability.REASONING)
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
                onFailure = { error ->
                    when {
                        error is OpenAiException && error.status == 503 -> {
                            modelCooldowns.markOverloaded(provider.id, modelName)
                            // Otherwise the next message reuses the cached
                            // orchestrator — built before this model went on
                            // cooldown — and hits the very same overloaded
                            // model it was just supposed to stop trying.
                            cachedOrchestrator = null
                            val cooldownMs = modelCooldowns.currentCooldownMs(provider.id, modelName)
                            appLog.record(
                                "MODEL_COOLDOWN",
                                "$providerTitle ($modelName): HTTP 503, skipping for ${cooldownMs / 60_000} min",
                            )
                        }
                        // Distinct from the 429/daily-limit path entirely —
                        // this is not a quota problem key rotation or a
                        // cooldown can route around, it means THIS request
                        // (with this conversation's current prompt size) is
                        // too big for this provider's free tier, full stop.
                        error is OpenAiException && error.status == 413 -> {
                            requestTooLargeForProvider.set(true)
                            appLog.record(
                                "GENERATION_ERROR",
                                "$providerTitle ($modelName): HTTP 413, request too large — " +
                                    "skipping the rest of this provider's models for this turn",
                            )
                        }
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
        return ModelRegistry(entries)
    }

    /**
     * Downloaded models, described from what is actually on disk: the binding's
     * artifact is the file path, and its size is the file's real size, so the
     * scorer and the runtime agree about what exists.
     */
    private fun localRegistry(): ModelRegistry = ModelRegistry(
        installedSeeds().map { seed ->
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
                            // Left unmeasured on purpose: effectiveRequiredRamBytes
                            // then errs high, which is the safe direction here.
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

    val runtimeLabel: String
        get() = enabledProviders().takeIf { it.isNotEmpty() }
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

    companion object {
        private const val CATALOG_ASSET = "catalog.example.json"

        // Not a real ceiling, just "large enough that this app's own context
        // engine is never the reason a cloud model didn't get enough
        // conversation/memory" — actual providers support far more than
        // this, and the request itself grows or shrinks with what's
        // actually assembled, not with this number.
        private const val CLOUD_CONTEXT_WINDOW_TOKENS = 32_000

        // Ceiling for a local context when nothing in this conversation
        // needs the user's full configured window — see effectiveContextTokens().
        private const val SMALL_CONTEXT_TOKENS = 2048

        // Ceiling for a local context even when a document or memory recall
        // IS in play — see effectiveContextTokens(). Room for real context,
        // just not the raw, cloud-sized settings.contextTokens value verbatim.
        private const val LOCAL_CONTEXT_TOKENS_CEILING = 4096

        // A local model's own output length, capped independently of
        // settings.maxResponseTokens — see its call site in buildOrchestrator().
        private const val LOCAL_MAX_OUTPUT_TOKENS = 512

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
