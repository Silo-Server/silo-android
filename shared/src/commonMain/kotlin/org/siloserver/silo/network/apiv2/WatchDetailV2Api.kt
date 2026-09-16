package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.network.*

/** Optional watch metadata; callers retain their original owner across local work. */
class WatchDetailV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    suspend fun capture(): AuthScopeSnapshot? = tokens.captureProfileScope()
    suspend fun current(owner: AuthScopeSnapshot): Boolean = owner.stillOwns(tokens, OwnerPolicy.FULL)
    suspend fun detail(id: String, owner: AuthScopeSnapshot): ApiResult<WatchDetail> =
        ownedV2Call<JsonObject, WatchDetail>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/watch/${id.encodeURLPathPart()}") { authScope(scope!!); requireSiloAuth() }
        }) { decodeWatchDetail(it, id) }
}

/** Adapt only fields consumed by WatchDetail; do not change the legacy model wire contract. */
private fun decodeWatchDetail(body: JsonObject, id: String): WatchDetail {
    val content = body["content_id"] as? JsonPrimitive
    check(content?.isString == true && content.content == id)
    fun numericId(value: JsonElement): JsonPrimitive {
        val text = value as? JsonPrimitive ?: error("Missing file identity")
        check(text.isString)
        return JsonPrimitive(checkedPositiveId(text.content))
    }
    val versions = body["versions"] as? JsonArray ?: error("Missing versions")
    val adapted = body.toMutableMap()
    adapted["versions"] = JsonArray(versions.map { value ->
        val row = value.jsonObject.toMutableMap()
        row["file_id"] = numericId(row.getValue("file_id"))
        val duration = row["duration_seconds"] as? JsonPrimitive ?: error("Missing duration")
        check(!duration.isString && duration.double.isFinite() && duration.double >= 0)
        row["duration"] = duration
        JsonObject(row)
    })
    for (key in listOf("intro", "credits", "recap", "preview")) {
        val marker = body[key]?.takeUnless { it is JsonNull }?.jsonObject ?: continue
        adapted[key] = buildJsonObject {
            put("start", marker.getValue("start_seconds"))
            put("end", marker.getValue("end_seconds"))
        }
    }
    body["user_data"]?.takeUnless { it is JsonNull }?.jsonObject?.let { data ->
        val row = data.toMutableMap()
        row["last_file_id"]?.takeUnless { it is JsonNull }?.let { row["last_file_id"] = numericId(it) }
        adapted["user_data"] = JsonObject(row)
    }
    return SiloJson.decodeFromJsonElement(JsonObject(adapted))
}
