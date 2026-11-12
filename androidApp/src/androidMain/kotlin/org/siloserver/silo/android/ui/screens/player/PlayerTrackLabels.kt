package org.siloserver.silo.android.ui.screens.player

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import java.util.Locale
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.player.subtitleTrackLabelParts
import org.siloserver.silo.player.trackLanguageDisplayName
import org.siloserver.silo.player.trackLanguageEnglishName

/**
 * The catalog words a track label is built from, resolved in composition by
 * [trackLabelStrings] so the label helpers below stay plain functions.
 */
internal class TrackLabelStrings(
    val audioNumbered: (Int) -> String,
    val subtitleNumbered: (Int) -> String,
    val default: String,
    val forced: String,
    val sdh: String,
    val embedded: String,
    val external: String,
    val downloaded: String,
    val server: String,
    val mono: String,
    val stereo: String,
    /** The interface language, which names track languages. */
    val languageLocale: Locale = LocaleFormat.interfaceLocale(),
)

@Composable
internal fun trackLabelStrings(): TrackLabelStrings {
    val resources = LocalResources.current
    // Built once per configuration: callers recompose on every position tick.
    return remember(LocalConfiguration.current) { TrackLabelStrings(
        audioNumbered = { resources.getString(CommonR.string.player_tracks_audioNumbered, it) },
        subtitleNumbered = { resources.getString(CommonR.string.player_tracks_subtitleNumbered, it) },
        default = resources.getString(CommonR.string.detail_trackDefault),
        forced = resources.getString(CommonR.string.detail_trackForced),
        sdh = resources.getString(CommonR.string.detail_trackSdh),
        embedded = resources.getString(CommonR.string.player_tracks_sourceEmbedded),
        external = resources.getString(CommonR.string.detail_trackExternal),
        downloaded = resources.getString(CommonR.string.player_tracks_sourceDownloaded),
        server = resources.getString(CommonR.string.player_tracks_sourceServer),
        mono = resources.getString(CommonR.string.detail_mono),
        stereo = resources.getString(CommonR.string.detail_stereo),
    ) }
}

/** A track laid out for a picker row: title, chips beside it, detail below. */
internal data class TrackPresentation(
    val title: String,
    val chips: List<String> = emptyList(),
    val detail: String? = null,
)

/**
 * Audio tracks are titled by language, not by whatever the muxer wrote into
 * the track title. Container titles are very often a codec description
 * ("ATSC A/52B (AC-3, E-AC-3)"), which says nothing a viewer can choose by;
 * the codec is shown in plain words in the detail line instead. A title that
 * does describe the track ("Director's commentary") is kept as the first
 * detail.
 */
internal fun audioTrackPresentation(track: AudioTrack, index: Int, strings: TrackLabelStrings): TrackPresentation {
    val language = trackLanguageDisplayName(track.language, strings.languageLocale)
    // A title that only names the language, as the server writes it ("English") or as shown.
    val englishLanguage = trackLanguageEnglishName(track.language)
    val descriptor = track.title
        ?.trim()
        ?.takeIf {
            it.isNotBlank() && !isCodecDescription(it) &&
                !it.equals(language, ignoreCase = true) && !it.equals(englishLanguage, ignoreCase = true)
        }
    val title = language ?: descriptor ?: strings.audioNumbered(index + 1)
    val detail = listOfNotNull(
        descriptor.takeIf { language != null },
        audioCodecDisplayName(track.codec),
        audioChannelsDisplayName(track.channels, strings),
        strings.default.takeIf { track.isDefault },
    ).joinToString(" · ").ifBlank { null }
    return TrackPresentation(title = title, detail = detail)
}

internal fun subtitleTrackPresentation(
    sub: PlayerSubtitleInfo,
    index: Int,
    strings: TrackLabelStrings,
    status: String? = null,
): TrackPresentation {
    val parts = subtitleTrackLabelParts(
        rawLabel = sub.label,
        language = sub.language,
        codecOrMime = sub.codec,
        isForced = sub.forced == true,
        languageLocale = strings.languageLocale,
    )
    val title = parts.languageName ?: parts.descriptor ?: strings.subtitleNumbered(index + 1)
    val chips = buildList {
        parts.aiKind?.let { add(it.displayName) }
        if (parts.forced) add(strings.forced)
        if (parts.sdh) add(strings.sdh)
    }
    val detail = listOfNotNull(
        parts.descriptor.takeIf { parts.languageName != null },
        parts.format,
        parts.provider ?: subtitleSourceLabel(sub, strings),
        status,
    ).distinctBy { it.lowercase(Locale.US) }.joinToString(" · ").ifBlank { null }
    return TrackPresentation(title = title, chips = chips, detail = detail)
}

/** The short value for the controls: "English", "English · SDH". */
internal fun subtitleTrackShortLabel(sub: PlayerSubtitleInfo?, index: Int, strings: TrackLabelStrings): String? {
    sub ?: return null
    val presentation = subtitleTrackPresentation(sub, index, strings)
    return (listOf(presentation.title) + presentation.chips).joinToString(" · ")
}

private fun subtitleSourceLabel(sub: PlayerSubtitleInfo, strings: TrackLabelStrings): String? =
    when ((sub.source ?: sub.catalogSource)?.lowercase(Locale.US)) {
        "embedded" -> strings.embedded
        "external" -> strings.external
        "downloaded" -> strings.downloaded
        "server_artifact" -> strings.server
        else -> null
    }

internal fun audioCodecDisplayName(codec: String?): String? {
    val clean = codec?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
    return when {
        clean == "eac3" || clean == "e-ac-3" || clean.contains("ec-3") -> "Dolby Digital Plus"
        clean == "ac3" || clean == "ac-3" -> "Dolby Digital"
        clean.contains("truehd") || clean == "mlp" -> "Dolby TrueHD"
        clean.startsWith("dts") -> "DTS"
        clean == "aac" || clean.startsWith("mp4a") -> "AAC"
        clean == "flac" -> "FLAC"
        clean == "opus" -> "Opus"
        clean == "vorbis" -> "Vorbis"
        clean == "alac" -> "ALAC"
        clean.startsWith("pcm") -> "PCM"
        else -> clean.uppercase(Locale.US)
    }
}

internal fun audioChannelsDisplayName(channels: Int?, strings: TrackLabelStrings): String? = when (channels) {
    null, 0 -> null
    1 -> strings.mono
    2 -> strings.stereo
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> "$channels ch"
}

private val codecDescription = Regex(
    """(?i)\b(atsc|a/52|e-?ac-?3|ac-?3|aac|dts(-hd)?|truehd|flac|pcm|lpcm|opus|mp3|vorbis|alac|dolby|surround|stereo|mono|atmos|\d\.\d|\d+\s?ch)\b""",
)
private val descriptiveWords = Regex("""(?i)\b(commentary|description|descriptive|director|dub|dubbed|original|isolated|score|karaoke)\b""")

private fun isCodecDescription(title: String): Boolean =
    codecDescription.containsMatchIn(title) && !descriptiveWords.containsMatchIn(title)
