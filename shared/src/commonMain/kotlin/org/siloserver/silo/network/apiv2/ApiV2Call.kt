package org.siloserver.silo.network.apiv2

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager

/**
 * Wraps one v2 exchange: a 2xx body decodes to [T] with the production
 * [SiloJson]; a non-2xx body is read as a [Problem] and surfaced as
 * [ApiResult.Error] (`error` = the problem code, `message` = its detail).
 * Nothing here retries.
 */
internal suspend inline fun <reified T> safeApiV2Call(
    gate: ApiV2Gate,
    block: () -> HttpResponse,
): ApiResult<T> {
    gate.blocked()?.let { return it }
    return try {
        val response = block()
        if (response.status.isSuccess()) {
            if (T::class == Unit::class) {
                @Suppress("UNCHECKED_CAST")
                ApiResult.Success(Unit as T)
            } else ApiResult.Success(SiloJson.decodeFromString(response.bodyAsText()))
        } else {
            response.toApiV2Error()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiResult.NetworkError(e)
    }
}

internal suspend fun HttpResponse.toApiV2Error(): ApiResult.Error {
    val problem = try {
        SiloJson.decodeFromString(Problem.serializer(), bodyAsText())
    } catch (_: Exception) {
        null
    }
    return ApiResult.Error(
        code = status.value,
        error = problem?.code ?: "",
        message = problem?.detail ?: "",
    )
}

/** How much of a captured [AuthScopeSnapshot] must still match the active one for a call to be owned. */
enum class OwnerPolicy {
    /** Same server, identity generation and credential epoch ([AuthScopeSnapshot.isSameIdentityAs]). */
    IDENTITY,
    /** [IDENTITY] plus the same profile id and profile token. */
    PROFILE,
    /** [PROFILE] plus the same server URL and credential generation. */
    FULL,
}

/** Whether this snapshot still owns the active scope of [tokens] at [policy] strength. */
internal suspend fun AuthScopeSnapshot.stillOwns(tokens: TokenManager?, policy: OwnerPolicy = OwnerPolicy.PROFILE): Boolean =
    matches(tokens?.snapshotCurrentScope(), policy)

/** Whether [now] is this snapshot's scope at [policy] strength. */
internal fun AuthScopeSnapshot.matches(now: AuthScopeSnapshot?, policy: OwnerPolicy): Boolean {
    if (!isSameIdentityAs(now)) return false
    return when (policy) {
        OwnerPolicy.IDENTITY -> true
        OwnerPolicy.PROFILE -> profileId == now?.profileId && profileToken == now?.profileToken
        OwnerPolicy.FULL -> profileId == now?.profileId && profileToken == now?.profileToken &&
            serverUrl == now?.serverUrl && credentialGenerationId == now?.credentialGenerationId
    }
}

/** The active scope when it names a profile; the owner a profile-scoped v2 read is pinned to. */
internal suspend fun TokenManager.captureProfileScope(): AuthScopeSnapshot? =
    snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }

/** The one error every v2 call returns when its owning identity moved before or during the exchange. */
internal fun identityChanged(): ApiResult.Error =
    ApiResult.Error(0, "identity_changed", "The acting account or profile changed.")

internal fun invalidResponse(detail: String? = null): ApiResult.Error =
    ApiResult.Error(0, "invalid_response", detail?.takeIf { it.isNotBlank() } ?: "The server returned an unsupported response.")

/**
 * One identity-guarded v2 exchange: gate, pre-guard [owner], call [block],
 * assert [expected] on success, `ensureActive`, post-guard, decode the 2xx
 * body as [T], then [project] it. Any owner change is [identityChanged];
 * a received response that cannot be decoded, has the wrong success
 * status, or fails [project] with an `IllegalArgument`/`IllegalState`
 * exception is [invalidResponse]. A null [owner] skips the guards.
 */
internal suspend inline fun <reified T, R> ownedV2Call(
    gate: ApiV2Gate,
    tokens: TokenManager?,
    owner: AuthScopeSnapshot?,
    policy: OwnerPolicy = OwnerPolicy.PROFILE,
    expected: HttpStatusCode? = null,
    crossinline block: suspend (AuthScopeSnapshot?) -> HttpResponse,
    crossinline project: (T) -> R,
): ApiResult<R> {
    gate.blocked()?.let { return it }
    if (owner != null && !owner.stillOwns(tokens, policy)) return identityChanged()
    var received = false
    val result = safeApiV2Call<T>(gate) {
        block(owner).also {
            received = true
            check(expected == null || !it.status.isSuccess() || it.status == expected) { "Unexpected status ${it.status.value}." }
        }
    }
    currentCoroutineContext().ensureActive()
    if (owner != null && !owner.stillOwns(tokens, policy)) return identityChanged()
    return when (result) {
        is ApiResult.Success -> try {
            ApiResult.Success(project(result.data))
        } catch (e: IllegalArgumentException) {
            invalidResponse(e.message)
        } catch (e: IllegalStateException) {
            invalidResponse(e.message)
        }
        is ApiResult.Error -> result
        is ApiResult.NetworkError ->
            if (received && result.exception !is IOException) invalidResponse(result.exception.message) else result
    }
}

/** A v2 string id that is a canonical positive integer; anything else is an invalid response. */
internal fun checkedPositiveId(text: String): Int {
    val id = text.toIntOrNull()
    require(id != null && id > 0 && id.toString() == text) { "Unsupported identifier." }
    return id
}
