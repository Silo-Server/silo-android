package org.siloserver.silo.common.player

import android.content.Context
import android.os.Build
import android.util.Log
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_DV81
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_HDR10

/**
 * Remembers client Dolby Vision transformations that failed on this device.
 *
 * The transformations are advertised from runtime evidence (a packaged RPU
 * converter, a hardware Profile 8 decoder, a panel that carries Dolby
 * Vision). That evidence is necessary but not sufficient: the SM-F976U1 met
 * all of it and still wedged after one frame. Advertising the route again on
 * the next session would make every fresh start pick the same doomed plan, so
 * a device-level failure withdraws the advertisement here and the next plan
 * request asks the server for its own recipe instead.
 *
 * Keyed by transformation name and the build fingerprint, so an OS update
 * earns a fresh attempt, and expiring after [TTL_MS] so a transient fault
 * does not permanently cost the device its best route.
 */
class DolbyVisionTransformQuarantine internal constructor(
    private val store: Store,
    private val fingerprint: String = Build.FINGERPRINT.orEmpty(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(PrefsStore(context))

    internal interface Store {
        fun get(key: String): Long?
        fun put(key: String, value: Long)
        fun remove(key: String)
    }

    private class PrefsStore(context: Context) : Store {
        private val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun get(key: String): Long? =
            if (prefs.contains(key)) prefs.getLong(key, 0L) else null

        override fun put(key: String, value: Long) {
            prefs.edit().putLong(key, value).apply()
        }

        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }
    }

    /** Transformation names withdrawn on this device build right now. */
    fun quarantined(): Set<String> = KNOWN_TRANSFORMATIONS.filterTo(mutableSetOf(), ::isQuarantined)

    fun isQuarantined(transformation: String): Boolean {
        val key = key(transformation)
        val recordedAt = store.get(key) ?: return false
        if (nowMs() - recordedAt > TTL_MS) {
            store.remove(key)
            return false
        }
        return true
    }

    /**
     * Withdraws every [activeTransformations] entry when [classification]
     * describes a fault in the local recipe rather than in the source, the
     * transport, or another renderer. Returns the names that were withdrawn.
     *
     * @param failedTrackType the Media3 track type of the renderer that
     * raised the error, when the failure came from a player exception. A
     * generic decoder classification only counts against the video recipe
     * when the video renderer raised it; an audio decoder failing under a
     * transformed plan says nothing about the transformation.
     */
    fun noteFailure(
        classification: String,
        activeTransformations: Collection<String>,
        failedTrackType: Int? = null,
    ): List<String> {
        if (activeTransformations.isEmpty()) return emptyList()
        if (!isDeviceFault(classification, failedTrackType)) return emptyList()
        val withdrawn = activeTransformations.filter { it in KNOWN_TRANSFORMATIONS }
        withdrawn.forEach { name ->
            Log.w(TAG, "Quarantining client transformation $name after $classification")
            store.put(key(name), nowMs())
        }
        return withdrawn
    }

    private fun key(transformation: String): String = "$transformation:$fingerprint"

    companion object {
        private const val TAG = "SiloDovi"
        private const val PREFS_NAME = "silo_dv_transform_quarantine"
        internal const val TTL_MS: Long = 14L * 24 * 60 * 60 * 1000
        internal val KNOWN_TRANSFORMATIONS: Set<String> = setOf(CLIENT_DV7_TO_DV81, CLIENT_DV7_TO_HDR10)

        /**
         * Classifications raised by the transformation itself, which name the
         * local recipe as the fault regardless of which renderer surfaced
         * them. A source that is not what the plan described, an encrypted
         * sample, or a network stall is not evidence against the device.
         */
        private val TRANSFORM_FAULT_PREFIXES = listOf(
            "dv7_transform_stall",
            "dv7_transform_unavailable",
            "dv7_transform_oversized",
            "dv7_transform_failed",
            "dv7_rpu_conversion_failed",
        )

        /**
         * Video-progress classifications from the stall detector. They watch
         * the video decoder counters, so no renderer type is needed.
         */
        private val VIDEO_STALL_CLASSIFICATIONS = setOf(
            "decoder_no_output",
            "render_startup_failure",
        )

        /**
         * Generic player-exception classifications. Media3 raises the same
         * codes for every renderer, so these count only when the exception
         * came from the video renderer.
         */
        private val RENDERER_DECODER_CLASSIFICATIONS = setOf("decoder_failure")

        /** Media3's `C.TRACK_TYPE_VIDEO`, restated so this file stays free of Media3 types. */
        internal const val TRACK_TYPE_VIDEO = 2

        internal fun isDeviceFault(classification: String, failedTrackType: Int? = null): Boolean = when {
            TRANSFORM_FAULT_PREFIXES.any(classification::startsWith) -> true
            classification in VIDEO_STALL_CLASSIFICATIONS -> true
            classification in RENDERER_DECODER_CLASSIFICATIONS -> failedTrackType == TRACK_TYPE_VIDEO
            else -> false
        }
    }
}

/**
 * The track type of the renderer that raised this exception, or null when
 * the error did not come from a renderer (source, remote, unexpected).
 *
 * The screens receive errors through a MediaController, and MediaSession
 * rebuilds the error as a base [androidx.media3.common.PlaybackException]
 * on the way across, so the renderer attribution is gone by then. Pass the
 * in-process [servicePlayer] when one is available: its own
 * [androidx.media3.exoplayer.ExoPlayer.getPlayerError] still carries the
 * typed exception for the same failure, matched here by error code and
 * timestamp so a stale error from an earlier item is never attributed.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun androidx.media3.common.PlaybackException.failedRendererTrackType(
    servicePlayer: androidx.media3.common.Player? = null,
): Int? {
    val exo = this as? androidx.media3.exoplayer.ExoPlaybackException
        ?: (servicePlayer as? androidx.media3.exoplayer.ExoPlayer)?.playerError
            ?.takeIf { it.errorCode == errorCode && it.timestampMs == timestampMs }
        ?: return null
    if (exo.type != androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER) return null
    val mime = exo.rendererFormat?.sampleMimeType ?: return null
    return androidx.media3.common.MimeTypes.getTrackType(mime)
}
