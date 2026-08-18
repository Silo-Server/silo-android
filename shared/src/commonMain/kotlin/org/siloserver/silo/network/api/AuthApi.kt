package org.siloserver.silo.network.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.siloserver.silo.model.auth.*
import org.siloserver.silo.network.ApiErrorBody
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.skipSiloAuth

class AuthApi(private val client: HttpClient) {

    suspend fun login(request: LoginRequest): ApiResult<LoginResponse> = safeApiCall {
        client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun refresh(request: RefreshRequest): ApiResult<RefreshResponse> = safeApiCall {
        client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun signup(request: SignupRequest): ApiResult<LoginResponse> = safeApiCall {
        client.post("/api/v1/auth/signup") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun setup(
        username: String,
        email: String,
        password: String
    ): ApiResult<LoginResponse> = safeApiCall {
        client.post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(SetupRequest(username = username, email = email, password = password))
        }
    }

    suspend fun getSetupStatus(): ApiResult<SetupStatusResponse> = safeApiCall {
        // Public, exactly like the explicit-server variant below — which
        // already opted out. Without this the relative form carries a bearer
        // it never needed, and a dead session would fail it.
        client.get("/api/v1/auth/setup") { skipSiloAuth() }
    }

    suspend fun getSetupStatus(serverUrl: String): ApiResult<SetupStatusResponse> = safeApiCall {
        client.get("${serverUrl.trimEnd('/')}/api/v1/auth/setup") {
            skipSiloAuth()
        }
    }

    suspend fun getSignupStatus(): ApiResult<SignupStatusResponse> = safeApiCall {
        client.get("/api/v1/auth/signup") { skipSiloAuth() }
    }

    suspend fun getSignupStatus(serverUrl: String): ApiResult<SignupStatusResponse> = safeApiCall {
        client.get("${serverUrl.trimEnd('/')}/api/v1/auth/signup") {
            skipSiloAuth()
        }
    }

    /**
     * Resolves an emailed-invitation claim token against the given server.
     * Unauthenticated: this runs before any account exists.
     */
    suspend fun lookupInvitation(
        serverUrl: String,
        token: String,
    ): ApiResult<InvitationLookupResponse> = safeApiCall {
        // The token arrives from an emailed link and is not ours to trust as
        // path-safe: a '/' or '?' in it would otherwise re-shape the request.
        client.get("${serverUrl.trimEnd('/')}/api/v1/invitations/${token.encodeURLPathPart()}") {
            skipSiloAuth()
        }
    }

    /**
     * Accepts an invitation: creates the account (username = the invitation's
     * email) and returns a normal login response.
     */
    suspend fun acceptInvitation(
        serverUrl: String,
        token: String,
        password: String,
    ): ApiResult<LoginResponse> = safeApiCall {
        client.post("${serverUrl.trimEnd('/')}/api/v1/invitations/${token.encodeURLPathPart()}/accept") {
            skipSiloAuth()
            contentType(ContentType.Application.Json)
            setBody(AcceptInvitationRequest(password = password))
        }
    }

    suspend fun getMe(): ApiResult<User> = safeApiCall {
        client.get("/api/v1/auth/me")
    }

    suspend fun logout(): ApiResult<Unit> = safeApiCall {
        client.post("/api/v1/auth/logout")
    }

}

/**
 * Checks membership endpoints that return HTTP 204 (present) / 404 (absent) with no body.
 *
 * - 2xx  → [ApiResult.Success]`(true)`
 * - 404  → [ApiResult.Success]`(false)`
 * - other HTTP errors → [ApiResult.Error]
 * - network failures  → [ApiResult.NetworkError]
 */
internal suspend fun safeStatusCall(
    block: suspend () -> HttpResponse
): ApiResult<Boolean> {
    return try {
        val response = block()
        when {
            response.status.isSuccess() -> ApiResult.Success(true)
            response.status == HttpStatusCode.NotFound -> ApiResult.Success(false)
            else -> {
                val error = try {
                    response.body<ApiErrorBody>()
                } catch (_: Exception) {
                    ApiErrorBody()
                }
                ApiResult.Error(response.status.value, error.error, error.message)
            }
        }
    } catch (e: Exception) {
        ApiResult.NetworkError(e)
    }
}

/**
 * Wraps an HTTP call in error handling, returning a typed [ApiResult].
 *
 * On success (2xx), deserializes the response body to [T].
 * On HTTP error, attempts to parse a standard [ApiErrorBody].
 * On network/parsing exceptions, returns [ApiResult.NetworkError].
 */
internal suspend inline fun <reified T> safeApiCall(
    block: () -> HttpResponse
): ApiResult<T> {
    return try {
        val response = block()
        if (response.status.isSuccess()) {
            // Handle Unit return type for endpoints that return no body
            if (T::class == Unit::class) {
                @Suppress("UNCHECKED_CAST")
                ApiResult.Success(Unit as T)
            } else {
                ApiResult.Success(response.body<T>())
            }
        } else {
            val error = try {
                response.body<ApiErrorBody>()
            } catch (_: Exception) {
                ApiErrorBody()
            }
            ApiResult.Error(response.status.value, error.error, error.message)
        }
    } catch (e: Exception) {
        ApiResult.NetworkError(e)
    }
}
