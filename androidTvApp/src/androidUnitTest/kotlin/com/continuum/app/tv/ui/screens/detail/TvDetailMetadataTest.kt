package com.continuum.app.tv.ui.screens.detail

import com.continuum.app.model.audiobook.AudiobookMetadata
import com.continuum.app.model.catalog.FileVersion
import com.continuum.app.model.catalog.ItemDetail
import com.continuum.app.model.ebook.MediaPerson
import kotlin.test.Test
import kotlin.test.assertEquals

class TvDetailMetadataTest {
    @Test
    fun audiobookSourceTokensIncludeTypePublisherAndNarrator() {
        val detail = ItemDetail(
            contentId = "a1",
            type = "audiobook",
            title = "Audio",
            audiobook = AudiobookMetadata(
                publisher = "Continuum Press",
                narrators = listOf(MediaPerson(name = "Nia Narrator")),
            ),
        )

        assertEquals(
            listOf("Audiobook", "Continuum Press", "Narrated by Nia Narrator"),
            TvDetailMetadata.sourceTokens(detail),
        )
    }

    @Test
    fun factsLineUsesPreferredQualityForVersionBadges() {
        val detail = ItemDetail(
            contentId = "m1",
            type = "movie",
            title = "Movie",
            versions = listOf(
                FileVersion(fileId = 1080, resolution = "1080p"),
                FileVersion(fileId = 2160, resolution = "2160p", hdr = true),
            ),
        )

        assertEquals(
            listOf(TvHeroFactToken.Chip("HD")),
            TvDetailMetadata.factsLine(detail, preferredQuality = "1080p"),
        )
    }
}
