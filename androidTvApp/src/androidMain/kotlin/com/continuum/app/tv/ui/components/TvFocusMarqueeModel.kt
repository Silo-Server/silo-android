package com.continuum.app.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.continuum.app.model.catalog.ItemDetail
import com.continuum.app.model.section.SectionItem
import kotlinx.coroutines.delay
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
    /** Backdrop art for the root hero. Episodes carry only a low-res episode
     *  still in the section payload, which is the wrong image for the cinematic
     *  hero — showing it first and then swapping to the enriched SERIES backdrop
     *  reads as a banner "switch". So for episodes the hero stays on the ambient
     *  wash (null) until [withEnrichment] folds in the series backdrop; only the
     *  correct banner ever appears. Non-episodes use their own section backdrop
     *  (poster fallback) immediately. */
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

            return TvMarqueeContent(
                id = "$rowTitle#${item.contentId}",
                title = if (isEpisode) (item.seriesTitle ?: item.title) else item.title,
                logoUrl = item.logoUrl?.takeIf { it.isNotBlank() },
                badges = badges,
                metaParts = meta,
                synopsis = item.overview?.takeIf { it.isNotBlank() },
                detailLine = null,
                // Episodes drop their low-res still here; the cinematic hero
                // waits for the enriched series backdrop (see heroBackdropUrl).
                backdropUrl = if (isEpisode) null else item.backdropUrl?.takeIf { it.isNotBlank() },
                backdropThumbhash = if (isEpisode) null else item.backdropThumbhash,
                posterUrl = item.posterUrl?.takeIf { it.isNotBlank() },
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
        private fun qualityBadges(summary: com.continuum.app.model.catalog.OverlaySummary?): List<String> {
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
 *  names sorted by [com.continuum.app.model.catalog.CastMember.order], joined
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
 * + tint) only swaps after focus has rested ~150 ms, so scrubbing across a row
 * never flashes intermediate backdrops. Focus is reported only on gain — rows
 * never report loss — so while focus is in chrome the last previewed item is
 * retained.
 */
class TvFocusMarqueeState internal constructor() {
    var content: TvMarqueeContent? by mutableStateOf(null)
        private set

    internal var candidate: TvMarqueeContent? by mutableStateOf(null)

    /** Per-contentId enrichment cache (tvOS `enrichmentCache`) so scrubbing
     *  back over a row never refetches item detail. Persists for the page. */
    private val enrichmentCache = mutableMapOf<String, TvMarqueeEnrichment>()

    /** Report card focus. The displayed content swaps after the rest debounce. */
    fun preview(item: SectionItem, rowTitle: String) {
        val next = TvMarqueeContent.from(item, rowTitle)
        // Focus is back on the already-displayed card: cancel any pending swap
        // so a brief A→B→A scrub within the debounce window can't commit a
        // stale B after focus has returned to A.
        if (next == displayedBase()) {
            candidate = null
            return
        }
        candidate = next
    }

    /**
     * Populate the passive marquee before any row card has focus. This is only
     * for page entry: once focus has produced displayed or pending content, the
     * seed is ignored so it never fights real navigation.
     */
    fun seedInitialPreview(item: SectionItem, rowTitle: String) {
        if (content != null || candidate != null) return
        commit(TvMarqueeContent.from(item, rowTitle))
    }

    /** The displayed content reduced to its un-enriched base, so a re-preview
     *  of the same card (whose payload carries no detailLine/enriched backdrop)
     *  still compares equal and is treated as a no-op. */
    private fun displayedBase(): TvMarqueeContent? {
        val current = content ?: return null
        return current.copy(
            detailLine = null,
            // Mirror `from()`: episodes carry no still here (hero waits for the
            // enriched series backdrop), so the base compares equal on re-focus.
            backdropUrl = if (current.isEpisode) null else current.source.backdropUrl?.takeIf { it.isNotBlank() },
            backdropThumbhash = if (current.isEpisode) null else current.source.backdropThumbhash,
        )
    }

    internal fun commit(value: TvMarqueeContent?) {
        // Re-apply any already-cached enrichment immediately on commit so a
        // scrub-back shows the enriched hero/detail line without a refetch.
        content = value?.let { base ->
            base.contentId.let { id -> enrichmentCache[id] }?.let(base::withEnrichment) ?: base
        }
    }

    /** True if detail for [contentId] is already cached (skip the fetch). */
    internal fun cachedEnrichment(contentId: String): TvMarqueeEnrichment? =
        enrichmentCache[contentId]

    /** Fold a freshly-fetched enrichment in: cache it, and if the displayed
     *  content is still that item, commit the enriched copy so the hero
     *  backdrop + detail line update (downstream consumers re-read [content]). */
    internal fun applyEnrichment(contentId: String, enrichment: TvMarqueeEnrichment) {
        enrichmentCache[contentId] = enrichment
        val current = content ?: return
        if (current.contentId == contentId) {
            content = current.withEnrichment(enrichment)
        }
    }
}

/** Focus-rest debounce before the marquee + backdrop swap (tvOS §4.2). */
const val TvMarqueeRestDebounceMs = 150L

/** Marquee text + backdrop crossfade duration in ms (tvOS §4.2: 240 ms). */
const val TvMarqueeCrossfadeMs = 240

/**
 * @param fetchDetail item-detail fetcher for the §9 marquee enrichment (air
 *  date, cast, series backdrop). Non-blocking: it runs only after the displayed
 *  content has rested, never delaying the marquee swap. `null` (the default)
 *  disables enrichment — the marquee falls back to the section payload only.
 */
@Composable
fun rememberTvFocusMarqueeState(
    fetchDetail: (suspend (String) -> ItemDetail?)? = null,
): TvFocusMarqueeState {
    val state = remember { TvFocusMarqueeState() }
    LaunchedEffect(state.candidate?.id) {
        val candidate = state.candidate ?: return@LaunchedEffect
        delay(TvMarqueeRestDebounceMs)
        state.commit(candidate)
    }
    // §9 detail enrichment: after a content swap rests, async-fetch item detail
    // for the displayed item, cache per contentId, and fold it in only if that
    // item is still displayed. Keyed on the committed content id so the
    // in-flight fetch is cancelled (and re-issued) when the displayed item
    // changes; a cached item never refetches (the swap re-applies it on commit).
    LaunchedEffect(state.content?.contentId, fetchDetail) {
        val fetch = fetchDetail ?: return@LaunchedEffect
        val contentId = state.content?.contentId ?: return@LaunchedEffect
        if (state.cachedEnrichment(contentId) != null) return@LaunchedEffect
        val detail = runCatching { fetch(contentId) }.getOrNull() ?: return@LaunchedEffect
        state.applyEnrichment(contentId, TvMarqueeEnrichment.from(detail))
    }
    return state
}
