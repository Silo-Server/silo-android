package org.siloserver.silo.overlays

import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText

/**
 * Single source of truth for which overlays exist. Order within the
 * list determines the default render order within each corner. These
 * entries mirror web's registry modules and iOS `OverlayRegistry` —
 * adding/removing one here MUST be mirrored on the other platforms, and
 * the [OverlayId] enum, or stored prefs from one platform will silently
 * drop on another.
 */
object OverlayRegistry {

    val all: List<OverlayDef> by lazy { tech + ratings + metadata + ribbons }

    private val byId: Map<OverlayId, OverlayDef> by lazy {
        all.associateBy { it.id }
    }

    fun def(id: OverlayId): OverlayDef? = byId[id]

    fun defs(category: OverlayCategory): List<OverlayDef> =
        all.filter { it.category == category }

    /**
     * Combined badges and the badges whose information each already shows.
     * Mirrors web's `COMBINED_BADGES`.
     */
    private val combinedBadges: Map<OverlayId, Set<OverlayId>> = mapOf(
        OverlayId.ResolutionHdrAudio to
            setOf(OverlayId.Resolution, OverlayId.Hdr, OverlayId.Audio, OverlayId.ResolutionHdr),
        OverlayId.ResolutionHdr to setOf(OverlayId.Resolution, OverlayId.Hdr),
    )

    /**
     * Whether [id] should be hidden because an enabled combined badge
     * already displays the same information. The user's stored prefs are
     * left untouched so toggling the combined badge off restores the
     * badges it hid.
     */
    fun isSuppressed(id: OverlayId, prefs: CardOverlayPrefs): Boolean =
        combinedBadges.any { (combined, covers) ->
            id in covers && prefs.items[combined]?.enabled == true
        }

    /**
     * Enabled overlays for a corner, in the user's chosen order. Any
     * overlay not listed in [CardOverlayPrefs.order] falls back to
     * registry order (preserved deterministically via a secondary index).
     *
     * Duplicate IDs in `order` — possible when the wire JSON was
     * hand-edited or written by an older client — are tolerated: the
     * first occurrence wins, later ones are dropped.
     */
    fun enabled(position: OverlayPosition, prefs: CardOverlayPrefs): List<OverlayDef> {
        val candidates = all.withIndex().filter { (_, def) ->
            val cfg = prefs.items[def.id] ?: return@filter false
            if (isSuppressed(def.id, prefs)) return@filter false
            cfg.enabled && cfg.position == position
        }
        if (prefs.order.isEmpty()) return candidates.map { it.value }

        // Keep the first index a given ID appears at, so a malformed
        // order like [a, b, a] doesn't break ranking.
        val rank: Map<String, Int> = buildMap {
            prefs.order.forEachIndexed { index, id -> putIfAbsent(id, index) }
        }
        return candidates
            .sortedWith(
                Comparator { lhs, rhs ->
                    val lhsRank = rank[lhs.value.id.raw] ?: Int.MAX_VALUE
                    val rhsRank = rank[rhs.value.id.raw] ?: Int.MAX_VALUE
                    if (lhsRank != rhsRank) lhsRank.compareTo(rhsRank)
                    // Equal ranks (typically both unranked) → fall back to
                    // registry order.
                    else lhs.index.compareTo(rhs.index)
                },
            )
            .map { it.value }
    }

    /**
     * Whether settings offer [def] (web `isOverlayOffered`): a rating badge
     * only while the server shows its source, since cards leave the score out
     * otherwise and the badge would never render.
     */
    fun isOffered(def: OverlayDef, shownRatingSources: Set<String>): Boolean =
        def.ratingSource == null || def.ratingSource in shownRatingSources

    /** Badge text that is the file's own data ("4K", "TrueHD", "PG-13"). */
    private fun String?.raw(): UiText? = this?.let(UiText::Raw)

    // MARK: - Tech helpers

    /** Web `prettyResolution` (`mediaFormat.ts`). */
    private fun prettyResolution(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        val v = value.lowercase().trim()
        if (v.isEmpty()) return null
        return when (v) {
            "2160p", "4k", "uhd" -> "4K"
            "4320p", "8k" -> "8K"
            else ->
                if (Regex("^\\d+p$").matches(v)) v
                else value.uppercase()
        }
    }

    /**
     * Web `compactHdrSuffix`: any Dolby Vision variant is "DV", HDR10+ keeps
     * its name, any other HDR variant is "HDR".
     */
    private fun compactHdrSuffix(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        if (value.contains("DV")) return "DV"
        if (value.contains("HDR10+")) return "HDR10+"
        return "HDR"
    }

    /** The value as the API sent it, or `null` when empty (web `d.x || null`). */
    private fun String?.key(): String? = this?.takeIf { it.isNotEmpty() }

    /** Raw API parts joined by a space, or `null` when none is set. */
    private fun joinedKey(vararg parts: String?): String? =
        parts.filter { !it.isNullOrEmpty() }.joinToString(" ").ifEmpty { null }

    // Mirrors web's `hdrIcon`: only exact wordmark matches get a brand
    // mark; anything else (HLG, combined strings like "HDR10+") renders
    // as plain text with no icon rather than a wrong generic mark.
    private fun hdrIcon(value: String?): OverlayIconId? {
        if (value.isNullOrEmpty()) return null
        if (value.contains("DV")) return OverlayIconId.DolbyVision
        if (value == "HDR10") return OverlayIconId.Hdr10
        if (value == "HDR") return OverlayIconId.Hdr
        return null
    }

    // The combined badge's label already carries an "HDR" suffix, so the
    // only icon worth doubling up is the Dolby Vision mark — mirrors
    // web's `resolution_hdr.getIcon`.
    private fun resolutionHdrIcon(value: String?): OverlayIconId? =
        if (value != null && value.contains("DV")) OverlayIconId.DolbyVision else null

    // Web's `audioIcon`: only a value that is exactly Atmos gets the brand
    // mark, which then stands in for the label.
    private fun audioIcon(value: String?): OverlayIconId? {
        if (value.isNullOrEmpty()) return null
        return if (value.lowercase() == "atmos") OverlayIconId.Atmos else OverlayIconId.Volume
    }

    private fun videoCodecIcon(value: String?): OverlayIconId? {
        if (value.isNullOrEmpty()) return null
        return if (value == "AV1") OverlayIconId.Av1 else OverlayIconId.Film
    }

    private val tech: List<OverlayDef> = listOf(
        OverlayDef(
            id = OverlayId.Resolution,
            category = OverlayCategory.Tech,
            label = "Resolution",
            description = "Video resolution (4K, 1080p, 720p, etc.)",
            defaultPosition = OverlayPosition.TopLeft,
            defaultEnabled = true,
            iconId = OverlayIconId.Monitor,
            iconCapable = true,
            // `prettyResolution`, not raw uppercase: web renders "4K" for a
            // `2160p` payload and the standalone badge must match it.
            getValue = { prettyResolution(it.resolution).raw() },
            getValueKey = { it.resolution.key() },
            valueKeySuggestions = listOf("2160p", "1080p", "720p", "480p"),
        ),
        OverlayDef(
            id = OverlayId.Hdr,
            category = OverlayCategory.Tech,
            label = "HDR / Dolby Vision",
            description = "Dynamic range format (HDR10, DV, HLG)",
            defaultPosition = OverlayPosition.TopLeft,
            defaultEnabled = true,
            iconCapable = true,
            getValue = { it.hdr.raw() },
            getValueKey = { it.hdr.key() },
            valueKeySuggestions = listOf("DV", "DV HDR10", "DV HDR10+", "HDR10", "HDR10+", "HLG", "HDR"),
            getIcon = { hdrIcon(it.hdr) },
        ),
        OverlayDef(
            id = OverlayId.ResolutionHdr,
            category = OverlayCategory.Tech,
            label = "Resolution + HDR",
            description = "Single badge combining resolution and dynamic range (e.g. \"4K DV\").",
            defaultPosition = OverlayPosition.TopLeft,
            defaultEnabled = false,
            iconCapable = true,
            getValue = { data ->
                val res = prettyResolution(data.resolution) ?: return@OverlayDef null
                val hdr = compactHdrSuffix(data.hdr)
                (if (hdr != null) "$res $hdr" else res).raw()
            },
            // The resolution and dynamic range as the API returns them,
            // joined by a space: "2160p DV HDR10", or "1080p" without HDR.
            getValueKey = { data -> data.resolution.key()?.let { joinedKey(it, data.hdr) } },
            getIcon = { resolutionHdrIcon(it.hdr) },
        ),
        OverlayDef(
            id = OverlayId.ResolutionHdrAudio,
            category = OverlayCategory.Tech,
            label = "Resolution + HDR + Audio",
            description = "Single badge combining resolution, dynamic range and audio codec (e.g. \"4K DV Atmos\", \"1080p TrueHD\").",
            defaultPosition = OverlayPosition.TopLeft,
            defaultEnabled = false,
            iconCapable = true,
            introducedInManifest = OVERLAY_COMBINED_AUDIO_MANIFEST_REVISION,
            // The parts the file has, so a file without a known resolution
            // still shows its audio codec while the standalone audio badge
            // is hidden. Mirrors web's `resolution_hdr_audio.getValue`.
            getValue = { data ->
                listOfNotNull(
                    prettyResolution(data.resolution),
                    compactHdrSuffix(data.hdr),
                    data.audio?.takeIf { it.isNotEmpty() },
                ).joinToString(" ").ifEmpty { null }.raw()
            },
            // "2160p DV HDR10 Atmos", or only the parts the file has.
            getValueKey = { joinedKey(it.resolution, it.hdr, it.audio) },
            getIcon = { resolutionHdrIcon(it.hdr) },
        ),
        OverlayDef(
            id = OverlayId.Audio,
            category = OverlayCategory.Tech,
            label = "Audio Codec",
            description = "Audio codec (Atmos, DTS-HD, TrueHD, etc.)",
            defaultPosition = OverlayPosition.TopLeft,
            defaultEnabled = true,
            iconCapable = true,
            getValue = { it.audio.raw() },
            getValueKey = { it.audio.key() },
            valueKeySuggestions = listOf("Atmos", "TrueHD", "DTS-HD", "DTS", "EAC3", "AC3", "AAC", "FLAC"),
            getIcon = { audioIcon(it.audio) },
        ),
        OverlayDef(
            id = OverlayId.AudioChannels,
            category = OverlayCategory.Tech,
            label = "Audio Channels",
            description = "Channel layout (Stereo, 5.1, 7.1)",
            defaultPosition = OverlayPosition.TopLeft,
            defaultEnabled = false,
            iconId = OverlayIconId.Volume,
            iconCapable = true,
            getValue = { it.audioChannels.raw() },
            getValueKey = { it.audioChannels.key() },
        ),
        OverlayDef(
            id = OverlayId.VideoCodec,
            category = OverlayCategory.Tech,
            label = "Video Codec",
            description = "Video codec (H.264, H.265, AV1)",
            defaultPosition = OverlayPosition.TopLeft,
            defaultEnabled = false,
            iconId = OverlayIconId.Film,
            iconCapable = true,
            getValue = { it.videoCodec.raw() },
            getValueKey = { it.videoCodec.key() },
            valueKeySuggestions = listOf("H.265", "H.264", "AV1", "VP9"),
            getIcon = { videoCodecIcon(it.videoCodec) },
        ),
        OverlayDef(
            id = OverlayId.Container,
            category = OverlayCategory.Tech,
            label = "Container",
            description = "File container (MKV, MP4, etc.)",
            defaultPosition = OverlayPosition.BottomLeft,
            defaultEnabled = false,
            iconCapable = false,
            getValue = { it.container.raw() },
            getValueKey = { it.container.key() },
        ),
        OverlayDef(
            id = OverlayId.AspectRatio,
            category = OverlayCategory.Tech,
            label = "Aspect Ratio",
            description = "Display aspect ratio (16:9, 2.39:1, etc.)",
            defaultPosition = OverlayPosition.BottomRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Layout,
            iconCapable = true,
            getValue = { it.aspectRatio.raw() },
            getValueKey = { it.aspectRatio.key() },
        ),
        OverlayDef(
            id = OverlayId.ReleaseType,
            category = OverlayCategory.Tech,
            label = "Release Type",
            description = "Source format (REMUX, BluRay, WEB-DL, etc.)",
            defaultPosition = OverlayPosition.BottomLeft,
            defaultEnabled = true,
            iconCapable = false,
            getValue = { it.releaseType.raw() },
            getValueKey = { it.releaseType.key() },
            valueKeySuggestions = listOf("REMUX", "BluRay", "WEB-DL", "WEBRip", "HDTV", "DVD"),
        ),
        OverlayDef(
            id = OverlayId.Edition,
            category = OverlayCategory.Tech,
            label = "Edition",
            description = "Edition label from the best available media version",
            defaultPosition = OverlayPosition.BottomLeft,
            defaultEnabled = false,
            iconCapable = false,
            getValue = { it.edition.raw() },
            getValueKey = { it.edition.key() },
        ),
        OverlayDef(
            id = OverlayId.MultiAudio,
            category = OverlayCategory.Tech,
            label = "Multi-Audio",
            description = "Shown when the file has audio in 2+ languages",
            defaultPosition = OverlayPosition.BottomRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Languages,
            iconCapable = true,
            getValue = { if (it.multiAudio == true) UiText.Key(SharedString.OverlayMultiAudio) else null },
        ),
        OverlayDef(
            id = OverlayId.MultiSub,
            category = OverlayCategory.Tech,
            label = "Subtitles Available",
            description = "Shown when the file has any subtitle track",
            defaultPosition = OverlayPosition.BottomRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Subtitles,
            iconCapable = true,
            getValue = { if (it.multiSub == true) UiText.Key(SharedString.OverlaySubtitles) else null },
        ),
    )

    // MARK: - Ratings helpers

    // A rating badge carries its source's mark in the label ("IMDb 8.5",
    // "RT 93%"), so a score is never shown without its source. Silo draws no
    // source artwork: the owners of these scores restrict their logos.
    // Mirrors web's `registry/ratings.ts`.

    /** Web `formatOutOfTen`: one decimal, halves rounded away from zero (7.35 → "7.4"). */
    internal fun formatOutOfTen(value: Double): String {
        val tenths = kotlin.math.floor(value * 10.0 + 0.5).toLong()
        val sign = if (tenths < 0) "-" else ""
        val abs = kotlin.math.abs(tenths)
        return "$sign${abs / 10}.${abs % 10}"
    }

    /** Web `formatPercent`. */
    internal fun formatPercent(value: Double): String = "${kotlin.math.floor(value + 0.5).toLong()}%"

    private fun outOfTen(value: Double?): String? = value?.let(::formatOutOfTen)

    private fun percent(value: Int?): String? = value?.let { formatPercent(it.toDouble()) }

    // "RT Audience" is translated on web (`web.item.card.rtAudience`). The
    // Android catalog has no key for it yet, so it stays English here, as on
    // Apple; mark and label must move to the catalog together.
    private const val RT_AUDIENCE_MARK = "RT Audience" // i18n-ignore

    private fun rating(
        id: OverlayId,
        label: String,
        description: String,
        mark: String,
        accent: String,
        ratingSource: String? = null,
        score: (OverlayData) -> String?,
    ) = OverlayDef(
        id = id,
        category = OverlayCategory.Ratings,
        label = label,
        description = description,
        defaultPosition = OverlayPosition.LeftEdge,
        defaultEnabled = false,
        defaultAccent = accent,
        iconCapable = false,
        ratingSource = ratingSource,
        getValue = { data -> score(data)?.let { UiText.Raw("$mark $it") } },
        sourceMark = UiText.Raw(mark),
        getScore = { data -> score(data)?.let(UiText::Raw) },
    )

    private val ratings: List<OverlayDef> = listOf(
        rating(OverlayId.RatingImdb, "IMDb Rating", "IMDb score out of 10", "IMDb", "#f5c518") { outOfTen(it.ratingImdb) },
        rating(OverlayId.RatingTmdb, "TMDB Rating", "TMDB score out of 10", "TMDB", "#01b4e4") { outOfTen(it.ratingTmdb) },
        rating(
            OverlayId.RatingRt, "RT Critics", "Rotten Tomatoes critic score", "RT", "#fa320a",
            ratingSource = "rt_critic",
        ) { percent(it.ratingRtCritic) },
        rating(
            OverlayId.RatingRtAudience, "RT Audience", "Rotten Tomatoes audience score", RT_AUDIENCE_MARK, "#fa6400",
            ratingSource = "rt_audience",
        ) { percent(it.ratingRtAudience) },
        OverlayDef(
            id = OverlayId.ContentRating,
            category = OverlayCategory.Ratings,
            label = "Age Rating",
            description = "Content rating (PG-13, TV-MA, R, etc.)",
            defaultPosition = OverlayPosition.BottomRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Shield,
            iconCapable = true,
            getValue = { it.contentRating.raw() },
            getValueKey = { it.contentRating.key() },
        ),
    )

    // MARK: - Metadata helpers

    private fun formatRuntime(minutes: Int?): UiText? {
        if (minutes == null || minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) {
            UiText.Key(SharedString.OverlayRuntimeHoursMinutes, listOf(UiText.Number(h), UiText.Number(m)))
        } else {
            UiText.Key(SharedString.OverlayRuntimeMinutes, listOf(UiText.Number(m)))
        }
    }

    private val metadata: List<OverlayDef> = listOf(
        OverlayDef(
            id = OverlayId.Year,
            category = OverlayCategory.Metadata,
            label = "Year",
            description = "Release year",
            defaultPosition = OverlayPosition.BottomLeft,
            defaultEnabled = false,
            iconCapable = false,
            getValue = { data ->
                val year = data.year
                if (year == null || year <= 0) null else UiText.Number(year)
            },
        ),
        OverlayDef(
            id = OverlayId.Runtime,
            category = OverlayCategory.Metadata,
            label = "Runtime",
            description = "Item runtime in hours and minutes",
            defaultPosition = OverlayPosition.BottomLeft,
            defaultEnabled = false,
            iconId = OverlayIconId.Clock,
            iconCapable = true,
            getValue = { formatRuntime(it.runtime) },
        ),
        OverlayDef(
            id = OverlayId.OriginalLanguage,
            category = OverlayCategory.Metadata,
            label = "Language",
            description = "Original language of the content",
            defaultPosition = OverlayPosition.BottomLeft,
            defaultEnabled = false,
            iconId = OverlayIconId.Globe,
            iconCapable = true,
            getValue = { data -> data.originalLanguage?.trim()?.takeIf { it.isNotEmpty() }?.let { UiText.LanguageName(it, uppercaseIfUnnamed = true) } },
            getValueKey = { it.originalLanguage.key() },
        ),
        OverlayDef(
            id = OverlayId.Studio,
            category = OverlayCategory.Metadata,
            label = "Studio",
            description = "Primary production studio (movies)",
            defaultPosition = OverlayPosition.BottomRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Building,
            iconCapable = true,
            getValue = { it.studio.raw() },
            getValueKey = { it.studio.key() },
        ),
        OverlayDef(
            id = OverlayId.Network,
            category = OverlayCategory.Metadata,
            label = "Network",
            description = "Primary network (series)",
            defaultPosition = OverlayPosition.BottomRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Tv,
            iconCapable = true,
            getValue = { it.network.raw() },
            getValueKey = { it.network.key() },
        ),
    )

    // MARK: - Ribbons helpers

    // Mirrors web's `formatShowStatus` — the recognized spellings and the
    // pass-through default must stay identical or the same library renders
    // different ribbons per platform.
    /**
     * Web `showStatusKey`: the status as the English key a per-value image
     * matches (Returning, Ended, Cancelled, Upcoming), or the API's own value,
     * unchanged, when it is not one of those. An empty value is no status.
     */
    internal fun showStatusKey(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        return when (value.lowercase()) {
            "returning", "returning series", "continuing", "in_production", "in production" -> "Returning"
            "ended" -> "Ended"
            "cancelled", "canceled" -> "Cancelled"
            "upcoming", "planned" -> "Upcoming"
            else -> value
        }
    }

    /** Web `formatShowStatus`: the known statuses translated, any other value as the API sent it. */
    private fun formatShowStatus(value: String?): UiText? =
        when (val key = showStatusKey(value)) {
            null -> null
            "Returning" -> UiText.Key(SharedString.OverlayReturning)
            "Ended" -> UiText.Key(SharedString.OverlayEnded)
            "Cancelled" -> UiText.Key(SharedString.OverlayCancelled)
            "Upcoming" -> UiText.Key(SharedString.OverlayUpcoming)
            else -> UiText.Raw(key)
        }

    private val ribbons: List<OverlayDef> = listOf(
        OverlayDef(
            id = OverlayId.ShowStatus,
            category = OverlayCategory.Ribbons,
            label = "Show Status",
            description = "Series lifecycle: Returning, Ended, Cancelled",
            defaultPosition = OverlayPosition.RightEdge,
            defaultEnabled = false,
            iconId = OverlayIconId.Tv,
            iconCapable = true,
            availabilityNote = "Populated by metadata plugins (TMDB/TVDB updates pending)",
            getValue = { formatShowStatus(it.showStatus) },
            getValueKey = { showStatusKey(it.showStatus) },
            valueKeySuggestions = listOf("Returning", "Ended", "Cancelled", "Upcoming"),
        ),
        OverlayDef(
            id = OverlayId.ImdbTop250,
            category = OverlayCategory.Ribbons,
            label = "IMDb Top 250",
            description = "Rank when present in the IMDb Top 250 chart",
            defaultPosition = OverlayPosition.RightEdge,
            defaultEnabled = false,
            iconId = OverlayIconId.Ribbon,
            defaultAccent = "#f5c518",
            iconCapable = true,
            availabilityNote = "Requires an IMDb Top 250 data source (planned)",
            getValue = { data ->
                val rank = data.imdbTop250 ?: return@OverlayDef null
                UiText.Raw("#$rank")
            },
        ),
        OverlayDef(
            id = OverlayId.RtCertifiedFresh,
            category = OverlayCategory.Ribbons,
            label = "RT Certified Fresh",
            description = "Shown for Rotten Tomatoes Certified Fresh titles",
            defaultPosition = OverlayPosition.RightEdge,
            defaultEnabled = false,
            iconId = OverlayIconId.Tomato,
            defaultAccent = "#fa320a",
            iconCapable = true,
            availabilityNote = "Requires Rotten Tomatoes certification data (planned)",
            getValue = { if (it.rtCertifiedFresh == true) UiText.Key(SharedString.OverlayCertifiedFresh) else null },
        ),
    )
}
