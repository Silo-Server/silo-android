package org.siloserver.silo.tv.ui.screens.settings

import org.siloserver.silo.model.settings.PrimaryMenuItem
import org.siloserver.silo.model.settings.UiCustomizationCodec

/**
 * Roots the Android TV menu editor can currently render and focus.
 *
 * [visibleLibraryIds] are the ids of the libraries this TV can actually show
 * (already `visibleOnTv()`-filtered by the caller). A family=tv document is
 * shared with clients that do expose ebooks/reading, so a library pin authored
 * elsewhere can name a library Android TV must never surface — filtering here
 * keeps such a pin out of the editor exactly as `visibleTvRoots` keeps it out
 * of the bar. Deliberately has no default: every call site must state its set,
 * so a future caller cannot silently drop every library pin.
 */
internal fun visibleTvMenuItems(
    items: List<PrimaryMenuItem>,
    visibleLibraryIds: Set<Int>,
): List<PrimaryMenuItem> = items.filter { item ->
    when (item) {
        is PrimaryMenuItem.Builtin -> true
        is PrimaryMenuItem.Library -> item.libraryId in visibleLibraryIds
        else -> false
    }
}

/**
 * Moves one editor-visible root and weaves that projection through the complete
 * synced document. Unsupported section/collection roots keep their exact slots
 * and relative order, so an Android TV reorder cannot silently move or discard
 * destinations authored by another TV-family client.
 *
 * Returns `null` for an unknown item, a zero offset, or a visible boundary.
 */
internal fun moveVisibleTvMenuItem(
    items: List<PrimaryMenuItem>,
    identity: String,
    offset: Int,
    visibleLibraryIds: Set<Int>,
): List<PrimaryMenuItem>? {
    if (offset == 0) return null
    val visible = visibleTvMenuItems(items, visibleLibraryIds)
    val from = visible.indexOfFirst { UiCustomizationCodec.identity(it) == identity }
    if (from < 0) return null
    val to = from + offset
    if (to !in visible.indices) return null

    val reordered = visible.toMutableList()
    val moved = reordered.removeAt(from)
    reordered.add(to, moved)

    val visibleIdentities = visible
        .mapTo(mutableSetOf(), UiCustomizationCodec::identity)
    val replacements = reordered.iterator()
    return buildList(items.size) {
        items.forEach { item ->
            if (UiCustomizationCodec.identity(item) in visibleIdentities) {
                add(replacements.next())
            } else {
                add(item)
            }
        }
    }
}
