package ai.localstudio.core.speech

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the 12 scenarios from docs/15-speech-routing.md's own test list,
 * against fakes only — no real ASR model, no real LID model. What's under
 * test is the routing *policy*: which model gets picked, when a switch
 * actually happens vs. is suppressed, and that failures degrade to a
 * fallback instead of losing the session.
 */
class DefaultStreamingSpeechRouterTest {

    private val sampleRate = 1_000
    private val windowMs = 100 // 100 samples
    private val strideMs = 50 // 50 samples
    private val chunk = ShortArray(50)

    @Test
    fun `RU routes to the RU specialist`() = runBlocking {
        val registry = InMemorySpeechModelRegistry().apply {
            register(fakeModel("ru-specialist", setOf(Language.RU)))
            register(fakeModel("multi-fallback", emptySet()))
        }
        val session = DefaultStreamingSpeechRouter(
            registry, ScriptedLanguageIdentifier(listOf(lid(Language.RU, 0.9f))),
            RoutingPolicy(fallbackModelId = "multi-fallback"), this, sampleRate, windowMs, strideMs,
        ).start()

        session.acceptAudio(chunk); session.acceptAudio(chunk) // 1 eval
        session.finish()

        val segments = session.segments.toList()
        assertEquals(1, segments.size)
        assertEquals(Language.RU, segments[0].language)
        assertEquals("ru-specialist", segments[0].modelId)
    }

    @Test
    fun `EN routes to the EN specialist`() = runBlocking {
        val registry = InMemorySpeechModelRegistry().apply {
            register(fakeModel("ru-specialist", setOf(Language.RU)))
            register(fakeModel("en-specialist", setOf(Language.EN)))
            register(fakeModel("multi-fallback", emptySet()))
        }
        val session = DefaultStreamingSpeechRouter(
            registry, ScriptedLanguageIdentifier(listOf(lid(Language.EN, 0.9f))),
            RoutingPolicy(fallbackModelId = "multi-fallback"), this, sampleRate, windowMs, strideMs,
        ).start()

        session.acceptAudio(chunk); session.acceptAudio(chunk)
        session.finish()

        val segments = session.segments.toList()
        assertEquals(1, segments.size)
        assertEquals("en-specialist", segments[0].modelId)
    }

    @Test
    fun `HE with no HE specialist falls back to the multilingual model`() = runBlocking {
        val registry = InMemorySpeechModelRegistry().apply {
            register(fakeModel("ru-specialist", setOf(Language.RU)))
            register(fakeModel("en-specialist", setOf(Language.EN)))
            register(fakeModel("multi-fallback", emptySet()))
        }
        val session = DefaultStreamingSpeechRouter(
            registry, ScriptedLanguageIdentifier(listOf(lid(Language.HE, 0.9f))),
            RoutingPolicy(fallbackModelId = "multi-fallback"), this, sampleRate, windowMs, strideMs,
        ).start()

        session.acceptAudio(chunk); session.acceptAudio(chunk)
        session.finish()

        val segments = session.segments.toList()
        assertEquals(1, segments.size)
        assertEquals("multi-fallback", segments[0].modelId)
        // Detected language is still worth recording for diagnostics, even
        // though no HE-specific model handled it.
        assertEquals(Language.HE, segments[0].language)
    }

    @Test
    fun `low-confidence UNKNOWN falls back to the multilingual model`() = runBlocking {
        val registry = InMemorySpeechModelRegistry().apply {
            register(fakeModel("ru-specialist", setOf(Language.RU)))
            register(fakeModel("multi-fallback", emptySet()))
        }
        val session = DefaultStreamingSpeechRouter(
            registry, ScriptedLanguageIdentifier(listOf(lid(Language.UNKNOWN, 0.9f))),
            RoutingPolicy(fallbackModelId = "multi-fallback"), this, sampleRate, windowMs, strideMs,
        ).start()

        session.acceptAudio(chunk); session.acceptAudio(chunk)
        session.finish()

        assertEquals("multi-fallback", session.segments.toList().single().modelId)
    }

    @Test
    fun `mixed-language result falls back to the multilingual model regardless of confidence`() = runBlocking {
        val registry = InMemorySpeechModelRegistry().apply {
            register(fakeModel("ru-specialist", setOf(Language.RU)))
            register(fakeModel("multi-fallback", emptySet()))
        }
        // High confidence, but isMixed=true — must still fall back, not
        // route to the RU specialist a naive "just use .language" read
        // would pick.
        val session = DefaultStreamingSpeechRouter(
            registry, ScriptedLanguageIdentifier(listOf(lid(Language.RU, 0.95f, isMixed = true))),
            RoutingPolicy(fallbackModelId = "multi-fallback"), this, sampleRate, windowMs, strideMs,
        ).start()

        session.acceptAudio(chunk); session.acceptAudio(chunk)
        session.finish()

        assertEquals("multi-fallback", session.segments.toList().single().modelId)
    }

    @Test
    fun `a stable RU to EN switch happens only after enough consecutive agreeing windows`() = runBlocking {
        val registry = InMemorySpeechModelRegistry().apply {
            register(fakeModel("ru-specialist", setOf(Language.RU)))
            register(fakeModel("en-specialist", setOf(Language.EN)))
            register(fakeModel("multi-fallback", emptySet()))
        }
        val script = listOf(lid(Language.RU, 0.9f), lid(Language.EN, 0.9f), lid(Language.EN, 0.9f))
        val session = DefaultStreamingSpeechRouter(
            registry, ScriptedLanguageIdentifier(script),
            RoutingPolicy(fallbackModelId = "multi-fallback", minStableWindows = 2), this, sampleRate, windowMs, strideMs,
        ).start()

        repeat(4) { session.acceptAudio(chunk) } // 3 evals: RU pick, EN candidate (not yet), EN confirmed -> switch
        session.finish()

        val segments = session.segments.toList()
        assertEquals(listOf("ru-specialist", "en-specialist"), segments.map { it.modelId })

        val decisions = session.decisions.toList()
        assertTrue(decisions.any { it.reason.contains("stable language switch") })
        assertTrue(decisions.any { it.reason.contains("window 1/2") })
    }

    @Test
    fun `a single brief incorrect EN reading does not switch away from RU`() = runBlocking {
        val registry = InMemorySpeechModelRegistry().apply {
            register(fakeModel("ru-specialist", setOf(Language.RU)))
            register(fakeModel("en-specialist", setOf(Language.EN)))
            register(fakeModel("multi-fallback", emptySet()))
        }
        val script = listOf(lid(Language.RU, 0.9f), lid(Language.EN, 0.9f), lid(Language.RU, 0.9f), lid(Language.RU, 0.9f))
        val session = DefaultStreamingSpeechRouter(
            registry, ScriptedLanguageIdentifier(script),
            RoutingPolicy(fallbackModelId = "multi-fallback", minStableWindows = 2), this, sampleRate, windowMs, strideMs,
        ).start()

        repeat(5) { session.acceptAudio(chunk) } // 4 evals
        session.finish()

        // Only ever on ru-specialist — one segment, from the final finish().
        val segments = session.segments.toList()
        assertEquals(listOf("ru-specialist"), segments.map { it.modelId })
        assertTrue(session.decisions.toList().none { it.reason.contains("switch") })
    }

    @Test
    fun `a specialist that fails to start falls back to the multilingual model`() = runBlocking {
        val registry = InMemorySpeechModelRegistry().apply {
            register(brokenModel("ru-specialist", setOf(Language.RU)))
            register(fakeModel("multi-fallback", emptySet()))
        }
        val session = DefaultStreamingSpeechRouter(
            registry, ScriptedLanguageIdentifier(listOf(lid(Language.RU, 0.9f))),
            RoutingPolicy(fallbackModelId = "multi-fallback"), this, sampleRate, windowMs, strideMs,
        ).start()

        session.acceptAudio(chunk); session.acceptAudio(chunk)
        session.finish()

        assertEquals("multi-fallback", session.segments.toList().single().modelId)
        assertNull((session as DefaultStreamingRoutingSession).lastError, "a recovered failure must not leave a stale error")
    }

    @Test
    fun `a specialist whose handle() throws (not installed) falls back to the multilingual model`() = runBlocking {
        val registry = InMemorySpeechModelRegistry().apply {
            register(unavailableModel("ru-specialist", setOf(Language.RU)))
            register(fakeModel("multi-fallback", emptySet()))
        }
        val session = DefaultStreamingSpeechRouter(
            registry, ScriptedLanguageIdentifier(listOf(lid(Language.RU, 0.9f))),
            RoutingPolicy(fallbackModelId = "multi-fallback"), this, sampleRate, windowMs, strideMs,
        ).start()

        session.acceptAudio(chunk); session.acceptAudio(chunk)
        session.finish()

        assertEquals("multi-fallback", session.segments.toList().single().modelId)
    }

    @Test
    fun `models can be registered and unregistered`() {
        val registry = InMemorySpeechModelRegistry()
        val ru = fakeModel("ru-specialist", setOf(Language.RU))

        registry.register(ru)
        assertEquals(listOf("ru-specialist"), registry.all().map { it.info.id })
        assertEquals(ru, registry.get("ru-specialist"))
        assertEquals(listOf("ru-specialist"), registry.findCandidates(Language.RU).map { it.info.id })

        registry.unregister("ru-specialist")
        assertTrue(registry.all().isEmpty())
        assertNull(registry.get("ru-specialist"))
        assertTrue(registry.findCandidates(Language.RU).isEmpty())
    }

    @Test
    fun `the same session shape serves both a microphone-style and a file-style caller`() = runBlocking {
        // The router's API has no notion of "source" at all — acceptAudio
        // takes a plain ShortArray. This runs two independent sessions from
        // one router, one fed in real-time-sized chunks (as a mic would),
        // one fed in one large chunk (as a file decoder might hand off a
        // whole buffer at once), and checks both still route correctly.
        val registry = InMemorySpeechModelRegistry().apply {
            register(fakeModel("ru-specialist", setOf(Language.RU)))
            register(fakeModel("multi-fallback", emptySet()))
        }
        val router = DefaultStreamingSpeechRouter(
            registry, ScriptedLanguageIdentifier(listOf(lid(Language.RU, 0.9f))),
            RoutingPolicy(fallbackModelId = "multi-fallback"), this, sampleRate, windowMs, strideMs,
        )

        val micStyleSession = router.start()
        micStyleSession.acceptAudio(chunk); micStyleSession.acceptAudio(chunk)
        micStyleSession.finish()
        assertEquals("ru-specialist", micStyleSession.segments.toList().single().modelId)

        val fileStyleRouter = DefaultStreamingSpeechRouter(
            registry, ScriptedLanguageIdentifier(listOf(lid(Language.RU, 0.9f))),
            RoutingPolicy(fallbackModelId = "multi-fallback"), this, sampleRate, windowMs, strideMs,
        )
        val fileStyleSession = fileStyleRouter.start()
        fileStyleSession.acceptAudio(ShortArray(100)) // one big chunk instead of many small ones
        fileStyleSession.finish()
        assertEquals("ru-specialist", fileStyleSession.segments.toList().single().modelId)
    }

    @Test
    fun `routing works for models the router has never heard of by name`() = runBlocking {
        // Proves the router isn't secretly keyed on "vosk"/"whisper" by
        // string: registering under unfamiliar ids and engine types must
        // route exactly the same way as the other tests' familiar-looking
        // ones — there is no per-engine branch anywhere to have missed.
        val registry = InMemorySpeechModelRegistry().apply {
            register(fakeModel("acme-ru-v7", setOf(Language.RU)))
            register(fakeModel("zorp-multilingual-9000", emptySet()))
        }
        val session = DefaultStreamingSpeechRouter(
            registry, ScriptedLanguageIdentifier(listOf(lid(Language.RU, 0.9f))),
            RoutingPolicy(fallbackModelId = "zorp-multilingual-9000"), this, sampleRate, windowMs, strideMs,
        ).start()

        session.acceptAudio(chunk); session.acceptAudio(chunk)
        session.finish()

        assertEquals("acme-ru-v7", session.segments.toList().single().modelId)
    }
}
