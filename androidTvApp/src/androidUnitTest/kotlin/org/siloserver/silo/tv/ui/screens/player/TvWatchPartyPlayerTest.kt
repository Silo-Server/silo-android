package org.siloserver.silo.tv.ui.screens.player

import androidx.media3.common.Player
import org.siloserver.silo.watchtogether.RoomPlaybackNotice
import org.siloserver.silo.watchtogether.RoomPlayerState
import org.siloserver.silo.watchtogether.RoomTransportIntent
import org.siloserver.silo.watchtogether.WatchPartyPlaybackContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvWatchPartyPlayerTest {
    private val room = WatchPartyPlaybackContext(
        roomId = "room-1",
        selectionRevision = 7L,
        contentId = "movie-1",
        fileId = 42,
        libraryId = 3,
        positionSeconds = 120.0,
        paused = true,
    )

    private val viewModelSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt",
    ).readText()

    // ---- Room start and restarts ----------------------------------------------

    @Test
    fun `a first room start uses the room position and file`() {
        val start = tvRoomStartContext(
            context = room,
            startPositionOverride = room.positionSeconds,
            currentSourcePositionSeconds = 0.0,
            preparedRevision = -1L,
        )

        assertEquals(room, start)
    }

    @Test
    fun `a restart of the prepared revision resumes where the player was, on the same file`() {
        val start = tvRoomStartContext(
            context = room,
            startPositionOverride = null,
            currentSourcePositionSeconds = 845.5,
            preparedRevision = room.selectionRevision,
        )

        assertEquals(845.5, start?.positionSeconds)
        assertEquals(room.fileId, start?.fileId)
        assertEquals(room.selectionRevision, start?.selectionRevision)
    }

    @Test
    fun `a restart before the revision was prepared keeps the room position`() {
        val start = tvRoomStartContext(
            context = room,
            startPositionOverride = null,
            currentSourcePositionSeconds = 845.5,
            preparedRevision = room.selectionRevision - 1,
        )

        assertEquals(room.positionSeconds, start?.positionSeconds)
    }

    @Test
    fun `a retry position from the previous title never carries into a new revision`() {
        // Retry passes the player's last position, which still belongs to the
        // previous revision until the new session publishes.
        val start = tvRoomStartContext(
            context = room,
            startPositionOverride = 1_800.0,
            currentSourcePositionSeconds = 1_800.0,
            preparedRevision = room.selectionRevision - 1,
        )

        assertEquals(room, start)
    }

    @Test
    fun `an explicit restart position wins and an invalid one falls back`() {
        assertEquals(
            300.0,
            tvRoomStartContext(room, 300.0, 845.5, room.selectionRevision)?.positionSeconds,
        )
        assertEquals(
            845.5,
            tvRoomStartContext(room, Double.NaN, 845.5, room.selectionRevision)?.positionSeconds,
        )
    }

    @Test
    fun `without a room context there is nothing to start`() {
        assertNull(tvRoomStartContext(null, 10.0, 0.0, -1L))
    }

    @Test
    fun `a room session is published paused and solo playback is not`() {
        assertTrue(tvPausedWhenReady(inWatchParty = true))
        assertFalse(tvPausedWhenReady(inWatchParty = false))
    }

    @Test
    fun `every room load reuses the room context and publishes paused`() {
        val load = viewModelSource
            .substringAfter("private fun loadContent(")
            .substringBefore("private fun startIntroAutoSkipObserver")

        assertTrue("tvRoomStartContext(" in load, "loadContent must build its start from the room context")
        assertTrue(
            "if (roomId != null && requestRoom == null) return" in load,
            "a room player must never start without the room's context",
        )
        assertTrue("room = requestRoom," in load, "the start request must carry the room context")
        assertTrue("isPaused = tvPausedWhenReady(inWatchParty = requestRoom != null)" in load)
    }

    @Test
    fun `a room player waits for the room instead of starting the route solo`() {
        val init = viewModelSource
            .substringAfter("sleepTimer.configure {")
            .substringBefore("fun onBackendCapabilities(")
        val roomBranch = init.indexOf("if (roomId != null) {")
        val soloLoad = init.indexOf("loadContent(startPositionOverride = resumePositionOverride)")

        assertTrue(roomBranch >= 0 && soloLoad > roomBranch, "the solo auto-load must sit behind the room check")
    }

    @Test
    fun `version and dolby vision restarts are refused in a room`() {
        val version = viewModelSource
            .substringAfter("fun onSelectFileVersion(")
            .substringBefore("private fun restartSessionInPlace(")
        val restart = viewModelSource
            .substringAfter("private fun restartSessionInPlace(")
            .substringBefore("fun retryServerReachability(")
        val dolbyVision = viewModelSource
            .substringAfter("fun onSetDolbyVisionEnabled(")
            .substringBefore("restartSessionInPlace(fileId)")

        assertTrue("if (roomId != null) return" in version)
        assertTrue("if (roomId != null) return" in restart)
        assertTrue("if (roomId != null) return@launch" in dolbyVision)
    }

    // ---- Speed ---------------------------------------------------------------

    @Test
    fun `a SiloCast speed change is refused in a room`() {
        assertNull(tvSiloCastPlaybackSpeed(inWatchParty = true, requested = 1.5))
        assertEquals(1.5, tvSiloCastPlaybackSpeed(inWatchParty = false, requested = 1.5))
        assertNull(tvSiloCastPlaybackSpeed(inWatchParty = false, requested = 0.0))
        assertNull(tvSiloCastPlaybackSpeed(inWatchParty = false, requested = Double.NaN))
    }

    @Test
    fun `the saved speed preference is never written from a room`() {
        val setter = viewModelSource
            .substringAfter("fun onSetPlaybackSpeed(value: Double) {")
            .substringBefore("fun onSetIntroSkipMode(")
        val guard = setter.indexOf("if (roomId != null) return")
        val write = setter.indexOf("setPlaybackSpeed(value)")

        assertTrue(guard >= 0 && write > guard, "the room guard must precede the preference write")
    }

    // ---- Buffered positions ------------------------------------------------------

    @Test
    fun `a target inside the buffered window of the mounted item is buffered`() {
        assertTrue(tvRoomPositionBuffered(targetPlayerSeconds = 105.0, currentPlayerSeconds = 100.0, bufferedPlayerSeconds = 130.0))
        assertTrue(tvRoomPositionBuffered(targetPlayerSeconds = 99.2, currentPlayerSeconds = 100.0, bufferedPlayerSeconds = 130.0))
        assertTrue(tvRoomPositionBuffered(targetPlayerSeconds = 130.0, currentPlayerSeconds = 100.0, bufferedPlayerSeconds = 130.0))
    }

    @Test
    fun `targets behind the playhead or past the buffer need new media`() {
        assertFalse(tvRoomPositionBuffered(targetPlayerSeconds = 98.5, currentPlayerSeconds = 100.0, bufferedPlayerSeconds = 130.0))
        assertFalse(tvRoomPositionBuffered(targetPlayerSeconds = 131.0, currentPlayerSeconds = 100.0, bufferedPlayerSeconds = 130.0))
    }

    @Test
    fun `inconsistent or unknown positions are never buffered`() {
        assertFalse(tvRoomPositionBuffered(targetPlayerSeconds = 100.0, currentPlayerSeconds = 100.0, bufferedPlayerSeconds = 90.0))
        assertFalse(tvRoomPositionBuffered(targetPlayerSeconds = Double.NaN, currentPlayerSeconds = 100.0, bufferedPlayerSeconds = 130.0))
        assertFalse(tvRoomPositionBuffered(targetPlayerSeconds = 100.0, currentPlayerSeconds = Double.NaN, bufferedPlayerSeconds = 130.0))
    }

    // ---- External seeks ----------------------------------------------------------

    @Test
    fun `a seek this screen issued is recognised by its target`() {
        val seeks = TvRoomIssuedSeeks()
        seeks.record(targetPlayerMs = 60_000L, nowMs = 1_000L)

        assertTrue(seeks.isOwn(targetPlayerMs = 60_000L, nowMs = 1_100L))
        assertTrue(seeks.isOwn(targetPlayerMs = 60_400L, nowMs = 1_100L), "Media3 may land slightly off the target")
    }

    @Test
    fun `a seek to another position is external`() {
        val seeks = TvRoomIssuedSeeks()
        seeks.record(targetPlayerMs = 60_000L, nowMs = 1_000L)

        assertFalse(seeks.isOwn(targetPlayerMs = 90_000L, nowMs = 1_100L))
        assertFalse(TvRoomIssuedSeeks().isOwn(targetPlayerMs = 60_000L, nowMs = 1_100L))
    }

    @Test
    fun `an issued seek is forgotten after a short while`() {
        val seeks = TvRoomIssuedSeeks(lifetimeMs = 3_000L)
        seeks.record(targetPlayerMs = 60_000L, nowMs = 1_000L)

        assertFalse(seeks.isOwn(targetPlayerMs = 60_000L, nowMs = 4_500L))
    }

    // ---- Player state, notices, quality ---------------------------------------

    @Test
    fun `media3 states map to room player states`() {
        assertEquals(RoomPlayerState.Buffering, tvRoomPlayerState(Player.STATE_BUFFERING, hasMedia = true))
        assertEquals(RoomPlayerState.Ready, tvRoomPlayerState(Player.STATE_READY, hasMedia = true))
        assertEquals(RoomPlayerState.Ended, tvRoomPlayerState(Player.STATE_ENDED, hasMedia = true))
        assertEquals(RoomPlayerState.Idle, tvRoomPlayerState(Player.STATE_IDLE, hasMedia = true))
        assertEquals(RoomPlayerState.Idle, tvRoomPlayerState(Player.STATE_READY, hasMedia = false))
        assertEquals(RoomPlayerState.Idle, tvRoomPlayerState(null, hasMedia = true))
    }

    @Test
    fun `room notices use plain copy`() {
        assertEquals("Only the host can seek.", tvWatchPartyNoticeText(RoomPlaybackNotice.Denied(RoomTransportIntent.Seek)))
        assertEquals(
            "Only the host can play or pause.",
            tvWatchPartyNoticeText(RoomPlaybackNotice.Denied(RoomTransportIntent.PlayPause)),
        )
        assertEquals("Reconnecting to the party…", tvWatchPartyNoticeText(RoomPlaybackNotice.Reconnecting))
        assertEquals("Couldn't reach the party. Try again.", tvWatchPartyNoticeText(RoomPlaybackNotice.Undelivered))
    }

    @Test
    fun `the lower quality offer is one rung below the current height`() {
        val ladder = listOf(
            VideoQualityOption(id = "auto", label = "Auto", isSelected = false),
            VideoQualityOption(id = "original", label = "Original", isSelected = true, resolution = "1080p"),
            VideoQualityOption(id = "1080p", label = "1080p", isSelected = false, resolution = "1080p"),
            VideoQualityOption(id = "720p", label = "720p", isSelected = false, resolution = "720p"),
            VideoQualityOption(id = "480p", label = "480p", isSelected = false, resolution = "480p"),
        )

        assertEquals("720p", tvLowerQualityOption(ladder, fallbackCurrentHeight = null)?.id)
        val onSeven = ladder.map { it.copy(isSelected = it.id == "720p") }
        assertEquals("480p", tvLowerQualityOption(onSeven, fallbackCurrentHeight = null)?.id)
        val onBottom = ladder.map { it.copy(isSelected = it.id == "480p") }
        assertNull(tvLowerQualityOption(onBottom, fallbackCurrentHeight = null), "no rung below: no offer")
    }

    @Test
    fun `auto uses the playing resolution to find the next rung`() {
        val ladder = listOf(
            VideoQualityOption(id = "auto", label = "Auto", isSelected = true),
            VideoQualityOption(id = "1080p", label = "1080p", isSelected = false, resolution = "1080p"),
            VideoQualityOption(id = "720p", label = "720p", isSelected = false, resolution = "720p"),
        )

        assertEquals("720p", tvLowerQualityOption(ladder, fallbackCurrentHeight = tvQualityHeight("1080p"))?.id)
        assertEquals("1080p", tvLowerQualityOption(ladder, fallbackCurrentHeight = tvQualityHeight("4K"))?.id)
    }
}
