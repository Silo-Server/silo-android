package org.siloserver.silo.model.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackEditionsTest {
    @Test
    fun namedCutsKeepTheirOwnFilesEvenWithIdenticalQuality() {
        val theatrical = FileVersion(fileId = 10, resolution = "1080p", editionRaw = "Theatrical", editionKey = "theatrical")
        val international = theatrical.copy(fileId = 20, editionRaw = "International", editionKey = "international")
        val international4k = international.copy(fileId = 30, resolution = "2160p")
        val editions = playbackEditions(listOf(theatrical, international, international4k))

        assertTrue(editions.hasEditionChoices())
        assertEquals(listOf("Theatrical", "International"), editions.map { it.label })
        assertEquals(listOf(10), editions[0].versions.map { it.fileId })
        assertEquals(listOf(20, 30), editions[1].versions.map { it.fileId })
    }

    @Test
    fun variantsOfferTheirFirstPartAndPreserveCatalogFileIdentity() {
        val files = listOf(FileVersion(30), FileVersion(20), FileVersion(10))
        val variant = PlaybackVariant(
            variantId = "international",
            editionRaw = " International ",
            editionKey = "international",
            parts = listOf(
                PlaybackVariantPart(partIndex = 2, defaultFileId = 30, versions = listOf(FileVersion(30))),
                PlaybackVariantPart(partIndex = 1, defaultFileId = 20, versions = listOf(FileVersion(10), FileVersion(20))),
            ),
        )
        val edition = playbackEditions(files, listOf(variant)).single()

        assertEquals("International", edition.label)
        // First-part files in the server's version order.
        assertEquals(listOf(20, 10), edition.versions.map { it.fileId })
        assertEquals(setOf(10, 20, 30), edition.fileIds)
    }

    @Test
    fun missingAndKeyOnlyLabelsRemainUsable() {
        val standard = FileVersion(1)
        val imax = FileVersion(2, editionRaw = " ", editionKey = "imax_extended-cut")
        assertEquals("IMAX Extended Cut", imax.editionLabel)
        assertEquals(listOf("Standard", "IMAX Extended Cut"), playbackEditions(listOf(standard, imax)).map { it.label })
        assertFalse(playbackEditions(listOf(standard, standard.copy(fileId = 3))).hasEditionChoices())
        assertTrue(playbackEditions(emptyList()).isEmpty())
    }
}
