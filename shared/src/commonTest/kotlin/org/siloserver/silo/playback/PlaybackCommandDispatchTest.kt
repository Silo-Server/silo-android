package org.siloserver.silo.playback

import org.siloserver.silo.network.PlaybackRealtimeEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackCommandDispatchTest {
    private val json = Json
    private fun cmd(name: String, payload: String = "{}", issuedBy: String? = null) =
        PlaybackRealtimeEvent.Command("c1", "s1", name, json.parseToJsonElement(payload).jsonObject, issuedBy)

    @Test fun pauseMapsToPause() {
        assertEquals(PlaybackAction.Pause, decidePlaybackAction(cmd("pause")))
    }

    @Test fun seekReadsPositionSeconds() {
        assertEquals(PlaybackAction.SeekTo(42.5), decidePlaybackAction(cmd("seek", """{"position_seconds":42.5}""")))
    }

    @Test fun seekReadsPositionAlias() {
        assertEquals(PlaybackAction.SeekTo(10.0), decidePlaybackAction(cmd("seek", """{"position":10.0}""")))
    }

    @Test fun seekReadsSecondsAlias() {
        assertEquals(PlaybackAction.SeekTo(5.0), decidePlaybackAction(cmd("seek", """{"seconds":5.0}""")))
    }

    @Test fun displayMessageReadsMessage() {
        assertEquals(PlaybackAction.ShowMessage("hi"), decidePlaybackAction(cmd("display_message", """{"message":"hi"}""")))
    }

    @Test fun terminateMapsToStop() {
        assertEquals(PlaybackAction.Stop(), decidePlaybackAction(cmd("terminate")))
    }

    @Test fun anAdministratorsStopOrTerminateCarriesItsNotice() {
        assertEquals(
            PlaybackAction.Stop(ADMIN_ENDED_PLAYBACK_NOTICE),
            decidePlaybackAction(cmd("terminate", issuedBy = "admin")),
        )
        assertEquals(
            PlaybackAction.Stop(ADMIN_STOPPED_PLAYBACK_NOTICE),
            decidePlaybackAction(cmd("stop", issuedBy = "admin")),
        )
    }

    @Test fun anAdministratorsOwnMessageReplacesTheNotice() {
        assertEquals(
            PlaybackAction.Stop("Server maintenance at 10"),
            decidePlaybackAction(cmd("terminate", """{"message":"Server maintenance at 10"}""", issuedBy = "admin")),
        )
        assertEquals(
            PlaybackAction.Stop(ADMIN_STOPPED_PLAYBACK_NOTICE),
            decidePlaybackAction(cmd("stop", """{"message":" "}""", issuedBy = " Admin ")),
        )
    }

    @Test fun aStopNotIssuedByAnAdministratorIsSilent() {
        assertEquals(PlaybackAction.Stop(), decidePlaybackAction(cmd("stop")))
        assertEquals(PlaybackAction.Stop(), decidePlaybackAction(cmd("stop", """{"message":"hi"}""", issuedBy = "server")))
    }

    @Test fun serverRestartingIsIgnored() {
        assertEquals(PlaybackAction.Ignore, decidePlaybackAction(cmd("server_restarting")))
    }

    @Test fun setVolumeIsRejected(/* not advertised, so not actioned */) {
        assertEquals(PlaybackAction.Reject, decidePlaybackAction(cmd("set_volume", """{"volume":0.5}""")))
    }

    @Test fun unsupportedCommandIsRejected() {
        assertEquals(PlaybackAction.Reject, decidePlaybackAction(cmd("play_media")))
    }

    @Test fun malformedSeekIsRejected() {
        assertEquals(PlaybackAction.Reject, decidePlaybackAction(cmd("seek", """{}""")))
    }

    @Test fun quotedNumberSeekIsRejected() {
        assertEquals(PlaybackAction.Reject, decidePlaybackAction(cmd("seek", """{"position_seconds":"42"}""")))
    }

    @Test fun nonFiniteSeekIsRejected() {
        // JSON has no NaN/Infinity literal; a quoted "Infinity" must not seek.
        assertEquals(PlaybackAction.Reject, decidePlaybackAction(cmd("seek", """{"position_seconds":"Infinity"}""")))
    }

    @Test fun setAudioTrackReadsAudioTrackIndex() {
        assertEquals(
            PlaybackAction.SetAudioTrack(2),
            decidePlaybackAction(cmd("set_audio_track", """{"audio_track_index":2}""")),
        )
    }

    @Test fun setAudioTrackReadsIndexAlias() {
        assertEquals(
            PlaybackAction.SetAudioTrack(1),
            decidePlaybackAction(cmd("set_audio_track", """{"index":1}""")),
        )
    }

    @Test fun setSubtitleTrackReadsSubtitleTrackIndex() {
        assertEquals(
            PlaybackAction.SetSubtitleTrack(3),
            decidePlaybackAction(cmd("set_subtitle_track", """{"subtitle_track_index":3}""")),
        )
    }

    @Test fun setSubtitleTrackNegativeOneMeansOff() {
        assertEquals(
            PlaybackAction.SetSubtitleTrack(-1),
            decidePlaybackAction(cmd("set_subtitle_track", """{"subtitle_track_index":-1}""")),
        )
    }

    @Test fun trackCommandWithMissingIndexIsRejected() {
        assertEquals(PlaybackAction.Reject, decidePlaybackAction(cmd("set_audio_track", """{}""")))
    }

    @Test fun trackCommandWithQuotedIndexIsRejected() {
        assertEquals(
            PlaybackAction.Reject,
            decidePlaybackAction(cmd("set_subtitle_track", """{"subtitle_track_index":"2"}""")),
        )
    }

    @Test fun transportActionsAreFlaggedForWatchTogetherGating() {
        assertTrue(PlaybackAction.Pause.isTransport)
        assertTrue(PlaybackAction.Unpause.isTransport)
        assertTrue(PlaybackAction.TogglePlayPause.isTransport)
        assertTrue(PlaybackAction.SeekTo(1.0).isTransport)
        assertTrue(PlaybackAction.Stop().isTransport)
        // Informational / no-op actions are NOT transport (allowed during WT).
        assertFalse(PlaybackAction.ShowMessage("hi").isTransport)
        assertFalse(PlaybackAction.Ignore.isTransport)
        assertFalse(PlaybackAction.Reject.isTransport)
        // Track selection is per-client → NOT transport (allowed during WT).
        assertFalse(PlaybackAction.SetAudioTrack(0).isTransport)
        assertFalse(PlaybackAction.SetSubtitleTrack(-1).isTransport)
    }
}
