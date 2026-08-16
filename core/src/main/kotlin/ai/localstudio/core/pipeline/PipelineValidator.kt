package ai.localstudio.core.pipeline

enum class IssueKind {
    DUPLICATE_NODE_ID,
    UNKNOWN_EDGE_ENDPOINT,
    SELF_LOOP,
    CYCLE,
    NO_SOURCE_NODE,
    NO_TERMINAL_NODE,
    UNREACHABLE_NODE,
    SOURCE_WITH_INPUT,
    EMPTY_PIPELINE,
}

data class PipelineIssue(val kind: IssueKind, val detail: String)

class PipelineValidationException(val issues: List<PipelineIssue>) :
    Exception("Invalid pipeline: " + issues.joinToString("; ") { "${it.kind}: ${it.detail}" })

/**
 * Structural checks run before a pipeline is saved or executed. Everything a
 * user can build in the editor is checked here, so the engine can assume a
 * well-formed DAG.
 */
object PipelineValidator {

    fun validate(spec: PipelineSpec): List<PipelineIssue> {
        val issues = mutableListOf<PipelineIssue>()

        if (spec.nodes.isEmpty()) {
            return listOf(PipelineIssue(IssueKind.EMPTY_PIPELINE, "pipeline ${spec.id} has no nodes"))
        }

        spec.nodes.groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .forEach { issues += PipelineIssue(IssueKind.DUPLICATE_NODE_ID, it) }

        val ids = spec.nodes.map { it.id }.toSet()
        for (edge in spec.edges) {
            if (edge.from !in ids) {
                issues += PipelineIssue(IssueKind.UNKNOWN_EDGE_ENDPOINT, "${edge.from} -> ${edge.to} (from)")
            }
            if (edge.to !in ids) {
                issues += PipelineIssue(IssueKind.UNKNOWN_EDGE_ENDPOINT, "${edge.from} -> ${edge.to} (to)")
            }
            if (edge.from == edge.to) {
                issues += PipelineIssue(IssueKind.SELF_LOOP, edge.from)
            }
        }

        val sources = spec.nodes.filter { it.type.isSource }
        if (sources.isEmpty()) {
            issues += PipelineIssue(IssueKind.NO_SOURCE_NODE, "pipeline ${spec.id} has no input node")
        }
        sources.filter { spec.incoming(it.id).isNotEmpty() }
            .forEach { issues += PipelineIssue(IssueKind.SOURCE_WITH_INPUT, it.id) }

        if (spec.nodes.none { it.type == NodeType.RESPONSE }) {
            issues += PipelineIssue(IssueKind.NO_TERMINAL_NODE, "pipeline ${spec.id} has no response node")
        }

        // Cycles are detected on the edges that reference real nodes, so a
        // dangling edge is reported once rather than masquerading as a cycle.
        val order = topologicalOrder(spec)
        if (order == null) {
            issues += PipelineIssue(IssueKind.CYCLE, "pipeline ${spec.id} is not acyclic")
        } else if (sources.isNotEmpty()) {
            val reachable = reachableFrom(spec, sources.map { it.id })
            spec.nodes.map { it.id }.filterNot { it in reachable }
                .forEach { issues += PipelineIssue(IssueKind.UNREACHABLE_NODE, it) }
        }

        return issues
    }

    fun requireValid(spec: PipelineSpec) {
        val issues = validate(spec)
        if (issues.isNotEmpty()) throw PipelineValidationException(issues)
    }

    /** Kahn's algorithm; null when the graph contains a cycle. */
    fun topologicalOrder(spec: PipelineSpec): List<String>? {
        val ids = spec.nodes.map { it.id }.distinct()
        val known = ids.toSet()
        val edges = spec.edges.filter { it.from in known && it.to in known && it.from != it.to }

        val indegree = ids.associateWith { id -> edges.count { it.to == id } }.toMutableMap()
        val ready = ArrayDeque(ids.filter { indegree.getValue(it) == 0 })
        val order = mutableListOf<String>()

        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            order += id
            for (next in edges.filter { it.from == id }.map { it.to }) {
                val remaining = indegree.getValue(next) - 1
                indegree[next] = remaining
                if (remaining == 0) ready += next
            }
        }

        return if (order.size == ids.size) order else null
    }

    private fun reachableFrom(spec: PipelineSpec, roots: List<String>): Set<String> {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            queue += spec.outgoing(id)
        }
        return seen
    }
}
