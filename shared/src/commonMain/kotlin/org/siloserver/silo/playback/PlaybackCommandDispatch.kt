package org.siloserver.silo.playback

import org.siloserver.silo.network.PlaybackCommandNames
import org.siloserver.silo.network.PlaybackRealtimeEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

private const val ISSUED_BY_ADMIN = "admin"

/**
 * Shown when an administrator ends the session: a terminate command, or the
 * server's 410 `playback_session_ended`. Apple and web use the same text.
 */
const val ADMIN_ENDED_PLAYBACK_NOTICE = "An administrator ended this playback session."

/** Shown when an administrator stops playback. Apple uses the same text. */
const val ADMIN_STOPPED_PLAYBACK_NOTICE = "An administrator stopped this playback session."

/** A resolved player action for a remote command. [Reject] → result status "rejected". */
sealed interface PlaybackAction {
    data object Pause : PlaybackAction
    data object Unpause : PlaybackAction
    data object TogglePlayPause : PlaybackAction
    data class SeekTo(val positionSeconds: Double) : PlaybackAction
    data class ShowMessage(val message: String) : PlaybackAction
    /** [notice] tells the viewer why playback ended; set only when an administrator stopped it. */
    data class Stop(val notice: String? = null) : PlaybackAction
    /** Re-select the audio track at this UI-list index (== the server's audio_track_index). */
    data class SetAudioTrack(val index: Int) : PlaybackAction
    /** Re-select the subtitle track at this index; -1 disables subtitles. */
    data class SetSubtitleTrack(val index: Int) : PlaybackAction
    data object Ignore : PlaybackAction
    data object Reject : PlaybackAction
}

/**
 * True for actions that drive the player's transport. These are gated while a
 * Watch Together room is active (the room is authoritative for transport, so an
 * admin command must not desync members); informational actions like
 * [PlaybackAction.ShowMessage] are not transport and are always allowed.
 */
val PlaybackAction.isTransport: Boolean
    get() = this is PlaybackAction.Pause ||
        this is PlaybackAction.Unpause ||
        this is PlaybackAction.TogglePlayPause ||
        this is PlaybackAction.SeekTo ||
        this is PlaybackAction.Stop

/**
 * Pure mapping from a realtime command to a player action. Unsupported or
 * malformed commands map to [PlaybackAction.Reject]; informational commands we
 * accept but do nothing about map to [PlaybackAction.Ignore].
 */
fun decidePlaybackAction(command: PlaybackRealtimeEvent.Command): PlaybackAction {
    // Numeric tokens only: a quoted number ("42") or a non-finite value is NOT
    // a valid position — reject rather than silently seek somewhere wrong.
    fun double(key: String): Double? =
        (command.payload[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.doubleOrNull
            ?.takeIf { it.isFinite() }
    fun string(key: String): String? =
        (command.payload[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }
    // Integer tokens only: a quoted number is NOT a valid index.
    fun int(key: String): Int? =
        (command.payload[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.intOrNull

    return when (command.name) {
        PlaybackCommandNames.Pause -> PlaybackAction.Pause
        PlaybackCommandNames.Unpause -> PlaybackAction.Unpause
        PlaybackCommandNames.PlayPause -> PlaybackAction.TogglePlayPause
        PlaybackCommandNames.Seek ->
            // R3: the issuer may send any of these spellings.
            (double("position_seconds") ?: double("position") ?: double("seconds"))
                ?.let { PlaybackAction.SeekTo(it) } ?: PlaybackAction.Reject
        PlaybackCommandNames.DisplayMessage ->
            string("message")?.let { PlaybackAction.ShowMessage(it) } ?: PlaybackAction.Reject
        PlaybackCommandNames.Stop -> PlaybackAction.Stop(
            ADMIN_STOPPED_PLAYBACK_NOTICE.takeIf { command.issuedBy == ISSUED_BY_ADMIN },
        )
        PlaybackCommandNames.Terminate -> PlaybackAction.Stop(
            ADMIN_ENDED_PLAYBACK_NOTICE.takeIf { command.issuedBy == ISSUED_BY_ADMIN },
        )
        PlaybackCommandNames.SetAudioTrack ->
            // The server's convention for "set audio by index" elsewhere is
            // `audio_track_index`; accept a bare `index` alias too.
            (int("audio_track_index") ?: int("index"))
                ?.let { PlaybackAction.SetAudioTrack(it) } ?: PlaybackAction.Reject
        PlaybackCommandNames.SetSubtitleTrack ->
            // `subtitle_track_index` (server convention) or `index`; -1 = off.
            (int("subtitle_track_index") ?: int("index"))
                ?.let { PlaybackAction.SetSubtitleTrack(it) } ?: PlaybackAction.Reject
        // Informational broadcasts we accept but take no client action on.
        PlaybackCommandNames.ServerRestarting,
        PlaybackCommandNames.ServerShuttingDown -> PlaybackAction.Ignore
        // Everything we don't advertise (incl. set_volume, play_media) is
        // rejected rather than silently no-op'd.
        else -> PlaybackAction.Reject
    }
}
