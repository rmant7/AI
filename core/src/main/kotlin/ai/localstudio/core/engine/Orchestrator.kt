package ai.localstudio.core.engine

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.context.AssembledContext
import ai.localstudio.core.pipeline.NodeTrace
import ai.localstudio.core.pipeline.NodeValue
import ai.localstudio.core.pipeline.PipelineEngine
import ai.localstudio.core.pipeline.PipelineSpec
import ai.localstudio.core.pipeline.RunContext
import ai.localstudio.core.router.CapabilityRouter
import ai.localstudio.core.router.RequestSignals
import ai.localstudio.core.router.RoutePlan

/** One user turn: a message, whatever came with it, and which conversation it belongs to. */
data class UserRequest(
    val conversationId: String,
    val text: String? = null,
    val attachment: NodeValue = NodeValue.Empty,
    val memoryEnabled: Boolean = true,
    val knowledgeEnabled: Boolean = false,
)

data class Answer(
    val text: String,
    val plan: RoutePlan,
    val pipeline: PipelineSpec,
    val context: AssembledContext?,
    val trace: List<NodeTrace>,
) {
    /** Everything the context engine had to leave out — worth surfacing in the UI. */
    val droppedFragments: Int get() = context?.dropped?.size ?: 0
}

/**
 * The top-level entry point: request in, answer out.
 *
 * Deliberately thin, because every decision it needs has an owner already —
 * the router decides which capabilities are involved, [PipelineBuilder] turns
 * that into a graph, [ModelSelector] picks models per capability, the runtime
 * manager decides what stays in memory and the context engine decides what the
 * model sees. The orchestrator only sequences them.
 *
 * Chat and user-authored pipelines share one path: [handle] builds a graph and
 * runs it, [run] takes a graph that already exists. There is no second
 * execution path that can drift.
 */
class Orchestrator(
    private val router: CapabilityRouter,
    private val executors: NodeExecutors,
    private val engine: PipelineEngine = PipelineEngine(executors.build()),
) {
    suspend fun handle(request: UserRequest): Answer {
        val signals = RequestSignals(
            text = request.text,
            hasAudio = request.attachment is NodeValue.Audio,
            hasImage = request.attachment is NodeValue.Image,
            hasVideo = request.attachment is NodeValue.Video,
            hasDocumentContext = request.knowledgeEnabled,
            memoryEnabled = request.memoryEnabled,
            knowledgeEnabled = request.knowledgeEnabled,
        )
        val plan = router.route(signals)
        val pipeline = PipelineBuilder.fromRoute(plan, signals, id = "chat-${request.conversationId}")

        val input = when {
            request.attachment !is NodeValue.Empty -> request.attachment
            request.text != null -> NodeValue.Text(request.text)
            else -> NodeValue.Empty
        }

        val result = engine.run(
            spec = pipeline,
            input = input,
            context = RunContext(conversationId = request.conversationId, userMessage = request.text),
        )

        return Answer(
            text = (result.output as? NodeValue.Text)?.text.orEmpty(),
            plan = plan,
            pipeline = pipeline,
            context = result.outputs.values.filterIsInstance<NodeValue.Context>().firstOrNull()?.context,
            trace = result.trace,
        )
    }

    /** Runs a saved pipeline as-is. */
    suspend fun run(spec: PipelineSpec, input: NodeValue, context: RunContext): Answer {
        val result = engine.run(spec, input, context)
        return Answer(
            text = (result.output as? NodeValue.Text)?.text.orEmpty(),
            plan = RoutePlan(spec.requiredCapabilities().toList(), spec.nodes.map { it.type }, emptyList()),
            pipeline = spec,
            context = result.outputs.values.filterIsInstance<NodeValue.Context>().firstOrNull()?.context,
            trace = result.trace,
        )
    }
}

/** Capabilities a pipeline needs but nothing installed provides — the "install a model" prompt. */
fun PipelineSpec.missingCapabilities(selector: ModelSelector): Set<Capability> =
    requiredCapabilities().filter { selector.selectOrNull(it) == null }.toSet()
