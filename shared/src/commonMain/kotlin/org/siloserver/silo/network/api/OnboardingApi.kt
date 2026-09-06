package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.model.onboarding.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.safeApiV2Call

/** State validators belong to the captured profile and serialize progress writes. */
class OnboardingApi(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted,
) {
    private data class Confirmed(val scope: AuthScopeSnapshot, val state: OnboardingState, val etag: String)
    private val stateLock = Mutex()
    private var confirmed: Confirmed? = null

    private suspend fun current(scope: AuthScopeSnapshot): Boolean =
        !scope.profileId.isNullOrBlank() && scope.isSameIdentityAs(tokens.snapshotCurrentScope())

    private fun changed() = ApiResult.Error(0, "identity_changed", "The onboarding account or profile changed.")

    suspend fun getFlow(surface: String, scope: AuthScopeSnapshot): ApiResult<OnboardingFlow> {
        if (!current(scope)) return changed()
        val result = safeApiV2Call<OnboardingFlow>(gate) {
            client.get("/api/v2/onboarding/flow") {
                authScope(scope); requireSiloAuth(); parameter("surface", surface)
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        return if (current(scope)) result else changed()
    }

    suspend fun getState(scope: AuthScopeSnapshot): ApiResult<OnboardingState> = stateLock.withLock {
        if (!current(scope)) return@withLock changed()
        confirmed = null
        stateExchange(scope)
    }

    suspend fun putProgress(request: OnboardingProgressRequest, scope: AuthScopeSnapshot): ApiResult<Unit> = stateLock.withLock {
        if (!current(scope)) return@withLock changed()
        val before = confirmed?.takeIf { it.scope == scope && it.state.tourId == request.tourId }
            ?: return@withLock ApiResult.Error(0, "onboarding_state_required", "Read the current tour state before updating progress.")
        // Consume before dispatch. Cancellation, 401, conflict, malformed receipt
        // and uncertain delivery cannot reuse or silently refresh this validator.
        confirmed = null
        stateExchange(scope, request, before.etag).map { Unit }
    }

    private suspend fun stateExchange(
        scope: AuthScopeSnapshot,
        request: OnboardingProgressRequest? = null,
        etag: String? = null,
    ): ApiResult<OnboardingState> {
        var receivedTag: String? = null
        val result = safeApiV2Call<OnboardingState>(gate) {
            client.request(if (request == null) "/api/v2/onboarding/state" else "/api/v2/onboarding/progress") {
                method = if (request == null) HttpMethod.Get else HttpMethod.Put
                authScope(scope); requireSiloAuth()
                if (request != null) {
                    singleAttempt()
                    header(HttpHeaders.IfMatch, requireNotNull(etag))
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            }.also {
                check(!it.status.isSuccess() || it.status == HttpStatusCode.OK)
                receivedTag = it.headers[HttpHeaders.ETag]
            }
        }
        if (!current(scope)) return changed()
        if (result is ApiResult.Success) {
            val tag = receivedTag
            if (tag == null || !strongTag(tag) ||
                (request != null && (result.data.tourId != request.tourId ||
                    ((request.completed || request.skipped) && !result.data.done)))) {
                return ApiResult.Error(0, "invalid_onboarding_state", "The server returned an invalid tour receipt.")
            }
            confirmed = Confirmed(scope, result.data, tag)
        }
        return result
    }

    private fun strongTag(tag: String): Boolean = tag.length >= 2 && tag.first() == '"' && tag.last() == '"' &&
        tag.substring(1, tag.lastIndex).all { it != '"' && it.code >= 0x21 && it.code != 0x7f }
}
