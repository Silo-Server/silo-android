package org.siloserver.silo.tv.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class TvLibraryPillSetTest {
    @Test
    fun videoLibraryTypesMatchTvOsDestinations() {
        val expected = listOf(
            TvLibraryPill.Recommended,
            TvLibraryPill.Browse,
            TvLibraryPill.Collections,
        )

        assertEquals(expected, TvLibraryPill.set(TvLibraryTabType.Movies))
        assertEquals(expected, TvLibraryPill.set(TvLibraryTabType.Series))
    }

    @Test
    fun audiobookLibrariesExposeAudioBookSpecificDestinationsWithoutEbookSurfaces() {
        assertEquals(
            listOf(
                TvLibraryPill.Recommended,
                TvLibraryPill.Browse,
                TvLibraryPill.Authors,
                TvLibraryPill.Series,
                TvLibraryPill.Collections,
                TvLibraryPill.Alphabet,
            ),
            TvLibraryPill.set(TvLibraryTabType.Audiobooks),
        )
    }
}
