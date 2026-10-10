package org.siloserver.silo.common.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

// Apple and web map the same types to the same marks; a change here should
// land on all three clients.
class DefaultArtworkKindTest {
    private fun assertKind(expected: DefaultArtworkKind, vararg types: String?) {
        for (type in types) {
            assertEquals(expected, DefaultArtworkKind.forItemType(type), "type=$type")
        }
    }

    @Test
    fun seriesSeasonsAndEpisodesGetTheTvMark() =
        assertKind(DefaultArtworkKind.Tv, "series", "season", "episode", " Episode ")

    @Test
    fun audiobooksAndPodcastsGetTheHeadphonesMark() =
        assertKind(DefaultArtworkKind.Audiobook, "audiobook", "podcast", "podcasts", "PODCAST")

    @Test
    fun readingTypesGetTheBookMark() =
        assertKind(DefaultArtworkKind.Book, "book", "ebook", "ebooks", "comic", "comics", "manga", "EBook")

    @Test
    fun moviesAndUnknownTypesGetTheFilmMark() =
        assertKind(DefaultArtworkKind.Video, "movie", "books", "audiobooks", "", null, "something-new")
}
