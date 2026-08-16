package ai.localstudio.core.pipeline

import ai.localstudio.core.capability.Capability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PipelineValidatorTest {

    private fun spec(nodes: List<NodeSpec>, edges: List<EdgeSpec>) =
        PipelineSpec(id = "p", name = "p", nodes = nodes, edges = edges)

    private val minimal = spec(
        nodes = listOf(
            NodeSpec("in", NodeType.TEXT_INPUT),
            NodeSpec("llm", NodeType.TEXT_GENERATION),
            NodeSpec("out", NodeType.RESPONSE),
        ),
        edges = listOf(EdgeSpec("in", "llm"), EdgeSpec("llm", "out")),
    )

    @Test
    fun `a well-formed pipeline has no issues`() {
        assertTrue(PipelineValidator.validate(minimal).isEmpty())
        assertEquals(listOf("in", "llm", "out"), PipelineValidator.topologicalOrder(minimal))
    }

    @Test
    fun `a cycle is reported and breaks the topological order`() {
        val cyclic = spec(
            nodes = minimal.nodes,
            edges = minimal.edges + EdgeSpec("out", "llm"),
        )

        assertTrue(PipelineValidator.validate(cyclic).any { it.kind == IssueKind.CYCLE })
        assertEquals(null, PipelineValidator.topologicalOrder(cyclic))
    }

    @Test
    fun `a dangling edge is reported as an unknown endpoint, not as a cycle`() {
        val broken = spec(nodes = minimal.nodes, edges = minimal.edges + EdgeSpec("llm", "ghost"))

        val kinds = PipelineValidator.validate(broken).map { it.kind }

        assertTrue(IssueKind.UNKNOWN_EDGE_ENDPOINT in kinds)
        assertTrue(IssueKind.CYCLE !in kinds)
    }

    @Test
    fun `duplicate node ids are reported`() {
        val duplicated = spec(
            nodes = minimal.nodes + NodeSpec("llm", NodeType.TEXT_GENERATION),
            edges = minimal.edges,
        )

        assertTrue(PipelineValidator.validate(duplicated).any { it.kind == IssueKind.DUPLICATE_NODE_ID })
    }

    @Test
    fun `a pipeline without an input or without a response is rejected`() {
        val noSource = spec(
            nodes = listOf(NodeSpec("llm", NodeType.TEXT_GENERATION), NodeSpec("out", NodeType.RESPONSE)),
            edges = listOf(EdgeSpec("llm", "out")),
        )
        val noResponse = spec(
            nodes = listOf(NodeSpec("in", NodeType.TEXT_INPUT), NodeSpec("llm", NodeType.TEXT_GENERATION)),
            edges = listOf(EdgeSpec("in", "llm")),
        )

        assertTrue(PipelineValidator.validate(noSource).any { it.kind == IssueKind.NO_SOURCE_NODE })
        assertTrue(PipelineValidator.validate(noResponse).any { it.kind == IssueKind.NO_TERMINAL_NODE })
    }

    @Test
    fun `a node nothing feeds into is reported as unreachable`() {
        val orphaned = spec(
            nodes = minimal.nodes + NodeSpec("orphan", NodeType.MEMORY_SEARCH),
            edges = minimal.edges,
        )

        assertEquals(
            listOf("orphan"),
            PipelineValidator.validate(orphaned)
                .filter { it.kind == IssueKind.UNREACHABLE_NODE }
                .map { it.detail },
        )
    }

    @Test
    fun `an input node cannot have incoming edges`() {
        val fedSource = spec(
            nodes = minimal.nodes,
            edges = minimal.edges + EdgeSpec("llm", "in"),
        )

        assertTrue(PipelineValidator.validate(fedSource).any { it.kind == IssueKind.SOURCE_WITH_INPUT })
    }

    @Test
    fun `requireValid throws with every issue attached`() {
        val empty = PipelineSpec(id = "p", name = "p", nodes = emptyList())

        val failure = assertFailsWith<PipelineValidationException> { PipelineValidator.requireValid(empty) }

        assertEquals(listOf(IssueKind.EMPTY_PIPELINE), failure.issues.map { it.kind })
    }

    @Test
    fun `required capabilities are derived from node types`() {
        assertEquals(
            setOf(Capability.SPEECH_TO_TEXT, Capability.EMBEDDING, Capability.TEXT_GENERATION),
            spec(
                nodes = listOf(
                    NodeSpec("mic", NodeType.MICROPHONE),
                    NodeSpec("stt", NodeType.SPEECH_TO_TEXT),
                    NodeSpec("rag", NodeType.KNOWLEDGE_SEARCH),
                    NodeSpec("llm", NodeType.TEXT_GENERATION),
                    NodeSpec("out", NodeType.RESPONSE),
                ),
                edges = emptyList(),
            ).requiredCapabilities(),
        )
    }
}
