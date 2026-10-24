package org.siloserver.silo.common.player

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.playback.resolveAudioTrackOrdinal

/**
 * The audio track a playback start asks the server for, as an ordinal into
 * the chosen file's catalog [tracks].
 *
 * 1. [explicitOrdinal]: a pick on the detail page, a route, a recovery
 *    restart, or a choice carried from the previous episode.
 * 2. [serverOrdinal]: the server's `effective_audio_track_index` for the file
 *    (with a choice still waiting to land laid over it), which already holds
 *    the profile's series choice and its language defaults. This device's
 *    per-file memory does not outrank it: a different track there is an older
 *    pick that the series choice has replaced, possibly on another device.
 * 3. Without a server answer: the per-file memory (not when [resetToAuto]:
 *    a series choice just set back to Auto that has not landed), then
 *    [languageFallback] (the device's language, with the best track this
 *    device can play).
 */
fun resolveStartAudioTrackOrdinal(
    explicitOrdinal: Int?,
    tracks: List<AudioTrack>,
    serverOrdinal: Int?,
    localFingerprint: String?,
    resetToAuto: Boolean = false,
    languageFallback: () -> Int?,
): Int? {
    explicitOrdinal?.let { return it }
    serverOrdinal?.takeIf { it in tracks.indices }?.let { return it }
    // An Auto reset still on its way to the server: the profile's language
    // decides, never an older per-file pick.
    if (resetToAuto) return languageFallback()
    return resolveAudioTrackOrdinal(tracks, localFingerprint) ?: languageFallback()
}
