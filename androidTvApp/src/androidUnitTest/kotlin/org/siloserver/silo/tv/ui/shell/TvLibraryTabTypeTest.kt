package org.siloserver.silo.tv.ui.shell

import org.siloserver.silo.model.personal.UserLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies [TvLibraryTabType.matches] classifies the known library-type
 * strings (from the shared [org.siloserver.silo.model.navigation] sets) under
 * the right tab — the Android mirror of tvOS `TVLibraryTabType.matches`.
 */
class TvLibraryTabTypeTest {

    @Test
    fun `mixed libraries are reachable from both video tabs only`() {
        for (value in listOf("mixed", " Mixed ")) {
            val mixed = library(value)
            assertTrue(TvLibraryTabType.Movies.matches(mixed))
            assertTrue(TvLibraryTabType.Series.matches(mixed))
            assertFalse(TvLibraryTabType.Music.matches(mixed))
            assertFalse(TvLibraryTabType.Audiobooks.matches(mixed))
        }
    }

    private fun library(type: String) = UserLibrary(id = 1, name = type, type = type)

    @Test
    fun `movie types match only Movies`() {
        for (type in listOf("movie", "movies", "Movies", " MOVIE ")) {
            assertMatchesOnly(TvLibraryTabType.Movies, type)
        }
    }

    @Test
    fun `series and show types match only Series`() {
        for (type in listOf("series", "show", "shows", "tv", "TV")) {
            assertMatchesOnly(TvLibraryTabType.Series, type)
        }
    }

    @Test
    fun `music types match only Music`() {
        for (type in listOf("music", "album", "albums", "artist", "artists", "audio")) {
            assertMatchesOnly(TvLibraryTabType.Music, type)
        }
    }

    @Test
    fun `audiobook types match only Audiobooks`() {
        for (type in listOf("audiobook", "audiobooks", "AudioBook")) {
            assertMatchesOnly(TvLibraryTabType.Audiobooks, type)
        }
    }

    @Test
    fun `unknown types match no tab`() {
        val lib = library("ebook")
        assertTrue(TvLibraryTabType.entries.none { it.matches(lib) })
    }

    @Test
    fun `tab metadata is populated`() {
        assertEquals("Movies", TvLibraryTabType.Movies.title)
        assertEquals("SERIES LIBRARIES", TvLibraryTabType.Series.librariesHeader)
        // Every tab resolves a distinct, non-null icon vector.
        assertEquals(
            TvLibraryTabType.entries.size,
            TvLibraryTabType.entries.map { it.icon }.distinct().size,
        )
    }

    /** Asserts [type] matches exactly [expected] and no other tab. */
    private fun assertMatchesOnly(expected: TvLibraryTabType, type: String) {
        val lib = library(type)
        for (tab in TvLibraryTabType.entries) {
            if (tab == expected) {
                assertTrue(tab.matches(lib), "expected $type to match $tab")
            } else {
                assertFalse(tab.matches(lib), "expected $type NOT to match $tab")
            }
        }
    }
}
