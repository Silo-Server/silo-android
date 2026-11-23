package org.siloserver.silo.android.ui.screens.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogLetterRailTest {
    private fun entry(letter: String) = LetterRailEntries.indexOf(letter)

    @Test
    fun aLetterWithTitlesIsItsOwnTarget() {
        assertEquals("C", letterRailTarget(entry("C"), setOf("A", "C", "X")))
        assertEquals("#", letterRailTarget(entry("#"), setOf("#", "A")))
    }

    @Test
    fun aDimmedLetterGoesToTheNextLetterWithTitles() {
        assertEquals("X", letterRailTarget(entry("D"), setOf("A", "C", "X")))
        assertEquals("A", letterRailTarget(entry("#"), setOf("A", "C")))
    }

    @Test
    fun pastTheLastLetterWithTitlesGoesBackToIt() {
        assertEquals("X", letterRailTarget(entry("Z"), setOf("A", "C", "X")))
    }

    @Test
    fun anIndexWithNoLettersHasNoTarget() {
        assertNull(letterRailTarget(entry("M"), emptySet()))
    }

    @Test
    fun theRailIsHashThenAToZ() {
        assertEquals(27, LetterRailEntries.size)
        assertEquals("#", LetterRailEntries.first())
        assertEquals("Z", LetterRailEntries.last())
    }
}
