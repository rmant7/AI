package ai.localstudio.commercialmemory

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExperimentLoggerTest {

    private fun record(id: String = "e1") = ExperimentRecord(
        experimentId = id,
        timestampEpochMs = 1_000L,
        mode = ExperimentMode.COMMERCIAL_MEMORY,
        queryLength = 10,
        candidateCount = 5,
        selectedCount = 2,
        selectedCharacters = 40,
        latencyMs = 3L,
    )

    @Test
    fun `InMemoryExperimentLogger keeps every record in order`() {
        val logger = InMemoryExperimentLogger()
        logger.log(record("a"))
        logger.log(record("b"))

        assertEquals(listOf("a", "b"), logger.all().map { it.experimentId })
    }

    @Test
    fun `JsonlExperimentLogger appends one JSON object per line`() {
        val file = File.createTempFile("experiment-log", ".jsonl").apply { deleteOnExit() }
        val logger = JsonlExperimentLogger(file)

        logger.log(record("a"))
        logger.log(record("b"))

        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"experimentId\":\"a\""))
        assertTrue(lines[1].contains("\"experimentId\":\"b\""))
    }

    @Test
    fun `JsonlExperimentLogger creates parent directories that do not exist yet`() {
        val dir = File.createTempFile("experiment-log-dir", "").apply { delete() }
        val file = File(dir, "nested/log.jsonl")

        JsonlExperimentLogger(file).log(record())

        assertTrue(file.isFile)
        dir.deleteRecursively()
    }
}
