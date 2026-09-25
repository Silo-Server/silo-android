package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.WatchTogetherCapabilitiesV2
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.apiv2.PlaybackCapabilitiesV2
import org.siloserver.silo.repository.WatchPartyEndReason
import org.siloserver.silo.repository.WatchPartyEnded
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartyAvailabilityAndRecentsTest {
    private val scopeA = AuthScopeSnapshot(serverId = "s", profileId = "p1", serverUrl = "https://a", profileToken = null, identityGeneration = 1)
    private val roomCaps = WatchTogetherCapabilitiesV2(
        state = "available", allowed = true, stagedSelection = true, lobbyReady = true, connectionReplaced = true,
        socketProtocol = "silo.room.v2",
    )
    private val playbackCaps = PlaybackCapabilitiesV2(
        state = "available", allowed = true, protocolVersions = listOf(3),
        features = listOf(WatchPartyPlaybackFeatures.Coordinator, WatchPartyPlaybackFeatures.FixedMediaFile),
    )

    private class Probe(var room: ApiResult<WatchTogetherCapabilitiesV2>, var playback: ApiResult<PlaybackCapabilitiesV2>) {
        var calls = 0
    }

    private fun availability(probe: Probe, scope: () -> AuthScopeSnapshot?) = WatchPartyAvailabilityRepository(
        roomCapabilities = { probe.calls++; probe.room },
        playbackCapabilities = { probe.playback },
        authScopeProvider = { scope() },
    )

    @Test
    fun `a definite answer is cached for the authority`() = runTest {
        val probe = Probe(ApiResult.Success(roomCaps), ApiResult.Success(playbackCaps))
        val repo = availability(probe) { scopeA }

        assertIs<WatchPartyAvailability.Available>(repo.refresh())
        assertIs<WatchPartyAvailability.Available>(repo.refresh())
        assertEquals(1, probe.calls)
        assertTrue(repo.features!!.stagedSelection)
    }

    @Test
    fun `a failed probe keeps a known answer and reports failure only when nothing is known`() = runTest {
        val probe = Probe(ApiResult.NetworkError(RuntimeException("offline")), ApiResult.Success(playbackCaps))
        val repo = availability(probe) { scopeA }

        assertIs<WatchPartyAvailability.ProbeFailed>(repo.refresh())
        probe.room = ApiResult.Success(roomCaps)
        assertIs<WatchPartyAvailability.Available>(repo.refresh())
        probe.room = ApiResult.NetworkError(RuntimeException("offline"))
        assertIs<WatchPartyAvailability.Available>(repo.refresh(force = true))
    }

    @Test
    fun `a profile change discards the cached answer`() = runTest {
        var scope = scopeA
        val probe = Probe(ApiResult.Success(roomCaps), ApiResult.Success(playbackCaps))
        val repo = availability(probe) { scope }
        repo.refresh()

        scope = scopeA.copy(profileId = "p2")
        probe.playback = ApiResult.Success(playbackCaps.copy(allowed = false))

        assertEquals(WatchPartyAvailability.NotAllowed, repo.refresh())
        assertEquals(2, probe.calls)
    }

    @Test
    fun `a server without rooms is unsupported without reading playback`() = runTest {
        val probe = Probe(
            ApiResult.Success(WatchTogetherCapabilitiesV2(state = "not_configured")),
            ApiResult.NetworkError(RuntimeException("must not be read")),
        )
        assertIs<WatchPartyAvailability.Unsupported>(availability(probe) { scopeA }.refresh())
    }

    // ---- recent party ------------------------------------------------------------

    private class MemoryStorage : RecentWatchPartyStorage {
        var value: String? = null
        override fun read() = value
        override fun write(value: String?) { this.value = value }
    }

    private val ownerA = RecentPartyOwner("server", "login-1", "profile-1")

    @Test
    fun `recent party is shown only to its owner and only for a day`() = runTest {
        var owner: RecentPartyOwner? = ownerA
        var now = 1_000L
        val storage = MemoryStorage()
        val recents = RecentWatchParties(storage, owner = { owner }, nowEpochMs = { now })

        recents.remember("room-1", "K7PQ2M4X", wasHost = true)
        assertEquals("K7PQ2M4X", recents.current()?.code)
        assertFalse(storage.value!!.contains("token"))

        owner = ownerA.copy(profileId = "profile-2")
        assertNull(recents.current())
        owner = ownerA
        now += 24 * 60 * 60_000L + 1
        assertNull(recents.current())
        assertNull(storage.value)
    }

    @Test
    fun `recorder keeps a replaced party and forgets an ended one`() = runTest {
        val storage = MemoryStorage()
        val recents = RecentWatchParties(storage, owner = { ownerA }, nowEpochMs = { 5L })
        val room = MutableStateFlow<RoomSnapshot?>(null)
        val ended = MutableStateFlow<WatchPartyEnded?>(null)
        val barrier = DefaultIdentityTransitionBarrier()
        WatchPartyRecentsRecorder(recents, room, ended, backgroundScope, barrier)

        room.value = RoomSnapshot(roomId = "room-1", code = "K7PQ2M4X", selfRole = MemberRole.Guest)
        runCurrent()
        assertEquals("room-1", recents.current()?.roomId)

        room.value = null
        ended.value = WatchPartyEnded("room-1", "K7PQ2M4X", WatchPartyEndReason.Replaced, wasHost = false)
        runCurrent()
        assertEquals("room-1", recents.current()?.roomId)

        ended.value = WatchPartyEnded("room-1", "K7PQ2M4X", WatchPartyEndReason.HostLeft, wasHost = false)
        runCurrent()
        assertNull(recents.current())
    }

    @Test
    fun `sign out clears the recent party before the identity changes`() = runTest {
        val storage = MemoryStorage()
        val recents = RecentWatchParties(storage, owner = { ownerA }, nowEpochMs = { 5L })
        val barrier = DefaultIdentityTransitionBarrier()
        WatchPartyRecentsRecorder(recents, MutableStateFlow(null), MutableStateFlow(null), backgroundScope, barrier)
        recents.remember("room-1", "K7PQ2M4X", wasHost = false)

        barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) {}
        assertEquals("room-1", recents.current()?.roomId)
        barrier.changing(IdentityTransitionKind.SIGN_OUT) { assertNull(storage.value) }
    }
}
