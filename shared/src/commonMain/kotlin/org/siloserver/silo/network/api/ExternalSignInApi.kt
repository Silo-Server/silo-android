package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.siloserver.silo.model.auth.AccountIdentities
import org.siloserver.silo.model.auth.AccountIdentity
import org.siloserver.silo.model.auth.AccountIdentityLinkTicket
import org.siloserver.silo.model.auth.ExternalSignInCapabilities
import org.siloserver.silo.model.auth.LoginResponse
import org.siloserver.silo.model.auth.NetworkIdentity
import org.siloserver.silo.model.auth.OAuthHandshakeCapabilities
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.model.auth.SignInProviders
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.apiv2.Problem
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.safeApiV2Call
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.freshSiloAuth
import org.siloserver.silo.network.map
import org.siloserver.silo.network.singleAttempt
import org.siloserver.silo.network.skipSiloAuth

/**
 * External sign-in (OIDC, LDAP, network identity) operations the apps use:
 * provider discovery, the native OAuth handoff's completion, the network
 * identity sign-in, and the account's Sign-in section.
 * Contract: silo-server `docs/auth-api.md` ("External sign-in", "OAuth sign-in
 * flows"). Nothing here retries; the completions are single use.
 */
interface ExternalSignInApi {
    /** `listAuthProviders` at [serverUrl], unauthenticated. */
    suspend fun listProviders(serverUrl: String): ApiResult<SignInProviders>

    /** `getOAuthHandshakeCapabilities` at [serverUrl], unauthenticated. */
    suspend fun oauthCapabilities(serverUrl: String): ApiResult<OAuthHandshakeCapabilities>

    /**
     * `getExternalSignInCapabilities` at [scope]'s server: whether it serves
     * the account's identities (`listAccountIdentities`, `deleteAccountIdentity`).
     */
    suspend fun externalSignInCapabilities(scope: AuthScopeSnapshot): ApiResult<ExternalSignInCapabilities>

    /** `completeOAuthLogin` for a native code: the token pair and the account. */
    suspend fun completeOAuthLogin(serverUrl: String, code: String, codeVerifier: String): ApiResult<LoginResponse>

    /**
     * `signInWithNetworkIdentity`: POST `{}` to [signInPath] (base-relative,
     * from provider discovery) on the saved base [serverUrl], unauthenticated.
     * Answers `login`'s token pair and account. Never retried: each attempt
     * spends the server's login rate-limit budget.
     */
    suspend fun signInWithNetworkIdentity(serverUrl: String, signInPath: String): ApiResult<LoginResponse>

    /** `listAccountIdentities` for [scope]'s account. */
    suspend fun listIdentities(scope: AuthScopeSnapshot): ApiResult<AccountIdentities>

    /** `deleteAccountIdentity`: disconnect one of [scope]'s identities. */
    suspend fun deleteIdentity(scope: AuthScopeSnapshot, identityId: String): ApiResult<Unit>

    /** `createAccountIdentityLinkTicket` after the account re-enters its local password. */
    suspend fun createLinkTicket(
        scope: AuthScopeSnapshot,
        installationId: String,
        password: String,
    ): ApiResult<AccountIdentityLinkTicket>

    /** `completeAccountIdentityLink`: confirm a native linking flow's code. */
    suspend fun completeLink(scope: AuthScopeSnapshot, code: String, codeVerifier: String): ApiResult<Unit>

    /**
     * `linkAccountIdentityWithCredentials`: link [scope]'s account to a
     * directory (LDAP) identity, confirming the account's local [password]
     * and signing in to the directory as [username] with [directoryPassword].
     */
    suspend fun linkWithCredentials(
        scope: AuthScopeSnapshot,
        installationId: String,
        password: String,
        username: String,
        directoryPassword: String,
    ): ApiResult<AccountIdentity>

    /**
     * `linkAccountIdentityWithNetwork`: link [scope]'s account to the person
     * who owns this device at network provider [installationId], confirming
     * the account's local [password]. The server allows it only through that
     * provider's network.
     */
    suspend fun linkWithNetwork(
        scope: AuthScopeSnapshot,
        installationId: String,
        password: String,
    ): ApiResult<AccountIdentity>
}

class DefaultExternalSignInApi(
    private val client: HttpClient,
    private val apiV2Gate: ApiV2Gate,
) : ExternalSignInApi {

    // Provider discovery is public and addressed by URL, often to a saved
    // server other than the active one, so the active server's update verdict
    // doesn't gate it (like the other URL-addressed sign-in reads). A server
    // without it answers 404, which leaves the password form alone.
    override suspend fun listProviders(serverUrl: String): ApiResult<SignInProviders> =
        safeApiV2Call<AuthProviderCollectionV2>(ApiV2Gate.Unrestricted) {
            client.get("${serverUrl.trimEnd('/')}/api/v2/auth/providers") { skipSiloAuth() }
        }.map { it.domain(serverUrl) }

    override suspend fun oauthCapabilities(serverUrl: String): ApiResult<OAuthHandshakeCapabilities> =
        safeApiV2Call<OAuthHandshakeCapabilitiesV2>(ApiV2Gate.Unrestricted) {
            client.get("${serverUrl.trimEnd('/')}/api/v2/auth/oauth/capabilities") { skipSiloAuth() }
        }.map { it.domain() }

    override suspend fun externalSignInCapabilities(scope: AuthScopeSnapshot): ApiResult<ExternalSignInCapabilities> =
        safeApiV2Call<ExternalSignInCapabilitiesV2>(apiV2Gate.forServer(scope.serverId)) {
            client.get("/api/v2/auth/external-sign-in/capabilities") { authScope(scope) }
        }.map { it.domain() }

    override suspend fun completeOAuthLogin(
        serverUrl: String,
        code: String,
        codeVerifier: String,
    ): ApiResult<LoginResponse> = safeApiV2Call<TokenPairV2>(apiV2Gate) {
        client.post("${serverUrl.trimEnd('/')}/api/v2/auth/oauth/complete") {
            skipSiloAuth(); singleAttempt()
            contentType(ContentType.Application.Json)
            setBody(CompleteOAuthLoginV2(code = code, codeVerifier = codeVerifier))
        }.requireAuthStatus(200)
    }.map { it.domain() }

    override suspend fun signInWithNetworkIdentity(serverUrl: String, signInPath: String): ApiResult<LoginResponse> {
        // Only the listed route, below the saved base: nothing is posted to another origin.
        val path = networkSignInPath(signInPath)
            ?: return ApiResult.Error(0, "invalid_request", "Not a network sign-in path.")
        return safeApiV2Call<TokenPairV2>(apiV2Gate) {
            client.post("${serverUrl.trimEnd('/')}$path") {
                skipSiloAuth(); singleAttempt()
                // The server refuses a sign-in without a JSON body (415), which
                // keeps a cross-site form post from signing a device in.
                contentType(ContentType.Application.Json)
                setBody(JsonObject(emptyMap()))
            }.requireAuthStatus(200)
        }.map { it.domain() }
    }

    override suspend fun listIdentities(scope: AuthScopeSnapshot): ApiResult<AccountIdentities> =
        safeApiV2Call<AccountIdentityCollectionV2>(apiV2Gate.forServer(scope.serverId)) {
            client.get("/api/v2/account/identities") { authScope(scope) }
        }.map { collection -> AccountIdentities(collection.items.map { it.domain() }, collection.canUnlink) }

    override suspend fun deleteIdentity(scope: AuthScopeSnapshot, identityId: String): ApiResult<Unit> =
        safeApiV2Call<Unit>(apiV2Gate.forServer(scope.serverId)) {
            client.delete("/api/v2/account/identities/${identityId.encodeURLPathPart()}") {
                authScope(scope); singleAttempt()
            }
        }

    override suspend fun createLinkTicket(
        scope: AuthScopeSnapshot,
        installationId: String,
        password: String,
    ): ApiResult<AccountIdentityLinkTicket> = locatedCall(scope, expected = 200, decode = { body ->
        SiloJson.decodeFromString(LinkTicketV2.serializer(), body).let {
            AccountIdentityLinkTicket(ticket = it.ticket, expiresAt = it.expiresAt)
        }
    }) {
        client.post("/api/v2/account/identities/link-ticket") {
            authScope(scope); freshSiloAuth(); singleAttempt()
            contentType(ContentType.Application.Json)
            setBody(LinkTicketRequestV2(installationId = installationId, password = password))
        }
    }

    override suspend fun completeLink(scope: AuthScopeSnapshot, code: String, codeVerifier: String): ApiResult<Unit> =
        safeApiV2Call<Unit>(apiV2Gate.forServer(scope.serverId)) {
            client.post("/api/v2/account/identities/link-complete") {
                authScope(scope); freshSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json)
                setBody(CompleteOAuthLoginV2(code = code, codeVerifier = codeVerifier))
            }
        }

    override suspend fun linkWithCredentials(
        scope: AuthScopeSnapshot,
        installationId: String,
        password: String,
        username: String,
        directoryPassword: String,
    ): ApiResult<AccountIdentity> = locatedCall(scope, expected = 201, decode = { body ->
        SiloJson.decodeFromString(AccountIdentityV2.serializer(), body).domain()
    }) {
        client.post("/api/v2/account/identities/link-credentials") {
            authScope(scope); freshSiloAuth(); singleAttempt()
            contentType(ContentType.Application.Json)
            setBody(
                LinkCredentialsRequestV2(
                    installationId = installationId,
                    password = password,
                    username = username,
                    directoryPassword = directoryPassword,
                ),
            )
        }
    }

    override suspend fun linkWithNetwork(
        scope: AuthScopeSnapshot,
        installationId: String,
        password: String,
    ): ApiResult<AccountIdentity> = locatedCall(scope, expected = 201, decode = { body ->
        SiloJson.decodeFromString(AccountIdentityV2.serializer(), body).domain()
    }) {
        client.post("/api/v2/account/identities/link-network") {
            authScope(scope); freshSiloAuth(); singleAttempt()
            contentType(ContentType.Application.Json)
            setBody(LinkNetworkRequestV2(installationId = installationId, password = password))
        }
    }

    /**
     * One v2 exchange whose 422s differ only by the member the problem names
     * (see [locatedError]); everything else is as [safeApiV2Call].
     */
    private suspend fun <T> locatedCall(
        scope: AuthScopeSnapshot,
        expected: Int,
        decode: (String) -> T,
        block: suspend () -> HttpResponse,
    ): ApiResult<T> {
        apiV2Gate.forServer(scope.serverId).blocked()?.let { return it }
        return try {
            val response = block()
            val body = response.bodyAsText()
            when {
                response.status.value == expected -> ApiResult.Success(decode(body))
                response.status.isSuccess() -> ApiResult.Error(0, "invalid_response", "Unexpected response status.")
                else -> locatedError(response.status.value, body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.NetworkError(e)
        }
    }

    companion object {
        /** A 422 at `body.password`: the account's local password is wrong. */
        const val WRONG_PASSWORD = "wrong_password"

        /** A 422 at `body.directory_password`: the directory refused the username and password. */
        const val DIRECTORY_REFUSED = "directory_refused"

        /**
         * The problem as an [ApiResult.Error] whose `error` is the problem
         * code, except for a 422 that names `body.directory_password`
         * ([DIRECTORY_REFUSED]) or `body.password` ([WRONG_PASSWORD]): those
         * two refusals share `validation_failed` and differ only by member.
         */
        internal fun locatedError(status: Int, body: String): ApiResult.Error {
            val problem = runCatching { SiloJson.decodeFromString(Problem.serializer(), body) }.getOrNull()
            val locations = problem?.errors.orEmpty().map { it.location }
            val code = when {
                status == 422 && "body.directory_password" in locations -> DIRECTORY_REFUSED
                status == 422 && "body.password" in locations -> WRONG_PASSWORD
                else -> problem?.code.orEmpty()
            }
            return ApiResult.Error(status, code, problem?.detail.orEmpty())
        }
    }
}

@Serializable
internal data class AuthProviderV2(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val mode: String,
    val default: Boolean = false,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("installation_id") val installationId: String? = null,
    @SerialName("native_start_path") val nativeStartPath: String? = null,
    @SerialName("network_sign_in_path") val networkSignInPath: String? = null,
    @SerialName("network_identity") val networkIdentity: AuthProviderNetworkIdentityV2? = null,
) {
    fun domain(serverUrl: String) = SignInProvider(
        id = id,
        displayName = displayName,
        mode = when (mode) {
            "credentials" -> SignInProvider.Mode.Credentials
            "oauth" -> SignInProvider.Mode.OAuth
            "network" -> SignInProvider.Mode.Network
            else -> SignInProvider.Mode.Unknown
        },
        isDefault = default,
        iconUrl = iconUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { absoluteUrl(it, serverUrl) },
        installationId = installationId?.takeIf { it.isNotBlank() },
        // Only the paths are kept: the app uses them on its own saved server base.
        nativeStartPath = nativeStartPath(nativeStartPath),
        networkSignInPath = networkSignInPath(networkSignInPath),
        networkIdentity = networkIdentity?.let { NetworkIdentity(displayName = it.displayName, username = it.username) },
    )
}

@Serializable
internal data class AuthProviderNetworkIdentityV2(
    @SerialName("display_name") val displayName: String = "",
    val username: String = "",
)

/**
 * The native start relative to the server base: `/api/v2/auth/oauth/{id}/native/start`
 * and any query, taken from `native_start_path`. An oauth provider without it
 * offers no native sign-in. Whatever comes before `/api/v2/` (a reverse
 * proxy's path prefix) is dropped: apps resolve the path against their own
 * saved base URL and never open another origin.
 * Null for anything that isn't that route.
 */
internal fun nativeStartPath(path: String?): String? = baseRelativeRoute(path, NATIVE_START_PREFIX, NATIVE_START_ROUTE)

/**
 * `signInWithNetworkIdentity` relative to the server base
 * (`/api/v2/auth/network/{id}/sign-in`), taken from `network_sign_in_path`
 * the way [nativeStartPath] takes the native start: a reverse proxy's path
 * prefix is dropped, and anything that isn't that route is null.
 */
internal fun networkSignInPath(path: String?): String? = baseRelativeRoute(path, NETWORK_SIGN_IN_PREFIX, NETWORK_SIGN_IN_ROUTE)

private fun baseRelativeRoute(path: String?, prefix: String, route: Regex): String? {
    val raw = path?.trim()
        ?.takeIf { it.startsWith("/") && !it.startsWith("//") }
        ?.substringBefore('#') ?: return null
    val start = raw.substringBefore('?').indexOf(prefix)
    if (start < 0) return null
    return raw.substring(start).takeIf { route.matches(it) }
}

private const val NATIVE_START_PREFIX = "/api/v2/auth/oauth/"
private val NATIVE_START_ROUTE = Regex("^/api/v2/auth/oauth/[A-Za-z0-9._~%-]+/native/start(\\?[^#\\s]*)?$")
private const val NETWORK_SIGN_IN_PREFIX = "/api/v2/auth/network/"
private val NETWORK_SIGN_IN_ROUTE = Regex("^/api/v2/auth/network/[A-Za-z0-9._~%-]+/sign-in$")

/** A site-relative icon path (`/api/v2/plugin-content/...`) resolves against the server that listed it. */
internal fun absoluteUrl(url: String, serverUrl: String): String? = when {
    url.startsWith("https://") || url.startsWith("http://") -> url
    url.startsWith("/") && !url.startsWith("//") -> serverUrl.trimEnd('/') + url
    else -> null
}

@Serializable
internal data class AuthProviderCollectionV2(
    val items: List<AuthProviderV2>,
    @SerialName("password_login") val passwordLogin: Boolean = true,
) {
    fun domain(serverUrl: String) = SignInProviders(
        providers = items.map { it.domain(serverUrl) },
        passwordLogin = passwordLogin,
    )
}

@Serializable
internal data class OAuthHandshakeCapabilitiesV2(
    val state: String? = null,
    val available: Boolean = false,
    val native: Boolean = false,
    val linking: Boolean = false,
    @SerialName("select_account") val selectAccount: Boolean = false,
) {
    fun domain(): OAuthHandshakeCapabilities {
        val usable = available && (state == null || state == "available")
        return OAuthHandshakeCapabilities(
            available = usable,
            native = usable && native,
            linking = usable && linking,
            selectAccount = usable && selectAccount,
        )
    }
}

@Serializable
internal data class ExternalSignInCapabilitiesV2(
    val state: String? = null,
    val available: Boolean = false,
    val identities: Boolean = false,
    @SerialName("credentials_linking") val credentialsLinking: Boolean = false,
    @SerialName("network_sign_in") val networkSignIn: Boolean = false,
) {
    fun domain(): ExternalSignInCapabilities {
        val usable = available && (state == null || state == "available")
        return ExternalSignInCapabilities(
            identities = usable && identities,
            credentialsLinking = usable && credentialsLinking,
            networkSignIn = usable && networkSignIn,
        )
    }
}

@Serializable
internal data class CompleteOAuthLoginV2(
    val code: String,
    @SerialName("code_verifier") val codeVerifier: String,
) {
    override fun toString(): String = "CompleteOAuthLoginV2(<redacted>)"
}

@Serializable
internal data class AccountIdentityV2(
    val id: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("provider_id") val providerId: String = "",
    @SerialName("provider_name") val providerName: String = "",
    val username: String = "",
    val email: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("linked_at") val linkedAt: String,
    @SerialName("last_sign_in_at") val lastSignInAt: String? = null,
) {
    fun domain() = AccountIdentity(
        id = id,
        installationId = installationId,
        providerId = providerId,
        providerName = providerName,
        username = username,
        email = email,
        displayName = displayName,
        linkedAt = linkedAt,
        lastSignInAt = lastSignInAt,
    )
}

@Serializable
internal data class AccountIdentityCollectionV2(
    val items: List<AccountIdentityV2>,
    @SerialName("can_unlink") val canUnlink: Boolean? = null,
)

@Serializable
internal data class LinkTicketRequestV2(
    @SerialName("installation_id") val installationId: String,
    val password: String,
) {
    override fun toString(): String = "LinkTicketRequestV2(installationId=$installationId, password=<redacted>)"
}

@Serializable
internal data class LinkTicketV2(
    val ticket: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    override fun toString(): String = "LinkTicketV2(<redacted>)"
}

@Serializable
internal data class LinkCredentialsRequestV2(
    @SerialName("installation_id") val installationId: String,
    val password: String,
    val username: String,
    @SerialName("directory_password") val directoryPassword: String,
) {
    override fun toString(): String = "LinkCredentialsRequestV2(installationId=$installationId, <credentials redacted>)"
}

@Serializable
internal data class LinkNetworkRequestV2(
    @SerialName("installation_id") val installationId: String,
    val password: String,
) {
    override fun toString(): String = "LinkNetworkRequestV2(installationId=$installationId, password=<redacted>)"
}
