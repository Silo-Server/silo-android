package org.siloserver.silo.network

import kotlin.test.*

class RealtimeRecoveryTest {
    @Test fun opensDoNotResetOpaqueOrOutageBackoff() {
        var now = 0L
        val recovery = RealtimeBackoff({ now }, { 0 })
        assertEquals(1_000L, recovery.disconnected())
        recovery.useful() // a new open/initial snapshot supplies no 30-second recovery
        assertEquals(2_000L, recovery.disconnected())
        assertEquals(2_000L, recovery.refused("realtime_unavailable", 250))
        recovery.useful()
        assertEquals(4_000L, recovery.refused("realtime_unavailable", 250))
        assertEquals(8_000L, recovery.refused("realtime_unavailable", 250))
        assertEquals(16_000L, recovery.refused("realtime_unavailable", 250))
        assertEquals(30_000L, recovery.refused("realtime_unavailable", 250))
        recovery.useful(); now = 30_000; recovery.useful()
        assertFalse(recovery.overloaded)
        assertEquals(2_000L, recovery.refused("realtime_unavailable", 250))
    }

    @Test fun onlyTheNamed1008UsesInputShieldDelaysAndJitterNeverShortensMinimum() {
        assertFalse(RealtimeSocketClosed(1008, "subscribe required").inputShield)
        assertFalse(RealtimeSocketClosed(1000, "realtime input shield").inputShield)
        assertTrue(RealtimeSocketClosed(1008, "realtime input shield").inputShield)
        val recovery = RealtimeBackoff({ 0 }, { 250 })
        assertEquals(listOf(5_250L, 10_250L, 20_250L, 30_250L), List(4) { recovery.disconnected(inputShield = true) })
        assertEquals(60_250L, recovery.refused("realtime_rate_limited", 60_000))
    }

    @Test fun incompleteOrDownstreamErrorsNeverProveNoWork() {
        assertFalse(RealtimeRefusal("realtime_rate_limited", requestId = "a", retryAfterMs = 250).preWork)
        assertFalse(RealtimeRefusal("downstream", requestId = "a", retryAfterMs = 250, workStarted = false).preWork)
        assertFalse(RealtimeRefusal("realtime_unavailable", requestId = "a", retryAfterMs = 250, workStarted = true).preWork)
        assertTrue(RealtimeRefusal("realtime_unavailable", requestId = "a", retryAfterMs = 2000, workStarted = false).preWork)
    }
    @Test fun admissionOwnerDoesNotRequireASelectedProfileOrChangeOnRefresh() = kotlinx.coroutines.test.runTest {
        var scope = AuthScopeSnapshot("server", null, "https://example.invalid", null, identityGeneration = 1)
        val tokens = object : TokenManager by TokenManagerImpl(), DurableLoginAuthorityProvider {
            override suspend fun snapshotCurrentScope() = scope
            override suspend fun snapshotDurableLoginAuthority(): DurableLoginAuthority? = null
            override suspend fun loginSessionId(serverId: String) = "stable-login"
        }
        val owner = tokens.realtimeAdmissionOwner(scope)
        assertEquals(RealtimeAdmissionOwner("server", "https://example.invalid", "stable-login"), owner)
        scope = scope.copy(profileId = "profile", profileToken = "synthetic-proof", identityGeneration = 2, credentialEpoch = 5)
        assertEquals(owner, tokens.realtimeAdmissionOwner(scope))
    }

    @Test fun changingAdmissionOwnerResetsOnlyAnUnrelatedLoginRecovery() {
        val recovery = RealtimeBackoff({ 0 }, { 0 })
        val first = RealtimeAdmissionOwner("server", "https://example.invalid", "login-a")
        recovery.bindAdmissionOwner(first)
        recovery.refused("realtime_rate_limited", 45_000, retainForAdmission = true)
        assertEquals(45_000L, recovery.disconnected(inputShield = true))
        recovery.bindAdmissionOwner(first.copy())
        assertEquals(45_000L, recovery.remainingAdmissionMs())
        assertTrue(recovery.overloaded)
        recovery.bindAdmissionOwner(first.copy(loginId = "login-b"))
        assertEquals(0L, recovery.remainingAdmissionMs()); assertFalse(recovery.overloaded)
        assertEquals(5_000L, recovery.disconnected(inputShield = true))
    }

}
