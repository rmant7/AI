package ai.localstudio.app.models.catalog

import ai.localstudio.model.CatalogDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Inputs both catalogue test classes share; paths come from app/build.gradle.kts. */
object LegacyCatalog {

    private fun path(property: String): File =
        File(requireNotNull(System.getProperty(property)) { "system property $property not set — run through Gradle" })

    val snapshotFile: File get() = path("localai.catalogSnapshot")

    val updateSnapshot: Boolean get() = System.getProperty("localai.updateCatalogSnapshot") == "true"

    /** The codes of madlad_languages.json, read the way MadladLanguages reads the asset. */
    val madladCodes: List<String> by lazy {
        Json.parseToJsonElement(path("localai.madladLanguages").readText())
            .jsonObject.getValue("languages").jsonArray
            .map { it.jsonObject.getValue("code").jsonPrimitive.content }
    }

    val document: CatalogDocument by lazy { LegacyCatalogMapper.bundledDocument(madladCodes) }
}
