package org.siloserver.silo.common.player.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject
import org.koin.java.KoinJavaComponent
import org.siloserver.silo.common.BuildConfig
import org.siloserver.silo.common.player.ActivePlayerHolder
import org.siloserver.silo.common.player.watchparty.WatchPartyDebugRegistry
import org.siloserver.silo.repository.WatchTogetherRepository

/**
 * Debug-only adb hook for scripted playback testing (see
 * `scripts/android-playback-test.sh`). Lets a test harness read player state as one
 * compact JSON line — or drive transport — with a single `am broadcast` call
 * instead of scraping logcat or `dumpsys media_session` (whose PlaybackState
 * positions are extrapolation snapshots, not ground truth).
 *
 * Status:
 *   adb shell am broadcast -n org.siloserver.silo/org.siloserver.silo.common.player.debug.PlaybackDebugReceiver \
 *     -a org.siloserver.silo.debug.PLAYBACK_STATUS
 * Command:
 *   ... -a org.siloserver.silo.debug.PLAYBACK_COMMAND --es cmd seek --el positionMs 300000
 *
 * The JSON comes back in `am broadcast`'s printed `data=` result. The explicit
 * component (`-n`) is required — implicit broadcasts don't reach
 * manifest-declared receivers on API 26+.
 *
 * Locked down two ways: the manifest entry requires the sender to hold
 * `android.permission.DUMP` (shell has it; third-party apps can't), and
 * everything no-ops in release builds.
 */
class PlaybackDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        // Manifest receivers dispatch on the main thread, which is also the
        // Player's application thread — direct access is safe here.
        val player = runCatching {
            KoinJavaComponent.get<ActivePlayerHolder>(ActivePlayerHolder::class.java)
        }.getOrNull()?.player?.value
        when (intent.action) {
            ACTION_STATUS -> setResultData(statusJson(player).toString())
            ACTION_COMMAND -> setResultData(runCommand(player, intent).toString())
        }
    }

    private fun statusJson(player: Player?): JSONObject {
        val json = JSONObject()
        // Correlate samples across devices: monotonic and wall time at the
        // moment of sampling, plus the room's server-clock estimate.
        json.put("sampledElapsedMs", SystemClock.elapsedRealtime())
        json.put("sampledWallMs", System.currentTimeMillis())
        partyJson()?.let { json.put("party", it) }
        PlaybackDebugState.screenError?.let { json.put("screenError", it) }
        PlaybackDebugState.screenPositionSec?.let { json.put("screenPositionSec", it) }
        PlaybackDebugState.screenDurationSec?.let { json.put("screenDurationSec", it) }
        if (player == null) {
            return json.put("player", "none")
        }
        json.put("player", "active")
        json.put(
            "state",
            when (player.playbackState) {
                Player.STATE_IDLE -> "idle"
                Player.STATE_BUFFERING -> "buffering"
                Player.STATE_READY -> "ready"
                Player.STATE_ENDED -> "ended"
                else -> "unknown"
            },
        )
        json.put("isPlaying", player.isPlaying)
        json.put("playWhenReady", player.playWhenReady)
        json.put("positionMs", player.currentPosition)
        json.put("bufferedMs", player.bufferedPosition)
        json.put("durationMs", player.duration.takeIf { it != C.TIME_UNSET } ?: -1L)
        json.put("speed", player.playbackParameters.speed.toDouble())
        player.currentMediaItem?.let { item ->
            json.put("mediaId", item.mediaId)
            item.mediaMetadata.title?.let { json.put("title", it.toString()) }
        }
        player.playerError?.let { error ->
            json.put("error", "${error.errorCodeName}: ${error.message}")
        }
        (player as? ExoPlayer)?.let { exo ->
            exo.videoFormat?.let { json.put("videoFormat", Format.toLogString(it)) }
            exo.audioFormat?.let { json.put("audioFormat", Format.toLogString(it)) }
            exo.videoDecoderCounters?.let { counters ->
                json.put("droppedFrames", counters.droppedBufferCount)
                json.put("renderedFrames", counters.renderedOutputBufferCount)
            }
        }
        return json
    }

    /** Room and binding state while a Watch Party is active; never tokens or tickets. */
    private fun partyJson(): JSONObject? {
        val repository = runCatching {
            KoinJavaComponent.get<WatchTogetherRepository>(WatchTogetherRepository::class.java)
        }.getOrNull() ?: return null
        val room = repository.roomSnapshot.value
        val ended = repository.ended.value
        if (room == null && ended == null) return null
        val json = JSONObject()
        room?.let {
            json.put("roomId", it.roomId)
            json.put("phase", it.phase.wire)
            json.put("playbackState", it.playbackState.wire)
            json.put("selectionRevision", it.selectionRevision)
            json.put("generation", it.generation)
            json.put("selfRole", it.selfRole.wire)
            json.put("anchorSec", it.anchorPositionSeconds)
            json.put("roomPaused", it.isPaused)
            json.put("attachedSessionId", it.attachedSessionId ?: JSONObject.NULL)
            json.put("selfReady", it.selfMember?.isReady == true)
            json.put("selfIgnoreWait", it.selfIgnoreWait)
            json.put("memberCount", it.memberCount)
        }
        ended?.let { json.put("endedReason", it.reason) }
        val connection = repository.connectionState.value
        json.put("connectionEpoch", connection.epoch)
        json.put("connected", connection.writable)
        val clock = repository.clock.value
        clock.offsetMs?.let { json.put("serverOffsetMs", it) }
        clock.rttMs?.let { json.put("clockRttMs", it) }
        repository.latestTransportCommand.value?.command?.let { command ->
            json.put("latestCommandId", command.commandId)
            json.put("latestCommandAction", command.action.wire)
        }
        WatchPartyDebugRegistry.binding?.debug?.value?.let { debug ->
            json.put("bindingAttachedSessionId", debug.attachedSessionId ?: JSONObject.NULL)
            json.put("serverAttached", debug.serverAttached)
            json.put("pendingCommandId", debug.pendingCommandId ?: JSONObject.NULL)
            json.put("appliedCommandId", debug.appliedCommandId ?: JSONObject.NULL)
            json.put("readinessCommandId", debug.readinessCommandId ?: JSONObject.NULL)
            json.put("catchingUp", debug.catchingUp)
            json.put("correctionRate", debug.correctionRate ?: JSONObject.NULL)
            json.put("reloadInFlight", debug.reloadInFlight)
            json.put("stallReported", debug.stallReported)
            json.put("suspended", debug.suspended)
        }
        return json
    }

    private fun runCommand(player: Player?, intent: Intent): JSONObject {
        val json = JSONObject()
        val cmd = intent.getStringExtra(EXTRA_CMD)
        json.put("cmd", cmd ?: "missing")
        if (player == null) {
            return json.put("ok", false).put("reason", "no active player")
        }
        when (cmd) {
            "play" -> player.play()
            "pause" -> player.pause()
            "stop" -> player.stop()
            "seek" -> {
                val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, -1L)
                if (positionMs < 0) {
                    return json.put("ok", false).put("reason", "seek needs --el positionMs <ms>")
                }
                player.seekTo(positionMs)
            }
            else -> return json.put("ok", false).put("reason", "unknown cmd")
        }
        return json.put("ok", true)
    }

    companion object {
        const val ACTION_STATUS = "org.siloserver.silo.debug.PLAYBACK_STATUS"
        const val ACTION_COMMAND = "org.siloserver.silo.debug.PLAYBACK_COMMAND"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_POSITION_MS = "positionMs"
    }
}
