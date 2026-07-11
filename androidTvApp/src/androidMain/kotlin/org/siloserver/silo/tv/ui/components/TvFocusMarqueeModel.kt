package org.siloserver.silo.tv.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.section.SectionItem
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Display payload for the focus marquee — built from section-item models only
 * (no per-item detail fetch). Mirrors tvOS `TVMarqueeContent`: whatever
 * synopsis/badge/runtime fields the section payload already carries render, the
 * rest are omitted.
 *
 * @property id crossfade identity; includes the source row so the same item
 *  focused from a different row still reads as a swap.
 */
data class TvMarqueeContent(
    val id: String,
    val title: String,
    val logoUrl: String?,
    /** Codec/HDR + content-rating chips (`4K`, `DOLBY VISION`, `ATMOS`). */
    val badges: List<String>,
    /** Dot-joined meta tokens after the badges: year · genre · runtime, or
     *  `S2 E7 · episode title · 45 min · 23m left` for episodes. */
    val metaParts: List<String>,
    val synopsis: String?,
    /** A quieter detail line: cast / air-date when carried by the payload. */
    val detailLine: String?,
    val backdropUrl: String?,
    val backdropThumbhash: String?,
    val posterUrl: String?,
    val posterThumbhash: String?,
    val isEpisode: Boolean,
    /** The source item, retained so the ambient tint extracts its palette from
     *  the same (debounced) card the marquee + backdrop show. */
    val source: SectionItem,
) {
    /** Backdrop art for the root hero. Like tvOS, the section artwork is shown
     *  immediately; an episode can later upgrade to its higher-resolution series
     *  backdrop without holding the initial marquee on an empty wash. */
    val heroBackdropUrl: String? get() = if (isEpisode) backdropUrl else (backdropUrl ?: posterUrl)
    val heroBackdropThumbhash: String? get() =
        if (isEpisode) backdropThumbhash else (backdropThumbhash ?: posterThumbhash)

    /** Stable per-item key for the §9 enrichment cache + stale-fetch guard. */
    val contentId: String get() = source.contentId

    /**
     * Fold a landed [TvMarqueeEnrichment] into this content (tvOS
     * `TVFocusMarqueeModel.backdropURL` + `detailLine`). The aired/cast line
     * applies to every item; the backdrop upgrade applies to episodes only —
     * non-episodes keep their own section backdrop. The crossfade [id] is
     * preserved, so the swap reads as an in-place refresh, not a new block.
     */
    fun withEnrichment(enrichment: TvMarqueeEnrichment): TvMarqueeContent {
        val upgradeBackdrop = isEpisode && !enrichment.backdropUrl.isNullOrBlank()
        return copy(
            detailLine = enrichment.detailLine ?: detailLine,
            backdropUrl = if (upgradeBackdrop) enrichment.backdropUrl else backdropUrl,
            backdropThumbhash = if (upgradeBackdrop) {
                enrichment.backdropThumbhash ?: backdropThumbhash
            } else {
                backdropThumbhash
            },
        )
    }

    companion object {
        fun from(item: SectionItem, rowTitle: String): TvMarqueeContent {
            val isEpisode = item.type.equals("episode", ignoreCase = true)

            val meta = mutableListOf<String>()
            if (isEpisode) {
                episodeToken(item.seasonNumber, item.episodeNumber)?.let(meta::add)
                if (item.title.isNotBlank()) meta.add(item.title)
                lengthText(item.durationSeconds)?.let(meta::add)
                timeLeftText(item.positionSeconds, item.durationSeconds)?.let(meta::add)
            } else {
                if (item.year > 0) meta.add(item.year.toString())
                item.genres.firstOrNull { it.isNotBlank() }?.let(meta::add)
                lengthText(item.durationSeconds)?.let(meta::add)
                item.ratingImdb?.let { meta.add(formatRating(it)) }
            }

            // Codec/HDR + content-rating chips (`4K · DOLBY VISION · ATMOS ·
            // TV-MA`) derived from the section payload's overlay summary, then
            // the content rating — mirrors tvOS `TVFocusMarquee.badges(from:)`.
            val badges = qualityBadges(item.overlaySummary).toMutableList()
            item.contentRating?.takeIf { it.isNotBlank() }?.let { badges.add(it.uppercase()) }

            val sectionBackdropUrl = item.backdropUrl?.takeIf { it.isNotBlank() }
            val sectionPosterUrl = item.posterUrl?.takeIf { it.isNotBlank() }
            return TvMarqueeContent(
                id = "$rowTitle#${item.contentId}",
                title = if (isEpisode) (item.seriesTitle ?: item.title) else item.title,
                logoUrl = item.logoUrl?.takeIf { it.isNotBlank() },
                badges = badges,
                metaParts = meta,
                synopsis = item.overview?.takeIf { it.isNotBlank() },
                detailLine = null,
                // Match tvOS: a section backdrop (or poster fallback) is always
                // available for the first rested frame. Episode enrichment may
                // replace it with series art later.
                backdropUrl = sectionBackdropUrl ?: sectionPosterUrl,
                backdropThumbhash = if (sectionBackdropUrl != null) {
                    item.backdropThumbhash
                } else {
                    item.posterThumbhash
                },
                posterUrl = sectionPosterUrl,
                posterThumbhash = item.posterThumbhash,
                isEpisode = isEpisode,
                source = item,
            )
        }

        /**
         * Headline quality trio — resolution, dynamic range, audio — uppercased
         * to the Skyline badge style, from the section payload's overlay summary.
         * Mirrors tvOS `TVFocusMarquee.badges(from:)`.
         */
        private fun qualityBadges(summary: org.siloserver.silo.model.catalog.OverlaySummary?): List<String> {
            if (summary == null) return emptyList()
            val badges = mutableListOf<String>()
            prettyResolution(summary.resolution)?.let(badges::add)
            summary.hdr?.takeIf { it.isNotBlank() }?.let { hdr ->
                val lower = hdr.lowercase()
                badges.add(if (lower.contains("dv") || lower.contains("dolby")) "HDR" else hdr.uppercase())
            }
            summary.audio?.takeIf { it.isNotBlank() }?.let { audio ->
                badges.add(audio.replace("atmos", "", ignoreCase = true).trim().ifBlank { "AUDIO" }.uppercase())
            }
            return badges
        }

        private fun prettyResolution(value: String?): String? {
            val v = value?.takeIf { it.isNotBlank() } ?: return null
            return when (v.lowercase()) {
                "2160p", "4k", "uhd" -> "4K"
                "4320p", "8k" -> "8K"
                else -> v.uppercase()
            }
        }

        private fun episodeToken(season: Int?, episode: Int?): String? = when {
            season != null && episode != null -> "S$season E$episode"
            season != null -> "Season $season"
            episode != null -> "Episode $episode"
            else -> null
        }

        private fun timeLeftText(position: Double?, duration: Double?): String? {
            if (position == null || duration == null || duration <= 0) return null
            if (position <= 60 || position / duration >= 0.95) return null
            val remaining = (((duration - position) / 60.0)).let { kotlin.math.ceil(it).toInt() }.coerceAtLeast(1)
            return "${remaining}m left"
        }

        private fun lengthText(durationSeconds: Double?): String? {
            if (durationSeconds == null || durationSeconds <= 0) return null
            val minutes = (durationSeconds / 60.0).roundToInt()
            if (minutes <= 0) return null
            return if (minutes >= 60) {
                val hours = minutes / 60
                val rest = minutes % 60
                if (rest == 0) "${hours}h" else "${hours}h ${rest}m"
            } else {
                "$minutes min"
            }
        }

        private fun formatRating(rating: Double): String {
            val rounded = (rating * 10).roundToInt() / 10.0
            return rounded.toString()
        }
    }
}

/**
 * §9 detail backfill for the marquee — the fields section payloads don't carry
 * (air date, cast) plus the detail-level backdrop. Faithful port of tvOS
 * `TVMarqueeEnrichment`: built from an item-detail fetch that never blocks the
 * marquee. For episodes the detail backdrop is the SERIES backdrop (far higher
 * res than the episode still the section payload carries), so the hero upgrades
 * to it once enrichment lands.
 *
 * @property detailLine `Aired Mar 12, 2026 · Pedro Pascal, Bella Ramsey, Anna Torv`
 *  — the abbreviated air date (omitted if unparseable/absent) and up to 3 cast
 *  names sorted by [org.siloserver.silo.model.catalog.CastMember.order], joined
 *  by " · ". `null` when both are empty.
 */
data class TvMarqueeEnrichment(
    val detailLine: String?,
    val backdropUrl: String?,
    val backdropThumbhash: String?,
) {
    companion object {
        fun from(detail: ItemDetail): TvMarqueeEnrichment {
            val parts = mutableListOf<String>()
            airDateText(detail.airDate)?.let { parts.add("Aired $it") }
            val cast = detail.cast
                .sortedBy { it.order }
                .take(3)
                .map { it.name }
                .filter { it.isNotBlank() }
            if (cast.isNotEmpty()) parts.add(cast.joinToString(", "))
            return TvMarqueeEnrichment(
                detailLine = if (parts.isEmpty()) null else parts.joinToString(" · "),
                backdropUrl = detail.backdropUrl?.takeIf { it.isNotBlank() },
                backdropThumbhash = detail.backdropThumbhash,
            )
        }

        /**
         * Abbreviated "MMM d, yyyy" from the server's `yyyy-MM-dd` (or full ISO
         * timestamp). Mirrors tvOS `.abbreviated` date formatting; returns `null`
         * if the string is absent or unparseable, so the "Aired" part is omitted.
         */
        private fun airDateText(raw: String?): String? {
            val value = raw?.takeIf { it.isNotBlank() } ?: return null
            // The payload is usually `yyyy-MM-dd`; full ISO timestamps carry the
            // date as their first 10 chars, so truncate to the date component.
            val datePart = value.take(10)
            val parsed = runCatching {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
                    .parse(datePart)
            }.getOrNull() ?: return null
            return SimpleDateFormat("MMM d, yyyy", Locale.US).format(parsed)
        }
    }
}

/**
 * Focused-card → marquee state for the Skyline Home. Row cards report focus
 * immediately via [preview]; the displayed [content] (and therefore the backdrop
 * + tint) swaps in the same focus transaction so the fade begins with the D-pad
 * move. Focus is reported only on gain — rows never report loss — so while focus
 * is in chrome the last previewed item is retained.
 */
class TvFocusMarqueeState internal constructor() {
    var content: TvMarqueeContent? by mutableStateOf(null)
        private set

    /** Late item-detail fields are deliberately separate from [content], just
     *  like tvOS. Landing enrichment must not make Compose crossfade and
     *  re-layout the entire hero as if focus moved to another card. */
    var enrichment: TvMarqueeEnrichment? by mutableStateOf(null)
        private set

    /** Base content with only its effective artwork upgraded for the backdrop. */
    val backdropContent: TvMarqueeContent?
        get() = content?.let { base -> enrichment?.let(base::withEnrichment) ?: base }

    internal var candidate: TvMarqueeContent? by mutableStateOf(null)

    /** Per-contentId enrichment cache (tvOS `enrichmentCache`) so scrubbing
     *  back over a row never refetches item detail. Persists for the page. */
    private val enrichmentCache = mutableMapOf<String, TvMarqueeEnrichment>()
    private val enrichmentRequests = mutableSetOf<String>()

    /** Report card focus. The displayed content swaps on the next composition turn. */
    fun preview(item: SectionItem, rowTitle: String) {
        val next = TvMarqueeContent.from(item, rowTitle)
        // Focus is back on the already-displayed card: cancel any pending swap
        // so a brief A→B→A scrub within the debounce window can't commit a
        // stale B after focus has returned to A.
        if (next == content) {
            candidate = null
            return
        }
        candidate = next
    }

    /**
     * Seed the same coordinated candidate transaction used by real focus. This
     * is only for page entry: once focus has produced displayed or pending
     * content, the seed is ignored so it never fights real navigation.
     */
    fun seedInitialPreview(item: SectionItem, rowTitle: String) {
        if (content != null || candidate != null) return
        candidate = TvMarqueeContent.from(item, rowTitle)
    }

    internal fun commit(value: TvMarqueeContent?) {
        // Apply cached enrichment in the same snapshot as the base-content swap,
        // so revisiting an item presents one complete frame without a refetch.
        enrichment = value?.contentId?.let(enrichmentCache::get)
        content = value
    }

    /** True if detail for [contentId] is already cached (skip the fetch). */
    internal fun cachedEnrichment(contentId: String): TvMarqueeEnrichment? =
        enrichmentCache[contentId]

    /** Claim a detail request across both focus-driven loading and proactive
     *  row warming. Only one coroutine may fetch an item at a time. */
    internal fun beginEnrichmentRequest(contentId: String): Boolean {
        if (contentId in enrichmentCache || contentId in enrichmentRequests) return false
        enrichmentRequests += contentId
        return true
    }

    internal fun finishEnrichmentRequest(contentId: String) {
        enrichmentRequests -= contentId
    }

    internal fun isEnrichmentRequestInFlight(contentId: String): Boolean =
        contentId in enrichmentRequests

    /** Cache freshly-fetched enrichment. It is intentionally not published into
     *  an already-rendered hero: the next coordinated commit consumes it so an
     *  aired/cast row never appears as a late second-stage UI update. */
    internal fun applyEnrichment(contentId: String, enrichment: TvMarqueeEnrichment) {
        enrichmentCache[contentId] = enrichment
    }
}

/** Tuned Skyline timing: 0.5 s with the tvOS `.easeInOut` curve. */
const val TvMarqueeCrossfadeMs = 500
val TvMarqueeEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * @param fetchDetail item-detail fetcher for the §9 marquee enrichment (air
 *  date, cast, series backdrop). Cached detail joins the first rendered frame;
 *  uncached detail loads only for a future visit and never postpones or mutates
 *  the active fade. `null` disables enrichment.
 */
@Composable
fun rememberTvFocusMarqueeState(
    fetchDetail: (suspend (String) -> ItemDetail?)? = null,
): TvFocusMarqueeState {
    val state = remember { TvFocusMarqueeState() }
    LaunchedEffect(state.candidate?.id) {
        val candidate = state.candidate ?: return@LaunchedEffect
        state.commit(candidate)
    }

    // Populate cache for the next visit without changing the currently visible
    // hero. Near-viewport proactive prefetch usually wins this request; the
    // shared claim prevents duplicates when it is already in flight.
    LaunchedEffect(state.content?.contentId, fetchDetail) {
        val fetch = fetchDetail ?: return@LaunchedEffect
        val contentId = state.content?.contentId ?: return@LaunchedEffect
        if (!state.beginEnrichmentRequest(contentId)) return@LaunchedEffect
        try {
            val detail = runCatching { fetch(contentId) }.getOrNull() ?: return@LaunchedEffect
            state.applyEnrichment(contentId, TvMarqueeEnrichment.from(detail))
        } finally {
            state.finishEnrichmentRequest(contentId)
        }
    }
    return state
}
