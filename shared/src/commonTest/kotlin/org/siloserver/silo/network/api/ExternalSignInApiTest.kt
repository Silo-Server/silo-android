package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.auth.AccountIdentities
import org.siloserver.silo.model.auth.AccountIdentity
import org.siloserver.silo.model.auth.OAuthHandshakeCapabilities
import org.siloserver.silo.model.auth.PasswordLoginFailure
import org.siloserver.silo.model.auth.SignInOptions
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.model.auth.SignInProviders
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeAttributeKey
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.FreshSiloAuthAttributeKey
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.SingleAttemptAttributeKey
import org.siloserver.silo.network.SkipSiloAuthAttributeKey
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.ExternalSignInRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * External sign-in on the wire: what the apps send to silo-server's
 * `listAuthProviders`, `completeOAuthLogin`, the identity operations and
 * `linkAccountIdentityWithCredentials`, and how each refusal comes back.
 */
class ExternalSignInApiTest {
    private val scope = AuthScopeSnapshot("server-a", "p1", "https://silo.example.test", null, identityGeneration = 1)

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): Pair<HttpClient, ExternalSignInApi> {
        val client = HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
        return client to DefaultExternalSignInApi(client, ApiV2Gate.Unrestricted)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.problem(code: String, status: Int, location: String? = null) = respond(
        buildString {
            append("""{"type":"https://siloserver.org/docs/api/v2/problems/$code","title":"x","status":$status,"detail":"d"""")
            if (location != null) append(""","errors":[{"location":"$location","code":"invalid","detail":"x"}]""")
            append("}")
        },
        HttpStatusCode.fromValue(status),
        headersOf(HttpHeaders.ContentType, "application/problem+json"),
    )

    private fun HttpRequestData.jsonBody() = Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test
    fun providersAndCapabilitiesAreReadUnauthenticatedAtTheGivenServer() = runTest {
        val paths = mutableListOf<String>()
        val (client, api) = api { request ->
            paths += request.url.toString()
            assertEquals(true, request.attributes.getOrNull(SkipSiloAuthAttributeKey))
            when (request.url.encodedPath) {
                "/api/v2/auth/providers" -> json("""{"items":[{"id":"local","display_name":"Silo","mode":"credentials","default":true}],"password_login":true}""")
                else -> json("""{"state":"available","available":true,"native":true,"linking":true,"provider_logout":false,"revision":"r"}""")
            }
        }
        try {
            val providers = assertIs<ApiResult.Success<SignInProviders>>(api.listProviders("https://lan.example:8096/"))
            assertEquals("local", providers.data.providers.single().id)
            val handshake = assertIs<ApiResult.Success<OAuthHandshakeCapabilities>>(api.oauthCapabilities("https://lan.example:8096"))
            assertTrue(handshake.data.native)
            assertEquals(
                listOf("https://lan.example:8096/api/v2/auth/providers", "https://lan.example:8096/api/v2/auth/oauth/capabilities"),
                paths,
            )
        } finally { client.close() }
    }

    @Test
    fun nativeCompletionSendsTheVerifierOnceAndReturnsTheUser() = runTest {
        val (client, api) = api { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://silo.example.test/api/v2/auth/oauth/complete", request.url.toString())
            assertEquals(true, request.attributes.getOrNull(SkipSiloAuthAttributeKey))
            assertEquals(true, request.attributes.getOrNull(SingleAttemptAttributeKey))
            val body = request.jsonBody()
            assertEquals("one-time", body["code"]?.jsonPrimitive?.content)
            assertEquals("v".repeat(43), body["code_verifier"]?.jsonPrimitive?.content)
            json(
                """{"access_token":"acc","refresh_token":"ref","expires_in":3600,"next":"/",
                   "user":{"id":"1","username":"alice","email":"a@example.test","role":"user"}}""",
            )
        }
        try {
            val login = assertIs<ApiResult.Success<*>>(api.completeOAuthLogin("https://silo.example.test", "one-time", "v".repeat(43)))
            assertEquals("alice", (login.data as org.siloserver.silo.model.auth.LoginResponse).user.username)
        } finally { client.close() }
    }

    @Test
    fun nativeCompletionRefusalsKeepTheirProblemCodes() = runTest {
        for ((code, status) in listOf("invalid_grant" to 400, "invalid_token" to 401)) {
            val (client, api) = api { problem(code, status) }
            try {
                val error = assertIs<ApiResult.Error>(api.completeOAuthLogin("https://silo.example.test", "c", "v".repeat(43)))
                assertEquals(status, error.code)
                assertEquals(code, error.error)
            } finally { client.close() }
        }
    }

    @Test
    fun linkTicketConfirmsThePasswordThroughTheAccountScope() = runTest {
        val (client, api) = api { request ->
            assertEquals("/api/v2/account/identities/link-ticket", request.url.encodedPath)
            assertEquals(scope, request.attributes.getOrNull(AuthScopeAttributeKey))
            assertEquals(true, request.attributes.getOrNull(FreshSiloAuthAttributeKey))
            assertEquals(true, request.attributes.getOrNull(SingleAttemptAttributeKey))
            val body = request.jsonBody()
            assertEquals("3", body["installation_id"]?.jsonPrimitive?.content)
            if (body["password"]?.jsonPrimitive?.content == "right") {
                json("""{"ticket":"t-123","expires_at":"2026-01-02T03:09:05.678Z"}""")
            } else {
                problem("validation_failed", 422, "body.password")
            }
        }
        try {
            val ticket = assertIs<ApiResult.Success<*>>(api.createLinkTicket(scope, "3", "right"))
            assertFalse(ticket.data.toString().contains("t-123"), "the ticket stays out of logs")
            val wrong = assertIs<ApiResult.Error>(api.createLinkTicket(scope, "3", "wrong"))
            assertEquals(DefaultExternalSignInApi.WRONG_PASSWORD, wrong.error)
        } finally { client.close() }
    }

    @Test
    fun linkWithCredentialsSendsAllFourMembersAndReadsTheCreatedIdentity() = runTest {
        val (client, api) = api { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v2/account/identities/link-credentials", request.url.encodedPath)
            assertEquals(scope, request.attributes.getOrNull(AuthScopeAttributeKey))
            assertEquals(true, request.attributes.getOrNull(SingleAttemptAttributeKey))
            val body = request.jsonBody()
            assertEquals(setOf("installation_id", "password", "username", "directory_password"), body.keys)
            assertEquals("6", body["installation_id"]?.jsonPrimitive?.content)
            assertEquals("local-pw", body["password"]?.jsonPrimitive?.content)
            assertEquals("alice", body["username"]?.jsonPrimitive?.content)
            assertEquals("dir-pw", body["directory_password"]?.jsonPrimitive?.content)
            respond(
                """{"id":"9","installation_id":"6","provider_id":"plugin:6:ldap","provider_name":"Directory",
                   "username":"alice","email":"","display_name":"Alice","linked_at":"2026-01-02T03:04:05.678Z",
                   "last_sign_in_at":null}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.Location to listOf("/api/v2/account/identities")),
            )
        }
        try {
            val linked = assertIs<ApiResult.Success<AccountIdentity>>(api.linkWithCredentials(scope, "6", "local-pw", "alice", "dir-pw"))
            assertEquals("Directory", linked.data.providerName)
            assertNull(linked.data.lastSignInAt)
        } finally { client.close() }
    }

    @Test
    fun linkWithCredentialsRefusalsAreDistinguishable() = runTest {
        val cases = listOf(
            Triple("validation_failed", 422, "body.password") to DefaultExternalSignInApi.WRONG_PASSWORD,
            Triple("validation_failed", 422, "body.directory_password") to DefaultExternalSignInApi.DIRECTORY_REFUSED,
            Triple("local_password_required", 409, null) to "local_password_required",
            Triple("not_permitted", 403, null) to "not_permitted",
            Triple("account_disabled", 403, null) to "account_disabled",
            Triple("password_expired", 403, null) to "password_expired",
            Triple("permission_denied", 403, null) to "permission_denied",
            Triple("identity_linked_elsewhere", 409, null) to "identity_linked_elsewhere",
            Triple("already_linked", 409, null) to "already_linked",
            Triple("not_found", 404, null) to "not_found",
            Triple("provider_unavailable", 503, null) to "provider_unavailable",
            Triple("rate_limited", 429, null) to "rate_limited",
        )
        for ((problem, expected) in cases) {
            val (code, status, location) = problem
            val (client, api) = api { problem(code, status, location) }
            try {
                val error = assertIs<ApiResult.Error>(api.linkWithCredentials(scope, "6", "a", "b", "c"))
                assertEquals(status, error.code, code)
                assertEquals(expected, error.error, code)
            } finally { client.close() }
        }
        // A success other than 201 is not an identity.
        val (client, api) = api { json("{}") }
        try {
            assertEquals("invalid_response", assertIs<ApiResult.Error>(api.linkWithCredentials(scope, "6", "a", "b", "c")).error)
        } finally { client.close() }
    }

    @Test
    fun identitiesAreListedAndDisconnectedThroughTheAccountScope() = runTest {
        val calls = mutableListOf<String>()
        val (client, api) = api { request ->
            calls += "${request.method.value} ${request.url.encodedPath}"
            assertEquals(scope, request.attributes.getOrNull(AuthScopeAttributeKey))
            when (request.method) {
                HttpMethod.Get -> json(
                    """{"items":[{"id":"4","installation_id":"3","provider_id":"plugin:3:oidc","provider_name":"SSO",
                       "username":"","email":"alice@example.test","display_name":"Alice","linked_at":"2026-01-02T03:04:05.000Z",
                       "last_sign_in_at":null}],"can_unlink":false}""",
                )
                else -> problem("last_sign_in_method", 409)
            }
        }
        try {
            val listed = assertIs<ApiResult.Success<AccountIdentities>>(api.listIdentities(scope)).data
            assertEquals(false, listed.canUnlink)
            val identity = listed.items.single()
            assertEquals("alice@example.test", identity.accountLabel, "the email stands in for an empty username")
            val refused = assertIs<ApiResult.Error>(api.deleteIdentity(scope, "4"))
            assertEquals("last_sign_in_method", refused.error)
            assertEquals(listOf("GET /api/v2/account/identities", "DELETE /api/v2/account/identities/4"), calls)
        } finally { client.close() }
    }

    @Test
    fun wireModelsNeverPrintSecrets() {
        val strings = listOf(
            CompleteOAuthLoginV2(code = "secret-code", codeVerifier = "secret-verifier").toString(),
            LinkTicketRequestV2(installationId = "3", password = "secret-pw").toString(),
            LinkCredentialsRequestV2("6", "secret-pw", "secret-user", "secret-dir").toString(),
        )
        strings.forEach { assertFalse(it.contains("secret"), it) }
    }
}

class SignInOptionsTest {
    private val local = SignInProvider("local", "Silo account", SignInProvider.Mode.Credentials, true, null, null, null)
    private val ldap = SignInProvider("plugin:6:ldap", "Directory", SignInProvider.Mode.Credentials, false, null, "6", null)
    private val oidc = SignInProvider(
        "plugin:5:oidc", "Keycloak", SignInProvider.Mode.OAuth, false, null, "5",
        "/api/v2/auth/oauth/5/native/start",
    )
    private val native = OAuthHandshakeCapabilities(available = true, native = true, linking = true)

    @Test
    fun oidcOnlyServerWithLocalPasswordsOffHidesThePasswordForm() {
        val options = SignInOptions.of(SignInProviders(listOf(oidc), passwordLogin = false), native)
        assertFalse(options.showPasswordForm)
        assertEquals(listOf(oidc), options.oauthProviders)
    }

    @Test
    fun ldapKeepsThePasswordFormWithoutAProviderField() {
        // Local sign-in off, directory on: the password form stays, and it
        // is the server that routes a password login to the directory.
        val options = SignInOptions.of(SignInProviders(listOf(ldap), passwordLogin = true), native)
        assertTrue(options.showPasswordForm)
        assertEquals(ldap, options.directoryProvider)
        // Even if a server said password_login=false, a listed credentials provider takes a password.
        assertTrue(SignInOptions.of(SignInProviders(listOf(ldap), passwordLogin = false), native).showPasswordForm)
    }

    @Test
    fun localAndOidcShowsBoth() {
        val options = SignInOptions.of(SignInProviders(listOf(local, oidc), passwordLogin = true), native)
        assertTrue(options.showPasswordForm)
        assertEquals(listOf(oidc), options.oauthProviders)
        assertNull(options.directoryProvider)
    }

    @Test
    fun providerButtonsNeedTheNativeHandoff() {
        val noNative = OAuthHandshakeCapabilities(available = true, native = false, linking = false)
        assertTrue(SignInOptions.of(SignInProviders(listOf(local, oidc), true), noNative).oauthProviders.isEmpty())
        assertTrue(SignInOptions.of(SignInProviders(listOf(local, oidc), true), OAuthHandshakeCapabilities.None).oauthProviders.isEmpty())
        val noStart = oidc.copy(nativeStartPath = null)
        assertTrue(SignInOptions.of(SignInProviders(listOf(noStart), false), native).oauthProviders.isEmpty())
    }

    @Test
    fun passwordLoginFailuresMapByProblemCodeFirst() {
        assertEquals(PasswordLoginFailure.LocalLoginDisabled, PasswordLoginFailure.of(403, "local_login_disabled"))
        assertEquals(PasswordLoginFailure.NotPermitted, PasswordLoginFailure.of(403, "not_permitted"))
        assertEquals(PasswordLoginFailure.PasswordExpired, PasswordLoginFailure.of(403, "password_expired"))
        assertEquals(PasswordLoginFailure.ProviderUnavailable, PasswordLoginFailure.of(503, "provider_unavailable"))
        assertEquals(PasswordLoginFailure.InvalidCredentials, PasswordLoginFailure.of(401, "invalid_credentials"))
        assertEquals(PasswordLoginFailure.AccountDisabled, PasswordLoginFailure.of(403, "account_disabled"))
        assertEquals(PasswordLoginFailure.ProviderUnavailable, PasswordLoginFailure.of(503, ""))
        assertEquals(PasswordLoginFailure.Other, PasswordLoginFailure.of(500, "internal"))
        assertEquals(PasswordLoginFailure.EmailInUse, PasswordLoginFailure.of(409, "email_in_use"))
        assertEquals(PasswordLoginFailure.IdentityLinkedElsewhere, PasswordLoginFailure.of(409, "identity_linked_elsewhere"))
        assertEquals(PasswordLoginFailure.RateLimited, PasswordLoginFailure.of(429, "rate_limited"))
        assertEquals(PasswordLoginFailure.RateLimited, PasswordLoginFailure.of(429, ""))
    }

    @Test
    fun selectAccountNeedsAProviderButton() {
        val choosing = native.copy(selectAccount = true)
        assertTrue(SignInOptions.of(SignInProviders(listOf(oidc), false), choosing).selectAccount)
        assertFalse(SignInOptions.of(SignInProviders(listOf(oidc), false), native).selectAccount)
        assertFalse(SignInOptions.of(SignInProviders(listOf(local), true), choosing).selectAccount)
    }

    @Test
    fun optionsAreUnknownOnlyWhenTheServerCouldNotBeAsked() = runTest {
        suspend fun optionsWith(
            providers: ApiResult<SignInProviders>,
            handshake: ApiResult<OAuthHandshakeCapabilities>,
        ): SignInOptions? = ExternalSignInRepository(StubDiscovery(providers, handshake)).signInOptions("https://silo.example.test")

        val listed = ApiResult.Success(SignInProviders(listOf(local, oidc), passwordLogin = true))
        val offline = ApiResult.NetworkError(RuntimeException("offline"))
        assertNull(optionsWith(offline, ApiResult.Success(native)), "a network failure leaves the options unknown")
        assertNull(optionsWith(listed, offline))
        assertEquals(SignInOptions.PasswordOnly, optionsWith(ApiResult.Error(404, "not_found", ""), ApiResult.Success(native)))
        assertEquals(
            SignInOptions.PasswordOnly,
            optionsWith(ApiResult.Error(0, ApiV2Gate.UPDATE_REQUIRED_ERROR, ""), ApiResult.Success(native)),
        )
        val noHandshake = optionsWith(listed, ApiResult.Error(404, "not_found", ""))
        assertTrue(noHandshake!!.oauthProviders.isEmpty(), "a server without the handshake shows no provider buttons")
        assertEquals(listOf(oidc), optionsWith(listed, ApiResult.Success(native))!!.oauthProviders)
    }

    private class StubDiscovery(
        private val providers: ApiResult<SignInProviders>,
        private val handshake: ApiResult<OAuthHandshakeCapabilities>,
    ) : ExternalSignInApi {
        override suspend fun listProviders(serverUrl: String) = providers
        override suspend fun oauthCapabilities(serverUrl: String) = handshake
        override suspend fun externalSignInCapabilities(scope: AuthScopeSnapshot) = TODO()
        override suspend fun completeOAuthLogin(serverUrl: String, code: String, codeVerifier: String) = TODO()
        override suspend fun listIdentities(scope: AuthScopeSnapshot) = TODO()
        override suspend fun deleteIdentity(scope: AuthScopeSnapshot, identityId: String) = TODO()
        override suspend fun createLinkTicket(scope: AuthScopeSnapshot, installationId: String, password: String) = TODO()
        override suspend fun completeLink(scope: AuthScopeSnapshot, code: String, codeVerifier: String) = TODO()
        override suspend fun linkWithCredentials(
            scope: AuthScopeSnapshot,
            installationId: String,
            password: String,
            username: String,
            directoryPassword: String,
        ) = TODO()
    }
}
