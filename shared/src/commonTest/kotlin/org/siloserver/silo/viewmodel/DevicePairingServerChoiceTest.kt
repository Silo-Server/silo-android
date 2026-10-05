package org.siloserver.silo.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.api.DeviceLoginApi
import org.siloserver.silo.repository.DeviceLoginRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Sign in a TV": a typed code is looked up on ONE chosen server (the active
 * one preselected), never on every saved server; a miss says so and offers
 * the others.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicePairingServerChoiceTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val home = DeviceApprovalServer("a", "https://home.example", "Home", isActive = true)
    private val cabin = DeviceApprovalServer("b", "https://cabin.example", "Cabin")

    private class ScopedApi(private val codesOn: Map<String, String>) : DeviceLoginApi {
        val lookedUpOn = mutableListOf<String>()
        val approvedOn = mutableListOf<Pair<String, String>>()
        var unscopedLookups = 0

        /** What a lookup answers after this phone approved; "pending" until then. */
        var statusAfterApproval = "pending"
        private var approved = false

        var onLookup: (String) -> Unit = {}

        /** Servers whose session is dead: approving there answers 401. */
        var signedOutOn: Set<String> = emptySet()

        /** Servers whose provider can't re-check the approver's session. */
        var providerDownOn: Set<String> = emptySet()

        override suspend fun lookupDeviceLoginForScope(scope: AuthScopeSnapshot, code: String): ApiResult<DeviceLoginLookupResponse> {
            lookedUpOn += scope.serverId
            onLookup(scope.serverId)
            return if (codesOn[code] == scope.serverId) {
                ApiResult.Success(
                    DeviceLoginLookupResponse(
                        status = if (approved) statusAfterApproval else "pending",
                        userCode = "4821-7730",
                        deviceName = "Living Room TV",
                        devicePlatform = "android-tv",
                        serverName = "Home",
                    ),
                )
            } else {
                ApiResult.Error(404, "not_found", "Device login request not found")
            }
        }

        override suspend fun approveDeviceLoginForScope(scope: AuthScopeSnapshot, code: String): ApiResult<DeviceLoginDecisionResponse> {
            if (scope.serverId in signedOutOn) return ApiResult.Error(401, "unauthorized", "")
            if (scope.serverId in providerDownOn) {
                return ApiResult.NetworkError(
                    org.siloserver.silo.network.SiloAuthUnavailableException(
                        org.siloserver.silo.network.SiloAuthUnavailableException.PROVIDER_UNAVAILABLE,
                    ),
                )
            }
            approvedOn += scope.serverId to code
            approved = true
            return ApiResult.Success(DeviceLoginDecisionResponse("approved"))
        }

        override suspend fun lookupDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginLookupResponse> {
            unscopedLookups++
            return ApiResult.Error(404, "not_found", "Device login request not found")
        }
        override suspend fun approveDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginDecisionResponse> = error("unscoped")
        override suspend fun denyDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginDecisionResponse> = error("unscoped")
        override suspend fun startDeviceLogin(deviceName: String?, devicePlatform: String?): ApiResult<DeviceLoginStartResponse> = error("unused")
        override suspend fun pollDeviceLogin(deviceCode: String): ApiResult<DeviceLoginPollResponse> = error("unused")
    }

    /** Every account read, in order, with the lookups: the approver's refresh point. */
    private val events = mutableListOf<String>()

    /** The account each server's own scope reads; a missing entry is a failed read. */
    private val accounts = mutableMapOf("a" to "laura")

    /** Servers whose provider can't re-check the session the account read renews. */
    private val providerDownFor = mutableSetOf<String>()

    private fun servers(vararg list: DeviceApprovalServer) = object : DeviceApprovalServers {
        override suspend fun list() = list.toList()
        override fun scope(server: DeviceApprovalServer) = AuthScopeSnapshot(server.id, null, server.url, null)
        override suspend fun accountName(server: DeviceApprovalServer): String? {
            events += "account:${server.id}"
            if (server.id in providerDownFor) {
                throw org.siloserver.silo.network.SiloAuthUnavailableException(
                    org.siloserver.silo.network.SiloAuthUnavailableException.PROVIDER_UNAVAILABLE,
                )
            }
            return accounts[server.id]
        }
    }

    @Test
    fun activeServerIsPreselectedAndTheCodeIsLookedUpThereOnly() = runTest {
        val api = ScopedApi(mapOf("48217730" to "a"))
        val vm = DevicePairingViewModel(DeviceLoginRepository(api), null, null, servers(cabin, home))
        assertEquals("a", vm.uiState.value.selectedServerId)
        assertEquals("laura", vm.uiState.value.accountName)

        vm.onCodeChanged("4821 7730")
        assertEquals("48217730", vm.uiState.value.code)
        vm.lookup()
        assertEquals(listOf("a"), api.lookedUpOn)
        assertEquals("Living Room TV", vm.uiState.value.lookup?.deviceName)

        vm.approve()
        assertEquals(listOf("a" to "48217730"), api.approvedOn)
        assertEquals("approved", vm.uiState.value.completedStatus)
    }

    /** Older servers issue letter codes; the phone's code field must still take them as typed. */
    @Test
    fun aLetterCodeFromAnOlderServerIsTypedAndLookedUp() = runTest {
        val api = ScopedApi(mapOf("ABCDEFGH" to "a"))
        val vm = DevicePairingViewModel(DeviceLoginRepository(api), null, null, servers(cabin, home))
        vm.onCodeChanged("abcd-efgh")
        assertEquals("ABCDEFGH", vm.uiState.value.code)
        vm.lookup()
        assertEquals(listOf("a"), api.lookedUpOn)
        assertEquals("Living Room TV", vm.uiState.value.lookup?.deviceName)
    }

    @Test
    fun aMissSaysSoAndOffersTheOtherServers() = runTest {
        val api = ScopedApi(mapOf("48217730" to "b"))
        val vm = DevicePairingViewModel(DeviceLoginRepository(api), null, null, servers(home, cabin))
        vm.onCodeChanged("48217730")
        vm.lookup()
        assertEquals(listOf("a"), api.lookedUpOn, "never looked up on every server")
        assertTrue(vm.uiState.value.notFound)
        assertEquals(DevicePairingError.NotFoundOn("Home"), vm.uiState.value.error)
        assertEquals(listOf(cabin), vm.uiState.value.otherServers)

        vm.selectServer("b")
        assertEquals(listOf("a", "b"), api.lookedUpOn)
        assertNull(vm.uiState.value.error)
        assertEquals("Living Room TV", vm.uiState.value.lookup?.deviceName)
        assertNull(vm.uiState.value.accountName)
    }

    @Test
    fun aLinkPreselectsItsServer() = runTest {
        val api = ScopedApi(mapOf("48217730" to "b"))
        val vm = DevicePairingViewModel(DeviceLoginRepository(api), null, "4821-7730", servers(home, cabin), initialServerId = "b")
        assertEquals("b", vm.uiState.value.selectedServerId)
        assertEquals(listOf("b"), api.lookedUpOn)
        assertEquals("Living Room TV", vm.uiState.value.lookup?.deviceName)
    }

    @Test
    fun aTokenLinkUsesTheActiveServerWithoutAChooserOrOtherServers() = runTest {
        val api = ScopedApi(emptyMap())
        val vm = DevicePairingViewModel(DeviceLoginRepository(api), "tok", null, servers(cabin, home), initialServerId = "b")
        assertEquals("a", vm.uiState.value.selectedServerId, "the card names the server the token goes to")
        assertEquals(false, vm.uiState.value.canChooseServer)
        assertEquals(1, api.unscopedLookups)
        assertEquals(DevicePairingError.NotFound, vm.uiState.value.error)
        assertEquals(false, vm.uiState.value.notFound, "no 'Try <server>' for a token")

        vm.selectServer("b")
        assertEquals("a", vm.uiState.value.selectedServerId)
        assertEquals(1, api.unscopedLookups)
    }

    @Test
    fun afterApprovingTheResultFollowsHowTheRequestEnded() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for ((status, outcome) in listOf(
            "consumed" to DeviceFollowOutcome.SignedIn,
            "canceled" to DeviceFollowOutcome.Canceled,
            "expired" to DeviceFollowOutcome.Expired,
            "denied" to DeviceFollowOutcome.Denied,
            "approved" to DeviceFollowOutcome.TimedOut,
        )) {
            val api = ScopedApi(mapOf("48217730" to "a")).apply { statusAfterApproval = status }
            val vm = DevicePairingViewModel(DeviceLoginRepository(api), null, "48217730", servers(home))
            advanceUntilIdle()
            vm.approve()
            runCurrent()
            assertNull(vm.uiState.value.followOutcome, "signing in until the TV says otherwise")
            advanceUntilIdle()
            assertEquals(outcome, vm.uiState.value.followOutcome, status)
        }
    }

    @Test
    fun aLinkNamingAnotherSavedServerApprovesThereOnly() = runTest {
        accounts["b"] = "laura.cabin"
        val api = ScopedApi(mapOf("48217730" to "b"))
        val vm = DevicePairingViewModel(
            DeviceLoginRepository(api), null, "48217730", servers(home, cabin),
            initialServerId = "b", lockServer = true,
        )
        assertEquals("b", vm.uiState.value.selectedServerId)
        assertEquals(false, vm.uiState.value.canChooseServer, "the link named the server")
        assertEquals(listOf("b"), api.lookedUpOn)

        vm.approve()
        assertEquals(listOf("b" to "48217730"), api.approvedOn, "approved with that server's own scope")
        assertEquals(0, api.unscopedLookups)
    }

    @Test
    fun aLockedServerThisPhoneIsntSignedInToAsksToSignInRatherThanTryingAnother() = runTest {
        val api = ScopedApi(mapOf("48217730" to "a"))
        val vm = DevicePairingViewModel(
            DeviceLoginRepository(api), null, "48217730", servers(home),
            initialServerId = "b", lockServer = true,
        )
        assertEquals(DevicePairingError.SignInFirst, vm.uiState.value.error)
        assertNull(vm.uiState.value.selectedServerId)
        assertEquals(emptyList(), api.lookedUpOn, "never looked up on the active server instead")
        assertEquals(0, api.unscopedLookups)

        vm.lookup()
        vm.approve()
        assertEquals(emptyList(), api.lookedUpOn)
        assertEquals(emptyList(), api.approvedOn)
    }

    @Test
    fun theAccountIsReadBeforeEveryLookup() = runTest {
        val api = ScopedApi(mapOf("48217730" to "b"))
        api.onLookup = { events += "lookup:$it" }
        val vm = DevicePairingViewModel(DeviceLoginRepository(api), null, null, servers(home, cabin))
        events.clear()
        vm.onCodeChanged("48217730")
        vm.lookup()
        vm.selectServer("b")
        assertEquals(listOf("account:a", "lookup:a", "account:b", "lookup:b"), events)
    }

    @Test
    fun anUnreadableAccountOnAnotherServerHoldsApprovalUntilARetryReadsIt() = runTest {
        val api = ScopedApi(mapOf("48217730" to "b"))
        val vm = DevicePairingViewModel(
            DeviceLoginRepository(api), null, "48217730", servers(home, cabin),
            initialServerId = "b", lockServer = true,
        )
        assertEquals("Living Room TV", vm.uiState.value.lookup?.deviceName)
        assertNull(vm.uiState.value.accountName)
        assertTrue(vm.uiState.value.accountUnconfirmed, "the card says it couldn't confirm the account")
        assertEquals(false, vm.uiState.value.canApprove)
        assertEquals(true, vm.uiState.value.canDecide, "declining is still possible")
        vm.approve()
        assertEquals(emptyList(), api.approvedOn, "never approved for an account nobody saw")

        accounts["b"] = "laura.cabin"
        vm.retryAccount()
        assertEquals("laura.cabin", vm.uiState.value.accountName)
        assertEquals(false, vm.uiState.value.accountUnconfirmed)
        vm.approve()
        assertEquals(listOf("b" to "48217730"), api.approvedOn)
    }

    @Test
    fun theActiveServersAccountNeedNotBeReadToApprove() = runTest {
        accounts.clear()
        val api = ScopedApi(mapOf("48217730" to "a"))
        val vm = DevicePairingViewModel(DeviceLoginRepository(api), null, "48217730", servers(home, cabin))
        assertEquals(false, vm.uiState.value.accountUnconfirmed)
        assertEquals(true, vm.uiState.value.canApprove)
    }

    @Test
    fun aSignInFirstOnTheChosenServerKeepsThatServerAndTheTypedCode() = runTest {
        accounts["b"] = "laura.cabin"
        val api = ScopedApi(mapOf("48217730" to "b")).apply { signedOutOn = setOf("b") }
        val vm = DevicePairingViewModel(DeviceLoginRepository(api), null, null, servers(home, cabin))
        vm.selectServer("b")
        vm.onCodeChanged("4821 7730")
        vm.lookup()
        vm.approve()
        val state = vm.uiState.value
        assertEquals(DevicePairingError.SignInFirst, state.error)
        // What "Sign in" carries to the sign-in flow: that server, not the active one.
        assertEquals("b", state.selectedServerId)
        assertEquals("48217730", state.code)
    }

    /**
     * The sign-in provider couldn't re-check this phone's session when it
     * renewed its bearer to approve: the session is kept and the person is
     * told to try again, not to sign in again.
     */
    @Test
    fun aProviderOutageKeepsTheSessionAndSaysSo() = runTest {
        val api = ScopedApi(mapOf("48217730" to "a")).apply { providerDownOn = setOf("a") }
        val vm = DevicePairingViewModel(DeviceLoginRepository(api), null, null, servers(home))
        vm.onCodeChanged("4821 7730")
        vm.lookup()
        vm.approve()
        assertEquals(DevicePairingError.ProviderUnavailable, vm.uiState.value.error)
        assertEquals(emptyList(), api.approvedOn)
    }

    /**
     * The account read on another saved server renews its bearer first, and
     * the provider couldn't re-check that session: the card says so rather
     * than "couldn't confirm the account", and approving waits for a retry.
     */
    @Test
    fun aProviderOutageOnTheAccountReadSaysSo() = runTest {
        providerDownFor += "b"
        val api = ScopedApi(mapOf("48217730" to "b"))
        val vm = DevicePairingViewModel(
            DeviceLoginRepository(api), null, "48217730", servers(home, cabin),
            initialServerId = "b", lockServer = true,
        )
        assertTrue(vm.uiState.value.accountUnconfirmed)
        assertEquals(DevicePairingError.ProviderUnavailable, vm.uiState.value.accountError)
        assertEquals(false, vm.uiState.value.canApprove)

        providerDownFor.clear()
        accounts["b"] = "laura.cabin"
        vm.retryAccount()
        assertNull(vm.uiState.value.accountError)
        assertEquals(true, vm.uiState.value.canApprove)
    }
}
