package org.siloserver.silo.tv.cast

internal data class SiloCastVolumeState(
    val volume: Double,
    val isMuted: Boolean,
)

/**
 * Retains the TV player's last audible level for the lifetime of the SiloCast
 * receiver, rather than one player composition. That lifetime matters across
 * episode/content transitions and while MediaController is connecting: mute
 * must publish silence without discarding the level a later unmute restores.
 */
internal class SiloCastVolumeTracker(
    initialVolume: Double = 1.0,
) {
    private val lock = Any()
    private var retainedVolume = initialVolume.coerceIn(0.0, 1.0)
    private var muted = false

    fun recordVolume(volume: Double) = synchronized(lock) {
        val clamped = volume.coerceIn(0.0, 1.0)
        muted = clamped <= SILENT_VOLUME
        if (!muted) retainedVolume = clamped
    }

    fun recordMuted(isMuted: Boolean, currentVolume: Double?) = synchronized(lock) {
        currentVolume
            ?.coerceIn(0.0, 1.0)
            ?.takeIf { it > SILENT_VOLUME }
            ?.let { retainedVolume = it }
        muted = isMuted
    }

    fun retainedAudibleVolume(): Double = synchronized(lock) { retainedVolume }

    fun resolve(currentVolume: Double?): SiloCastVolumeState = synchronized(lock) {
        if (currentVolume != null) {
            val clamped = currentVolume.coerceIn(0.0, 1.0)
            muted = clamped <= SILENT_VOLUME
            if (!muted) retainedVolume = clamped
        }
        SiloCastVolumeState(
            volume = retainedVolume,
            isMuted = muted,
        )
    }

    private companion object {
        const val SILENT_VOLUME = 0.001
    }
}
