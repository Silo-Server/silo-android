package org.siloserver.silo.tv.ui.util

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvMediaTypeFiltersTest {
    @Test
    fun identifiesHiddenReadingTypes() {
        listOf(
            "ebook",
            "ebooks",
            "book",
            "books",
            "comic",
            "comics",
            "manga",
            "reading",
        ).forEach { type ->
            assertTrue(isTvHiddenMediaType(type), "$type should be hidden on TV")
            assertTrue(isTvHiddenMediaType(type.uppercase()), "${type.uppercase()} should be hidden on TV")
        }

        assertFalse(isTvHiddenMediaType("audiobook"))
        assertFalse(isTvHiddenMediaType("audiobooks"))
        assertFalse(isTvHiddenMediaType("movie"))
    }

    @Test
    fun identifiesAudiobookTypesForTv() {
        assertTrue(isAudiobookMediaType("audiobook"))
        assertTrue(isAudiobookMediaType("audiobooks"))
        assertTrue(isAudiobookMediaType("AUDIOBOOK"))
        assertFalse(isAudiobookMediaType("ebook"))
        assertFalse(isAudiobookMediaType("movie"))
    }

    @Test
    fun filtersBrowseItemsForTv() {
        val items = listOf(
            BrowseItem(contentId = "m1", type = "movie", title = "Movie"),
            BrowseItem(contentId = "e1", type = "ebook", title = "Book"),
            BrowseItem(contentId = "b1", type = "book", title = "Book"),
            BrowseItem(contentId = "c1", type = "comic", title = "Comic"),
            BrowseItem(contentId = "manga1", type = "manga", title = "Manga"),
            BrowseItem(contentId = "r1", type = "reading", title = "Reading"),
            BrowseItem(contentId = "a1", type = "audiobook", title = "Audio"),
        )

        assertEquals(listOf("m1", "a1"), items.visibleOnTv().map { it.contentId })
    }

    @Test
    fun filtersResolvedSectionsForTvRows() {
        val sections = listOf(
            ResolvedSection(
                id = "mixed",
                sectionType = "manual",
                title = "Mixed",
                items = listOf(
                    SectionItem(contentId = "m1", type = "movie", title = "Movie"),
                    SectionItem(contentId = "e1", type = "ebook", title = "Book"),
                    SectionItem(contentId = "b1", type = "books", title = "Books"),
                    SectionItem(contentId = "c1", type = "comics", title = "Comics"),
                    SectionItem(contentId = "manga1", type = "manga", title = "Manga"),
                    SectionItem(contentId = "r1", type = "reading", title = "Reading"),
                    SectionItem(contentId = "a1", type = "audiobook", title = "Audio"),
                ),
            ),
            ResolvedSection(
                id = "ebooks",
                sectionType = "manual",
                title = "Books",
                items = listOf(
                    SectionItem(contentId = "e2", type = "ebook", title = "Book 2"),
                    SectionItem(contentId = "b2", type = "book", title = "Book 3"),
                    SectionItem(contentId = "c2", type = "comic", title = "Comic 2"),
                    SectionItem(contentId = "m2", type = "manga", title = "Manga 2"),
                    SectionItem(contentId = "r2", type = "reading", title = "Reading 2"),
                ),
            ),
        )

        val visibleSections = sections.visibleOnTv()

        assertEquals(listOf("mixed"), visibleSections.map { it.id })
        assertEquals(listOf("m1", "a1"), visibleSections.single().items.map { it.contentId })
    }

    @Test
    fun normalizesWhitespaceLikeSharedTaxonomy() {
        assertTrue(isTvHiddenMediaType(" ebook "))
        assertTrue(isAudiobookMediaType(" audiobook "))
        assertFalse(isTvHiddenMediaType(null))
        assertFalse(isTvHiddenMediaType("  "))
        assertFalse(isAudiobookMediaType(null))
    }

    @Test
    fun mapsTvLibraryTypeToCatalogMediaType() {
        assertEquals("series", tvCatalogMediaTypeFor("shows"))
        assertEquals("audiobook", tvCatalogMediaTypeFor("audiobook"))
        assertEquals("audiobook", tvCatalogMediaTypeFor("audiobooks"))
        assertEquals("movie", tvCatalogMediaTypeFor("movies"))
        // Unmapped types (music/audio, ebooks) must NOT be forced to "movie" —
        // browse is scoped by libraryId; forcing "movie" hid every item.
        assertNull(tvCatalogMediaTypeFor("music"))
        assertNull(tvCatalogMediaTypeFor("ebooks"))
    }

    @Test
    fun collectionPreviewDropsReadingTitlesAndFallsBackToTheCollectionCard() {
        val collection = org.siloserver.silo.model.section.SectionCollectionCard(id = "c1", source = "user", title = "Weekend")
        fun item(id: String, type: String) = BrowseItem(contentId = id, type = type, title = id)
        val mixed = org.siloserver.silo.viewmodel.ItemCollectionsRow.Preview(
            collection, listOf(item("m1", "movie"), item("b1", "ebook"), item("s1", "series")),
        )
        val shown = mixed.visibleOnTv() as org.siloserver.silo.viewmodel.ItemCollectionsRow.Preview
        assertEquals(listOf("m1", "s1"), shown.items.map { it.contentId })

        val onlyBooks = org.siloserver.silo.viewmodel.ItemCollectionsRow.Preview(collection, listOf(item("b1", "ebook")))
        assertEquals(org.siloserver.silo.viewmodel.ItemCollectionsRow.AlsoIn(listOf(collection)), onlyBooks.visibleOnTv())
    }

    @Test
    fun collectionsRowKeepsOnlyServerCollectionsOfLibrariesTvShows() {
        fun card(id: String, source: String, libraryId: String? = null) =
            org.siloserver.silo.model.section.SectionCollectionCard(id = id, source = source, title = id, libraryId = libraryId)
        val movies = card("c-movies", "library", "1")
        val books = card("c-books", "library", "2")
        val mine = card("c-mine", "user")
        val shown = setOf(1)

        assertEquals(
            org.siloserver.silo.viewmodel.ItemCollectionsRow.AlsoIn(listOf(movies, mine)),
            org.siloserver.silo.viewmodel.ItemCollectionsRow.AlsoIn(listOf(movies, books, mine)).withTvShownCollections(shown),
        )
        assertNull(org.siloserver.silo.viewmodel.ItemCollectionsRow.AlsoIn(listOf(books)).withTvShownCollections(shown))
        val preview = { collection: org.siloserver.silo.model.section.SectionCollectionCard ->
            org.siloserver.silo.viewmodel.ItemCollectionsRow.Preview(collection, listOf(BrowseItem(contentId = "m1", type = "movie", title = "m1")))
        }
        assertNull(preview(books).withTvShownCollections(shown))
        assertEquals(preview(movies), preview(movies).withTvShownCollections(shown))
    }
}
