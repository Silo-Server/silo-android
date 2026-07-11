package org.siloserver.silo.tv.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A section ("pill") offered within a library type's cascade flyout (§3 /
 * §5.3). 1:1 with tvOS `TVLibraryPill` (`TVLibraryPillRow.swift`):
 *
 * - [Recommended] — the server-driven landing feed (recommendations +
 *   sections); always first / the landing default.
 * - [Browse] — the full library grid.
 * - [Collections] — curated collections within the library.
 * - [Authors] / [Series] — audiobook-native browse shortcuts.
 *
 * The set is type-aware so the cascade never exposes ebook-only or unsupported
 * destinations on TV.
 */
enum class TvLibraryPill {
    Recommended,
    Collections,
    Browse,
    Genres,
    Alphabet,
    RecentlyAdded,
    Authors,
    Series;

    /** Pill / flyout-row label. */
    val title: String
        get() = when (this) {
            Recommended -> "Recommended"
            Collections -> "Collections"
            Browse -> "Browse"
            Genres -> "Genres"
            Alphabet -> "A-Z"
            RecentlyAdded -> "Recently Added"
            Authors -> "Authors"
            Series -> "Series"
        }

    /** Glyph shown beside the section name in the cascade flyout (§5.3). */
    val icon: ImageVector
        get() = when (this) {
            Recommended -> Icons.Filled.AutoAwesome // tvOS `sparkles`
            Collections -> Icons.Filled.Collections // tvOS `square.stack.3d.up`
            Browse -> Icons.Filled.GridView // tvOS `square.grid.2x2`
            Genres -> Icons.Filled.AutoAwesome
            Alphabet -> Icons.Filled.GridView
            RecentlyAdded -> Icons.Filled.AutoAwesome
            Authors -> Icons.Filled.Person
            Series -> Icons.Filled.Collections
        }

    companion object {
        /**
         * Sections offered for [type]. Movies/Series mirror tvOS's compact
         * Recommended / Browse / Collections set; audiobooks expose only
         * audiobook-safe destinations. Music is intentionally conservative
         * until the server exposes native album/artist/playlist grouping.
         */
        fun set(type: TvLibraryTabType): List<TvLibraryPill> = when (type) {
            TvLibraryTabType.Movies,
            TvLibraryTabType.Series -> listOf(
                Recommended,
                Browse,
                Collections,
            )
            TvLibraryTabType.Audiobooks -> listOf(
                Recommended,
                Browse,
                Authors,
                Series,
                Collections,
                Alphabet,
            )
            TvLibraryTabType.Music -> listOf(
                Recommended,
                Browse,
                Genres,
            )
        }
    }
}
