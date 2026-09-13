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
    private val gate: ApiV2Gate,
) {
    suspend fun refresh(id: Long, owner: AuthScopeSnapshot): ApiResult<Unit> {
        if (id <= 0) return ApiResult.Error(422, "validation_failed", "Invalid person identity.")
        if (owner.profileId.isNullOrBlank()) return identityChanged()
        return ownedV2Call<JsonObject, Unit>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.Accepted, { scope ->
            client.post("/api/v2/catalog/people/$id/refresh") { authScope(scope!!); requireSiloAuth(); singleAttempt() }
        }) { receipt ->
            require(receipt["status"] == JsonPrimitive("queued") && receipt["person_id"] == JsonPrimitive(id.toString())) {
                "The server did not acknowledge this person refresh."
            }
        }
    }
    suspend fun detail(id: Long, owner: AuthScopeSnapshot): ApiResult<Person> {
        if (id <= 0) return ApiResult.Error(422, "validation_failed", "Invalid person identity.")
        if (owner.profileId.isNullOrBlank()) return identityChanged()
        return ownedV2Call<PersonReadV2, Person>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/catalog/people/$id") { authScope(scope!!); requireSiloAuth() }
        }) { wire ->
            val person = wire.toDomain()
            require(person.id == id) { "The server returned a different person." }
            person
        }
    }
}
