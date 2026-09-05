package org.siloserver.silo.network.apiv2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.siloserver.silo.network.SiloJson

/** Reads one vendored file under `api/v2/fixtures/` from the test resources. */
expect fun readApiV2FixtureResource(name: String): String

/** One entry of the vendored `index.json` (the members these tests consume). */
@Serializable
data class ApiV2FixtureEntry(
    val name: String,
    @SerialName("operation_id") val operationId: String? = null,
    @SerialName("expected_status") val expectedStatus: Int,
    @SerialName("response_media_type") val responseMediaType: String,
    @SerialName("body_file") val bodyFile: String,
    val request: ApiV2FixtureRequest,
)

@Serializable
data class ApiV2FixtureRequest(
    val method: String,
    val path: String,
    val body: String? = null,
)

@Serializable
data class ApiV2FixtureIndex(val fixtures: List<ApiV2FixtureEntry>)

object ApiV2Fixtures {
    /** The production decoder; every assertion in these tests runs through it. */
    val json = SiloJson

    val index: ApiV2FixtureIndex by lazy {
        json.decodeFromString(ApiV2FixtureIndex.serializer(), readApiV2FixtureResource("index.json"))
    }

    fun body(name: String): String = readApiV2FixtureResource("$name.json")

    fun bodyObject(name: String): JsonObject = json.parseToJsonElement(body(name)).jsonObject

    /** The vendored SOURCE file's `key=value` header lines. */
    val source: Map<String, String> by lazy {
        readApiV2FixtureResource("SOURCE").lineSequence()
            .takeWhile { it.isNotBlank() }
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
    }

    /** Copies [base] with [overrides] applied (a null value removes the member). */
    fun with(base: JsonObject, vararg overrides: Pair<String, JsonElement?>): JsonObject {
        val members = LinkedHashMap<String, JsonElement>(base)
        overrides.forEach { (key, value) -> if (value == null) members.remove(key) else members[key] = value }
        return JsonObject(members)
    }

    fun JsonObject.plusUnknown(): JsonObject = with(
        this,
        "zz_unknown_member" to JsonObject(mapOf("nested" to JsonPrimitive(true))),
        "zz_unknown_scalar" to JsonPrimitive("later-contract"),
    )

    inline fun <reified T> decode(element: JsonObject): T = json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), element)
}
