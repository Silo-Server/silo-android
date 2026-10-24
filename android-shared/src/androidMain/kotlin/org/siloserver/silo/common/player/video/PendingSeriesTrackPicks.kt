package org.siloserver.silo.common.player.video

import org.siloserver.silo.model.playback.SubtitleIdentity

/**
 * The viewer's track picks waiting for playback to take them. A pick is
 * remembered for the series only once it lands: a staged pick can be refused
 * and a committed one rolled back, and either leaves the earlier track
 * playing. Each pick is handed back at most once.
 *
 * An audio pick the player switches to itself ([local]) lands when the player
 * is on the track; one the server must deliver lands when its replan commits.
 * Called from the player and from the track transaction's persistence, which
 * may run on another thread.
 */
class PendingSeriesTrackPicks {
    private var subtitle: SubtitleIdentity? = null
    private var audio: Pair<Int, Boolean>? = null

    @Synchronized
    fun pickSubtitle(identity: SubtitleIdentity) {
        subtitle = identity
    }

    @Synchronized
    fun pickAudio(catalogOrdinal: Int, local: Boolean) {
        audio = catalogOrdinal to local
    }

    /** Whether [committed] is the subtitle the viewer picked. */
    @Synchronized
    fun takeSubtitle(committed: SubtitleIdentity): Boolean {
        if (subtitle?.isSameTrackAs(committed) != true) return false
        subtitle = null
        return true
    }

    /** Whether [catalogOrdinal], landing [local]ly or through a replan, is the audio the viewer picked. */
    @Synchronized
    fun takeAudio(catalogOrdinal: Int?, local: Boolean): Boolean {
        if (catalogOrdinal == null || audio != (catalogOrdinal to local)) return false
        audio = null
        return true
    }

    @Synchronized
    fun clear() {
        subtitle = null
        audio = null
    }
}

/** The same track, though playback may deliver it another way (an embedded track as a sidecar). */
private fun SubtitleIdentity.isSameTrackAs(other: SubtitleIdentity): Boolean =
    this == other || serverIndexOrNull()?.let { it == other.serverIndexOrNull() } == true ||
        this is SubtitleIdentity.Downloaded && other is SubtitleIdentity.Downloaded && downloadId == other.downloadId

private fun SubtitleIdentity.serverIndexOrNull(): Int? = when (this) {
    is SubtitleIdentity.ServerSidecar -> serverIndex
    is SubtitleIdentity.ServerBurnIn -> serverIndex
    is SubtitleIdentity.Embedded -> serverIndex
    else -> null
}
