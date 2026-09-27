package ai.localstudio.core.engine

import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.pipeline.NodeValue

/** Convenience for callers that only have a URI. */
fun audioInput(uri: String, durationMs: Long? = null): NodeValue = NodeValue.Audio(AudioRef(uri, durationMs))

fun imageInput(uri: String): NodeValue = NodeValue.Image(ImageRef(uri))

fun textInput(text: String): NodeValue = NodeValue.Text(text)
