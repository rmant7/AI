package ai.localstudio.core.pipeline

import ai.localstudio.core.registry.ModelCatalog
import kotlinx.serialization.json.Json

/**
 * JSON is the storage format for pipelines and for the model catalog: both are
 * user-visible, diffable and shareable between the phone and the development
 * environment.
 */
object PipelineCodec {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun decodePipeline(text: String): PipelineSpec = json.decodeFromString(PipelineSpec.serializer(), text)

    fun encodePipeline(spec: PipelineSpec): String = json.encodeToString(PipelineSpec.serializer(), spec)

    fun decodeCatalog(text: String): ModelCatalog = json.decodeFromString(ModelCatalog.serializer(), text)

    fun encodeCatalog(catalog: ModelCatalog): String = json.encodeToString(ModelCatalog.serializer(), catalog)
}
