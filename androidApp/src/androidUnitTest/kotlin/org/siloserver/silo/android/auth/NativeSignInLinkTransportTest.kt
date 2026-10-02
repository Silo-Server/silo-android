package org.siloserver.silo.android.auth

import android.app.Application
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.EncryptedTokenManagerImpl
import org.siloserver.silo.network.SiloAuthPlugin
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.DefaultExternalSignInApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.AuthRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A server saved at its LAN address signs in and links there, through the
 * app's own registry, token manager, [SiloAuthPlugin] and API: the code and
 * the account's bearer go to the LAN address, and the saved server keeps its
 * address and id (no move, no second entry).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NativeSignInLinkTransportTest {
    private class Sent(val url: String, val authorization: String?)

    private val sent = mutableListOf<Sent>()
    private var linkStatus = HttpStatusCode.NoContent
    private val http = HttpClient(
        MockEngine { request ->
            sent += Sent(request.url.toString(), request.headers[HttpHeaders.Authorization])
            when {
                request.url.encodedPath.endsWith("/auth/oauth/complete") -> respond(
                    """{"access_token":"sso-access","refresh_token":"sso-refresh","expires_in":3600,"next":"/",""" +
                        """"user":{"id":"1","username":"alice","email":"a@example.test","role":"user"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                linkStatus == HttpStatusCode.NoContent -> respond("", linkStatus)
                else -> respond(
                    """{"type":"https://siloserver.org/docs/api/v2/problems/invalid_grant","title":"Bad request","status":400}""",
                    linkStatus,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
            }
        },
    )

    @AfterTest fun tearDown() = http.close()

    private inner class Fixture {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("native-sign-in-link-transport", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }
        val registry = AndroidServerRegistry(prefs)
        lateinit var tokens: EncryptedTokenManagerImpl
        lateinit var lanId: String
        lateinit var completer: RepositoryNativeSignInCompleter

        suspend fun savedAtLan(signedIn: Boolean): AuthScopeSnapshot? {
            lanId = registry.addOrUpdate(LAN)
            registry.setVerifiedServerId(lanId, "srv-1")
            registry.switchTo(lanId)
            tokens = EncryptedTokenManagerImpl(prefs, registry)
            tokens.switchActiveServer(lanId)
            if (signedIn) tokens.saveTokens("lan-access", "lan-refresh", 3600)
            val client = http.config {
                install(ContentNegotiation) { json(SiloJson) }
                install(SiloAuthPlugin) { tokenManager = tokens }
            }
            completer = RepositoryNativeSignInCompleter(
                authRepository = AuthRepository(AuthApi(client, ApiV2Gate.Unrestricted), tokens, serverRegistry = registry),
                api = DefaultExternalSignInApi(client, ApiV2Gate.Unrestricted),
                tokenManager = tokens,
            )
            return tokens.snapshotCurrentScope()
        }

        fun flow(purpose: NativeSignInPurpose, scope: AuthScopeSnapshot? = null) = PendingNativeSignIn(
            purpose = purpose,
            serverEntryId = lanId,
            verifiedServerId = "srv-1",
            startOrigin = LAN,
            appState = "state",
            codeVerifier = "verifier",
            providerName = "Keycloak",
            startedAtEpochMs = 0L,
            identityGeneration = scope?.identityGeneration,
            credentialEpoch = scope?.credentialEpoch,
        )
    }

    @Test
    fun aLanSavedServerSignsInAndStaysOnItsLanAddress() = runTest {
        val f = Fixture()
        f.savedAtLan(signedIn = false)

        assertIs<ApiResult.Success<*>>(f.completer.signIn(f.flow(NativeSignInPurpose.SignIn), "code-1"))

        assertEquals("$LAN/api/v2/auth/oauth/complete", sent.single().url)
        val saved = f.registry.entries.value.single()
        assertEquals(f.lanId, saved.id)
        assertEquals(LAN, saved.url, "the saved address never changes")
        assertEquals(f.lanId, f.registry.activeServerId.value)
        assertEquals("sso-access", f.tokens.getAccessToken())
    }

    @Test
    fun linkingConfirmsAtTheLanAddressWithTheAccountsBearer() = runTest {
        val f = Fixture()
        val scope = requireNotNull(f.savedAtLan(signedIn = true))

        assertIs<ApiResult.Success<Unit>>(f.completer.link(f.flow(NativeSignInPurpose.Link, scope), "code-1"))

        val confirmation = sent.single()
        assertEquals("$LAN/api/v2/account/identities/link-complete", confirmation.url)
        assertEquals("Bearer lan-access", confirmation.authorization)
        assertEquals(LAN, f.registry.entries.value.single().url)
        assertEquals("lan-access", f.tokens.getAccessToken(), "the session stays signed in")
    }

    @Test
    fun aRefusedLinkChangesNothing() = runTest {
        val f = Fixture()
        val scope = requireNotNull(f.savedAtLan(signedIn = true))
        linkStatus = HttpStatusCode.BadRequest

        val refused = assertIs<ApiResult.Error>(f.completer.link(f.flow(NativeSignInPurpose.Link, scope), "code-1"))

        assertEquals("state_invalid", NativeSignInCoordinator.completionReason(refused))
        assertEquals(LAN, f.registry.entries.value.single().url)
        assertEquals("lan-access", f.tokens.getAccessToken())
    }

    private companion object {
        const val LAN = "http://192.168.1.10:8096"
    }
}
