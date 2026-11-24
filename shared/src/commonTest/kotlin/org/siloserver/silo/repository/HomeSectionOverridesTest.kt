package org.siloserver.silo.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.model.section.ProfileSectionSetting
import org.siloserver.silo.model.section.SectionOverride
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeSectionOverridesTest {
    private val config = JsonObject(mapOf("genre" to JsonPrimitive("drama")))
    private val renamed = SectionOverride(
        id = "ov-recent", sectionId = "recent", position = 0, hidden = false,
        title = "New this week", featured = true, itemLimit = 12, config = config,
    )
    private val custom = SectionOverride(
        id = "ov-custom", position = 1, isUserAdded = true,
        userSectionType = "hidden_gems", userTitle = "Gems", userConfig = JsonObject(emptyMap()),
    )
    private val removed = SectionOverride(id = "ov-gone", sectionId = "gone", position = 4, removed = true)

    private fun row(id: String, hidden: Boolean = false, isCustom: Boolean = false) =
        ProfileSectionSetting(id = id, title = id, hidden = hidden, isCustom = isCustom)

    @Test
    fun reorderKeepsRenamesAddedAndRemovedRowsAndWritesEveryPosition() {
        val ids = ArrayDeque(listOf("new-1"))
        val built = buildHomeSectionOverrides(
            rows = listOf(row("continue", hidden = true), row("ov-custom", isCustom = true), row("recent")),
            saved = listOf(renamed, custom, removed),
            baseline = listOf(row("recent"), row("ov-custom", isCustom = true), row("continue")),
            newId = { ids.removeFirst() },
        )

        assertEquals(
            listOf(
                removed,
                SectionOverride(id = "new-1", sectionId = "continue", position = 0, hidden = true),
                custom.copy(position = 1, hidden = false),
                renamed.copy(position = 2, hidden = false),
            ),
            built,
        )
    }

    @Test
    fun hidingARowWithASavedOverrideKeepsItsOtherFields() {
        val built = buildHomeSectionOverrides(
            rows = listOf(row("recent", hidden = true)),
            saved = listOf(renamed),
            baseline = listOf(row("recent")),
            newId = { error("no new id expected") },
        )

        assertEquals(listOf(renamed.copy(position = 0, hidden = true)), built)
    }

    @Test
    fun aSavedOverrideWithoutAnIdGetsANewOne() {
        val built = buildHomeSectionOverrides(
            rows = listOf(row("recent")),
            saved = listOf(renamed.copy(id = "")),
            baseline = listOf(row("recent")),
            newId = { "fresh" },
        )

        assertEquals(listOf(renamed.copy(id = "fresh", position = 0)), built)
    }

    // Until the profile orders its Home itself, rows keep following the
    // admin's order, so a visibility change must not pin any position.
    @Test
    fun hidingARowOnAFreshProfileWritesNoPositionsAndOnlyThatRow() {
        val built = buildHomeSectionOverrides(
            rows = listOf(row("recent"), row("continue", hidden = true), row("trending")),
            saved = emptyList(),
            baseline = listOf(row("recent"), row("continue"), row("trending")),
            newId = { "new" },
        )

        assertEquals(listOf(SectionOverride(id = "new", sectionId = "continue", hidden = true)), built)
    }

    @Test
    fun showingARowAgainKeepsItsUnpositionedOverride() {
        val hiddenOnly = SectionOverride(id = "ov-continue", sectionId = "continue", hidden = true)
        val built = buildHomeSectionOverrides(
            rows = listOf(row("recent"), row("continue")),
            saved = listOf(hiddenOnly),
            baseline = listOf(row("recent"), row("continue", hidden = true)),
            newId = { error("no new id expected") },
        )

        assertEquals(listOf(hiddenOnly.copy(hidden = false)), built)
    }

    @Test
    fun reorderingAFreshProfileWritesAPositionForEveryRow() {
        val ids = ArrayDeque(listOf("n1", "n2"))
        val built = buildHomeSectionOverrides(
            rows = listOf(row("continue"), row("recent")),
            saved = emptyList(),
            baseline = listOf(row("recent"), row("continue")),
            newId = { ids.removeFirst() },
        )

        assertEquals(
            listOf(
                SectionOverride(id = "n1", sectionId = "continue", position = 0),
                SectionOverride(id = "n2", sectionId = "recent", position = 1),
            ),
            built,
        )
    }

    @Test
    fun hidingARowAfterAnEarlierReorderKeepsWritingPositions() {
        val built = buildHomeSectionOverrides(
            rows = listOf(row("continue"), row("recent", hidden = true)),
            saved = listOf(
                SectionOverride(id = "ov-continue", sectionId = "continue", position = 0),
                SectionOverride(id = "ov-recent", sectionId = "recent", position = 1),
            ),
            baseline = listOf(row("continue"), row("recent")),
            newId = { error("no new id expected") },
        )

        assertEquals(
            listOf(
                SectionOverride(id = "ov-continue", sectionId = "continue", position = 0),
                SectionOverride(id = "ov-recent", sectionId = "recent", position = 1, hidden = true),
            ),
            built,
        )
    }
}
