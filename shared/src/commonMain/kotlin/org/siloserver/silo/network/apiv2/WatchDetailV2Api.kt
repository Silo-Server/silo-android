package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.network.*

/** Optional watch metadata; callers retain their original owner across local work. */
class WatchDetailV2Api(private val client: HttpClient, private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted) {
    suspend fun capture() = tokens.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }
    suspend fun current(owner: AuthScopeSnapshot): Boolean {
        val now = capture()
        currentCoroutineContext().ensureActive()
        return owner.isSameIdentityAs(now) && owner.serverUrl == now?.serverUrl &&
            owner.profileId == now?.profileId && owner.profileToken == now?.profileToken &&
            owner.credentialGenerationId == now?.credentialGenerationId
    }
    suspend fun detail(id: String, owner: AuthScopeSnapshot): ApiResult<WatchDetail> {
        if (!current(owner)) return changed()
        val result = safeApiV2Call<JsonObject>(gate) {
            client.get("/api/v2/watch/${id.encodeURLPathPart()}") { authScope(owner); requireSiloAuth() }
                .also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!current(owner)) return changed()
        return when (result) {
            is ApiResult.Success -> try { ApiResult.Success(decodeWatchDetail(result.data, id)) }
                catch (_: Exception) { ApiResult.Error(0, "invalid_watch_detail", "Unsupported watch metadata.") }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
    private fun changed() = ApiResult.Error(0, "watch_authority_changed", "The initiating watch identity changed.")
}

/** Adapt only fields consumed by WatchDetail; do not change the legacy model wire contract. */
internal fun decodeWatchDetail(body: JsonObject, id: String): WatchDetail {
    val content = body["content_id"] as? JsonPrimitive
    check(content?.isString == true && content.content == id)
    fun numericId(value: JsonElement): JsonPrimitive {
        val text = value as? JsonPrimitive ?: error("Missing file identity")
        check(text.isString)
        val number = text.content.toIntOrNull() ?: error("Unsupported file identity")
        check(number > 0 && number.toString() == text.content)
        return JsonPrimitive(number)
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
