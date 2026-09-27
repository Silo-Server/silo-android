package org.siloserver.silo.android.ui.screens.player

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.siloserver.silo.common.player.watchparty.WatchPartyPlayback
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.api.DefaultWatchTogetherApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.watchtogether.RoomPlayerObservation
import org.siloserver.silo.watchtogether.RoomPlayerPort
import org.siloserver.silo.watchtogether.RoomSession
import org.siloserver.silo.watchtogether.RoomTransportResult
import org.siloserver.silo.watchtogether.WatchPartyAvailabilityRepository
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WatchPartyExternalSeekTest {
    @Test
    fun everyRapidDeniedSeekReturnsToThePreviousPosition() = runTest {
        withGuestPlayback { playback ->
            var position = 20.0
            val restored = mutableListOf<Double>()
            for (target in listOf(80.0, 100.0, 120.0, 140.0, 160.0)) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(300))
                val previous = position
                position = target
                val result = playback.onExternalSeek(previous, target) {
                    restored += it
                    position = it
                }
                assertEquals(RoomTransportResult.Denied, result)
                assertEquals(20.0, position, "The guest's seek to $target was left applied")
            }
            assertEquals(List(5) { 20.0 }, restored)
        }
    }

    @Test
    fun duplicateControllerAndSessionCallbacksDoNotUndoRoomSeeksOrRestores() = runTest {
        withGuestPlayback { playback ->
            val issued = IssuedSeekTracker { 0L }
            var outsideSeeks = 0
            val restores = mutableListOf<Double>()
            fun discontinuity(fromMs: Long, toMs: Long) {
                if (!issued.isExternal(toMs)) return
                check(++outsideSeeks < 10) { "Restoration callbacks started a seek loop" }
                playback.onExternalSeek(fromMs / 1_000.0, toMs / 1_000.0) { restore ->
                    restores += restore
                    val restoreMs = (restore * 1_000).toLong()
                    // PlayerScreen records before seekTo; MediaController
                    // emits immediately, then the session reports it again.
                    issued.note(restoreMs)
                    repeat(2) { discontinuity(toMs, restoreMs) }
                }
            }

            issued.note(60_000L)
            repeat(2) { discontinuity(20_000L, 60_000L) }
            assertEquals(0, outsideSeeks)

            discontinuity(60_000L, 120_000L)
            assertEquals(1, outsideSeeks)
            assertEquals(listOf(60.0), restores)
        }
    }

    private suspend fun TestScope.withGuestPlayback(block: (WatchPartyPlayback) -> Unit) {
        val client = HttpClient(MockEngine {
            respond(
                """{"room":{"room_id":"room","phase":"playing","self_role":"guest"},"room_access_token":"proof"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { install(ContentNegotiation) { json() } }
        try {
            val authority = AuthScopeSnapshot(
                serverId = "server", profileId = "profile", serverUrl = "https://silo.example",
                profileToken = null, identityGeneration = 1,
            )
            val repository = WatchTogetherRepository(
                api = DefaultWatchTogetherApi(client, ApiV2Gate.Unrestricted),
                authScopeProvider = { authority },
            )
            assertIs<ApiResult.Success<*>>(repository.joinRoom(JoinRoomRequest(code = "K7PQ2M4X")))
            val session = RoomSession(repository, backgroundScope, DefaultIdentityTransitionBarrier())
            val availability = WatchPartyAvailabilityRepository(
                roomCapabilities = { error("This test does not probe capabilities") },
                playbackCapabilities = { error("This test does not probe capabilities") },
                authScopeProvider = { authority },
            )
            val player = object : RoomPlayerPort {
                override val observations = MutableStateFlow(RoomPlayerObservation())
                override fun seekTo(sourceSeconds: Double) = Unit
                override fun setPlaying(playing: Boolean) = Unit
                override fun setCorrectionRate(rate: Double?) = Unit
                override fun isBuffered(sourceSeconds: Double) = true
            }
            val playback = WatchPartyPlayback("room", repository, session, availability, player, backgroundScope)
            try {
                block(playback)
            } finally {
                playback.dispose()
            }
        } finally {
            client.close()
        }
    }
}
