package ai.localstudio.core.engine

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.context.ContextEngine
import ai.localstudio.core.context.ContextFragment
import ai.localstudio.core.context.FragmentSource
import ai.localstudio.core.knowledge.KnowledgeProvider
import ai.localstudio.core.knowledge.KnowledgeQuery
import ai.localstudio.core.memory.MemoryProvider
import ai.localstudio.core.memory.MemoryQuery
import ai.localstudio.core.memory.MemoryScope
import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.pipeline.NodeExecutor
import ai.localstudio.core.pipeline.NodeType
import ai.localstudio.core.pipeline.NodeValue
import ai.localstudio.core.pipeline.RunContext
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.RuntimeManager
import ai.localstudio.core.runtime.SpeechModelHandle
import ai.localstudio.core.runtime.TextModelHandle
import ai.localstudio.core.runtime.VisionModelHandle

/**
 * Wires the pipeline vocabulary to the actual services.
 *
 * Every stage resolves its model through [ModelSelector] by capability and
 * holds it only for the duration of the call via [RuntimeManager], which is
 * what lets a phone run a five-stage pipeline it could never load at once.
 */
class NodeExecutors(
    private val selector: ModelSelector,
    private val runtimeManager: RuntimeManager,
    private val contextEngine: ContextEngine,
    private val memory: MemoryProvider? = null,
    private val knowledge: KnowledgeProvider? = null,
    private val systemPrompt: String? = null,
    // Kept in sync with LlamaBridge.DEFAULT_CONTEXT_TOKENS on purpose: this is
    // what gets assembled *before* textGeneration() picks a model, so the two
    // must agree on a size every model can actually load, not just the
    // biggest one — a mismatch either rejects a turn that fit by this
    // engine's own accounting, or forces every model into a KV cache sized
    // for the largest, which is what pushed tight-memory devices into OOM.
    private val contextWindowTokens: Int = 4_096,
    private val defaultTemperature: Double = 0.7,
    private val defaultTopP: Double = 0.95,
    private val defaultTopK: Int = 40,
    private val defaultRepeatPenalty: Double = 1.2,
    private val defaultMaxTokens: Int = 1024,
) {

    fun build(): Map<NodeType, NodeExecutor> = buildMap {
        NodeType.entries.filter { it.isSource }.forEach { put(it, passthrough()) }
        put(NodeType.VAD, passthrough())
        put(NodeType.FRAME_EXTRACT, passthrough())
        put(NodeType.DIARIZATION, passthrough())
        put(NodeType.SPEECH_TO_TEXT, speechToText())
        put(NodeType.VISION_ANALYZE, vision(Capability.IMAGE_UNDERSTANDING, FragmentSource.VISION))
        put(NodeType.OCR, vision(Capability.OCR, FragmentSource.VISION))
        put(NodeType.MEMORY_SEARCH, memorySearch())
        put(NodeType.KNOWLEDGE_SEARCH, knowledgeSearch())
        put(NodeType.CONTEXT_BUILD, contextBuild())
        put(NodeType.TEXT_GENERATION, textGeneration())
        put(NodeType.MEMORY_UPDATE, memoryUpdate())
        put(NodeType.RESPONSE, passthrough())
    }

    private fun passthrough() = NodeExecutor { _, inputs, _ ->
        inputs.firstOrNull() ?: NodeValue.Empty
    }

    private fun speechToText() = NodeExecutor { node, inputs, _ ->
        val audio = inputs.filterIsInstance<NodeValue.Audio>().firstOrNull()?.ref
            ?: return@NodeExecutor inputs.firstOrNull() ?: NodeValue.Empty
        val selected = selector.select(Capability.SPEECH_TO_TEXT)
        val transcript = runtimeManager.withModel(selected.model, selected.binding) { loaded ->
            val handle = loaded as? SpeechModelHandle
                ?: throw ModelLoadException("${selected.model.id} did not load as a speech model")
            handle.transcribe(audio, node.params["language"]?.takeIf { it != "auto" })
        }
        NodeValue.Speech(transcript)
    }

    /**
     * A dedicated vision/OCR model is optional infrastructure, not a
     * requirement for an image to reach the model at all: no runtime in
     * this app currently registers one, and a multimodal chat model (sent
     * the image directly via [GenerationRequest.images] once it reaches
     * [textGeneration]) can answer about an image without this stage doing
     * anything first. Failing this node outright — as `select()` (throwing)
     * would — turned "no OCR model installed" into "attaching an image
     * breaks the turn entirely" instead of just skipping straight to the
     * chat model with the image still attached.
     */
    private fun vision(capability: Capability, source: FragmentSource) = NodeExecutor { node, inputs, _ ->
        val image = inputs.filterIsInstance<NodeValue.Image>().firstOrNull()
            ?: return@NodeExecutor NodeValue.Empty
        val selected = selector.selectOrNull(capability) ?: return@NodeExecutor image
        val result = runtimeManager.withModel(selected.model, selected.binding) { loaded ->
            val handle = loaded as? VisionModelHandle
                ?: throw ModelLoadException("${selected.model.id} did not load as a vision model")
            handle.analyze(image.ref, node.params["prompt"])
        }
        val text = listOfNotNull(result.description, result.ocrText?.takeIf { it.isNotBlank() })
            .joinToString("\n\n")
        NodeValue.Bundle(
            listOf(image, NodeValue.Fragments(listOf(ContextFragment(source, text, relevance = result.confidence ?: 0.0)))),
        )
    }

    private fun memorySearch() = NodeExecutor { node, inputs, context ->
        val provider = memory ?: return@NodeExecutor NodeValue.Empty
        val query = queryText(inputs, context) ?: return@NodeExecutor NodeValue.Empty
        val scopes = node.params["scopes"]
            ?.split(',')
            ?.mapNotNull { name -> MemoryScope.entries.firstOrNull { it.name.equals(name.trim(), true) } }
            ?.toSet()
            ?: setOf(MemoryScope.EPISODIC, MemoryScope.SEMANTIC)

        val items = provider.search(
            MemoryQuery(
                text = query,
                scopes = scopes,
                limit = node.params["limit"]?.toIntOrNull() ?: 8,
            ),
        )
        NodeValue.Fragments(
            items.map { item ->
                ContextFragment(
                    source = if (item.scope == MemoryScope.SEMANTIC) {
                        FragmentSource.SEMANTIC_MEMORY
                    } else {
                        FragmentSource.EPISODIC_MEMORY
                    },
                    text = item.text,
                    relevance = item.relevance ?: 0.0,
                )
            },
        )
    }

    private fun knowledgeSearch() = NodeExecutor { node, inputs, context ->
        val provider = knowledge ?: return@NodeExecutor NodeValue.Empty
        val query = queryText(inputs, context) ?: return@NodeExecutor NodeValue.Empty
        val chunks = provider.search(
            KnowledgeQuery(
                text = query,
                limit = node.params["limit"]?.toIntOrNull() ?: 6,
                rerank = node.params["rerank"]?.toBooleanStrictOrNull() ?: true,
            ),
        )
        NodeValue.Fragments(
            chunks.map { chunk ->
                ContextFragment(
                    source = FragmentSource.KNOWLEDGE,
                    text = chunk.text,
                    label = chunk.metadata["title"] ?: chunk.documentId,
                    relevance = chunk.score,
                )
            },
        )
    }

    private fun contextBuild() = NodeExecutor { _, inputs, context ->
        val fragments = mutableListOf<ContextFragment>()
        // Deliberately *not* also added as a SYSTEM fragment here. Every
        // runtime already receives it as GenerationRequest.systemPrompt and
        // puts it where that runtime's API wants it — a "system" role message
        // for an OpenAI-compatible endpoint, the system slot of the model's
        // own chat template for llama.cpp. Adding it to the context as well
        // sent it twice in the same request, once properly and once as a
        // heading inside the user turn, paying for those tokens on every
        // turn and giving the model the same instructions in two places at
        // two different levels of authority.

        // The ordinary "does the model remember what I just said" case — as
        // opposed to MEMORY_SEARCH, which only runs for an explicit recall
        // ("напомни", "что мы решили вчера") and searches across
        // conversations, not just this one's immediate back-and-forth.
        if (context.history.isNotEmpty()) {
            fragments += ContextFragment(
                source = FragmentSource.CONVERSATION,
                text = context.history.joinToString("\n") { "${it.role}: ${it.text}" },
            )
        }

        if (context.attachedDocuments.isNotEmpty()) {
            fragments += ContextFragment(
                source = FragmentSource.KNOWLEDGE,
                text = "Пользователь прикрепил файлы: ${context.attachedDocuments.joinToString(", ")}. " +
                    "Их содержимое доступно через фрагменты ниже, если они относятся к вопросу.",
            )
        }

        val images = mutableListOf<ImageRef>()
        // vision()'s degrade-gracefully path (see its own comment) can hand
        // this node a Bundle carrying both the raw image and whatever
        // description/OCR text a vision model produced for it — flattened
        // one level here rather than teaching every consumer of `inputs`
        // about Bundle, since this is currently the only place that produces one.
        for (value in inputs.flatMap { if (it is NodeValue.Bundle) it.values else listOf(it) }) {
            when (value) {
                is NodeValue.Fragments -> fragments += value.fragments
                is NodeValue.Speech -> fragments += ContextFragment(
                    source = FragmentSource.TRANSCRIPT,
                    text = value.transcript.text,
                    relevance = value.transcript.confidence ?: 0.0,
                )

                is NodeValue.Text -> fragments += ContextFragment(FragmentSource.USER_MESSAGE, value.text)
                is NodeValue.Image -> images += value.ref
                else -> Unit
            }
        }
        context.userMessage
            ?.takeIf { message -> fragments.none { it.source == FragmentSource.USER_MESSAGE && it.text == message } }
            ?.let { fragments += ContextFragment(FragmentSource.USER_MESSAGE, it) }

        NodeValue.Context(contextEngine.assemble(fragments, contextWindowTokens, images))
    }

    private fun textGeneration() = NodeExecutor { node, inputs, context ->
        val assembled = inputs.filterIsInstance<NodeValue.Context>().firstOrNull()?.context
            ?: throw IllegalStateException("text_generation received no assembled context")

        val capability = node.params["capability"]
            ?.let { runCatching { Capability.fromId(it) }.getOrNull() }
        val selected = selector.selectAny(listOfNotNull(capability, Capability.TEXT_GENERATION))

        val text = runtimeManager.withModel(selected.model, selected.binding) { loaded ->
            val handle = loaded as? TextModelHandle
                ?: throw ModelLoadException("${selected.model.id} did not load as a text model")
            // Collected chunk by chunk, not .toList().joinToString(""): every
            // runtime already streams token by token underneath (that's the
            // whole point of Flow<String> here), but folding it into one
            // string before returning threw that streaming away — the UI
            // only ever saw the complete answer at the very end, with
            // nothing to show for however long generation actually took.
            // onPartialText is what lets ChatActivity render the same
            // progressive output a runtime like llama.cpp already produces.
            val buffer = StringBuilder()
            handle.generate(
                GenerationRequest(
                    prompt = assembled.render(),
                    systemPrompt = systemPrompt,
                    maxTokens = node.params["max_tokens"]?.toIntOrNull() ?: defaultMaxTokens,
                    temperature = node.params["temperature"]?.toDoubleOrNull() ?: defaultTemperature,
                    topP = node.params["top_p"]?.toDoubleOrNull() ?: defaultTopP,
                    topK = node.params["top_k"]?.toIntOrNull() ?: defaultTopK,
                    repeatPenalty = node.params["repeat_penalty"]?.toDoubleOrNull() ?: defaultRepeatPenalty,
                    images = assembled.images,
                ),
            ).collect { chunk ->
                buffer.append(chunk)
                context.onPartialText?.invoke(buffer.toString())
            }
            buffer.toString()
        }
        NodeValue.Text(text)
    }

    private fun memoryUpdate() = NodeExecutor { node, inputs, context ->
        val provider = memory ?: return@NodeExecutor inputs.firstOrNull() ?: NodeValue.Empty
        val answer = inputs.filterIsInstance<NodeValue.Text>().firstOrNull()?.text
        val scope = node.params["scope"]
            ?.let { name -> MemoryScope.entries.firstOrNull { it.name.equals(name.trim(), true) } }
            ?: MemoryScope.WORKING

        context.userMessage?.let {
            provider.remember(it, scope, mapOf(CONVERSATION_KEY to context.conversationId, ROLE_KEY to "user"))
        }
        answer?.let {
            provider.remember(it, scope, mapOf(CONVERSATION_KEY to context.conversationId, ROLE_KEY to "assistant"))
        }
        inputs.firstOrNull() ?: NodeValue.Empty
    }

    /** The text a retrieval stage searches for: the transcript when there was one, else the typed message. */
    private fun queryText(inputs: List<NodeValue>, context: RunContext): String? =
        inputs.filterIsInstance<NodeValue.Speech>().firstOrNull()?.transcript?.text
            ?: inputs.filterIsInstance<NodeValue.Text>().firstOrNull()?.text
            ?: context.userMessage

    private companion object {
        const val CONVERSATION_KEY = "conversationId"
        const val ROLE_KEY = "role"
    }
}

/** Convenience for callers that only have a URI. */
fun audioInput(uri: String, durationMs: Long? = null): NodeValue = NodeValue.Audio(AudioRef(uri, durationMs))

fun imageInput(uri: String): NodeValue = NodeValue.Image(ImageRef(uri))

fun textInput(text: String): NodeValue = NodeValue.Text(text)
