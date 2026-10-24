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
 * 3. Without a server answer (an older server, or a series choice just reset
 *    to Auto that has not landed): the per-file memory, then
 *    [languageFallback] (the device's language, with the best track this
 *    device can play).
 */
fun resolveStartAudioTrackOrdinal(
    explicitOrdinal: Int?,
    tracks: List<AudioTrack>,
    serverOrdinal: Int?,
    localFingerprint: String?,
    languageFallback: () -> Int?,
): Int? {
    explicitOrdinal?.let { return it }
    serverOrdinal?.takeIf { it in tracks.indices }?.let { return it }
    return resolveAudioTrackOrdinal(tracks, localFingerprint) ?: languageFallback()
}
