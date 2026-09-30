package org.siloserver.silo.overlays

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
     * Whether [id] should be hidden because another enabled overlay
     * already displays the same information. The combined `resolution_hdr`
     * badge subsumes the standalone `resolution` and `hdr` badges. The
     * user's stored prefs are left untouched so toggling the combined
     * badge off restores the standalones automatically.
     */
    fun isSuppressed(id: OverlayId, prefs: CardOverlayPrefs): Boolean =
        when (id) {
            OverlayId.Resolution, OverlayId.Hdr ->
                prefs.items[OverlayId.ResolutionHdr]?.enabled == true
            else -> false
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
        val rank: Map<OverlayId, Int> = buildMap {
            prefs.order.forEachIndexed { index, id -> putIfAbsent(id, index) }
        }
        return candidates
            .sortedWith(
                Comparator { lhs, rhs ->
                    val lhsRank = rank[lhs.value.id] ?: Int.MAX_VALUE
                    val rhsRank = rank[rhs.value.id] ?: Int.MAX_VALUE
                    if (lhsRank != rhsRank) lhsRank.compareTo(rhsRank)
                    // Equal ranks (typically both unranked) → fall back to
                    // registry order.
                    else lhs.index.compareTo(rhs.index)
                },
            )
            .map { it.value }
    }

    // MARK: - Tech helpers

    private fun prettyResolution(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        val v = value.lowercase()
        return when (v) {
            "2160p", "4k", "uhd" -> "4K"
            "4320p", "8k" -> "8K"
            else ->
                if (Regex("^\\d+p$").matches(v)) v
                else value.uppercase()
        }
    }

    private fun compactHdrSuffix(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        return if (value.contains("DV")) "DV" else "HDR"
    }

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

    private fun audioIcon(value: String?): OverlayIconId? {
        if (value.isNullOrEmpty()) return null
        // `contains` instead of equality so "TrueHD Atmos" still picks the
        // brand mark.
        return if (value.lowercase().contains("atmos")) OverlayIconId.Atmos else OverlayIconId.Volume
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
            getValue = { prettyResolution(it.resolution) },
        ),
        OverlayDef(
            id = OverlayId.Hdr,
            category = OverlayCategory.Tech,
            label = "HDR / Dolby Vision",
            description = "Dynamic range format (HDR10, DV, HLG)",
            defaultPosition = OverlayPosition.TopLeft,
            defaultEnabled = true,
            iconCapable = true,
            getValue = { it.hdr },
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
                if (hdr != null) "$res $hdr" else res
            },
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
            getValue = { it.audio },
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
            getValue = { it.audioChannels },
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
            getValue = { it.videoCodec },
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
            getValue = { it.container },
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
            getValue = { it.aspectRatio },
        ),
        OverlayDef(
            id = OverlayId.ReleaseType,
            category = OverlayCategory.Tech,
            label = "Release Type",
            description = "Source format (REMUX, BluRay, WEB-DL, etc.)",
            defaultPosition = OverlayPosition.BottomLeft,
            defaultEnabled = true,
            iconCapable = false,
            getValue = { it.releaseType },
        ),
        OverlayDef(
            id = OverlayId.Edition,
            category = OverlayCategory.Tech,
            label = "Edition",
            description = "Edition label from the best available media version",
            defaultPosition = OverlayPosition.BottomLeft,
            defaultEnabled = false,
            iconCapable = false,
            getValue = { it.edition },
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
            getValue = { if (it.multiAudio == true) "Multi-Audio" else null },
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
            getValue = { if (it.multiSub == true) "CC" else null },
        ),
    )

    // MARK: - Ratings helpers

    private fun formatRating(value: Double?, max: Int, suffix: String? = null): String? {
        if (value == null) return null
        if (max == 100) {
            val intValue = value.toInt()
            return if (suffix != null) "$intValue% $suffix" else "$intValue%"
        }
        val formatted = formatOneDecimal(value)
        return if (suffix != null) "$formatted $suffix" else formatted
    }

    private fun formatPercent(value: Int?, suffix: String? = null): String? {
        if (value == null) return null
        return if (suffix != null) "$value% $suffix" else "$value%"
    }

    private val ratings: List<OverlayDef> = listOf(
        OverlayDef(
            id = OverlayId.RatingImdb,
            category = OverlayCategory.Ratings,
            label = "IMDb Rating",
            description = "IMDb score out of 10",
            defaultPosition = OverlayPosition.TopRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Star,
            defaultAccent = "#f5c518",
            iconCapable = true,
            getValue = { formatRating(it.ratingImdb, max = 10) },
        ),
        OverlayDef(
            id = OverlayId.RatingTmdb,
            category = OverlayCategory.Ratings,
            label = "TMDB Rating",
            description = "TMDB score out of 10",
            defaultPosition = OverlayPosition.TopRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Star,
            defaultAccent = "#01b4e4",
            iconCapable = true,
            getValue = { formatRating(it.ratingTmdb, max = 10) },
        ),
        OverlayDef(
            id = OverlayId.RatingRt,
            category = OverlayCategory.Ratings,
            label = "RT Critics",
            description = "Rotten Tomatoes critic score",
            defaultPosition = OverlayPosition.TopRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Tomato,
            defaultAccent = "#fa320a",
            iconCapable = true,
            getValue = { formatPercent(it.ratingRtCritic) },
        ),
        OverlayDef(
            id = OverlayId.RatingRtAudience,
            category = OverlayCategory.Ratings,
            label = "RT Audience",
            description = "Rotten Tomatoes audience score",
            defaultPosition = OverlayPosition.TopRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Tomato,
            defaultAccent = "#fa6400",
            iconCapable = true,
            getValue = { formatPercent(it.ratingRtAudience) },
        ),
        OverlayDef(
            id = OverlayId.ContentRating,
            category = OverlayCategory.Ratings,
            label = "Age Rating",
            description = "Content rating (PG-13, TV-MA, R, etc.)",
            defaultPosition = OverlayPosition.BottomRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Shield,
            iconCapable = true,
            getValue = { it.contentRating },
        ),
    )

    // MARK: - Metadata helpers

    private fun formatRuntime(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
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
                if (year == null || year <= 0) null else year.toString()
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
            getValue = { data -> data.originalLanguage?.let { overlayLanguageName(it) } },
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
            getValue = { it.studio },
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
            getValue = { it.network },
        ),
    )

    // MARK: - Ribbons helpers

    // Mirrors web's `formatShowStatus` — the recognized spellings and the
    // pass-through default must stay identical or the same library renders
    // different ribbons per platform.
    private fun formatShowStatus(value: String?): String? {
        val normalized = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return when (normalized.lowercase()) {
            "returning", "returning series", "continuing", "in_production", "in production" ->
                "Returning"
            "ended" -> "Ended"
            "cancelled", "canceled" -> "Cancelled"
            "upcoming", "planned" -> "Upcoming"
            else -> normalized
        }
    }

    private val ribbons: List<OverlayDef> = listOf(
        OverlayDef(
            id = OverlayId.ShowStatus,
            category = OverlayCategory.Ribbons,
            label = "Show Status",
            description = "Series lifecycle: Returning, Ended, Cancelled",
            defaultPosition = OverlayPosition.TopRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Tv,
            iconCapable = true,
            availabilityNote = "Populated by metadata plugins (TMDB/TVDB updates pending)",
            getValue = { formatShowStatus(it.showStatus) },
        ),
        OverlayDef(
            id = OverlayId.ImdbTop250,
            category = OverlayCategory.Ribbons,
            label = "IMDb Top 250",
            description = "Rank when present in the IMDb Top 250 chart",
            defaultPosition = OverlayPosition.TopRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Ribbon,
            defaultAccent = "#f5c518",
            iconCapable = true,
            availabilityNote = "Requires an IMDb Top 250 data source (planned)",
            getValue = { data ->
                val rank = data.imdbTop250 ?: return@OverlayDef null
                "#$rank"
            },
        ),
        OverlayDef(
            id = OverlayId.RtCertifiedFresh,
            category = OverlayCategory.Ribbons,
            label = "RT Certified Fresh",
            description = "Shown for Rotten Tomatoes Certified Fresh titles",
            defaultPosition = OverlayPosition.TopRight,
            defaultEnabled = false,
            iconId = OverlayIconId.Tomato,
            defaultAccent = "#fa320a",
            iconCapable = true,
            availabilityNote = "Requires Rotten Tomatoes certification data (planned)",
            getValue = { if (it.rtCertifiedFresh == true) "Certified Fresh" else null },
        ),
    )

    /**
     * Format a double with exactly one decimal place, matching Apple's
     * `String(format: "%.1f")`. Avoids platform locale dependence (always
     * uses a `.` decimal separator) so the wire/label format is stable.
     */
    private fun formatOneDecimal(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        val whole = rounded.toLong()
        val frac = kotlin.math.round((rounded - whole) * 10.0).toLong().let {
            // Guard against -0 / rounding to 10.
            if (it < 0) -it else it
        }
        return if (frac >= 10) "${whole + 1}.0" else "$whole.$frac"
    }
}
