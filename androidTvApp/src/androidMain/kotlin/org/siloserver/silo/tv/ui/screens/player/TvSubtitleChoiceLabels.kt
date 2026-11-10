package org.siloserver.silo.tv.ui.screens.player

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.player.aiSubtitleKind
import org.siloserver.silo.player.aiSubtitleSourceLabel
import org.siloserver.silo.player.trackLanguageDisplayName
import org.siloserver.silo.player.trackLanguageEnglishName
import java.util.Locale

/**
 * The current [Resources] for the label builders below, which also run
 * outside composition (picker rows built on Select). Taking them from
 * [LocalResources] recomposes the caller when the app language changes.
 */
@Composable
@ReadOnlyComposable
internal fun tvLabelResources(): Resources = LocalResources.current

/**
 * Human labels for mounted subtitle rows, using the same identity hierarchy
 * as the detail-page selector. Release filenames and runtime-only labels such
 * as "Server subtitle" stay available for identity/matching, but are never
 * presented to the user.
 */
internal fun subtitleChoiceLabel(row: PlayerSubtitleInfo, position: Int, resources: Resources): String {
    // trackLanguageDisplayName handles ISO 639-2/B codes ("ger","fre","dut")
    // that Locale(code) alone cannot; fall back to the uppercased raw code.
    val language = row.language?.trim()?.takeIf { it.isNotBlank() }
        ?.let { trackLanguageDisplayName(it, LocaleFormat.interfaceLocale()) ?: it.uppercase(Locale.US) }
    val format = subtitleFormatShortName(
        row.codec?.trim()?.takeIf { it.isNotBlank() }
            ?: row.url.substringBefore('?').substringBefore('#').substringAfterLast('.', ""),
    )
    // An AI track names how it was made instead of its release-style title,
    // keeping the server label only when there is no language to show.
    val rawLabel = row.catalogLabel ?: row.label
    val aiKind = aiSubtitleKind(rawLabel)
    val title = when {
        aiKind == null -> meaningfulSubtitleRowTitle(row, language, format)
        language == null -> aiSubtitleSourceLabel(rawLabel)
        else -> null
    }
    val base = language ?: title ?: resources.getString(CommonR.string.options_track, LocaleFormat.integer(position + 1))
    val detail = buildList {
        aiKind?.let { add(it.displayName) }
        if (title != null && title != base) add(title)
        if (format != null) add(format)
        if (row.forced == true) add(resources.getString(CommonR.string.detail_trackForced))
        if (row.isDefault == true) add(resources.getString(CommonR.string.detail_trackDefault))
        when (row.catalogSource ?: row.source) {
            "external" -> add(resources.getString(CommonR.string.detail_trackExternal))
            "downloaded" -> add(resources.getString(CommonR.string.player_tracks_sourceDownloaded))
        }
    }
    return if (detail.isEmpty()) base else "$base — ${detail.joinToString(" · ")}"
}

/** ffmpeg codec ids / file extensions → the short format names users know. */
internal fun subtitleFormatShortName(codec: String?): String? =
    when (val normalized = codec?.trim()?.lowercase(Locale.US)) {
        null, "" -> null
        "subrip", "srt" -> "SRT"
        "ass", "ssa" -> "ASS"
        "webvtt", "vtt" -> "VTT"
        "mov_text", "tx3g" -> "MP4"
        "hdmv_pgs_subtitle", "pgs", "pgssub", "sup" -> "PGS"
        "dvd_subtitle", "dvdsub", "vobsub", "sub" -> "VobSub"
        "dvb_subtitle", "dvbsub" -> "DVB"
        "dvb_teletext" -> "Teletext"
        "eia_608", "eia608", "cea_608", "cea608" -> "CC"
        else -> normalized.uppercase(Locale.US)
    }

/**
 * A row label worth showing next to language+format: not blank, not a generic
 * runtime artifact label, not codec junk, not redundant with language, and
 * never a release/show filename.
 */
private fun meaningfulSubtitleRowTitle(
    row: PlayerSubtitleInfo,
    language: String?,
    format: String?,
): String? {
    val label = (row.catalogLabel ?: row.label)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val lowered = label.lowercase(Locale.US)
    if (label.length > 28 || '[' in label || SUBTITLE_FILENAME_SUFFIXES.any(lowered::endsWith)) return null
    if (lowered in GENERIC_SUBTITLE_LABELS) return null
    if (language != null && lowered == language.lowercase(Locale.US)) return null
    // Server labels name the language in English, whatever the interface shows.
    if (lowered == trackLanguageEnglishName(row.language)?.lowercase(Locale.US)) return null
    if (row.language != null && lowered == row.language!!.lowercase(Locale.US)) return null
    if (format != null && lowered == format.lowercase(Locale.US)) return null
    if (row.codec != null && lowered == row.codec!!.lowercase(Locale.US)) return null
    return label
}

private val GENERIC_SUBTITLE_LABELS = setOf("server subtitle", "subtitle", "subtitles")
private val SUBTITLE_FILENAME_SUFFIXES = listOf(
    ".srt", ".ass", ".ssa", ".vtt", ".sub", ".sup", ".idx",
)

/**
 * Human labels for audio track rows ("English DTS 5.1", "Japanese AAC
 * Stereo") — the audio twin of [subtitleChoiceLabel]. Media3 labels echo
 * server identity strings and the old fallback was a bare uppercased ISO
 * code, so pickers build from language + codec + channel layout instead.
 */
internal fun audioChoiceLabel(entry: PlayerTrackEntry, position: Int, resources: Resources): String {
    val language = entry.language?.trim()?.takeIf { it.isNotBlank() }
        ?.let { trackLanguageDisplayName(it, LocaleFormat.interfaceLocale()) ?: it.uppercase(Locale.US) }
    val codec = audioFormatShortName(entry.codecOrMime)
    val layout = audioChannelLayoutLabel(entry.channelCount, resources)
    val parts = listOfNotNull(language, codec, layout)
    if (parts.isEmpty()) {
        return entry.displayLabel.trim().takeIf { it.isNotBlank() }
            ?: resources.getString(CommonR.string.options_track, LocaleFormat.integer(position + 1))
    }
    val qualifier = meaningfulAudioTrackTitle(
        displayLabel = entry.displayLabel,
        rawLanguage = entry.language,
        language = language,
        rawCodecOrMime = entry.codecOrMime,
        codec = codec,
        layout = layout,
        serverLayout = AUDIO_LAYOUT_TOKENS[entry.channelCount],
    )
    return buildString {
        append(parts.joinToString(" "))
        qualifier?.let { append(" ($it)") }
    }
}

internal fun meaningfulAudioTrackTitle(
    displayLabel: String,
    rawLanguage: String?,
    language: String?,
    rawCodecOrMime: String?,
    codec: String?,
    layout: String?,
    // The English layout word servers put in track titles, stripped even when
    // [layout] is translated.
    serverLayout: String? = null,
): String? {
    val original = displayLabel.trim().takeIf { it.isNotBlank() } ?: return null
    val lower = original.lowercase(Locale.US)
    // Track titles name the language in English ("English", "eng"), whatever [language] shows.
    val englishLanguage = trackLanguageEnglishName(rawLanguage)
    if (AUDIO_FILENAME_SUFFIXES.any(lower::endsWith)) return null
    if (lower.matches(Regex("\\d+"))) return null
    if (lower.matches(Regex("(?:audio\\s+)?track\\s*\\d+"))) return null
    if (lower.matches(Regex("[a-z]{2,3}")) &&
        englishLanguage != null &&
        trackLanguageEnglishName(lower)?.equals(englishLanguage, ignoreCase = true) == true
    ) {
        return null
    }

    var qualifier = original
    // Remove only complete metadata tokens. Substring replacement corrupts
    // legitimate titles (for example AAC inside "Isaac Commentary").
    listOfNotNull(
        language,
        englishLanguage,
        rawLanguage?.trim(),
        codec,
        rawCodecOrMime?.substringAfterLast('/')?.trim(),
        layout,
        serverLayout,
    )
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.US) }
        .sortedByDescending(String::length)
        .forEach { redundant ->
            qualifier = qualifier.replace(
                Regex(
                    "(?<![\\p{L}\\p{N}])${Regex.escape(redundant)}(?![\\p{L}\\p{N}])",
                    RegexOption.IGNORE_CASE,
                ),
                " ",
            )
        }
    // A raw two/three-letter alias can differ from the selected track's raw
    // code ("en" metadata with an "eng" server label). Remove it only when
    // both codes resolve to the same language.
    if (englishLanguage != null) {
        qualifier = qualifier.replace(
            Regex("(?<![\\p{L}\\p{N}])[a-z]{2,3}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE),
        ) { match ->
            if (trackLanguageEnglishName(match.value)?.equals(englishLanguage, ignoreCase = true) == true) {
                " "
            } else {
                match.value
            }
        }
    }
    qualifier = qualifier
        .replace(Regex("[()\\[\\]{}|•·,_/\\-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return qualifier.takeIf { it.isNotBlank() }
}

private val AUDIO_FILENAME_SUFFIXES = listOf(
    ".aac", ".ac3", ".eac3", ".dts", ".dtshd", ".flac", ".m4a", ".mp3", ".opus", ".wav",
)

/** Media3 audio mimes / codec ids → the short names users know. */
internal fun audioFormatShortName(codecOrMime: String?): String? =
    when (codecOrMime?.trim()?.lowercase(Locale.US)?.substringAfterLast('/')) {
        null, "" -> null
        "mp4a-latm", "aac", "mp4a" -> "AAC"
        "ac3" -> "AC3"
        "eac3" -> "E-AC3"
        "eac3-joc" -> "Atmos"
        "true-hd", "truehd" -> "TrueHD"
        "vnd.dts", "dts" -> "DTS"
        "vnd.dts.hd", "dts.hd" -> "DTS-HD"
        "vnd.dts.uhd;profile=p2", "dts.uhd" -> "DTS:X"
        "opus" -> "Opus"
        "flac" -> "FLAC"
        "mpeg", "mp3", "mpeg-l2" -> "MP3"
        "vorbis" -> "Vorbis"
        "raw", "pcm", "wav" -> "PCM"
        else -> codecOrMime.substringAfterLast('/').uppercase(Locale.US).take(12)
    }

/** Channel count → familiar layout name. */
private val AUDIO_LAYOUT_TOKENS = mapOf(1 to "Mono", 2 to "Stereo")

internal fun audioChannelLayoutLabel(channelCount: Int, resources: Resources): String? = when {
    channelCount <= 0 -> null
    channelCount == 1 -> resources.getString(CommonR.string.detail_mono)
    channelCount == 2 -> resources.getString(CommonR.string.detail_stereo)
    channelCount == 6 -> "5.1"
    channelCount == 8 -> "7.1"
    else -> "${channelCount}ch"
}
