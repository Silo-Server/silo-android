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
    /** [surface]'s widths with cards scaled by [cardScale], the card-size preference's poster scale. */
    fun widths(surface: ArtworkSurface, cardScale: Float): ArtworkWidths

    /** The active card-size preference's poster scale. */
    fun cardScale(): Float = 1f
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
        val widths = layout.widths(surface, layout.cardScale())
        return widths.only(slots).takeUnless { it.isEmpty }?.copy(serverId = probe.serverId)
    }

    /**
     * Whether an answer read with [sent] widths may be cached under this
     * layout's fingerprint. Not when it went without widths only because the
     * capability check hadn't answered: the server may size artwork per
     * screen, and this answer isn't sized for any.
     */
    fun mayCache(sent: ArtworkWidths?): Boolean =
        sent != null || layout == null || capabilities == null || capabilities.hasSettled

    /**
     * The cache fingerprint of [surface] on this device: its widths at the
     * standard card size, whether or not the server takes them. It doesn't
     * change when the capability probe or the card-size preference hasn't
     * loaded yet (offline, for one). Capture it before a read and cache the
     * result under it, so a fold during the read can't file one layout's
     * artwork under another's key.
     */
    fun cacheFingerprint(surface: ArtworkSurface): String = layout?.widths(surface, 1f)?.key.orEmpty()

    /** [prefix] bound to [surface]'s current cache fingerprint. */
    fun cacheKey(prefix: String, surface: ArtworkSurface): String = cacheKey(prefix, cacheFingerprint(surface))

    companion object {
        /** Sends no widths; for tests and callers outside the apps. */
        val None = ArtworkSizing(null, null)

        /** [prefix] bound to a captured cache [fingerprint]. */
        fun cacheKey(prefix: String, fingerprint: String): String =
            if (fingerprint.isEmpty()) prefix else "$prefix@$fingerprint"
    }
}
