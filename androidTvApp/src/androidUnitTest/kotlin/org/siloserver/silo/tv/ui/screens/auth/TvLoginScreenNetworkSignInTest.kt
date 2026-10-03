package org.siloserver.silo.tv.ui.screens.auth

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondBadRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.common.pairing.DeviceLoginRepositoryPort
import org.siloserver.silo.common.pairing.PairingAuthPort
import org.siloserver.silo.common.pairing.PairingDeviceIdentity
import org.siloserver.silo.common.pairing.PairingReceiver
import org.siloserver.silo.common.pairing.TvPairingAdvertiser
import org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse
import org.siloserver.silo.model.auth.LoginResponse
import org.siloserver.silo.model.auth.NetworkIdentity
import org.siloserver.silo.model.auth.OAuthHandshakeCapabilities
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.model.auth.SignInProviders
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.AccountSessionExpectation
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.DeviceLoginApi
import org.siloserver.silo.network.api.ExternalSignInApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.DeviceLoginRepository
import org.siloserver.silo.repository.ExternalSignInRepository
import org.siloserver.silo.tv.ui.theme.SiloTvTheme

/**
 * "Continue as …" on a server without device sign-in, where the screen skips
 * the code. With passwords on it opens on the password form, which offers the
 * button above the fields and focuses it; with network identity alone it
 * stays on the code screen, which offers it too.
 */
@RunWith(RobolectricTestRunner::class)
// The reference TV surface: 1920x1080 at 320dpi.
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-xhdpi")
class TvLoginScreenNetworkSignInTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun thePasswordFormOffersContinueAsAndFocusesIt() {
        show(SignInProviders(listOf(LOCAL, TAILSCALE), passwordLogin = true))
        composeRule.onNodeWithText("USERNAME").assertIsDisplayed()
        composeRule.onNodeWithText(CONTINUE_AS).assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun aServerWithOnlyNetworkSignInStillOffersContinueAs() {
        show(SignInProviders(listOf(TAILSCALE), passwordLogin = false))
        composeRule.onNodeWithText("USERNAME").assertDoesNotExist()
        composeRule.onNodeWithText(CONTINUE_AS).assertIsDisplayed().assertIsFocused()
    }

    /** The providers can answer before the device check: focus follows the button into the form. */
    @Test
    fun focusFollowsContinueAsIntoTheForm() {
        val deviceCheck = CompletableDeferred<Unit>()
        show(SignInProviders(listOf(LOCAL, TAILSCALE), passwordLogin = true), deviceCheck)
        composeRule.onNodeWithText("USERNAME").assertDoesNotExist()
        composeRule.onNodeWithText(CONTINUE_AS).assertIsFocused()

        deviceCheck.complete(Unit)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("USERNAME").fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(CONTINUE_AS).assertIsFocused()
    }

    /**
     * While "Continue as …" waits, the password form takes nothing either: its
     * fields and Sign in are disabled, focus stays on the waiting button, and
     * the D-pad steps past the disabled controls to the secondary actions.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun continueAsInProgressDisablesThePasswordForm() {
        show(SignInProviders(listOf(LOCAL, TAILSCALE), passwordLogin = true))
        composeRule.onAllNodes(TEXT_FIELD).assertCountEquals(2).assertAll(isEnabled())
        composeRule.onNodeWithText(SIGN_IN).assertIsEnabled()

        composeRule.onNodeWithText(CONTINUE_AS).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(SIGNING_IN).assertIsFocused()
        composeRule.onAllNodes(TEXT_FIELD).assertCountEquals(2).assertAll(isNotEnabled())
        composeRule.onNodeWithText(SIGN_IN).assertIsNotEnabled()

        composeRule.onNodeWithText(SIGNING_IN).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(CHANGE_SERVER).assertIsFocused()
        composeRule.onNodeWithText(CHANGE_SERVER).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(SIGNING_IN).assertIsFocused()
    }

    private fun show(providers: SignInProviders, deviceCheck: CompletableDeferred<Unit> = CompletableDeferred(Unit)) {
        // D-pad, not touch: the screen claims focus only for remote users.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val deviceLogin = DeviceLoginRepository(NoDeviceSignIn(deviceCheck))
        val tokens = object : TokenManager by TokenManagerImpl() {
            override suspend fun captureAccountSessionExpectation() = AccountSessionExpectation(0, null, SERVER.url)
        }
        val viewModel = TvLoginViewModel(
            authRepository = AuthRepository(AuthApi(HttpClient(MockEngine { respondBadRequest() }), ApiV2Gate.Unrestricted), tokens),
            tokenManager = tokens,
            deviceLogin = deviceLogin,
            serverRegistry = OneServer,
            externalSignIn = ExternalSignInRepository(Providers(providers)),
        )
        val receiver = PairingReceiver(NoPairing, DeviceLoginRepositoryPort(deviceLogin), { PairingDeviceIdentity("TV", "tv-1") })
        composeRule.setContent {
            SiloTvTheme {
                TvLoginScreen(
                    onLoginSuccess = {},
                    viewModel = viewModel,
                    pairingReceiver = receiver,
                    pairingAdvertiser = TvPairingAdvertiser(ApplicationProvider.getApplicationContext(), receiver),
                )
            }
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(CONTINUE_AS).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
    }

    /** A server without device sign-in, which says so once [answered] completes. */
    private class NoDeviceSignIn(private val answered: CompletableDeferred<Unit>) : DeviceLoginApi {
        override suspend fun deviceLoginCapabilityAt(serverUrl: String): ApiResult<DeviceLoginCapabilityResponse> {
            answered.await()
            return ApiResult.Success(DeviceLoginCapabilityResponse(deviceLoginAvailable = false))
        }
        override suspend fun startDeviceLogin(deviceName: String?, devicePlatform: String?) = error("no device sign-in")
        override suspend fun pollDeviceLogin(deviceCode: String) = error("no device sign-in")
        override suspend fun lookupDeviceLogin(token: String?, code: String?) = error("unused")
        override suspend fun approveDeviceLogin(token: String?, code: String?) = error("unused")
        override suspend fun denyDeviceLogin(token: String?, code: String?) = error("unused")
    }

    private class Providers(private val providers: SignInProviders) : ExternalSignInApi {
        override suspend fun listProviders(serverUrl: String) = ApiResult.Success(providers)
        override suspend fun oauthCapabilities(serverUrl: String) = ApiResult.Success(OAuthHandshakeCapabilities.None)
        override suspend fun externalSignInCapabilities(scope: AuthScopeSnapshot) = error("unused")
        override suspend fun completeOAuthLogin(serverUrl: String, code: String, codeVerifier: String) = error("unused")
        // Waits like a server that hasn't answered yet.
        override suspend fun signInWithNetworkIdentity(serverUrl: String, signInPath: String): ApiResult<LoginResponse> =
            awaitCancellation()
        override suspend fun listIdentities(scope: AuthScopeSnapshot) = error("unused")
        override suspend fun deleteIdentity(scope: AuthScopeSnapshot, identityId: String) = error("unused")
        override suspend fun createLinkTicket(scope: AuthScopeSnapshot, installationId: String, password: String) = error("unused")
        override suspend fun completeLink(scope: AuthScopeSnapshot, code: String, codeVerifier: String) = error("unused")
        override suspend fun linkWithCredentials(
            scope: AuthScopeSnapshot, installationId: String, password: String, username: String, directoryPassword: String,
        ) = error("unused")
        override suspend fun linkWithNetwork(scope: AuthScopeSnapshot, installationId: String, password: String) = error("unused")
    }

    private object NoPairing : PairingAuthPort {
        override suspend fun persistApprovedSession(
            serverUrl: String, serverName: String?, accessToken: String, refreshToken: String, expiresIn: Long,
            expectedIdentity: AccountSessionExpectation?, verifiedServerId: String?,
        ) = error("unused")
    }

    private object OneServer : ServerRegistry {
        override val entries = MutableStateFlow(listOf(SERVER))
        override val activeServerId = MutableStateFlow<String?>(SERVER.id)
        override val activeEntry = MutableStateFlow<ServerEntry?>(SERVER)
        override suspend fun addOrUpdate(url: String, fetchedName: String?): String = SERVER.id
        override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
        override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
        override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun signOut(serverId: String) = Unit
        override suspend fun switchTo(serverId: String) = Unit
        override suspend fun touchActive() = Unit
    }

    private companion object {
        const val CONTINUE_AS = "Continue as Alice Example"
        const val SIGNING_IN = "Signing in…"
        const val SIGN_IN = "Sign in"
        const val CHANGE_SERVER = "Change server"
        val TEXT_FIELD = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        val SERVER = ServerEntry(id = "entry-1", url = "https://silo.test", fetchedName = "Silo")
        val LOCAL = SignInProvider("local", "Silo account", SignInProvider.Mode.Credentials, true, null, null, null)
        val TAILSCALE = SignInProvider(
            "plugin:7:tailscale", "Tailscale", SignInProvider.Mode.Network, false, null, "7", null,
            networkSignInPath = "/api/v2/auth/network/7/sign-in",
            networkIdentity = NetworkIdentity("Alice Example", "alice@example.test"),
        )
    }
}
