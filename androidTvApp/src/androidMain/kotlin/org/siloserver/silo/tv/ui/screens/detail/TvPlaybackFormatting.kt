@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.common.player.TrackSelectionPresets
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.AutoSubtitleContext
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.catalogAutoSubtitleCandidates
import org.siloserver.silo.model.playback.combinedSubtitleSelectionIndexes
import org.siloserver.silo.model.playback.resolveAutoSubtitle
import org.siloserver.silo.model.playback.selectedCandidate
import org.siloserver.silo.player.DolbyVisionDetection
import org.siloserver.silo.tv.ui.navigation.TvSubtitleLaunchSelection
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired
import java.util.Locale

internal fun automaticTrackLabel(resolvedLabel: String?): String =
    "Auto - ${resolvedLabel ?: "None"}"

internal fun resolveTvAutomaticAudioTrackOrdinal(
    version: FileVersion?,
    preferredAudioLanguage: String?,
    capabilities: ClientCodecCapabilities?,
): Int? = if (version == null || capabilities == null) {
    null
} else {
    TrackSelectionPresets.selectBestCompatibleAudioTrackOrdinal(
        tracks = version.audioTracks.orEmpty(),
        preferredAudioLanguage = preferredAudioLanguage,
        capabilities = capabilities,
    )
}

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
 *   combined subtitle selection index shared with the player. This identity is
 *   derived from catalog order plus external-track placement; it is not the raw
 *   [SubtitleTrack.index] stream index and is not the visible sorted-row ordinal.
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
        /** Combined subtitle selection index passed to `onSelectSubtitleTrack`. */
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

    /**
     * "4K · HEVC · DV · TrueHD" / "1080P · H.264 · AAC" / "Auto" (null or no
     * usable tokens → "Auto"). Same token set as tvOS's
     * `DetailPlaybackFormatting.versionShortLabel` (resolution · video codec ·
     * dynamic range · audio codec) so the Version pill tells the user which
     * audio the file carries, not just its resolution.
     */
    fun versionShortLabel(version: FileVersion?): String {
        if (version == null) return "Auto"
        val tokens = buildList {
            displayResolution(version.resolution)?.let { add(it) }
            resolvedVideoCodec(version)?.let { add(it) }
            when {
                isDolbyVision(version) -> add("DV")
                isHdr(version) -> add("HDR")
            }
            resolvedAudioCodec(version)?.let { add(it) }
        }
        return if (tokens.isEmpty()) "Auto" else tokens.joinToString(" · ")
    }

    /** Resting connected-segment value: resolution plus HDR family. */
    fun versionCompactLabel(version: FileVersion?): String {
        if (version == null) return "Auto"
        val tokens = buildList {
            resolvedResolution(version)?.let(::displayResolution)?.let { add(it) }
            when {
                isDolbyVision(version) -> add("DV")
                isHdr(version) -> add("HDR")
            }
        }
        return if (tokens.isEmpty()) "Auto" else tokens.joinToString(" · ")
    }

    /**
     * Picker labels for a whole version list, disambiguated against each other.
     *
     * [versionShortLabel] is built from resolution / codecs / HDR-DV alone, so a
     * title holding two 4K HEVC Dolby Vision TrueHD files (a remux and an
     * encode, say) renders two identical rows and the user cannot tell them
     * apart. Selection still works (the option id is the unique fileId) — the
     * list is just unreadable.
     *
     * Only colliding labels get a suffix, so the common single-version-per-tier
     * case is untouched. Attributes are accumulated until the group's labels
     * are actually distinct: no single attribute need be unique on its own.
     * Size usually does the work when a remux and an encode share a codec;
     * container is the last resort.
     */
    fun versionPickerLabels(versions: List<FileVersion>): List<String> {
        val base = versions.map { versionShortLabel(it) }
        val colliding = base.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (colliding.isEmpty()) return base

        val suffixes = MutableList(versions.size) { "" }
        for (label in colliding) {
            val indexes = base.indices.filter { base[it] == label }
            // Widen the attribute tuple until this group is separated, or until
            // we run out of attributes and accept an honest duplicate.
            for (depth in 1..VERSION_DISCRIMINATORS.size) {
                val attempt = indexes.associateWith { index ->
                    VERSION_DISCRIMINATORS.take(depth)
                        .mapNotNull { it(versions[index]) }
                        .joinToString(" · ")
                }
                val distinct = attempt.values.toSet().size
                // Only keep a tuple that actually separates something; two
                // identical versions would otherwise both gain the same suffix
                // — a fabricated difference that distinguishes nothing.
                if (distinct > 1) indexes.forEach { suffixes[it] = attempt.getValue(it) }
                if (distinct == indexes.size) break
            }
        }
        return base.mapIndexed { index, label ->
            val suffix = suffixes[index]
            if (suffix.isBlank()) label else "$label · $suffix"
        }
    }

    /**
     * Attributes tried, in order, when version labels collide. Codecs are
     * already part of [versionShortLabel], so they never need to be appended.
     */
    private val VERSION_DISCRIMINATORS: List<(FileVersion) -> String?> = listOf(
        { v -> formatFileSize(v.fileSize) },
        { v -> v.container?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT) },
    )

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
        return (version.audioTracks ?: emptyList()).mapIndexed { ordinal, track ->
            TvAudioOption(
                ordinal = ordinal,
                title = audioTitle(track, ordinal),
                detail = audioDetail(track, ordinal, version),
                isSelected = isAudioSelectorOptionSelected(ordinal, selectedAudioTrackIndex),
            )
        }
    }

    fun audioValueLabel(
        version: FileVersion?,
        selectedAudioTrackIndex: Int?,
        automaticAudioTrackOrdinal: Int? = null,
    ): String {
        val tracks = version?.audioTracks ?: return "Unknown"
        val ordinal = resolvedAudioOrdinal(
            version,
            selectedAudioTrackIndex,
            automaticAudioTrackOrdinal,
        ) ?: return "Unknown"
        val track = tracks.getOrNull(ordinal) ?: return "Unknown"
        val summary = audioSummary(track, ordinal)
        // Auto shows what it resolved to ("Auto: English · EAC3 · 5.1"); a
        // manual pick shows just the track (tvOS `annotateAuto`). Audio auto
        // follows the same language/capability resolution used by playback.
        return if (selectedAudioTrackIndex == null) "Auto: $summary" else summary
    }

    /**
     * Language of the track [audioValueLabel] would display for Auto — feeds
     * the subtitle auto-resolver, whose "auto" mode hides subs when the audio
     * is already in the preferred subtitle language. Mirrors silo-apple's
     * `DetailPlaybackFormatting.resolvedAudioLanguage`.
     */
    fun resolvedAudioLanguage(
        version: FileVersion?,
        selectedAudioTrackIndex: Int?,
        automaticAudioTrackOrdinal: Int? = null,
    ): String? {
        val tracks = version?.audioTracks ?: return null
        val ordinal = resolvedAudioOrdinal(
            version,
            selectedAudioTrackIndex,
            automaticAudioTrackOrdinal,
        ) ?: return null
        return tracks.getOrNull(ordinal)?.language
    }

    private fun resolvedAudioOrdinal(
        version: FileVersion?,
        selectedAudioTrackIndex: Int?,
        automaticAudioTrackOrdinal: Int? = null,
    ): Int? {
        val tracks = version?.audioTracks ?: return null
        if (tracks.isEmpty()) return null
        if (selectedAudioTrackIndex != null && selectedAudioTrackIndex in tracks.indices) {
            return selectedAudioTrackIndex
        }
        if (automaticAudioTrackOrdinal != null && automaticAudioTrackOrdinal in tracks.indices) {
            return automaticAudioTrackOrdinal
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
    /**
     * Source-identity summary for the audio the playback plan selected, keyed
     * by ORDINAL into `audio_tracks` — the server's contract for audio.
     *
     * Deliberately NOT keyed on [AudioTrack.index]: the server sends no index
     * for audio tracks (subtitles do get one), so that field is `0` for every
     * row and cannot identify anything.
     *
     * The player HUD used to label this row from the mounted Media3 track,
     * which describes what was *delivered*: a DTS 5.1 source transcoded to
     * stereo AAC rendered as "UND AAC Stereo" while every other surface
     * correctly said "English · DTS · 5.1".
     */
    fun audioSummaryForOrdinal(
        version: FileVersion?,
        ordinal: Int?,
        tracks: List<AudioTrack>? = null,
    ): String? {
        if (ordinal == null) return null
        val rows = tracks?.takeIf { it.isNotEmpty() } ?: version?.audioTracks ?: return null
        val track = rows.getOrNull(ordinal) ?: return null
        return audioSummary(track, ordinal)
    }

    /**
     * Picker-row label. Keeps a meaningful title and the Default marker, which
     * [audioSummaryForOrdinal] drops: two English AAC stereo tracks named "Main"
     * and "Director Commentary" summarise identically, and the title is often
     * the only thing that separates them.
     */
    fun audioChoiceLabelForOrdinal(tracks: List<AudioTrack>, ordinal: Int): String? {
        val track = tracks.getOrNull(ordinal) ?: return null
        val summary = audioSummary(track, ordinal)
        // Keyed off the RENDERED summary, not audioTitle: an untitled-language
        // track falls back to its title for audioTitle, which then rejected the
        // qualifier and dropped the only thing naming it ("Director Commentary"
        // with no language rendered as bare "AAC · Stereo").
        val qualifier = usefulAudioTitle(track.title)?.takeIf { !summary.contains(it) }
        return buildString {
            append(summary)
            if (qualifier != null) append(" · ").append(qualifier)
            if (track.isDefault) append(" · Default")
        }
    }

    /**
     * The catalog ordinal playback will actually use: the plan's selection when
     * it is in range, else the server's effective ordinal, else the default
     * flag, else the first row. Without this the HUD shows nothing checked
     * whenever the plan carries no audio index.
     */
    fun effectiveAudioOrdinal(tracks: List<AudioTrack>, planOrdinal: Int?, version: FileVersion? = null): Int? {
        if (tracks.isEmpty()) return null
        planOrdinal?.takeIf { it in tracks.indices }?.let { return it }
        version?.effectiveAudioTrackIndex?.takeIf { it in tracks.indices }?.let { return it }
        return tracks.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
    }

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

    fun subtitleOptions(
        version: FileVersion?,
        selectedSubtitleTrackIndex: Int?,
        preferredLanguage: String? = null,
    ): List<TvSubtitleOption> {
        val tracks = version?.subtitleTracks ?: return emptyList()
        // Selection travels in the server's COMBINED space (externals first,
        // embedded after — the identity subtitle_track_index resolves and
        // mounted subtitle_urls carry). Catalog positions and raw catalog
        // `index` values are different spaces; sending either selects the
        // wrong track on files with external subtitles.
        val combined = combinedSubtitleSelectionIndexes(tracks)
        return orderedSubtitleCatalogOrdinals(tracks, preferredLanguage).map { ordinal ->
            val track = tracks[ordinal]
            TvSubtitleOption(
                selectionIndex = combined[ordinal],
                title = subtitleTitle(track, ordinal),
                detail = subtitleDetail(track),
                isSelected = selectedSubtitleTrackIndex == combined[ordinal],
                stableId = "sub-$ordinal",
            )
        }
    }

    /**
     * Display-only ordering shared with tvOS: preferred language first, then
     * language groups alphabetically; within a language prefer text formats,
     * then full-dialogue, forced, and SDH variants. Original catalog ordinals
     * travel with every row so combined-space selection identity is unchanged.
     */
    internal fun orderedSubtitleCatalogOrdinals(
        tracks: List<SubtitleTrack>,
        preferredLanguage: String?,
    ): List<Int> {
        if (tracks.size < 2) return tracks.indices.toList()
        val preferredKey = canonicalSubtitleLanguageKey(preferredLanguage)
        val namedKeys = tracks
            .mapNotNull { canonicalSubtitleLanguageKey(it.language) }
            .distinct()
            .sortedWith(Comparator<String> { a, b ->
                when {
                    a == preferredKey && b != preferredKey -> -1
                    b == preferredKey && a != preferredKey -> 1
                    else -> {
                        val byName = languageDisplayName(a).orEmpty().compareTo(
                            languageDisplayName(b).orEmpty(),
                            ignoreCase = true,
                        )
                        if (byName != 0) byName else a.compareTo(b)
                    }
                }
            })
        val groupRanks = namedKeys.withIndex().associate { it.value to it.index }
        val unknownGroupRank = namedKeys.size
        return tracks.indices.sortedWith(
            compareBy<Int> {
                canonicalSubtitleLanguageKey(tracks[it].language)
                    ?.let(groupRanks::get) ?: unknownGroupRank
            }.thenBy { subtitleFormatRank(tracks[it].codec) }
                .thenBy {
                    when {
                        isHearingImpairedSubtitle(tracks[it]) -> 2
                        tracks[it].forced -> 1
                        else -> 0
                    }
                }
                .thenBy { if (tracks[it].isDefault) 0 else 1 }
                .thenBy { it },
        )
    }

    private fun subtitleFormatRank(codec: String?): Int {
        val value = codec?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return 7
        return when {
            value == "srt" || value.contains("subrip") -> 0
            value.contains("ass") -> 1
            value.contains("ssa") -> 2
            value == "vtt" || value.contains("webvtt") -> 3
            value.contains("mov_text") || value.contains("movtext") || value.contains("tx3g") -> 4
            value.contains("pgs") || value.contains("hdmv") -> 5
            value.contains("dvd") || value.contains("vobsub") || value.contains("dvb") -> 6
            else -> 7
        }
    }

    private fun canonicalSubtitleLanguageKey(value: String?): String? {
        val primary = value
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace('_', '-')
            ?.substringBefore('-')
            ?.takeIf { it.isNotBlank() && it != "und" }
            ?: return null
        return languageCodeAliases[primary] ?: primary
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
                    automaticTrackLabel(subtitlePillSummary(resolved.first, resolved.second))
                } else {
                    automaticTrackLabel(null)
                }
            }
            if (tracks != null && tracks.size == 1) return subtitlePillSummary(tracks[0], 0)
            return "Auto"
        }
        if (selectedSubtitleTrackIndex == -1) return "Off"
        // An explicit positive selection that doesn't resolve in this version's
        // track list: a subtitle IS requested, so "On" (not "Auto"/"Off").
        if (tracks == null) return "On"
        // The selection is a combined-space index (see subtitleOptions); map it
        // back to the catalog position for display.
        val ordinal = combinedSubtitleSelectionIndexes(tracks).indexOf(selectedSubtitleTrackIndex)
        val track = tracks.getOrNull(ordinal) ?: return "On"
        return subtitlePillSummary(track, ordinal)
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
        val ordinal = autoResolvedSubtitleOrdinal(tracks, context) ?: return null
        return tracks[ordinal] to ordinal
    }

    /**
     * The catalog ordinal Auto resolves to, or null for "no subtitle".
     *
     * Ranking lives in the shared [resolveAutoSubtitle]; this only translates
     * between catalog ordinals (what the pill renders) and the combined
     * selection space the resolver addresses.
     */
    internal fun autoResolvedSubtitleOrdinal(
        tracks: List<SubtitleTrack>,
        context: SubtitleAutoContext,
    ): Int? {
        if (tracks.isEmpty()) return null
        val selected = resolveAutoSubtitle(
            candidates = catalogAutoSubtitleCandidates(tracks),
            context = AutoSubtitleContext(
                preferredLanguage = context.preferredLanguage,
                mode = context.mode,
                showForced = context.showForced,
                audioLanguage = context.audioLanguage,
            ),
        ).selectedCandidate() ?: return null
        return combinedSubtitleSelectionIndexes(tracks)
            .indexOf(selected.selectionIndex)
            .takeIf { it >= 0 }
    }

    /**
     * The subtitle decision the selector row is DISPLAYING, in combined
     * selection space, ready to hand to playback.
     *
     * Auto used to hand over nothing at all, so the start request carried no
     * `subtitle_track_index`, the initial plan mounted no sidecar, and the
     * player re-derived Auto over Media3's mounted tracks — where an external
     * SRT does not exist yet. The row's own answer travels instead, tagged
     * [TvSubtitleLaunchSelection.autoResolved] so the player can apply it
     * without recording it as a choice the viewer made.
     *
     * Null only when the row itself cannot say (no auto context): the player
     * then falls back to its own resolution, as before.
     */
    fun subtitleLaunchSelection(
        version: FileVersion?,
        selectedSubtitleTrackIndex: Int?,
        autoContext: SubtitleAutoContext?,
    ): TvSubtitleLaunchSelection? {
        if (selectedSubtitleTrackIndex != null) {
            return TvSubtitleLaunchSelection(selectedSubtitleTrackIndex, autoResolved = false)
        }
        val context = autoContext ?: return null
        val tracks = version?.subtitleTracks.orEmpty()
        val ordinal = autoResolvedSubtitleOrdinal(tracks, context)
            // "Auto - None" is a decision too: start explicitly Off rather than
            // letting the player re-derive something the row never showed.
            ?: return TvSubtitleLaunchSelection(-1, autoResolved = true)
        return TvSubtitleLaunchSelection(
            selectionIndex = combinedSubtitleSelectionIndexes(tracks)[ordinal],
            autoResolved = true,
        )
    }

    /** Title-based CC/SDH detection shared with player identity and auto-selection. */
    private fun isHearingImpairedSubtitle(track: SubtitleTrack): Boolean {
        return subtitleLabelIndicatesHearingImpaired(track.title)
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
        // Catalog titles for external tracks are often the complete media/show
        // release filename. Keep that identity internally; never render it in
        // either subtitle selector.
        if (title.length > 28 || '[' in title || SUBTITLE_FILENAME_SUFFIXES.any(lowered::endsWith)) {
            return null
        }
        if (lowered == "forced" || lowered in listOf("sdh", "cc", "hearing impaired")) {
            return null
        }
        return displayTitle(title)
    }

    private val SUBTITLE_FILENAME_SUFFIXES = listOf(
        ".srt", ".ass", ".ssa", ".vtt", ".sub", ".sup", ".idx",
    )

    /** Mirrors tvOS `isForced`: flag OR a "forced" mention in the title. */
    private fun isForcedSubtitle(track: SubtitleTrack): Boolean =
        track.forced || track.title?.contains("forced", ignoreCase = true) == true

    /** Mirrors tvOS `containsAccessibilityMarker` — keeps the pill from
     *  doubling up markers a custom title already carries. */
    private fun containsAccessibilityMarker(value: String): Boolean {
        return subtitleLabelIndicatesHearingImpaired(value)
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
