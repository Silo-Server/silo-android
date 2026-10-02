package org.siloserver.silo.tv.ui.screens.auth

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.siloserver.silo.common.pairing.NearbySignInOutcome
import org.siloserver.silo.model.auth.DeviceLoginCancelResponse
import org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.network.AccountSessionChangedException
import org.siloserver.silo.network.AccountSessionExpectation
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloAuthPlugin
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.DeviceLoginApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.DeviceLoginClock
import org.siloserver.silo.repository.DeviceLoginRepository
import org.siloserver.silo.repository.ExternalSignInRepository
import org.siloserver.silo.model.auth.OAuthHandshakeCapabilities
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.model.auth.SignInProviders
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.api.ExternalSignInApi
import org.siloserver.silo.network.api.ServerConnections
import org.siloserver.silo.network.api.ServerIdentityApi
import org.siloserver.silo.network.api.ServerIdentityProbe
import org.siloserver.silo.repository.ServerIdentityRepository
import org.siloserver.silo.pairing.PairingReceiverState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The TV sign-in panel's device-code behavior: #421 (no silent "Loading"
 * hang), #422 (no false "Signed in!"), lifecycle-aware polling (#420) and
 * server-side cancel of an abandoned code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvLoginViewModelDeviceSignInTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test
    fun missingSignInContextOffersTryAgainInsteadOfSpinning() = runTest(dispatcher) {
        val tokens = FakeTokens(nullExpectations = 3)
        val api = FakeDeviceApi()
        val vm = viewModel(tokens, api)
        advanceUntilIdle()
        assertEquals(TvSignInStatus.Failed, vm.deviceSignIn.value.status)
        assertEquals(0, api.starts)

        vm.restartDeviceLogin()
        runCurrent()
        assertEquals(TvSignInStatus.Waiting, vm.deviceSignIn.value.status)
        assertEquals("4821 7730", vm.deviceSignIn.value.code?.userCode)
        assertEquals("silo.test/activate", vm.deviceSignIn.value.code?.activateText)
    }

    @Test
    fun failedSaveSaysCouldntFinishAndTryAgainGetsANewCode() = runTest(dispatcher) {
        val tokens = FakeTokens(failInstall = true)
        val api = FakeDeviceApi(approveAfterPolls = 1)
        val vm = viewModel(tokens, api)
        advanceUntilIdle()
        assertEquals(TvSignInStatus.CouldntFinish, vm.deviceSignIn.value.status)
        assertFalse(vm.uiState.value.loginSuccess)

        tokens.failInstall = false
        api.approveAfterPolls = Int.MAX_VALUE
        vm.restartDeviceLogin()
        runCurrent()
        assertEquals(TvSignInStatus.Waiting, vm.deviceSignIn.value.status)
        assertEquals(2, api.starts, "the consumed request can't be collected again; a new code is needed")
    }

    @Test
    fun approvalShowsSignedInAsBeforeRouting() = runTest(dispatcher) {
        val tokens = FakeTokens()
        val api = FakeDeviceApi(approveAfterPolls = 1)
        val vm = viewModel(tokens, api)
        runCurrent()
        advanceTimeBy(500)
        assertEquals(TvSignInStatus.SignedIn, vm.deviceSignIn.value.status)
        assertEquals("laura", vm.deviceSignIn.value.accountName)
        assertFalse(vm.uiState.value.loginSuccess)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loginSuccess)
        assertEquals("qr-access", tokens.accessToken)
    }

    @Test
    fun stoppingWhileTheApprovalIsSavedStillSignsInAndRoutes() = runTest(dispatcher) {
        val tokens = FakeTokens()
        val api = FakeDeviceApi(approveAfterPolls = 1)
        val vm = viewModel(tokens, api)
        runCurrent()
        advanceTimeBy(500)
        assertEquals(TvSignInStatus.SignedIn, vm.deviceSignIn.value.status)
        assertFalse(vm.uiState.value.loginSuccess)

        // Between the approved poll and routing on (the "Signed in" dwell).
        vm.onStop()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loginSuccess, "the handoff finishes in the background")
        assertEquals("qr-access", tokens.accessToken)
    }

    @Test
    fun stoppingDuringAnApprovedPollKeepsItsTokens() = runTest(dispatcher) {
        val tokens = FakeTokens()
        val api = FakeDeviceApi(approveAfterPolls = 1, pollDelayMs = 1_000L)
        val vm = viewModel(tokens, api)
        runCurrent()
        assertEquals(1, api.polls, "the poll is in flight")
        vm.onStop()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loginSuccess)
        assertEquals("qr-access", tokens.accessToken)
        assertEquals(1, api.starts, "the code was not silently renewed")
    }

    @Test
    fun aFailedPasswordSaveLeavesTheCodeWorking() = runTest(dispatcher) {
        val tokens = FakeTokens(failFirstInstall = true)
        val api = FakeDeviceApi()
        api.approveWhen = { tokens.installAttempts >= 1 }
        val vm = viewModel(tokens, api, loginStatus = HttpStatusCode.OK)
        runCurrent()
        vm.onUsernameChanged("jim")
        vm.onPasswordChanged("pw")
        vm.onLoginClick()
        advanceUntilIdle()
        // The password save failed; a phone then approved the code on screen.
        assertTrue(vm.uiState.value.loginSuccess)
        assertEquals("qr-access", tokens.accessToken)
        assertEquals(TvSignInStatus.SignedIn, vm.deviceSignIn.value.status)
    }

    @Test
    fun aNearbyPhoneApprovesTheCodeOnScreen() = runTest(dispatcher) {
        val tokens = FakeTokens()
        val api = FakeDeviceApi()
        val vm = viewModel(tokens, api)
        runCurrent()
        val code = assertNotNull(vm.codeForNearbyApproval())
        assertEquals("dev-1", code.deviceCode)
        var outcome: NearbySignInOutcome? = null
        backgroundScope.launch { outcome = vm.nearbyApprovalOutcome(code.deviceCode) }
        runCurrent()
        assertEquals(null, outcome)

        api.approveAfterPolls = api.polls + 1
        advanceUntilIdle()
        assertEquals(NearbySignInOutcome.SignedIn, outcome)
        assertEquals(1, api.starts, "one code on screen, one request")
        assertEquals("qr-access", tokens.accessToken)
    }

    @Test
    fun stoppingPausesPollingAndStartingPollsAtOnce() = runTest(dispatcher) {
        val api = FakeDeviceApi()
        val vm = viewModel(FakeTokens(), api)
        runCurrent()
        advanceTimeBy(11_000)
        vm.onStop()
        val polls = api.polls
        advanceTimeBy(120_000)
        assertEquals(polls, api.polls, "no polling while the screen is stopped")

        vm.onStart()
        runCurrent()
        assertEquals(polls + 1, api.polls)
        assertEquals(1, api.starts)
    }

    @Test
    fun stopStartStopDuringAPollLeavesPollingStopped() = runTest(dispatcher) {
        val api = FakeDeviceApi(pollDelayMs = 1_000L)
        val vm = viewModel(FakeTokens(), api)
        runCurrent()
        assertEquals(1, api.polls, "the first poll is in flight")

        // All while that poll is still out: the second run waits for it.
        vm.onStop()
        vm.onStart()
        runCurrent()
        vm.onStop()
        advanceTimeBy(120_000)
        assertEquals(1, api.polls, "no polling while the screen is stopped")

        vm.onStart()
        runCurrent()
        assertEquals(2, api.polls, "back in the foreground it polls at once")
        assertEquals(1, api.starts)
    }

    @Test
    fun restartingDuringAStoppedSlowPollLeavesOnePollStreamOnTheNewCode() = runTest(dispatcher) {
        for (late in listOf("pending", "expired")) {
            // dev-1's poll is on the wire and can't be called back; it answers
            // at 12 s, between the new code's polls at 10 s and 15 s.
            val api = FakeDeviceApi(pollDelayMs = 12_000L, pollsIgnoreCancel = true)
            api.statusFor = { code -> if (code == "dev-1") late else "pending" }
            val vm = viewModel(FakeTokens(), api)
            runCurrent()
            assertEquals(listOf("dev-1"), api.polledCodes, "the first poll is in flight")
            api.pollDelayMs = 0L

            // "Try again" in the foreground after a stop/start during that poll.
            vm.onStop()
            vm.onStart()
            vm.restartDeviceLogin()
            runCurrent()
            assertEquals("dev-2", vm.codeForNearbyApproval()?.deviceCode)

            advanceTimeBy(12_001)
            assertEquals("dev-2", vm.codeForNearbyApproval()?.deviceCode, "the withdrawn code never comes back ($late)")
            advanceTimeBy(18_000)
            assertEquals("dev-2", vm.codeForNearbyApproval()?.deviceCode, "the new code isn't orphaned ($late)")
            assertEquals(2, api.starts, "no third code ($late)")
            assertEquals(1, api.polledCodes.count { it == "dev-1" }, "the old run polls no more ($late)")
            // One stream: dev-2 polled at 0, 5, ... 30 s.
            assertEquals(7, api.polledCodes.count { it == "dev-2" }, "one poll stream ($late): ${api.polledCodes}")
            assertEquals(listOf("dev-1"), api.canceled)
        }
    }

    @Test
    fun startingAfterAPasswordSignInWonAsksForNoNewCode() = runTest(dispatcher) {
        val api = FakeDeviceApi()
        val vm = viewModel(FakeTokens(), api, loginStatus = HttpStatusCode.OK)
        runCurrent()
        vm.onUsernameChanged("jim")
        vm.onPasswordChanged("pw")
        // The password sign-in completes while the app is in the background.
        vm.onStop()
        vm.onLoginClick()
        // The login request runs on the mock engine's own threads: wait for
        // its outcome rather than for virtual time, which is idle while stopped.
        vm.uiState.first { it.loginSuccess }
        runCurrent()
        val starts = api.starts

        vm.onStart()
        advanceTimeBy(60_000)
        assertEquals(starts, api.starts, "no code for a TV that is already signed in")
    }

    @Test
    fun stoppingBeforeTheCodeIsRequestedWaitsForTheScreenToReturn() = runTest(dispatcher) {
        val api = FakeDeviceApi()
        // The first read of the sign-in context races and is retried, so the
        // machine doesn't exist yet when the screen stops.
        val vm = viewModel(FakeTokens(nullExpectations = 1), api)
        runCurrent()
        vm.onStop()
        advanceTimeBy(120_000)
        assertEquals(0, api.starts, "no code requested while the screen is stopped")
        assertEquals(0, api.polls)

        vm.onStart()
        runCurrent()
        assertEquals(1, api.starts)
        assertEquals(1, api.polls)
        assertEquals(TvSignInStatus.Waiting, vm.deviceSignIn.value.status)
    }

    @Test
    fun passwordSignInWithdrawsTheCodeOnTheServer() = runTest(dispatcher) {
        val api = FakeDeviceApi()
        val tokens = FakeTokens()
        val vm = viewModel(tokens, api, loginStatus = HttpStatusCode.OK)
        runCurrent()
        vm.onUsernameChanged("jim")
        vm.onPasswordChanged("pw")
        vm.onLoginClick()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loginSuccess)
        // The login request runs on the mock engine's own threads while virtual
        // time advances, so codes may have renewed meanwhile (a renewal
        // withdraws the code it replaces). Whatever was live when the password
        // won is withdrawn: no code the TV showed stays approvable.
        assertTrue(api.polledCodes.isNotEmpty())
        assertEquals(api.polledCodes.distinct(), api.canceled.distinct())
    }

    @Test
    fun leavingTheScreenWithdrawsTheCodeOnlyWhenTheServerSupportsCancel() = runTest(dispatcher) {
        val api = FakeDeviceApi()
        val store = ViewModelStore()
        store.put("login", viewModel(FakeTokens(), api, track = false))
        runCurrent()
        store.clear()
        advanceUntilIdle()
        assertEquals(listOf("dev-1"), api.canceled)

        val old = FakeDeviceApi(capability = DeviceLoginCapabilityResponse(cancel = false))
        val oldStore = ViewModelStore()
        oldStore.put("login", viewModel(FakeTokens(), old, track = false))
        runCurrent()
        oldStore.clear()
        advanceUntilIdle()
        assertEquals(emptyList(), old.canceled)
    }

    @Test
    fun serverWithoutDeviceSignInGoesToThePasswordForm() = runTest(dispatcher) {
        val api = FakeDeviceApi(capability = DeviceLoginCapabilityResponse(deviceLoginAvailable = false))
        val vm = viewModel(FakeTokens(), api)
        advanceUntilIdle()
        assertTrue(vm.deviceSignIn.value.passwordOnly)
        assertEquals(0, api.starts)
    }

    // --- Password gating (external sign-in) ---

    @Test
    fun oidcOnlyServerWithLocalPasswordsOffOffersNoPassword() = runTest(dispatcher) {
        val signIn = FakeSignInApi(SignInProviders(listOf(OIDC), passwordLogin = false))
        val vm = viewModel(FakeTokens(), FakeDeviceApi(), registry = FakeRegistry(), externalSignIn = signIn)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.passwordAvailable, "the TV signs in with a phone only")
        assertEquals(listOf("https://silo.test"), signIn.asked)
    }

    @Test
    fun ldapServerKeepsThePasswordFormAndSendsNoProvider() = runTest(dispatcher) {
        val signIn = FakeSignInApi(SignInProviders(listOf(LDAP), passwordLogin = true))
        val bodies = mutableListOf<String>()
        val vm = viewModel(
            FakeTokens(), FakeDeviceApi(), loginStatus = HttpStatusCode.OK,
            registry = FakeRegistry(), externalSignIn = signIn, loginBodies = bodies,
        )
        runCurrent()
        assertTrue(vm.uiState.value.passwordAvailable)
        vm.onUsernameChanged("alice")
        vm.onPasswordChanged("directory-pw")
        vm.onLoginClick()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loginSuccess, "${vm.uiState.value}")
        val login = kotlinx.serialization.json.Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(setOf("username", "password"), login.keys, "the server routes a directory login by account")
    }

    @Test
    fun theProvidersNamesAreKeptForThePasswordFormsErrors() = runTest(dispatcher) {
        val signIn = FakeSignInApi(SignInProviders(listOf(OIDC, LDAP), passwordLogin = true))
        val vm = viewModel(FakeTokens(), FakeDeviceApi(), registry = FakeRegistry(), externalSignIn = signIn)
        advanceUntilIdle()
        assertEquals(OIDC.displayName, vm.uiState.value.providerName)
        assertEquals(LDAP.displayName, vm.uiState.value.directoryName)
    }

    @Test
    fun directoryRefusalsAndThrottlingHaveTheirOwnErrors() {
        assertEquals(TvLoginError.EmailInUse, tvLoginError(ApiResult.Error(409, "email_in_use", "raw detail")))
        assertEquals(TvLoginError.IdentityLinkedElsewhere, tvLoginError(ApiResult.Error(409, "identity_linked_elsewhere", "raw")))
        assertEquals(TvLoginError.RateLimited, tvLoginError(ApiResult.Error(429, "", "raw")))
    }

    @Test
    fun aServerThatCantListProvidersKeepsThePasswordForm() = runTest(dispatcher) {
        val vm = viewModel(FakeTokens(), FakeDeviceApi(), registry = FakeRegistry(), externalSignIn = FakeSignInApi(null))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.passwordAvailable)
    }

    /**
     * An admin turns password sign-in off while the TV shows the form or sits
     * in the background: the TV re-reads its options when the person goes
     * back to the code, when the screen returns, and when the server refuses
     * a password, so "Sign in with a password" goes away.
     */
    @Test
    fun theSignInOptionsAreReadAgainOnReturn() = runTest(dispatcher) {
        val signIn = FakeSignInApi(SignInProviders(listOf(LOCAL, OIDC), passwordLogin = true))
        val vm = viewModel(FakeTokens(), FakeDeviceApi(), registry = FakeRegistry(), externalSignIn = signIn)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.passwordAvailable)
        assertEquals(1, signIn.asked.size)

        // Back from the password form ("Use your phone instead").
        signIn.providers = SignInProviders(listOf(OIDC), passwordLogin = false)
        vm.restartDeviceLogin()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.passwordAvailable)
        assertEquals(2, signIn.asked.size)

        // The screen comes back from the background.
        signIn.providers = SignInProviders(listOf(LOCAL, OIDC), passwordLogin = true)
        vm.onStop()
        vm.onStart()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.passwordAvailable)
        assertEquals(3, signIn.asked.size)

        // A start without a stop first (the screen's first start) asks nothing more.
        vm.onStart()
        advanceUntilIdle()
        assertEquals(3, signIn.asked.size)
    }

    @Test
    fun aRefusedPasswordBecauseLocalLoginIsOffRereadsTheOptions() = runTest(dispatcher) {
        val signIn = FakeSignInApi(SignInProviders(listOf(LOCAL, OIDC), passwordLogin = true))
        val vm = viewModel(
            FakeTokens(), FakeDeviceApi(), loginStatus = HttpStatusCode.Forbidden,
            loginBody = """{"type":"https://siloserver.org/docs/api/v2/problems/local_login_disabled","title":"x","status":403}""",
            registry = FakeRegistry(), externalSignIn = signIn,
        )
        runCurrent()
        assertTrue(vm.uiState.value.passwordAvailable)
        signIn.providers = SignInProviders(listOf(OIDC), passwordLogin = false)
        vm.onUsernameChanged("jim")
        vm.onPasswordChanged("pw")
        vm.onLoginClick()
        advanceUntilIdle()
        // The form closes; the screen shows the refusal outside it.
        assertFalse(vm.uiState.value.passwordAvailable)
        assertTrue(vm.uiState.value.passwordTurnedOff)

        // Passwords on again: the form can come back, without the old refusal.
        signIn.providers = SignInProviders(listOf(LOCAL, OIDC), passwordLogin = true)
        vm.onStop()
        vm.onStart()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.passwordAvailable)
        assertFalse(vm.uiState.value.passwordTurnedOff)
        assertNull(vm.uiState.value.error)
    }

    // --- st=login rollout gate (silo-apple PairingProtocol.advertisesSignInTVs) ---

    /** Released phones push servers without an identity, so a release TV must not advertise st=login yet. */
    @Test
    fun gateOffAdvertisesNothingEvenWithAVerifiedIdentity() = runTest(dispatcher) {
        val identity = FakeIdentityApi()
        val vm = viewModel(
            FakeTokens(), FakeDeviceApi(), registry = FakeRegistry(), identityApi = identity,
            advertisesSignIn = false,
        )
        advanceUntilIdle()
        assertNull(vm.pairingAdvertisement.value)
        assertEquals(0, identity.probes, "no identity probe when nothing will be advertised")
    }

    @Test
    fun gateOnAdvertisesLoginWithTheServersIdentity() = runTest(dispatcher) {
        val vm = viewModel(
            FakeTokens(), FakeDeviceApi(), registry = FakeRegistry(), identityApi = FakeIdentityApi(),
            advertisesSignIn = true,
        )
        advanceUntilIdle()
        val advertisement = assertNotNull(vm.pairingAdvertisement.value)
        assertEquals(PairingReceiverState.Login, advertisement.state)
        assertEquals("server-1", advertisement.serverIdentity)
        assertEquals("https://silo.test", advertisement.serverUrl)
    }

    /** Password sign-in turned off while the form showed another error: that error goes with the form. */
    @Test
    fun theFormsErrorsCloseWithIt() = runTest(dispatcher) {
        val signIn = FakeSignInApi(SignInProviders(listOf(LOCAL, OIDC), passwordLogin = true))
        val vm = viewModel(FakeTokens(), FakeDeviceApi(), registry = FakeRegistry(), externalSignIn = signIn)
        runCurrent()
        vm.onLoginClick()
        assertEquals(TvLoginError.UsernameRequired, vm.uiState.value.error)
        signIn.providers = SignInProviders(listOf(OIDC), passwordLogin = false)
        vm.onStop()
        vm.onStart()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.passwordAvailable)
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.passwordTurnedOff)
    }

    @Test
    fun localLoginDisabledHasItsOwnError() = runTest(dispatcher) {
        val vm = viewModel(
            FakeTokens(), FakeDeviceApi(), loginStatus = HttpStatusCode.Forbidden,
            loginBody = """{"type":"https://siloserver.org/docs/api/v2/problems/local_login_disabled","title":"x","status":403}""",
        )
        runCurrent()
        vm.onUsernameChanged("jim")
        vm.onPasswordChanged("pw")
        vm.onLoginClick()
        advanceUntilIdle()
        assertEquals(TvLoginError.LocalLoginDisabled, vm.uiState.value.error)
    }

    private fun TestScope.viewModel(
        tokens: FakeTokens,
        api: FakeDeviceApi,
        loginStatus: HttpStatusCode = HttpStatusCode.NotFound,
        track: Boolean = true,
        registry: ServerRegistry? = null,
        externalSignIn: ExternalSignInApi? = null,
        loginBodies: MutableList<String>? = null,
        loginBody: String? = null,
        identityApi: ServerIdentityApi? = null,
        advertisesSignIn: Boolean = false,
    ): TvLoginViewModel {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("/auth/login")) {
                        loginBodies?.add((request.body as io.ktor.http.content.TextContent).text)
                    }
                    respond(
                        loginBody ?: if (loginStatus == HttpStatusCode.OK) LOGIN_JSON else "{}",
                        loginStatus,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokens }
        }
        val vm = TvLoginViewModel(
            authRepository = AuthRepository(AuthApi(client, ApiV2Gate.Unrestricted), tokens),
            tokenManager = tokens,
            deviceLogin = DeviceLoginRepository(api),
            // The test scope itself (not backgroundScope, which advanceUntilIdle
            // doesn't wait for), so the best-effort cancel call is observable.
            backgroundScope = this,
            clock = DeviceLoginClock { testScheduler.currentTime },
            serverRegistry = registry,
            serverIdentities = identityApi?.let { api -> registry?.let { ServerIdentityRepository(it, api) } },
            externalSignIn = externalSignIn?.let(::ExternalSignInRepository),
            advertisesSignIn = advertisesSignIn,
        )
        if (track) stores += ViewModelStore().also { it.put("login", vm) }
        return vm
    }

    private class FakeDeviceApi(
        val capability: DeviceLoginCapabilityResponse = DeviceLoginCapabilityResponse(cancel = true, openedSignal = true),
        var approveAfterPolls: Int = Int.MAX_VALUE,
        /** Approves the next poll once this is true, whatever the poll count. */
        var approveWhen: () -> Boolean = { false },
        /** How long each poll call takes, so a test can stop the screen mid-call. */
        var pollDelayMs: Long = 0L,
        /** A poll call that ignores cancellation, like a request already on the wire. */
        val pollsIgnoreCancel: Boolean = false,
    ) : DeviceLoginApi {
        /** What a poll that doesn't approve answers for each code. */
        var statusFor: (String) -> String = { "pending" }

        var starts = 0
        var polls = 0
        val canceled = mutableListOf<String>()

        /** Each code polled, i.e. shown on the TV and live on the server. */
        val polledCodes = mutableListOf<String>()

        override suspend fun deviceLoginCapabilityAt(serverUrl: String) = ApiResult.Success(capability)
        override suspend fun startDeviceLoginAt(serverUrl: String, deviceName: String?, devicePlatform: String?) =
            ApiResult.Success(
                DeviceLoginStartResponse(
                    deviceCode = "dev-${++starts}",
                    userCode = "4821-7730",
                    matchCode = "warm pony",
                    verificationUri = "https://silo.test/activate",
                    verificationUriComplete = "https://silo.test/activate?code=48217730",
                    expiresAt = "2099-01-01T00:00:00Z",
                    expiresIn = 900,
                    interval = 5,
                    deviceName = deviceName.orEmpty(),
                    devicePlatform = devicePlatform.orEmpty(),
                ),
            )

        override suspend fun pollDeviceLoginAt(serverUrl: String, deviceCode: String): ApiResult<DeviceLoginPollResponse> {
            polls++
            polledCodes += deviceCode
            val took = pollDelayMs
            if (took > 0) {
                if (pollsIgnoreCancel) withContext(NonCancellable) { delay(took) } else delay(took)
            }
            return ApiResult.Success(
                if (polls >= approveAfterPolls || approveWhen()) {
                    DeviceLoginPollResponse(
                        status = "approved",
                        accessToken = "qr-access",
                        refreshToken = "qr-refresh",
                        expiresIn = 3600,
                        user = User(id = "1", username = "laura", email = "laura@example.test", role = "user"),
                    )
                } else {
                    DeviceLoginPollResponse(status = statusFor(deviceCode))
                },
            )
        }

        override suspend fun cancelDeviceLoginAt(serverUrl: String, deviceCode: String): ApiResult<DeviceLoginCancelResponse> {
            canceled += deviceCode
            return ApiResult.Success(DeviceLoginCancelResponse("canceled"))
        }

        override suspend fun startDeviceLogin(deviceName: String?, devicePlatform: String?) = error("unscoped")
        override suspend fun pollDeviceLogin(deviceCode: String) = error("unscoped")
        override suspend fun lookupDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginLookupResponse> = error("unused")
        override suspend fun approveDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginDecisionResponse> = error("unused")
        override suspend fun denyDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginDecisionResponse> = error("unused")
    }

    private class FakeRegistry : ServerRegistry {
        private val entry = ServerEntry(id = "entry-1", url = "https://silo.test", fetchedName = "Silo")
        override val entries = MutableStateFlow(listOf(entry))
        override val activeServerId = MutableStateFlow<String?>(entry.id)
        override val activeEntry = MutableStateFlow<ServerEntry?>(entry)
        override suspend fun addOrUpdate(url: String, fetchedName: String?): String = entry.id
        override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
        override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
        override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun signOut(serverId: String) = Unit
        override suspend fun switchTo(serverId: String) = Unit
        override suspend fun touchActive() = Unit
    }

    private class FakeIdentityApi : ServerIdentityApi {
        var probes = 0
        override suspend fun probeIdentity(serverUrl: String): ServerIdentityProbe {
            probes++
            return ServerIdentityProbe.Identity("server-1")
        }
        override suspend fun connections(scope: AuthScopeSnapshot): ServerConnections? = null
    }

    /** Provider discovery; [providers] null = a server that can't list them. */
    private class FakeSignInApi(var providers: SignInProviders?) : ExternalSignInApi {
        val asked = mutableListOf<String>()
        override suspend fun listProviders(serverUrl: String): ApiResult<SignInProviders> {
            asked += serverUrl
            return providers?.let { ApiResult.Success(it) } ?: ApiResult.Error(404, "not_found", "")
        }
        override suspend fun oauthCapabilities(serverUrl: String): ApiResult<OAuthHandshakeCapabilities> =
            ApiResult.Success(OAuthHandshakeCapabilities(available = true, native = true, linking = true))
        override suspend fun externalSignInCapabilities(scope: AuthScopeSnapshot) = error("TVs have no Sign-in settings")
        override suspend fun completeOAuthLogin(serverUrl: String, code: String, codeVerifier: String) = error("TVs never run OAuth")
        override suspend fun listIdentities(scope: AuthScopeSnapshot) = error("unused")
        override suspend fun deleteIdentity(scope: AuthScopeSnapshot, identityId: String) = error("unused")
        override suspend fun createLinkTicket(scope: AuthScopeSnapshot, installationId: String, password: String) = error("unused")
        override suspend fun completeLink(scope: AuthScopeSnapshot, code: String, codeVerifier: String) = error("unused")
        override suspend fun linkWithCredentials(
            scope: AuthScopeSnapshot, installationId: String, password: String, username: String, directoryPassword: String,
        ) = error("unused")
    }

    private class FakeTokens(
        var nullExpectations: Int = 0,
        var failInstall: Boolean = false,
        /** Fail only the first install, then work. */
        var failFirstInstall: Boolean = false,
    ) : TokenManager {
        private var generation = 0L
        var accessToken: String? = null
        private var refreshToken: String? = null
        var installAttempts = 0

        override suspend fun captureAccountSessionExpectation(): AccountSessionExpectation? {
            if (nullExpectations > 0) {
                nullExpectations--
                return null
            }
            return AccountSessionExpectation(generation, null, "https://silo.test")
        }

        override suspend fun replaceAccountSession(
            serverId: String?, serverUrl: String?, accessToken: String, refreshToken: String,
            expiresIn: Long, profileId: String?, profileToken: String?, expectedIdentity: AccountSessionExpectation?,
        ) {
            if (expectedIdentity != null && expectedIdentity.generation != generation) throw AccountSessionChangedException()
            installAttempts++
            if (failInstall || (failFirstInstall && installAttempts == 1)) throw IllegalStateException("keystore unavailable")
            generation++
            saveTokens(accessToken, refreshToken, expiresIn)
        }

        override val sessionExpired = MutableSharedFlow<Unit>()
        override suspend fun getAccessToken(): String? = accessToken
        override suspend fun getRefreshToken(): String? = refreshToken
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
            this.accessToken = accessToken
            this.refreshToken = refreshToken
        }
        override suspend fun clearTokens() {
            accessToken = null
            refreshToken = null
        }
        override suspend fun invalidateSession() = Unit
        override suspend fun getProfileId(): String? = null
        override suspend fun setProfileId(profileId: String?) = Unit
        override suspend fun getProfileToken(): String? = null
        override suspend fun setProfileToken(token: String?) = Unit
        override suspend fun getServerUrl(): String = "https://silo.test"
        override suspend fun setServerUrl(url: String) = Unit
        override suspend fun getCurrentServerId(): String? = null
        override suspend fun switchActiveServer(serverId: String?) = Unit
        override suspend fun signOutCurrentServer() = Unit
    }

    private companion object {
        val OIDC = SignInProvider(
            "plugin:5:oidc", "Keycloak", SignInProvider.Mode.OAuth, false, null, "5",
            "/api/v2/auth/oauth/5/native/start",
        )
        val LDAP = SignInProvider("plugin:6:ldap", "Directory", SignInProvider.Mode.Credentials, false, null, "6", null)
        val LOCAL = SignInProvider("local", "Silo account", SignInProvider.Mode.Credentials, true, null, null, null)
        val LOGIN_JSON = """
            {"access_token":"credential-access","refresh_token":"credential-refresh","expires_in":3600,
             "user":{"id":"1","username":"jim","email":"jim@example.com","role":"user","download_allowed":true}}
        """.trimIndent()
    }
}
