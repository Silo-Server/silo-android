package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.player.DolbyVisionDetection
import java.util.Locale

/**
 * Pure formatting helpers for the TV detail playback selector row (Version /
 * Audio / Subtitles / Edition). Mirrors silo-apple's
 * `Screens/Detail/DetailPlaybackFormatting.swift` + `PlaybackEditions.swift`,
 * adapted to the real Android [FileVersion] / [AudioTrack] / [SubtitleTrack]
 * field names.
 *
 * Track-index semantics (preserved from the existing VM contract):
 * - audio `selectedAudioTrackIndex`: `null` = Auto/default, else the
 *   zero-based ordinal into [FileVersion.audioTracks].
 * - subtitle `selectedSubtitleTrackIndex`: `null` = Auto, `-1` = Off, else the
 *   zero-based ORDINAL into [FileVersion.subtitleTracks]. (TV playback selects
 *   subtitles by flat text-track ordinal — `PlayerTrackEntry.index` — NOT the
 *   raw [SubtitleTrack.index] stream index, which is non-ordinal and collides
 *   at 0 for external/unindexed tracks.)
 *
 * NOTE on editions: unlike Apple's `FileVersion` (which carries
 * `edition_key` / `edition_raw` / `edition`), the Android [FileVersion] model
 * exposes NO edition data. [editions] therefore always returns a single
 * "Standard" group (or empty for no versions), so the Edition selector in the
 * UI stays hidden. This becomes meaningful only once the model/server adds
 * edition fields (see SPEC §6 / §11).
 */
object TvPlaybackFormatting {

    data class TvAudioOption(
        val ordinal: Int,
        val title: String,
        val detail: String,
        val isSelected: Boolean,
    )

    data class TvSubtitleOption(
        /** Zero-based ordinal among subtitle tracks — the value to pass to
         *  `onSelectSubtitleTrack` (matches the player's flat text-track index). */
        val selectionIndex: Int,
        val title: String,
        val detail: String,
        val isSelected: Boolean,
        val stableId: String,
    )

    data class TvEdition(
        val id: String,
        val label: String,
        val versions: List<FileVersion>,
    )

    // --- Version ---------------------------------------------------------

    /** Selector value mirrors tvOS's resolved stream summary. */
    fun versionValueLabel(version: FileVersion?, selectedVersionFileId: Int?): String {
        val detail = versionResolvedLabel(version)
        return if (selectedVersionFileId == null) {
            if (version == null) "Auto" else "Auto: $detail"
        } else {
            detail
        }
    }

    /** "2160p · HEVC · DV · TrueHD" — the same facts tvOS exposes inline. */
    fun versionResolvedLabel(version: FileVersion?): String {
        if (version == null) return "Auto"
        val tokens = buildList {
            resolvedResolution(version)?.let { add(it) }
            resolvedVideoCodec(version)?.let { add(it) }
            when {
                isDolbyVision(version) -> add("DV")
                isHdr(version) -> add("HDR")
            }
            resolvedAudioCodec(version)?.let { add(it) }
        }
        return if (tokens.isEmpty()) "Auto" else tokens.joinToString(" · ")
    }

    /** "4K · HDR" / "1080P" / "Auto" (null or no usable tokens → "Auto"). */
    fun versionShortLabel(version: FileVersion?): String {
        if (version == null) return "Auto"
        val tokens = buildList {
            displayResolution(version.resolution)?.let { add(it) }
            when {
                isDolbyVision(version) -> add("DV")
                isHdr(version) -> add("HDR")
            }
        }
        return if (tokens.isEmpty()) "Auto" else tokens.joinToString(" · ")
    }

    fun isDolbyVision(version: FileVersion): Boolean =
        DolbyVisionDetection.isDolbyVision(videoCodec = version.codecVideo) ||
            version.videoTracks.orEmpty().any { track ->
                !track.dolbyVision.isNullOrBlank() ||
                    DolbyVisionDetection.isDolbyVision(
                        dolbyVisionProfile = track.dolbyVisionProfile,
                        hdrFormat = track.hdrFormat,
                        videoCodec = track.codec,
                    )
            }

    fun isHdr(version: FileVersion): Boolean =
        version.hdr || version.videoTracks.orEmpty().any { track ->
            track.hdr || !track.hdrFormat.isNullOrBlank()
        }

    private fun resolvedResolution(version: FileVersion): String? {
        val raw = nonEmpty(version.resolution)
            ?: version.videoTracks.orEmpty().firstNotNullOfOrNull { nonEmpty(it.resolution) }
            ?: return null
        val lowered = raw.lowercase(Locale.US)
        return when {
            lowered.contains("4320") -> "4320p"
            lowered.contains("2160") -> "2160p"
            lowered.contains("1080") -> "1080p"
            lowered.contains("720") -> "720p"
            lowered == "8k" || lowered == "4k" -> raw.uppercase(Locale.US)
            else -> raw
        }
    }

    private fun resolvedVideoCodec(version: FileVersion): String? =
        normalizedVideoCodec(
            nonEmpty(version.codecVideo)
                ?: version.videoTracks.orEmpty().firstNotNullOfOrNull { nonEmpty(it.codec) },
        )

    private fun resolvedAudioCodec(version: FileVersion): String? {
        val resolvedTrackCodec = resolvedAudioOrdinal(version, selectedAudioTrackIndex = null)
            ?.let { version.audioTracks?.getOrNull(it)?.codec }
        return normalizedAudioCodec(resolvedTrackCodec ?: version.codecAudio)
    }

    /** resolution · codec · container · size detail line. */
    fun versionDetailLabel(version: FileVersion): String {
        val tokens = buildList {
            normalizedVideoCodec(version.codecVideo)?.let { add(it) }
            nonEmpty(version.container)?.uppercase(Locale.US)?.let { add(it) }
            formatFileSize(version.fileSize)?.let { add(it) }
        }
        return tokens.joinToString(" · ")
    }

    // --- Audio -----------------------------------------------------------

    fun audioOptions(version: FileVersion?, selectedAudioTrackIndex: Int?): List<TvAudioOption> {
        if (version == null) return emptyList()
        val selectedOrdinal = resolvedAudioOrdinal(version, selectedAudioTrackIndex)
        return (version.audioTracks ?: emptyList()).mapIndexed { ordinal, track ->
            TvAudioOption(
                ordinal = ordinal,
                title = audioTitle(track, ordinal),
                detail = audioDetail(track, ordinal, version),
                isSelected = selectedOrdinal == ordinal,
            )
        }
    }

    fun audioValueLabel(version: FileVersion?, selectedAudioTrackIndex: Int?): String {
        val tracks = version?.audioTracks ?: return "Unknown"
        val ordinal = resolvedAudioOrdinal(version, selectedAudioTrackIndex) ?: return "Unknown"
        val track = tracks.getOrNull(ordinal) ?: return "Unknown"
        val summary = audioSummary(track, ordinal)
        // Auto shows what it resolved to ("Auto: English · EAC3 · 5.1"); a
        // manual pick shows just the track (tvOS `annotateAuto`). Audio auto
        // is the file's default/first track — the same signal the player uses
        // (there is no client-side language audio resolver; the server drives
        // audio selection), so unlike subtitles this preview needs no prefs.
        return if (selectedAudioTrackIndex == null) "Auto: $summary" else summary
    }

    /**
     * Language of the track [audioValueLabel] would display for Auto — feeds
     * the subtitle auto-resolver, whose "auto" mode hides subs when the audio
     * is already in the preferred subtitle language. Mirrors silo-apple's
     * `DetailPlaybackFormatting.resolvedAudioLanguage`.
     */
    fun resolvedAudioLanguage(version: FileVersion?, selectedAudioTrackIndex: Int?): String? {
        val tracks = version?.audioTracks ?: return null
        val ordinal = resolvedAudioOrdinal(version, selectedAudioTrackIndex) ?: return null
        return tracks.getOrNull(ordinal)?.language
    }

    private fun resolvedAudioOrdinal(version: FileVersion?, selectedAudioTrackIndex: Int?): Int? {
        val tracks = version?.audioTracks ?: return null
        if (tracks.isEmpty()) return null
        if (selectedAudioTrackIndex != null && selectedAudioTrackIndex in tracks.indices) {
            return selectedAudioTrackIndex
        }
        // Server-resolved effective track beats the isDefault flag (Apple
        // parity: selected → effective → default → first).
        version?.effectiveAudioTrackIndex?.takeIf { it in tracks.indices }?.let { return it }
        val defaultIndex = tracks.indexOfFirst { it.isDefault }
        if (defaultIndex >= 0) return defaultIndex
        return 0
    }

    /** Menu-row title. Mirrors tvOS `audioTitle`: language → useful custom
     *  title → "Track N". */
    private fun audioTitle(track: AudioTrack, ordinal: Int): String =
        languageDisplayName(track.language)
            ?: usefulAudioTitle(track.title)
            ?: "Track ${ordinal + 1}"

    /** Pill-value summary. Mirrors tvOS `audioSummary`:
     *  "English · EAC3 · 5.1". */
    private fun audioSummary(track: AudioTrack, ordinal: Int): String {
        val tokens = listOfNotNull(
            languageDisplayName(track.language),
            normalizedAudioCodec(track.codec),
            compactAudioLayout(track),
        )
        return if (tokens.isEmpty()) audioTitle(track, ordinal) else tokens.joinToString(" · ")
    }

    /** Menu-row detail. Mirrors tvOS `audioDetail`: custom title (when it
     *  isn't already the row title), codec, layout, Default, Preferred. */
    private fun audioDetail(track: AudioTrack, ordinal: Int, version: FileVersion?): String {
        val tokens = buildList {
            usefulAudioTitle(track.title)
                ?.takeIf { it != audioTitle(track, ordinal) }
                ?.let { add(it) }
            normalizedAudioCodec(track.codec)?.let { add(it) }
            compactAudioLayout(track)?.let { add(it) }
            if (track.isDefault) add("Default")
            if (version?.effectiveAudioTrackIndex == ordinal) add("Preferred")
        }
        return tokens.joinToString(" · ")
    }

    // --- Subtitles -------------------------------------------------------

    fun subtitleOptions(version: FileVersion?, selectedSubtitleTrackIndex: Int?): List<TvSubtitleOption> {
        val tracks = version?.subtitleTracks ?: return emptyList()
        return tracks.mapIndexed { ordinal, track ->
            TvSubtitleOption(
                selectionIndex = ordinal,
                title = subtitleTitle(track, ordinal),
                detail = subtitleDetail(track),
                isSelected = selectedSubtitleTrackIndex == ordinal,
                stableId = "sub-$ordinal",
            )
        }
    }

    /**
     * Inputs needed to preview what the player's subtitle auto-resolver would
     * land on, so the row can annotate "Auto" with the concrete track (or
     * "None"). Mirrors the subset of [TvPlayerViewModel.resolveAutoSubtitleSelection]'s
     * inputs the detail page can supply. Analogue of silo-apple's
     * `DetailPlaybackFormatting.SubtitleAutoContext`.
     */
    data class SubtitleAutoContext(
        /** Cascaded `subtitle_language`. `null` = no preference; empty = "no subs". */
        val preferredLanguage: String?,
        /** Cascaded `subtitle_mode`. `null`/blank → "auto". */
        val mode: String?,
        /** Whether forced subs should be auto-selected when available. */
        val showForced: Boolean = false,
        /** Language of the Auto-resolved audio track (see [resolvedAudioLanguage]);
         *  "auto" mode skips subs when it matches [preferredLanguage]. */
        val audioLanguage: String? = null,
    )

    /**
     * Selector VALUE label for Subtitles.
     *
     * When [autoContext] is supplied, the Auto preview is resolved through the
     * SAME rules the player runs at launch ([TvPlayerViewModel.resolveAutoSubtitleSelection],
     * mirrored in [autoResolvedSubtitle]) — preferred-language / mode /
     * forced-subs — so the row shows exactly what will play, including
     * "Auto - None" when Auto resolves to no subtitles. It never consults the
     * catalog `isDefault` flag, which the resolver ignores and which
     * contradicted playback (QA 2026-07-09 / tvOS parity).
     *
     * Without [autoContext] the resolution inputs are unknown, so we cannot
     * honestly preview the pick and fall back to a bare "Auto" (single-track
     * files show that track) — matching the shared iOS/tvOS formatter's
     * no-context branch rather than guessing.
     */
    fun subtitleValueLabel(
        version: FileVersion?,
        selectedSubtitleTrackIndex: Int?,
        autoContext: SubtitleAutoContext? = null,
    ): String {
        val tracks = version?.subtitleTracks
        if (selectedSubtitleTrackIndex == null) {
            if (autoContext != null) {
                val resolved = autoResolvedSubtitle(version, autoContext)
                return if (resolved != null) {
                    "Auto: ${subtitlePillSummary(resolved.first, resolved.second)}"
                } else {
                    "Auto: Off"
                }
            }
            if (tracks != null && tracks.size == 1) return subtitlePillSummary(tracks[0], 0)
            return "Auto"
        }
        if (selectedSubtitleTrackIndex == -1) return "Off"
        // An explicit positive selection that doesn't resolve in this version's
        // track list: a subtitle IS requested, so "On" (not "Auto"/"Off").
        if (tracks == null) return "On"
        val track = tracks.getOrNull(selectedSubtitleTrackIndex) ?: return "On"
        return subtitlePillSummary(track, selectedSubtitleTrackIndex)
    }

    /**
     * Preview the ordinal (into [FileVersion.subtitleTracks]) the player's
     * subtitle auto-resolver would pick for the no-override case, or `null`
     * when Auto resolves to no subtitles (mode off, "no subs" preference, no
     * language match, or audio already in the preferred language). Mirrors
     * [TvPlayerViewModel.resolveAutoSubtitleSelection] branch-for-branch over
     * the catalog [SubtitleTrack] list. A resolver `NoChange`/`Disable` maps to
     * `null` here: the detail page starts from nothing playing, so both mean
     * "no subtitle".
     */
    private fun autoResolvedSubtitle(
        version: FileVersion?,
        context: SubtitleAutoContext,
    ): Pair<SubtitleTrack, Int>? {
        val tracks = version?.subtitleTracks ?: return null
        if (tracks.isEmpty()) return null

        val mode = context.mode?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "auto"
        if (mode == "off") return null

        val preferred = context.preferredLanguage
        if (preferred != null && preferred.isBlank()) return null

        val targetLanguage = autoSubtitleLanguageKey(preferred)
        if (targetLanguage == null) {
            return if (mode == "always") {
                bestAutoSubtitleTrack(tracks, targetLanguage = null, preferForced = context.showForced)
            } else {
                null
            }
        }

        val audioLanguage = autoSubtitleLanguageKey(context.audioLanguage)
        if (mode == "auto" && audioLanguage != null && audioLanguage == targetLanguage) {
            if (context.showForced) {
                bestForcedAutoSubtitleTrack(tracks, targetLanguage)?.let { return it }
            }
            return null
        }

        return bestAutoSubtitleTrack(tracks, targetLanguage, preferForced = context.showForced)
            ?: if (context.showForced) {
                tracks.withIndex().firstOrNull { it.value.forced }?.let { it.value to it.index }
            } else {
                null
            }
    }

    /** Mirrors `TvPlayerViewModel.bestAutoSubtitleTrack` over catalog tracks. */
    private fun bestAutoSubtitleTrack(
        tracks: List<SubtitleTrack>,
        targetLanguage: String?,
        preferForced: Boolean,
    ): Pair<SubtitleTrack, Int>? {
        val pool = tracks.withIndex().filter { (_, t) ->
            targetLanguage == null || autoSubtitleLanguageKey(t.language) == targetLanguage
        }
        if (pool.isEmpty()) return null
        if (preferForced) {
            pool.firstOrNull { (_, t) -> t.forced && !isHearingImpairedSubtitle(t) && !isBitmapSubtitle(t.codec) }
                ?.let { return it.value to it.index }
        }
        pool.firstOrNull { (_, t) -> !t.forced && !isHearingImpairedSubtitle(t) && !isBitmapSubtitle(t.codec) }
            ?.let { return it.value to it.index }
        pool.firstOrNull { (_, t) -> !t.forced && !isBitmapSubtitle(t.codec) }
            ?.let { return it.value to it.index }
        pool.firstOrNull { (_, t) -> !isBitmapSubtitle(t.codec) }
            ?.let { return it.value to it.index }
        return pool.first().let { it.value to it.index }
    }

    /** Mirrors `TvPlayerViewModel.bestForcedAutoSubtitleTrack` over catalog tracks. */
    private fun bestForcedAutoSubtitleTrack(
        tracks: List<SubtitleTrack>,
        targetLanguage: String?,
    ): Pair<SubtitleTrack, Int>? {
        val pool = tracks.withIndex().filter { (_, t) ->
            (targetLanguage == null || autoSubtitleLanguageKey(t.language) == targetLanguage) && t.forced
        }
        if (pool.isEmpty()) return null
        pool.firstOrNull { (_, t) -> !isHearingImpairedSubtitle(t) && !isBitmapSubtitle(t.codec) }
            ?.let { return it.value to it.index }
        pool.firstOrNull { (_, t) -> !isHearingImpairedSubtitle(t) }
            ?.let { return it.value to it.index }
        return pool.first().let { it.value to it.index }
    }

    /**
     * ISO-639 folding used by the RESOLVER (`TvPlayerViewModel.normalizedSubtitleLanguage`)
     * — deliberately its own smaller alias table (not [languageDisplayName]'s),
     * dropping `und`, so the preview matches the player's language comparison
     * exactly rather than the row's display grouping.
     */
    private fun autoSubtitleLanguageKey(language: String?): String? {
        val primary = language
            ?.trim()
            ?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
            ?.lowercase(Locale.US)
            ?.replace('_', '-')
            ?.substringBefore('-')
            ?: return null
        return when (primary) {
            "eng" -> "en"
            "spa" -> "es"
            "fre", "fra" -> "fr"
            "ger", "deu" -> "de"
            "dut", "nld" -> "nl"
            "jpn" -> "ja"
            "dan" -> "da"
            else -> primary
        }
    }

    private val hearingImpairedSubtitleTokenRegex =
        Regex("""(^|[^a-z0-9])(cc|sdh|hi)([^a-z0-9]|$)""", RegexOption.IGNORE_CASE)

    /** Title-based HI/CC/SDH detection mirroring the player's `indicatesHearingImpairedSubtitle`. */
    private fun isHearingImpairedSubtitle(track: SubtitleTrack): Boolean {
        val title = track.title ?: return false
        val lower = title.lowercase(Locale.US)
        return lower.contains("closed caption") ||
            lower.contains("hearing impaired") ||
            lower.contains("hearing-impaired") ||
            lower.contains("hearing") ||
            hearingImpairedSubtitleTokenRegex.containsMatchIn(title)
    }

    /**
     * Mirrors `isBitmapSubtitleCodecOrMime` (PGS / VobSub / DVB / HDMV).
     * Normalization strips ALL non-alphanumerics so ffprobe names
     * ("dvb_subtitle", "hdmv_pgs_subtitle"), short names ("dvbsub"/"dvbsubs")
     * and Media3 mimes classify identically — Apple parity with
     * `ApplePlaybackRoutePlanner`'s bitmap token set.
     */
    private fun isBitmapSubtitle(codec: String?): Boolean {
        val n = codec?.filter { it.isLetterOrDigit() }?.lowercase(Locale.US)
            ?.takeIf { it.isNotEmpty() } ?: return false
        return n.contains("pgs") || n.contains("hdmv") || n.contains("dvd") ||
            n.contains("dvbsub") || n.contains("vobsub")
    }

    /** Menu-row title. Mirrors tvOS `subtitleTitle`: language → meaningful
     *  custom title → "Track N". */
    private fun subtitleTitle(track: SubtitleTrack, ordinal: Int): String =
        languageDisplayName(track.language)
            ?: meaningfulSubtitleTitle(track)
            ?: "Track ${ordinal + 1}"

    /**
     * Pill-value summary. Mirrors tvOS `subtitlePillSummary`:
     * "English (Forced) · SRT" / "English (SDH) · PGS".
     */
    private fun subtitlePillSummary(track: SubtitleTrack, ordinal: Int): String {
        var name = subtitleTitle(track, ordinal)
        if (isHearingImpairedSubtitle(track) && !containsAccessibilityMarker(name)) {
            name += " (SDH)"
        }
        if (isForcedSubtitle(track) && !name.contains("forced", ignoreCase = true)) {
            name += " (Forced)"
        }
        val codec = normalizedSubtitleCodec(track.codec) ?: return name
        return "$name · $codec"
    }

    /** Menu-row detail. Mirrors tvOS `subtitleDetail`: custom title, codec,
     *  Forced, SDH, Default, External. */
    private fun subtitleDetail(track: SubtitleTrack): String {
        val tokens = buildList {
            meaningfulSubtitleTitle(track)?.let { add(it) }
            normalizedSubtitleCodec(track.codec)?.let { add(it) }
            if (isForcedSubtitle(track)) add("Forced")
            if (isHearingImpairedSubtitle(track)) add("SDH")
            if (track.isDefault) add("Default")
            if (track.external) add("External")
        }
        return tokens.joinToString(" · ")
    }

    /** Mirrors tvOS `meaningfulSubtitleTitle`: a custom title worth showing —
     *  not language/codec-redundant and not a bare accessibility/forced tag. */
    private fun meaningfulSubtitleTitle(track: SubtitleTrack): String? {
        val title = nonEmpty(track.title)?.takeUnless { isRedundantSubtitleTitle(it, track) }
            ?: return null
        val lowered = title.lowercase(Locale.US)
        if (lowered == "forced" || lowered in listOf("sdh", "cc", "hi", "hearing impaired")) {
            return null
        }
        return displayTitle(title)
    }

    /** Mirrors tvOS `isForced`: flag OR a "forced" mention in the title. */
    private fun isForcedSubtitle(track: SubtitleTrack): Boolean =
        track.forced || track.title?.contains("forced", ignoreCase = true) == true

    /** Mirrors tvOS `containsAccessibilityMarker` — keeps the pill from
     *  doubling up markers a custom title already carries. */
    private fun containsAccessibilityMarker(value: String): Boolean {
        val lowered = value.lowercase(Locale.US)
        val words = lowered.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return "sdh" in words || "cc" in words || "hi" in words ||
            lowered.contains("hearing impaired")
    }

    // --- Editions (Android model has no edition data) --------------------

    fun currentEdition(versions: List<FileVersion>, currentVersion: FileVersion?): TvEdition? =
        edition(forFileId = currentVersion?.fileId, versions = versions)
            ?: editions(versions).firstOrNull()

    /**
     * Distinct editions in first-seen order. The Android [FileVersion] carries
     * no edition fields, so every version lands in one "Standard" group; this
     * keeps the UI's Edition selector hidden until model support lands.
     */
    fun editions(versions: List<FileVersion>): List<TvEdition> {
        if (versions.isEmpty()) return emptyList()
        return listOf(TvEdition(id = "standard", label = "Standard", versions = versions))
    }

    private fun edition(forFileId: Int?, versions: List<FileVersion>): TvEdition? {
        if (forFileId == null) return null
        return editions(versions).firstOrNull { ed -> ed.versions.any { it.fileId == forFileId } }
    }

    // --- Codec / layout / size normalization (mirrors Swift) -------------

    fun normalizedVideoCodec(codec: String?): String? {
        val lowered = codec?.lowercase(Locale.US)?.trim()
        if (lowered.isNullOrEmpty()) return null
        return when {
            lowered.contains("hevc") || lowered.contains("h265") -> "HEVC"
            lowered.contains("av1") -> "AV1"
            lowered.contains("avc") || lowered.contains("h264") -> "H.264"
            else -> lowered.uppercase(Locale.US)
        }
    }

    fun normalizedAudioCodec(codec: String?): String? {
        val lowered = codec?.lowercase(Locale.US)?.trim()
        if (lowered.isNullOrEmpty()) return null
        return when {
            lowered.contains("eac3") || lowered.contains("e-ac-3") || lowered.contains("ec-3") -> "EAC3"
            lowered.contains("ac3") || lowered.contains("ac-3") -> "AC3"
            lowered.contains("aac") -> "AAC"
            lowered.contains("mp3") -> "MP3"
            lowered.contains("truehd") -> "TrueHD"
            lowered.contains("dts") -> "DTS"
            lowered.contains("flac") -> "FLAC"
            else -> lowered.uppercase(Locale.US)
        }
    }

    private fun normalizedSubtitleCodec(codec: String?): String? {
        val lowered = codec?.lowercase(Locale.US)?.trim()
        if (lowered.isNullOrEmpty()) return null
        return when {
            lowered == "srt" || lowered.contains("subrip") -> "SRT"
            lowered.contains("ass") -> "ASS"
            lowered.contains("ssa") -> "SSA"
            lowered == "vtt" || lowered.contains("webvtt") -> "WebVTT"
            lowered.contains("pgs") || lowered.contains("hdmv") -> "PGS"
            lowered.contains("dvd") || lowered.contains("vobsub") -> "VobSub"
            lowered.contains("mov_text") || lowered.contains("tx3g") -> "TX3G"
            else -> lowered.uppercase(Locale.US)
        }
    }

    /**
     * Display-friendly resolution token. Apple's helper just uppercases the raw
     * resolution; the TV row additionally maps 2160 → "4K" (SPEC §6 value
     * table: "4K · HDR" / "1080P").
     */
    private fun displayResolution(value: String?): String? {
        val token = nonEmpty(value) ?: return null
        val lowered = token.lowercase(Locale.US)
        return when {
            lowered.contains("4320") || lowered.contains("8k") -> "8K"
            lowered.contains("2160") || lowered == "4k" || lowered.contains("uhd") -> "4K"
            else -> token.uppercase(Locale.US)
        }
    }

    /**
     * Mirrors Apple's `ByteCountFormatter` (`.file` countStyle, adaptive):
     * DECIMAL units (÷1000), GB to 2 decimals / MB to 1 decimal. e.g. 8 GiB →
     * "8.59 GB", 500 MiB → "524.3 MB". Returns null for non-positive counts.
     */
    private fun formatFileSize(bytes: Long): String? {
        if (bytes <= 0) return null
        val gb = bytes / 1_000_000_000.0
        if (gb >= 1.0) return String.format(Locale.US, "%.2f GB", gb)
        val mb = bytes / 1_000_000.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun compactAudioLayout(track: AudioTrack): String? {
        nonEmpty(track.channelLayout)?.let { layout ->
            val lowered = layout.lowercase(Locale.US)
            return when {
                lowered.contains("atmos") -> "Atmos"
                lowered.contains("7.1") -> "7.1"
                lowered.contains("5.1") -> "5.1"
                lowered.contains("stereo") -> "Stereo"
                else -> layout
            }
        }
        return when (track.channels) {
            null -> null
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${track.channels}ch"
        }
    }

    private fun usefulAudioTitle(title: String?): String? {
        val trimmed = nonEmpty(title) ?: return null
        val lowered = trimmed.lowercase(Locale.US)
        val technicalTerms = listOf(
            "atsc", "a/52", "ac-3", "e-ac-3", "eac3", "truehd", "dts", "aac", "flac",
        )
        if (technicalTerms.any { lowered.contains(it) }) return null
        return displayTitle(trimmed)
    }

    private fun isRedundantSubtitleTitle(title: String, track: SubtitleTrack): Boolean {
        val lowered = title.lowercase(Locale.US)
        val language = languageDisplayName(track.language)?.lowercase(Locale.US)
        val languageCode = nonEmpty(track.language)?.lowercase(Locale.US)
        if (lowered == "subtitle" || lowered == "subtitles") return true
        if (language != null && lowered == language) return true
        if (languageCode != null && lowered == languageCode) return true
        val codec = normalizedSubtitleCodec(track.codec)?.lowercase(Locale.US)
        if (codec != null && (lowered == codec || lowered == track.codec?.lowercase(Locale.US))) return true
        return false
    }

    private fun displayTitle(title: String): String {
        val trimmed = title.trim()
        return when (trimmed.lowercase(Locale.US)) {
            "sdh" -> "SDH"
            "cc" -> "CC"
            "srt", "subrip" -> "SRT"
            "webvtt", "vtt" -> "WebVTT"
            else -> trimmed
        }
    }

    private fun languageDisplayName(value: String?): String? {
        val trimmed = nonEmpty(value) ?: return null
        val normalized = trimmed.lowercase(Locale.US).replace('_', '-')
        val primary = normalized.split('-').firstOrNull() ?: normalized
        if (primary.length > 3) return capitalizeWords(trimmed)
        val languageCode = languageCodeAliases[primary] ?: primary
        val display = Locale.forLanguageTag(languageCode).getDisplayLanguage(Locale.ENGLISH)
        return if (display.isNotBlank() && !display.equals(languageCode, ignoreCase = true)) {
            capitalizeWords(display)
        } else {
            trimmed.uppercase(Locale.US)
        }
    }

    private val languageCodeAliases: Map<String, String> = mapOf(
        "ara" to "ar", "chi" to "zh", "cze" to "cs", "dan" to "da", "deu" to "de",
        "dut" to "nl", "eng" to "en", "fin" to "fi", "fra" to "fr", "fre" to "fr",
        "ger" to "de", "hin" to "hi", "ita" to "it", "jpn" to "ja", "kor" to "ko",
        "nld" to "nl", "nor" to "no", "pol" to "pl", "por" to "pt", "rus" to "ru",
        "spa" to "es", "swe" to "sv", "zho" to "zh",
    )

    private fun capitalizeWords(value: String): String =
        value.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }

    private fun nonEmpty(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
