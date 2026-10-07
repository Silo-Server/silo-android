package org.siloserver.silo.android.ui.screens.player

import java.util.Locale
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.player.subtitleTrackLabelParts
import org.siloserver.silo.player.trackLanguageDisplayName

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
internal fun audioTrackPresentation(track: AudioTrack, index: Int): TrackPresentation {
    val language = trackLanguageDisplayName(track.language)
    val descriptor = track.title
        ?.trim()
        ?.takeIf { it.isNotBlank() && !isCodecDescription(it) && !it.equals(language, ignoreCase = true) }
    val title = language ?: descriptor ?: "Audio ${index + 1}"
    val detail = listOfNotNull(
        descriptor.takeIf { language != null },
        audioCodecDisplayName(track.codec),
        audioChannelsDisplayName(track.channels),
        "Default".takeIf { track.isDefault },
    ).joinToString(" · ").ifBlank { null }
    return TrackPresentation(title = title, detail = detail)
}

internal fun subtitleTrackPresentation(
    sub: PlayerSubtitleInfo,
    index: Int,
    status: String? = null,
): TrackPresentation {
    val parts = subtitleTrackLabelParts(
        rawLabel = sub.label,
        language = sub.language,
        codecOrMime = sub.codec,
        isForced = sub.forced == true,
    )
    val title = parts.languageName ?: parts.descriptor ?: "Subtitle ${index + 1}"
    val chips = buildList {
        if (parts.aiGenerated) add("AI")
        if (parts.forced) add("Forced")
        if (parts.sdh) add("SDH")
    }
    val detail = listOfNotNull(
        parts.descriptor.takeIf { parts.languageName != null },
        parts.format,
        parts.provider ?: subtitleSourceLabel(sub),
        status,
    ).distinctBy { it.lowercase(Locale.US) }.joinToString(" · ").ifBlank { null }
    return TrackPresentation(title = title, chips = chips, detail = detail)
}

/** The short value for the controls: "English", "English · SDH". */
internal fun subtitleTrackShortLabel(sub: PlayerSubtitleInfo?, index: Int): String? {
    sub ?: return null
    val presentation = subtitleTrackPresentation(sub, index)
    return (listOf(presentation.title) + presentation.chips).joinToString(" · ")
}

private fun subtitleSourceLabel(sub: PlayerSubtitleInfo): String? =
    when ((sub.source ?: sub.catalogSource)?.lowercase(Locale.US)) {
        "embedded" -> "Embedded"
        "external" -> "External"
        "downloaded" -> "Downloaded"
        "server_artifact" -> "Server"
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

internal fun audioChannelsDisplayName(channels: Int?): String? = when (channels) {
    null, 0 -> null
    1 -> "Mono"
    2 -> "Stereo"
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
