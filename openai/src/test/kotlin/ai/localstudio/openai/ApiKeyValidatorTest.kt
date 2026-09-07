package ai.localstudio.openai

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

class ApiKeyValidatorTest {

    private val server = FakeOpenAiServer()

    @AfterTest
    fun stop() = server.close()

    @Test
    fun `a key the server accepts is reported valid`() {
        server.modelsStatus = 200

        val result = ApiKeyValidator.validate(server.baseUrl, "any-key")

        assertIs<ApiKeyValidationResult.Valid>(result)
    }

    @Test
    fun `a key the server rejects with 401 is reported invalid, not merely unknown`() {
        server.modelsStatus = 401

        val result = ApiKeyValidator.validate(server.baseUrl, "wrong-key")

        assertIs<ApiKeyValidationResult.Invalid>(result)
    }

    @Test
    fun `a 429 while validating is rate-limited, not proof the key is bad`() {
        server.modelsStatus = 429

        val result = ApiKeyValidator.validate(server.baseUrl, "maybe-fine-key")

        assertIs<ApiKeyValidationResult.RateLimited>(result)
    }

    @Test
    fun `an unreachable server is reported unknown, never invalid`() {
        server.close()

        val result = ApiKeyValidator.validate(server.baseUrl, "any-key")

        assertIs<ApiKeyValidationResult.Unknown>(result)
    }
}
