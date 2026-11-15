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
 * Whether the row ends with an "Explore all" card. Every non-featured row with
 * cards gets one, not only rows whose total exceeds what they show: Home drops
 * titles another row already shows, so a row can look complete while its full
 * page holds more.
 */
val ResolvedSection.offersExploreAll: Boolean
    get() = !featured && items.isNotEmpty()

/**
 * The `item_limit` a section list request may carry, given the server's
 * advertised `section_item_limit_max`. Null sends nothing: older servers
 * answer an unknown query parameter with 422 `unknown_parameter`.
 */
fun sectionItemLimitParam(advertisedMax: Int?): Int? =
    advertisedMax?.takeIf { it >= 1 }?.let { minOf(SECTION_ROW_MAX_ITEMS, it) }
