package org.siloserver.silo.audiobook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Mirrors Apple's `AudiobookDetailFormattingTests` so both clients clean titles alike. */
class AudiobookDetailFormattingTest {

    @Test
    fun `series prefix and trailing volume are lifted out`() {
        assertEquals(
            "Wind and Truth",
            AudiobookDetailFormatting.cleanTitle("Stormlight Archive 5 - Wind and Truth (5 of 5)", "The Stormlight Archive"),
        )
        assertEquals(
            "Torment and Treachery",
            AudiobookDetailFormatting.cleanTitle("Black Rose Chronicles 2 - Torment and Treachery", "Black Rose Chronicles"),
        )
    }

    @Test
    fun `titles without a recognised pattern are untouched`() {
        assertEquals("Project Hail Mary", AudiobookDetailFormatting.cleanTitle("Project Hail Mary", null))
        assertEquals("The Way of Kings", AudiobookDetailFormatting.cleanTitle("The Way of Kings", "The Stormlight Archive"))
        assertEquals("Some Story", AudiobookDetailFormatting.cleanTitle("Some Story (2 of 3)", null))
    }

    @Test
    fun `series name must end at a word boundary`() {
        assertEquals("American Gods", AudiobookDetailFormatting.cleanTitle("American Gods", "A"))
        assertEquals("Dunes of Arrakis", AudiobookDetailFormatting.cleanTitle("Dunes of Arrakis", "Dune"))
        assertEquals("Dune Messiah", AudiobookDetailFormatting.cleanTitle("Dune Saga 12 - Dune Messiah", "Dune Saga"))
    }

    @Test
    fun `series name is lifted out only before a volume number or separator`() {
        assertEquals("Dune Messiah", AudiobookDetailFormatting.cleanTitle("Dune Messiah", "Dune"))
        assertEquals("Children of Dune", AudiobookDetailFormatting.cleanTitle("Children of Dune", "Dune"))
        assertEquals("Wind and Truth", AudiobookDetailFormatting.cleanTitle("Stormlight Archive: Wind and Truth", "Stormlight Archive"))
        assertEquals("Wind and Truth", AudiobookDetailFormatting.cleanTitle("Stormlight Archive 5 Wind and Truth", "Stormlight Archive"))
        assertEquals("Wind and Truth", AudiobookDetailFormatting.cleanTitle("Stormlight Archive #5: Wind and Truth", "Stormlight Archive"))
    }

    @Test
    fun `a title that is only the series name is kept`() {
        assertEquals("Mistborn", AudiobookDetailFormatting.cleanTitle("Mistborn", "Mistborn"))
    }

    @Test
    fun `volume locator parses index and total`() {
        assertEquals(AudiobookDetailFormatting.Volume(5, 5), AudiobookDetailFormatting.volume("Wind and Truth (5 of 5)"))
        assertEquals(AudiobookDetailFormatting.Volume(null, null), AudiobookDetailFormatting.volume("Wind and Truth"))
    }

    @Test
    fun `series index prefers a whole-number entry over the title`() {
        val entries = listOf("a" to 1.0, "b" to 2.5, "c" to null)
        assertEquals(1, AudiobookDetailFormatting.seriesIndex("a", "X (4 of 9)", entries))
        assertEquals(4, AudiobookDetailFormatting.seriesIndex("b", "X (4 of 9)", entries))
        assertNull(AudiobookDetailFormatting.seriesIndex("c", "X", entries))
    }

    @Test
    fun `people summary splits joined names and never says one more`() {
        assertEquals(
            AudiobookDetailFormatting.PeopleSummary(listOf("Danny Montooth", "James Konicek"), 3),
            AudiobookDetailFormatting.peopleSummary(listOf("Danny Montooth, James Konicek, A, B, C")),
        )
        assertEquals(
            AudiobookDetailFormatting.PeopleSummary(listOf("A", "B", "C"), 0),
            AudiobookDetailFormatting.peopleSummary(listOf("A", "B", "C")),
        )
        assertNull(AudiobookDetailFormatting.peopleSummary(listOf(" ", "")))
        assertEquals("A, B & C", AudiobookDetailFormatting.naturalJoin(listOf("A", "B", "C")))
    }
}
