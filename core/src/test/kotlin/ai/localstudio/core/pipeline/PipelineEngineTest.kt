package ai.localstudio.core.pipeline

import ai.localstudio.core.context.ContextEngine
import ai.localstudio.core.context.ContextFragment
import ai.localstudio.core.context.FragmentSource
import ai.localstudio.core.model.Transcript
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The engine is exercised with fake stages: the point of the abstraction is
 * that a graph runs identically against fakes, against an OpenAI-compatible
 * endpoint in development, and against on-device runtimes.
 */
class PipelineEngineTest {

    private val executed = mutableListOf<String>()
    private val contextEngine = ContextEngine()

    private fun record(id: String) = executed.add(id)

    private val executors: Map<NodeType, NodeExecutor> = mapOf(
        NodeType.MICROPHONE to NodeExecutor { node, inputs, _ ->
            record(node.id)
            inputs.firstOrNull() ?: NodeValue.Empty
        },
        NodeType.VAD to NodeExecutor { node, inputs, _ ->
            record(node.id)
            inputs.firstOrNull() ?: NodeValue.Empty
        },
        NodeType.SPEECH_TO_TEXT to NodeExecutor { node, _, _ ->
            record(node.id)
            NodeValue.Speech(Transcript(text = "найди мне лучшие локальные модели", language = "ru", confidence = 0.94))
        },
        NodeType.MEMORY_SEARCH to NodeExecutor { node, _, _ ->
            record(node.id)
            NodeValue.Fragments(listOf(ContextFragment(FragmentSource.EPISODIC_MEMORY, "вчера обсуждали registry")))
        },
        NodeType.KNOWLEDGE_SEARCH to NodeExecutor { node, _, _ ->
            record(node.id)
            NodeValue.Fragments(listOf(ContextFragment(FragmentSource.KNOWLEDGE, "device profile: 16 GB RAM")))
        },
        NodeType.CONTEXT_BUILD to NodeExecutor { node, inputs, _ ->
            record(node.id)
            val fragments = inputs.flatMap { value ->
                when (value) {
                    is NodeValue.Fragments -> value.fragments
                    is NodeValue.Speech -> listOf(ContextFragment(FragmentSource.TRANSCRIPT, value.transcript.text))
                    is NodeValue.Text -> listOf(ContextFragment(FragmentSource.USER_MESSAGE, value.text))
                    else -> emptyList()
                }
            }
            NodeValue.Context(contextEngine.assemble(fragments, contextWindowTokens = 4096))
        },
        NodeType.TEXT_GENERATION to NodeExecutor { node, inputs, _ ->
            record(node.id)
            val context = assertIs<NodeValue.Context>(inputs.single()).context
            NodeValue.Text("answer over ${context.fragments.size} fragments")
        },
        NodeType.MEMORY_UPDATE to NodeExecutor { node, inputs, _ ->
            record(node.id)
            inputs.first()
        },
        NodeType.RESPONSE to NodeExecutor { node, inputs, _ ->
            record(node.id)
            inputs.first()
        },
    )

    private val engine = PipelineEngine(executors)

    private val voiceRag = PipelineCodec.decodePipeline(
        """
        {
          "id": "voice_rag",
          "name": "voice",
          "nodes": [
            { "id": "mic", "type": "microphone" },
            { "id": "vad", "type": "vad" },
            { "id": "stt", "type": "speech_to_text" },
            { "id": "memory", "type": "memory_search" },
            { "id": "rag", "type": "knowledge_search" },
            { "id": "context", "type": "context_build" },
            { "id": "llm", "type": "text_generation" },
            { "id": "save", "type": "memory_update" },
            { "id": "out", "type": "response" }
          ],
          "edges": [
            { "from": "mic", "to": "vad" },
            { "from": "vad", "to": "stt" },
            { "from": "stt", "to": "memory" },
            { "from": "stt", "to": "rag" },
            { "from": "stt", "to": "context" },
            { "from": "memory", "to": "context" },
            { "from": "rag", "to": "context" },
            { "from": "context", "to": "llm" },
            { "from": "llm", "to": "save" },
            { "from": "llm", "to": "out" }
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun `a voice pipeline runs its stages in dependency order`() = runBlocking {
        val result = engine.run(voiceRag, context = RunContext(conversationId = "c1"))

        assertEquals("answer over 3 fragments", assertIs<NodeValue.Text>(result.output).text)
        assertTrue(executed.indexOf("stt") < executed.indexOf("context"))
        assertTrue(executed.indexOf("memory") < executed.indexOf("context"))
        assertTrue(executed.indexOf("rag") < executed.indexOf("context"))
        assertTrue(executed.indexOf("context") < executed.indexOf("llm"))
        assertEquals("out", executed.last())
    }

    @Test
    fun `every node output is retained for tracing`() = runBlocking {
        val result = engine.run(voiceRag, context = RunContext(conversationId = "c1"))

        assertEquals(9, result.outputs.size)
        assertEquals(9, result.trace.size)
        assertEquals(NodeType.SPEECH_TO_TEXT, result.trace.first { it.nodeId == "stt" }.type)
        assertIs<NodeValue.Speech>(result.outputs.getValue("stt"))
    }

    @Test
    fun `source nodes receive the run input`() = runBlocking {
        val textPipeline = PipelineSpec(
            id = "text",
            name = "text",
            nodes = listOf(
                NodeSpec("in", NodeType.MICROPHONE),
                NodeSpec("context", NodeType.CONTEXT_BUILD),
                NodeSpec("llm", NodeType.TEXT_GENERATION),
                NodeSpec("out", NodeType.RESPONSE),
            ),
            edges = listOf(EdgeSpec("in", "context"), EdgeSpec("context", "llm"), EdgeSpec("llm", "out")),
        )

        val result = engine.run(
            textPipeline,
            input = NodeValue.Text("привет"),
            context = RunContext(conversationId = "c1"),
        )

        assertEquals("answer over 1 fragments", assertIs<NodeValue.Text>(result.output).text)
    }

    @Test
    fun `a missing executor fails before anything is generated`() = runBlocking {
        val engineWithoutLlm = PipelineEngine(executors - NodeType.TEXT_GENERATION)

        val failure = assertFailsWith<MissingExecutorException> {
            engineWithoutLlm.run(voiceRag, context = RunContext(conversationId = "c1"))
        }

        assertEquals(NodeType.TEXT_GENERATION, failure.type)
    }

    @Test
    fun `an invalid pipeline never runs`() = runBlocking {
        val cyclic = voiceRag.copy(edges = voiceRag.edges + EdgeSpec("out", "context"))

        assertFailsWith<PipelineValidationException> {
            engine.run(cyclic, context = RunContext(conversationId = "c1"))
        }
        assertTrue(executed.isEmpty())
    }
}
