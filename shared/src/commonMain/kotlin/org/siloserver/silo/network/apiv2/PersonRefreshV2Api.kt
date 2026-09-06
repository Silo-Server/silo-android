package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.network.*

/** Viewer refresh acknowledges queue admission only; it is not a durable job. */
class PersonRefreshV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted,
) {
    private suspend fun current(owner: AuthScopeSnapshot): Boolean {
        val now = tokens.snapshotCurrentScope()
        return owner.isSameIdentityAs(now) && owner.serverUrl == now?.serverUrl &&
            owner.profileId == now?.profileId && owner.profileToken == now?.profileToken &&
            owner.credentialGenerationId == now?.credentialGenerationId && !owner.profileId.isNullOrBlank()
    }
    private fun changed() = ApiResult.Error(0, "person_authority_changed", "The person view's identity changed.")
    suspend fun refresh(id: Long, owner: AuthScopeSnapshot): ApiResult<Unit> {
        if (id <= 0) return ApiResult.Error(422, "validation_failed", "Invalid person identity.")
        if (!current(owner)) return changed()
        val result = safeApiV2Call<JsonObject>(gate) {
            client.post("/api/v2/catalog/people/$id/refresh") {
                authScope(owner); requireSiloAuth(); singleAttempt()
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.Accepted) }
        }
        if (!current(owner)) return changed()
        return when (result) {
            is ApiResult.Success -> if (result.data["status"] == JsonPrimitive("queued") &&
                result.data["person_id"] == JsonPrimitive(id.toString())) ApiResult.Success(Unit)
                else ApiResult.Error(0, "invalid_person_refresh_receipt", "The server did not acknowledge this person refresh.")
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
    suspend fun detail(id: Long, owner: AuthScopeSnapshot): ApiResult<Person> {
        if (id <= 0) return ApiResult.Error(422, "validation_failed", "Invalid person identity.")
        if (!current(owner)) return changed()
        val result = safeApiV2Call<PersonReadV2>(gate) {
            client.get("/api/v2/catalog/people/$id") { authScope(owner); requireSiloAuth() }
                .also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!current(owner)) return changed()
        return when (result) {
            is ApiResult.Success -> try {
                val person = result.data.toDomain()
                check(person.id == id)
                ApiResult.Success(person)
            } catch (_: Exception) { ApiResult.Error(0, "invalid_person", "The server returned a different or unsupported person.") }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
}
