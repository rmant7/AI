package ai.localstudio.core.pipeline

import ai.localstudio.core.context.AssembledContext
import ai.localstudio.core.context.ContextFragment
import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.DocumentRef
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.model.Transcript
import ai.localstudio.core.model.VideoRef

/** What flows along the edges of a pipeline. */
sealed interface NodeValue {
    data object Empty : NodeValue
    data class Text(val text: String) : NodeValue
    data class Speech(val transcript: Transcript) : NodeValue
    data class Fragments(val fragments: List<ContextFragment>) : NodeValue
    data class Context(val context: AssembledContext) : NodeValue
    data class Audio(val ref: AudioRef) : NodeValue
    data class Image(val ref: ImageRef) : NodeValue
    data class Video(val ref: VideoRef) : NodeValue
    data class Document(val ref: DocumentRef) : NodeValue
    data class Bundle(val values: List<NodeValue>) : NodeValue
}

/** One earlier turn of the same conversation, for context building. */
data class ConversationTurn(val role: String, val text: String)

/** Per-run state shared by all nodes: conversation id, user turn, cancellation. */
data class RunContext(
    val conversationId: String,
    val userMessage: String? = null,
    /**
     * Recent turns of *this* conversation, oldest first. This is what makes a
     * reply to "и второе?" make sense: without it, every turn is generated as
     * if it were the first message ever sent, because [MemoryScope.WORKING]
     * (where turns are also stored) is only searched when the message itself
     * contains a recall keyword — see [ai.localstudio.core.router.CapabilityRouter].
     */
    val history: List<ConversationTurn> = emptyList(),
    val params: Map<String, String> = emptyMap(),
)

data class NodeTrace(val nodeId: String, val type: NodeType, val durationMs: Long)

data class RunResult(
    val output: NodeValue,
    val outputs: Map<String, NodeValue>,
    val trace: List<NodeTrace>,
)

fun interface NodeExecutor {
    suspend fun execute(node: NodeSpec, inputs: List<NodeValue>, context: RunContext): NodeValue
}

class MissingExecutorException(val type: NodeType) :
    Exception("No executor registered for node type ${type.id}")

/**
 * Executes a validated pipeline in topological order.
 *
 * The engine knows nothing about models, memory or RAG — every stage is a
 * [NodeExecutor] supplied from outside. That is what keeps the same graph
 * runnable against real runtimes on the phone and against fakes in tests, or
 * against an OpenAI-compatible endpoint during development.
 */
class PipelineEngine(
    private val executors: Map<NodeType, NodeExecutor>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        spec: PipelineSpec,
        input: NodeValue = NodeValue.Empty,
        context: RunContext,
    ): RunResult {
        PipelineValidator.requireValid(spec)
        val order = PipelineValidator.topologicalOrder(spec)
            ?: throw PipelineValidationException(
                listOf(PipelineIssue(IssueKind.CYCLE, "pipeline ${spec.id} is not acyclic")),
            )

        val outputs = LinkedHashMap<String, NodeValue>()
        val trace = mutableListOf<NodeTrace>()

        for (nodeId in order) {
            val node = spec.node(nodeId) ?: continue
            val executor = executors[node.type] ?: throw MissingExecutorException(node.type)

            val inputs = if (node.type.isSource) {
                listOf(input)
            } else {
                spec.incoming(nodeId).mapNotNull { outputs[it] }.filterNot { it is NodeValue.Empty }
            }

            val startedAt = clock()
            outputs[nodeId] = executor.execute(node, inputs, context)
            trace += NodeTrace(nodeId, node.type, clock() - startedAt)
        }

        val terminalId = spec.nodes.lastOrNull { it.type == NodeType.RESPONSE }?.id
        val output = terminalId?.let { outputs[it] } ?: NodeValue.Empty

        return RunResult(output, outputs, trace)
    }
}
