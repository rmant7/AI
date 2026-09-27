package ai.localstudio.core.errors

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.engine.NoModelForCapabilityException
import ai.localstudio.core.runtime.InsufficientMemoryException
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.OperationTimeoutException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A minimal [HttpStatusError] a real provider exception looks like from the classifier's side. */
private class FakeHttpError(override val status: Int, override val body: String, message: String) :
    Exception(message), HttpStatusError

class AIErrorClassifierTest {

    @Test
    fun `401 and 403 classify as AUTHENTICATION`() {
        assertEquals(AIErrorCode.AUTHENTICATION, classify(FakeHttpError(401, "", "unauthorized")).code)
        assertEquals(AIErrorCode.AUTHENTICATION, classify(FakeHttpError(403, "", "forbidden")).code)
    }

    @Test
    fun `404 classifies as NOT_FOUND`() {
        assertEquals(AIErrorCode.NOT_FOUND, classify(FakeHttpError(404, "", "model not found")).code)
    }

    @Test
    fun `400, 413 and 422 classify as INVALID_REQUEST`() {
        assertEquals(AIErrorCode.INVALID_REQUEST, classify(FakeHttpError(400, "", "bad request")).code)
        assertEquals(AIErrorCode.INVALID_REQUEST, classify(FakeHttpError(413, "", "request too large")).code)
        assertEquals(AIErrorCode.INVALID_REQUEST, classify(FakeHttpError(422, "", "unprocessable")).code)
    }

    @Test
    fun `5xx classifies as UNAVAILABLE`() {
        assertEquals(AIErrorCode.UNAVAILABLE, classify(FakeHttpError(500, "", "server error")).code)
        assertEquals(AIErrorCode.UNAVAILABLE, classify(FakeHttpError(503, "", "overloaded")).code)
    }

    @Test
    fun `an unmapped status classifies as UNKNOWN`() {
        assertEquals(AIErrorCode.UNKNOWN, classify(FakeHttpError(418, "", "teapot")).code)
    }

    @Test
    fun `a plain per-minute 429 classifies as RATE_LIMIT with a retry hint`() {
        val error = classify(FakeHttpError(429, "", "Rate limit reached. Please try again in 15.84s."))
        assertEquals(AIErrorCode.RATE_LIMIT, error.code)
        assertEquals(17840L, error.retryAfterMs) // 15.84s + 2s padding
    }

    @Test
    fun `a daily-quota 429 classifies as QUOTA with no retry hint even if it also names seconds`() {
        // Real device report: a daily TPD/RPD limit's message can still
        // contain "try again in Ns" — the daily-limit check must win, or the
        // key comes back out of cooldown in seconds and re-hits the same
        // exhausted daily limit immediately.
        val error = classify(
            FakeHttpError(429, "", "Rate limit exceeded for tokens per day (TPD). Please try again in 5s."),
        )
        assertEquals(AIErrorCode.QUOTA, error.code)
        assertNull(error.retryAfterMs)
    }

    @Test
    fun `a 429 naming RPD is also QUOTA`() {
        assertEquals(AIErrorCode.QUOTA, classify(FakeHttpError(429, "", "requests per day (RPD) exceeded")).code)
    }

    @Test
    fun `a 429 with no parseable wait has a null retry hint`() {
        assertNull(classify(FakeHttpError(429, "", "too many requests")).retryAfterMs)
    }

    @Test
    fun `provider status and body survive classification`() {
        val error = classify(FakeHttpError(503, """{"error":"overloaded"}""", "overloaded"))
        assertEquals(503, error.providerStatus)
        assertEquals("""{"error":"overloaded"}""", error.providerBody)
    }

    @Test
    fun `InsufficientMemoryException classifies as OUT_OF_MEMORY`() {
        val error = classify(InsufficientMemoryException(requestedBytes = 8_000_000_000L, budgetBytes = 4_000_000_000L, residentBytes = 0L))
        assertEquals(AIErrorCode.OUT_OF_MEMORY, error.code)
    }

    @Test
    fun `OperationTimeoutException classifies as TIMEOUT`() {
        assertEquals(AIErrorCode.TIMEOUT, classify(OperationTimeoutException(30_000L, deadlineHit = false)).code)
    }

    @Test
    fun `NoModelForCapabilityException classifies as UNSUPPORTED`() {
        assertEquals(AIErrorCode.UNSUPPORTED, classify(NoModelForCapabilityException(Capability.VISION)).code)
    }

    @Test
    fun `ModelLoadException classifies as RUNTIME_ERROR`() {
        assertEquals(AIErrorCode.RUNTIME_ERROR, classify(ModelLoadException("could not load")).code)
    }

    @Test
    fun `SocketTimeoutException classifies as TIMEOUT`() {
        assertEquals(AIErrorCode.TIMEOUT, classify(SocketTimeoutException("timed out")).code)
    }

    @Test
    fun `UnknownHostException and ConnectException classify as NETWORK`() {
        assertEquals(AIErrorCode.NETWORK, classify(UnknownHostException("no dns")).code)
        assertEquals(AIErrorCode.NETWORK, classify(ConnectException("refused")).code)
    }

    @Test
    fun `a plain IOException classifies as NETWORK`() {
        assertEquals(AIErrorCode.NETWORK, classify(IOException("connection reset")).code)
    }

    @Test
    fun `an unrecognized exception classifies as UNKNOWN, keeping its message`() {
        val error = classify(IllegalStateException("something else broke"))
        assertEquals(AIErrorCode.UNKNOWN, error.code)
        assertEquals("something else broke", error.message)
    }

    @Test
    fun `classifying an AIError returns it unchanged`() {
        val original = AIError(AIErrorCode.CONTENT_ERROR, "blocked")
        assertTrue(classify(original) === original)
    }

    @Test
    fun `classifying a CancellationException rethrows it instead of wrapping it`() {
        assertFailsWith<CancellationException> { classify(CancellationException("cancelled")) }
    }

    private fun classify(t: Throwable): AIError = AIErrorClassifier.classify(t)
}
