package org.siloserver.silo.model.section

/**
 * The most cards a Home or library row shows. The rest live on the row's full
 * page ("Explore all"), so the cap holds whatever the server sends.
 */
const val SECTION_ROW_MAX_ITEMS = 20

/** The row as it is drawn: at most [SECTION_ROW_MAX_ITEMS] cards. */
fun ResolvedSection.cappedForRow(): ResolvedSection =
    if (items.size <= SECTION_ROW_MAX_ITEMS) this else copy(items = items.take(SECTION_ROW_MAX_ITEMS))

/**
 * Rows built from playback state: Continue Watching, In Progress and Next Up.
 * Their cards can be removed from Home, and their `total_count` counts the
 * list itself.
 */
val PLAYBACK_SECTION_TYPES = setOf("continue_watching", "in_progress", "next_up")

/**
 * Whether the row is known to hold its whole list, so its full page would
 * repeat it. Only a playback row ([PLAYBACK_SECTION_TYPES]) can be: the server
 * counts its list directly, where it counts other rows after dropping titles
 * an earlier row already shows. The row must also report a `total_count` no
 * larger than its cards and hold fewer cards than its limit, because a row
 * filled to its limit was cut there and reports the cut size as its total.
 * A missing `total_count` decodes as 0 and proves nothing.
 */
val ResolvedSection.showsWholeList: Boolean
    get() {
        if (sectionType.lowercase() !in PLAYBACK_SECTION_TYPES) return false
        val limit = if (itemLimit > 0) minOf(SECTION_ROW_MAX_ITEMS, itemLimit) else SECTION_ROW_MAX_ITEMS
        return items.size < limit && totalCount in 1..items.size
    }

/**
 * Whether the row ends with an "Explore all" card. A non-featured row with
 * cards gets one unless it [showsWholeList], not only when its total exceeds
 * what it shows: Home drops titles another row already shows, so a row can
 * look complete while its full page holds more.
 */
val ResolvedSection.offersExploreAll: Boolean
    get() = !featured && items.isNotEmpty() && !showsWholeList

/**
 * The `item_limit` a section list request may carry, given the server's
 * advertised `section_item_limit_max`. Null sends nothing: older servers
 * answer an unknown query parameter with 422 `unknown_parameter`.
 */
fun sectionItemLimitParam(advertisedMax: Int?): Int? =
    advertisedMax?.takeIf { it >= 1 }?.let { minOf(SECTION_ROW_MAX_ITEMS, it) }
