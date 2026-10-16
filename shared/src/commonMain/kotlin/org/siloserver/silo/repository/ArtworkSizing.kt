package org.siloserver.silo.repository

import org.siloserver.silo.network.ArtworkWidths

/** The reads whose artwork the apps size per slot. */
enum class ArtworkSurface {
    /** Home sections and layout, and a library's section rows. */
    HOME,

    /** For You (`/recommendations/discover`). */
    DISCOVER,

    /** Item detail: the hero backdrop, title logo and cast photos. */
    DETAIL,

    /** A season's episodes on its series' page. */
    EPISODES,

    /** A season's episodes on a season's or an episode's own page. */
    SEASON_EPISODES,

    /** The detail page's similar rail. */
    SIMILAR,

    /** Catalog grids: library browse, favorites, watchlist, filmography and collections. */
    BROWSE,
}

/** How wide each app draws each surface's artwork, in device pixels. */
fun interface ArtworkLayout {
    fun widths(surface: ArtworkSurface): ArtworkWidths
}

/**
 * Per-slot artwork widths for each read, sent only to a server that
 * advertises them; requests to an older server stay as they were.
 */
class ArtworkSizing(
    private val layout: ArtworkLayout?,
    private val capabilities: ImageCapabilitiesSession?,
) {
    /**
     * The widths to send with [surface]'s read, or null to send none. They are
     * bound to the server that advertised them.
     */
    suspend fun request(surface: ArtworkSurface): ArtworkWidths? {
        val layout = layout ?: return null
        val probe = capabilities?.settled() ?: return null
        val slots = probe.capabilities?.acceptedWidthSlots.orEmpty()
        return layout.widths(surface).only(slots).takeUnless { it.isEmpty }?.copy(serverId = probe.serverId)
    }

    /**
     * Binds a cached read of [surface] to the widths this device draws it at,
     * whether or not the server takes them, so the key does not change when
     * the capability probe has not answered yet (offline, for one).
     */
    fun cacheKey(prefix: String, surface: ArtworkSurface): String {
        val widths = layout?.widths(surface)?.key.orEmpty()
        return if (widths.isEmpty()) prefix else "$prefix@$widths"
    }

    companion object {
        /** Sends no widths; for tests and callers outside the apps. */
        val None = ArtworkSizing(null, null)
    }
}
