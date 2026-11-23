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
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.common.cast.SiloCastNsdAdvertiser
import org.siloserver.silo.common.cast.SiloCastNsdBrowser
import org.siloserver.silo.common.cast.SiloCastTarget
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
 * Remote Control (emulators sit behind NAT that mDNS can't cross):
 *   ... -a org.siloserver.silo.debug.CAST_STATUS    (TV: the live port + TXT record)
 *   ... -a org.siloserver.silo.debug.CAST_TARGET --es host 10.0.2.2 --ei port 4711 \
 *       --es name "Den TV" --es txt "v=2;id=tv-1;server=srv;serverName=Home;playing=0"
 *       (phone: adds or replaces a target; `--ez clear true` removes them all;
 *       `--es type _silocast-roku._tcp` with `sec=plain` in txt adds a Roku)
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
            ACTION_CAST_STATUS -> setResultData(castStatusJson().toString())
            ACTION_CAST_TARGET -> setResultData(setCastTarget(intent).toString())
        }
    }

    private fun castStatusJson(): JSONObject {
        val advertiser = runCatching {
            KoinJavaComponent.get<SiloCastNsdAdvertiser>(SiloCastNsdAdvertiser::class.java)
        }.getOrNull() ?: return JSONObject().put("advertising", false).put("reason", "no receiver in this app")
        val (name, port, record) = advertiser.currentAdvertisement()
            ?: return JSONObject().put("advertising", false)
        return JSONObject()
            .put("advertising", true)
            .put("name", name)
            .put("port", port)
            .put("txt", JSONObject(record))
    }

    private fun setCastTarget(intent: Intent): JSONObject {
        val browser = runCatching {
            KoinJavaComponent.get<SiloCastNsdBrowser>(SiloCastNsdBrowser::class.java)
        }.getOrNull() ?: return JSONObject().put("ok", false).put("reason", "no browser in this app")
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
            injectedCastTargets.clear()
            browser.setDebugTargets(emptyList())
            return JSONObject().put("ok", true).put("targets", 0)
        }
        val txt = intent.getStringExtra(EXTRA_TXT).orEmpty()
            .split(';')
            .filter { '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val target = SiloCastNsdBrowser.targetFromRecord(
            host = intent.getStringExtra(EXTRA_HOST) ?: return JSONObject().put("ok", false).put("reason", "host missing"),
            port = intent.getIntExtra(EXTRA_PORT, 0),
            serviceName = intent.getStringExtra(EXTRA_NAME) ?: txt["name"].orEmpty(),
            txt = txt,
            serviceType = intent.getStringExtra(EXTRA_SERVICE_TYPE) ?: SiloCastProtocol.serviceType,
        ) ?: return JSONObject().put("ok", false).put("reason", "port missing")
        injectedCastTargets[target.deviceId] = target
        browser.setDebugTargets(injectedCastTargets.values.toList())
        return JSONObject().put("ok", true).put("targets", injectedCastTargets.size)
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
        repository.lastSocketEnd?.let { json.put("lastSocketEnd", it) }
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
        const val ACTION_CAST_STATUS = "org.siloserver.silo.debug.CAST_STATUS"
        const val ACTION_CAST_TARGET = "org.siloserver.silo.debug.CAST_TARGET"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_POSITION_MS = "positionMs"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        const val EXTRA_TXT = "txt"
        const val EXTRA_SERVICE_TYPE = "type"
        const val EXTRA_CLEAR = "clear"

        /** Targets injected so far, keyed by device id; main-thread only. */
        private val injectedCastTargets = linkedMapOf<String, SiloCastTarget>()
    }
}
