package org.siloserver.silo.common.player.video

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.siloserver.silo.common.player.normalizedSubtitleCodecFamily
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.player.DolbyVisionDetection
import org.siloserver.silo.playback.canonicalSubtitleLanguage
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired

/**
 * Session-only, cross-episode playback intent. It deliberately carries no
 * episode-local identity: target IDs and subtitle indexes are resolved only
 * after the next episode's catalog detail is available.
 */
@Serializable
data class EpisodeSelectionHandoff(
    val source: EpisodeSourceIntent? = null,
    val subtitle: EpisodeSubtitleIntent = EpisodeSubtitleIntent.auto(),
    /**
     * The audio the viewer chose, carried the same way subtitles are.
     *
     * Nothing carried audio before, so a household watching a dub or a
     * commentary track was returned to the server default at every automatic
     * episode change — a choice they had to make again all evening.
     *
     * Described by metadata rather than index for the same reason subtitles
     * are: the next episode's track list is a different list, and position
     * three in one file has nothing to do with position three in the next.
     */
    val audio: EpisodeAudioIntent = EpisodeAudioIntent.auto(),
)

@Serializable
enum class EpisodeAudioMode { AUTO, TRACK }

@Serializable
data class EpisodeAudioIntent(
    val mode: EpisodeAudioMode,
    val language: String? = null,
    val codecFamily: String? = null,
    val channelCount: Int? = null,
    val title: String? = null,
) {
    companion object {
        fun auto() = EpisodeAudioIntent(EpisodeAudioMode.AUTO)
    }
}

/**
 * Pick the track in [candidates] that best answers [intent].
 *
 * Language is canonicalised first, because "eng" and "en" are the same choice
 * spelled two ways and the catalog and the player disagree about which to use.
 * Codec is reduced to a family for the same reason: the player reports MIME
 * types like `audio/eac3` where the catalog says `eac3`.
 *
 * Title carries real weight rather than being decoration. A commentary track
 * routinely shares language, codec AND channel count with the main mix, so
 * those three cannot tell them apart — the name is the only thing that can.
 *
 * An unresolved tie returns null. Guessing between two tracks that both match
 * everything known about the choice is how a viewer ends up in a director's
 * commentary they never asked for, and the server default is a better answer
 * than a coin toss.
 */
fun resolveEpisodeAudioIntent(
    intent: EpisodeAudioIntent,
    candidates: List<EpisodeAudioCandidate>,
): Int? {
    if (intent.mode != EpisodeAudioMode.TRACK) return null
    if (candidates.isEmpty()) return null

    val language = canonicalEpisodeLanguage(intent.language)
    // A track with no language at all is still a choice a viewer made, so an
    // intent without one narrows on the other fields rather than giving up.
    var pool = if (language == null) {
        candidates
    } else {
        candidates.filter { canonicalEpisodeLanguage(it.language) == language }
    }
    if (pool.isEmpty()) return null
    if (pool.size == 1) return pool.single().index

    val title = normalizedEpisodeToken(intent.title)
    if (title != null) {
        val byTitle = pool.filter { normalizedEpisodeToken(it.title) == title }
        if (byTitle.size == 1) return byTitle.single().index
        if (byTitle.isNotEmpty()) pool = byTitle
    }

    val codec = episodeAudioCodecFamily(intent.codecFamily)
    if (codec != null) {
        val byCodec = pool.filter { episodeAudioCodecFamily(it.codecFamily) == codec }
        if (byCodec.size == 1) return byCodec.single().index
        if (byCodec.isNotEmpty()) pool = byCodec
    }

    val channels = intent.channelCount
    if (channels != null) {
        val byChannels = pool.filter { it.channelCount == channels }
        if (byChannels.size == 1) return byChannels.single().index
        if (byChannels.isNotEmpty()) pool = byChannels
    }

    // Still ambiguous: say so rather than pick.
    return pool.singleOrNull()?.index
}

/**
 * The one canonicaliser, shared with subtitles.
 *
 * An earlier version here rolled its own and was wrong three ways: it ran the
 * token through a normaliser that strips '-', so `en-US` became `enus` before
 * any locale lookup; it passed three-letter codes straight through, so `fre`
 * and `fra` never met; and `Locale.isO3Language` throws on unrecognised input,
 * turning odd metadata into a failed playback start.
 *
 * canonicalSubtitleLanguage already handles all of that and treats `und` as
 * absent. Audio and subtitles have no reason to disagree about what a language
 * is.
 */
private fun canonicalEpisodeLanguage(raw: String?): String? =
    canonicalSubtitleLanguage(raw)

/** `audio/eac3` from the player and `eac3` from the catalog are one codec. */
private fun episodeAudioCodecFamily(raw: String?): String? =
    normalizedEpisodeToken(raw?.substringAfterLast('/'))

data class EpisodeAudioCandidate(
    val index: Int,
    val language: String?,
    val codecFamily: String?,
    val channelCount: Int?,
    /** Often the only thing separating a commentary track from the main mix. */
    val title: String? = null,
)

/**
 * Carries a manual audio choice across a file-version replacement by semantic
 * identity, never by list position. A null result deliberately hands control
 * back to the target file's persisted/language/default selection chain.
 */
fun resolveAudioSelectionAcrossVersions(
    sourceTracks: List<AudioTrack>,
    selectedSourceOrdinal: Int?,
    targetTracks: List<AudioTrack>,
): Int? {
    val source = selectedSourceOrdinal?.let(sourceTracks::getOrNull) ?: return null
    return resolveEpisodeAudioIntent(
        intent = EpisodeAudioIntent(
            mode = EpisodeAudioMode.TRACK,
            language = source.language,
            codecFamily = source.codec,
            channelCount = source.channels?.takeIf { it > 0 },
            title = source.title,
        ),
        candidates = targetTracks.mapIndexed { ordinal, track ->
            EpisodeAudioCandidate(
                index = ordinal,
                language = track.language,
                codecFamily = track.codec,
                channelCount = track.channels?.takeIf { it > 0 },
                title = track.title,
            )
        },
    )
}

@Serializable
data class EpisodeSourceIntent(
    val resolution: String,
    val videoCodec: String? = null,
    val dynamicRange: EpisodeDynamicRange? = null,
    val container: String? = null,
)

@Serializable
enum class EpisodeDynamicRange { SDR, HDR, DOLBY_VISION }

@Serializable
enum class EpisodeSubtitleMode { AUTO, OFF, TRACK }

@Serializable
data class EpisodeSubtitleIntent(
    val mode: EpisodeSubtitleMode,
    val language: String? = null,
    val codecFamily: String? = null,
    val forced: Boolean? = null,
    val hearingImpaired: Boolean? = null,
    val external: Boolean? = null,
) {
    companion object {
        fun auto() = EpisodeSubtitleIntent(EpisodeSubtitleMode.AUTO)
        fun off() = EpisodeSubtitleIntent(EpisodeSubtitleMode.OFF)
    }
}

data class ResolvedEpisodeSubtitle(
    val trackIndex: Int?,
    val intentSpecified: Boolean,
)

data class ResolvedEpisodeSelection(
    val fileId: Int?,
    val subtitleTrackIndex: Int?,
    val subtitleIntentSpecified: Boolean,
    /** Null keeps the server default, which is what AUTO means. */
    val audioTrackIndex: Int? = null,
)

fun captureEpisodeSourceIntent(version: FileVersion?): EpisodeSourceIntent? {
    val version = version ?: return null
    val resolution = normalizedEpisodeResolution(
        version.resolution ?: version.videoTracks?.firstOrNull()?.resolution,
    ) ?: return null
    return EpisodeSourceIntent(
        resolution = resolution,
        videoCodec = normalizedEpisodeToken(
            version.codecVideo ?: version.videoTracks?.firstOrNull()?.codec,
        ),
        dynamicRange = version.episodeDynamicRange(),
        container = normalizedEpisodeToken(version.container),
    )
}

fun captureEpisodeSubtitleIntent(
    selectedTrackIndex: Int?,
    subtitles: List<PlayerSubtitleInfo>,
): EpisodeSubtitleIntent = when (selectedTrackIndex) {
    null -> EpisodeSubtitleIntent.auto()
    -1 -> EpisodeSubtitleIntent.off()
    else -> subtitles
        .singleOrNull { it.index == selectedTrackIndex }
        ?.toEpisodeSubtitleIntent()
        ?: EpisodeSubtitleIntent.auto()
}

fun resolveEpisodeSourceIntent(
    intent: EpisodeSourceIntent?,
    targetVersions: List<FileVersion>,
): Int? {
    val intent = intent ?: return null
    val candidates = targetVersions.filter {
        normalizedEpisodeResolution(it.resolution ?: it.videoTracks?.firstOrNull()?.resolution) == intent.resolution
    }
    if (candidates.isEmpty()) return null

    val priority = compareBy<FileVersion>(
        { it.matchesEpisodeVideoCodec(intent) },
        { it.matchesEpisodeDynamicRange(intent) },
        { it.matchesEpisodeContainer(intent) },
    )
    val best = candidates.maxWithOrNull(priority) ?: return null
    return candidates
        .filter { priority.compare(it, best) == 0 }
        .singleOrNull()
        ?.fileId
}

fun resolveEpisodeSubtitleIntent(
    intent: EpisodeSubtitleIntent,
    targetSubtitles: List<PlayerSubtitleInfo>,
): ResolvedEpisodeSubtitle = when (intent.mode) {
    EpisodeSubtitleMode.AUTO -> ResolvedEpisodeSubtitle(
        trackIndex = null,
        intentSpecified = false,
    )
    EpisodeSubtitleMode.OFF -> ResolvedEpisodeSubtitle(
        trackIndex = -1,
        intentSpecified = true,
    )
    EpisodeSubtitleMode.TRACK -> ResolvedEpisodeSubtitle(
        trackIndex = targetSubtitles
            .map { track -> EpisodeSubtitleMatch(track, track.episodeSubtitleMatchScore(intent)) }
            .filter { it.score.isMeaningfulFor(intent) }
            .let { matches ->
                val best = matches.maxWithOrNull(episodeSubtitleMatchComparator) ?: return@let null
                matches
                    .filter { episodeSubtitleMatchComparator.compare(it, best) == 0 }
                    .singleOrNull()
                    ?.track
                    ?.index
            },
        intentSpecified = true,
    )
}

fun resolveEpisodeSelectionHandoff(
    handoff: EpisodeSelectionHandoff?,
    targetVersions: List<FileVersion>,
    targetSubtitles: List<PlayerSubtitleInfo>,
): ResolvedEpisodeSelection {
    val subtitle = resolveEpisodeSubtitleIntent(
        handoff?.subtitle ?: EpisodeSubtitleIntent.auto(),
        targetSubtitles,
    )
    return ResolvedEpisodeSelection(
        fileId = resolveEpisodeSourceIntent(handoff?.source, targetVersions),
        subtitleTrackIndex = subtitle.trackIndex,
        subtitleIntentSpecified = subtitle.intentSpecified,
    )
}

fun encodeEpisodeSelectionHandoff(handoff: EpisodeSelectionHandoff): String =
    episodeSelectionHandoffJson.encodeToString(handoff)

fun decodeEpisodeSelectionHandoff(value: String?): EpisodeSelectionHandoff? =
    value?.takeIf { it.isNotBlank() }?.let { encoded ->
        runCatching { episodeSelectionHandoffJson.decodeFromString<EpisodeSelectionHandoff>(encoded) }
            .getOrNull()
    }

private fun FileVersion.matchesEpisodeVideoCodec(intent: EpisodeSourceIntent): Boolean =
    intent.videoCodec != null &&
        normalizedEpisodeToken(codecVideo ?: videoTracks?.firstOrNull()?.codec) == intent.videoCodec

private fun FileVersion.matchesEpisodeDynamicRange(intent: EpisodeSourceIntent): Boolean =
    intent.dynamicRange != null && episodeDynamicRange() == intent.dynamicRange

private fun FileVersion.matchesEpisodeContainer(intent: EpisodeSourceIntent): Boolean =
    intent.container != null && normalizedEpisodeToken(container) == intent.container

private fun FileVersion.episodeDynamicRange(): EpisodeDynamicRange {
    val tracks = videoTracks.orEmpty()
    val isDolbyVision = tracks.any { track ->
        DolbyVisionDetection.isDolbyVision(
            dolbyVisionProfile = track.dolbyVisionProfile,
            hdrFormat = track.hdrFormat,
            videoCodec = track.codec,
        )
    } || DolbyVisionDetection.isDolbyVision(videoCodec = codecVideo)
    return when {
        isDolbyVision -> EpisodeDynamicRange.DOLBY_VISION
        hdr || tracks.any { it.hdr } -> EpisodeDynamicRange.HDR
        else -> EpisodeDynamicRange.SDR
    }
}

private fun PlayerSubtitleInfo.toEpisodeSubtitleIntent(): EpisodeSubtitleIntent = EpisodeSubtitleIntent(
    mode = EpisodeSubtitleMode.TRACK,
    language = canonicalSubtitleLanguage(language),
    codecFamily = normalizedSubtitleCodecFamily(codec),
    forced = forced,
    hearingImpaired = subtitleLabelIndicatesHearingImpaired(catalogLabel ?: label),
    external = episodeSubtitleExternal(),
)

private data class EpisodeSubtitleMatch(
    val track: PlayerSubtitleInfo,
    val score: EpisodeSubtitleMatchScore,
)

private data class EpisodeSubtitleMatchScore(
    val language: Boolean,
    val forced: Boolean,
    val hearingImpaired: Boolean,
    val external: Boolean,
    val codecFamily: Boolean,
) {
    fun isMeaningfulFor(intent: EpisodeSubtitleIntent): Boolean = when {
        intent.language != null -> language
        intent.forced == true -> forced
        intent.hearingImpaired == true -> hearingImpaired
        else -> external || codecFamily
    }
}

private val episodeSubtitleMatchComparator = compareBy<EpisodeSubtitleMatch>(
    { it.score.language },
    { it.score.forced },
    { it.score.hearingImpaired },
    { it.score.external },
    { it.score.codecFamily },
)

private fun PlayerSubtitleInfo.episodeSubtitleMatchScore(
    intent: EpisodeSubtitleIntent,
): EpisodeSubtitleMatchScore = EpisodeSubtitleMatchScore(
    language = intent.language != null && canonicalSubtitleLanguage(language) == intent.language,
    forced = intent.forced != null && forced == intent.forced,
    hearingImpaired = intent.hearingImpaired != null &&
        episodeSubtitleHearingImpaired() == intent.hearingImpaired,
    external = intent.external != null && episodeSubtitleExternal() == intent.external,
    codecFamily = intent.codecFamily != null && normalizedSubtitleCodecFamily(codec) == intent.codecFamily,
)

private fun PlayerSubtitleInfo.episodeSubtitleHearingImpaired(): Boolean =
    subtitleLabelIndicatesHearingImpaired(catalogLabel ?: label)

private fun PlayerSubtitleInfo.episodeSubtitleExternal(): Boolean? = when (
    (catalogSource ?: source)?.trim()?.lowercase()
) {
    "embedded" -> false
    "external", "downloaded" -> true
    else -> null
}

private fun normalizedEpisodeResolution(value: String?): String? {
    val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        normalized.contains("4320") || normalized.contains("8k") -> "4320p"
        normalized.contains("2160") || normalized.contains("4k") || normalized.contains("uhd") -> "2160p"
        normalized.contains("1440") || normalized.contains("qhd") -> "1440p"
        normalized.contains("1080") || normalized.contains("fhd") -> "1080p"
        normalized.contains("720") || normalized.contains("hd") -> "720p"
        normalized.contains("576") -> "576p"
        normalized.contains("480") || normalized.contains("sd") -> "480p"
        else -> normalized
    }
}

private fun normalizedEpisodeToken(value: String?): String? =
    value
        ?.trim()
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)
        ?.takeIf { it.isNotEmpty() }

private val episodeSelectionHandoffJson = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
}
