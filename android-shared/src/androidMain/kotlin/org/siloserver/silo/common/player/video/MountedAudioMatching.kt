package org.siloserver.silo.common.player.video

import org.siloserver.silo.model.catalog.AudioTrack

/**
 * A mounted audio track as the player reports it, reduced to the fields that
 * can identify it. Client-agnostic so phone and TV can both feed their own
 * track-entry type in without duplicating the matcher.
 *
 * [ordinal] is the Media3 audio-group ordinal — the argument
 * `AudioTrackManager.selectAudioTrack` expects. It is NOT a catalog position.
 */
data class MountedAudioTrack(
    val ordinal: Int,
    val language: String?,
    val codecOrMime: String?,
    val channelCount: Int?,
    val label: String? = null,
)

/**
 * Finds the mounted track that IS [catalog], or null.
 *
 * Audio selection on TV only ever staged a server replan, so when a stream
 * direct-plays and carries several audio tracks, choosing a different one
 * updated the plan while the player kept decoding the original. Switching
 * locally needs the mounted track that corresponds to the chosen catalog row —
 * and catalog ordinals are not Media3 ordinals, so that has to be established
 * by identity.
 *
 * Deliberately conservative. A null result means "not present in this stream,
 * ask the server to replan", which is the correct answer for a transcode: a
 * DTS 5.1 source delivered as stereo AAC is not the same track, and matching it
 * would silently pick the wrong audio and skip the replan that was needed.
 *
 * An unresolved tie also returns null rather than guessing, for the reason
 * [resolveEpisodeAudioIntent] gives: two tracks that agree on everything known
 * are how a viewer lands in a director's commentary they never asked for.
 */
fun matchMountedAudioTrack(
    catalog: AudioTrack,
    mounted: List<MountedAudioTrack>,
): MountedAudioTrack? {
    if (mounted.isEmpty()) return null

    val language = canonicalAudioLanguage(catalog.language)
    var pool = if (language == null) {
        mounted
    } else {
        mounted.filter { canonicalAudioLanguage(it.language) == language }
    }
    if (pool.isEmpty()) return null

    // Codec must agree when both sides state one. This is what keeps a
    // transcoded representation from matching its own source.
    val codec = canonicalAudioCodecFamily(catalog.codec)
    if (codec != null) {
        val stated = pool.filter { canonicalAudioCodecFamily(it.codecOrMime) != null }
        if (stated.isNotEmpty()) {
            pool = stated.filter { canonicalAudioCodecFamily(it.codecOrMime) == codec }
            if (pool.isEmpty()) return null
        }
    }
    if (pool.size == 1) return pool.single()

    val channels = catalog.channels?.takeIf { it > 0 }
    if (channels != null) {
        val stated = pool.filter { (it.channelCount ?: 0) > 0 }
        if (stated.isNotEmpty()) {
            val byChannels = stated.filter { it.channelCount == channels }
            if (byChannels.isEmpty()) return null
            pool = byChannels
        }
    }
    if (pool.size == 1) return pool.single()

    // Language, codec and channel count are routinely identical between a main
    // mix and its commentary; the name is the only thing left that separates
    // them.
    val title = normalizedAudioToken(catalog.title)
    if (title != null) {
        val byTitle = pool.filter { normalizedAudioToken(it.label) == title }
        if (byTitle.size == 1) return byTitle.single()
    }

    return null
}

/**
 * Canonical audio codec family across catalog spellings and Media3 identifiers.
 *
 * The catalog says `aac` where Media3 says `audio/mp4a-latm` or `mp4a.40.2`,
 * and `dts` where Media3 says `audio/vnd.dts`. Stripping to the last path
 * segment — which is all the episode-handoff normaliser does — leaves
 * `mp4a-latm`, which matches nothing.
 */
fun canonicalAudioCodecFamily(raw: String?): String? {
    val token = normalizedAudioToken(raw?.substringAfterLast('/')) ?: return null
    return when {
        token.startsWith("mp4a") || token == "aac" || token == "aacl" -> "aac"
        token.startsWith("vnddts") || token.startsWith("dts") || token == "dca" -> "dts"
        // `audio/eac3-joc` is the same E-AC-3 family: the catalog spells the
        // codec `eac3` and records Atmos in the layout, not the codec name.
        token.startsWith("ec3") || token.startsWith("eac3") || token == "ddp" -> "eac3"
        token.startsWith("ac3") -> "ac3"
        token.contains("truehd") || token == "mlp" -> "truehd"
        token.startsWith("flac") -> "flac"
        token.startsWith("opus") -> "opus"
        token.startsWith("vorbis") -> "vorbis"
        token.startsWith("mpeg") || token == "mp3" || token == "mp2" -> "mp3"
        token.startsWith("pcm") || token.startsWith("raw") -> "pcm"
        else -> token
    }
}

/** Shares subtitle's canonicaliser so "eng" and "en" are one answer. */
internal fun canonicalAudioLanguage(raw: String?): String? =
    org.siloserver.silo.playback.canonicalSubtitleLanguage(raw)

/** Lowercase, strip everything that is not a letter or digit. */
private fun normalizedAudioToken(raw: String?): String? = raw
    ?.lowercase()
    ?.filter { it.isLetterOrDigit() }
    ?.takeIf { it.isNotBlank() }
