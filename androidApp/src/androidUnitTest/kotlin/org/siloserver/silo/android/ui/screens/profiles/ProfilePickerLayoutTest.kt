package org.siloserver.silo.android.ui.screens.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Who's watching?" must lay out 1–20 profiles (plus "Add profile") on every
 * phone size: avatars that fit their columns and the screen, and that only
 * get smaller as the household grows.
 */
class ProfilePickerLayoutTest {
    /** Grid room (width, height between title and footer) on representative screens, in dp. */
    private val screens = listOf(
        Triple("small phone", 312f, 340f),
        Triple("Pixel 9", 364f, 520f),
        Triple("large phone", 392f, 600f),
        Triple("tablet", 440f, 900f),
    )

    @Test
    fun everyHouseholdSizeFitsEveryScreen() {
        for ((name, width, height) in screens) {
            var previous: ProfilePickerLayout? = null
            for (profiles in 1..20) {
                val tiles = profiles + 1
                val layout = ProfilePickerLayout.of(tiles, width, height)
                val context = "$name, $profiles profiles"

                assertTrue(layout.avatarSize >= ProfilePickerLayout.MINIMUM_AVATAR, context)
                assertTrue(layout.perRow * layout.columnWidth <= width, context)
                assertTrue(layout.avatarSize < layout.columnWidth, "$context: avatar wider than its column")
                assertTrue(layout.addSize <= layout.avatarSize, context)

                if (!layout.scrolls) {
                    val rows = ((tiles + layout.perRow - 1) / layout.perRow).toFloat()
                    val used = rows * (layout.avatarSize + ProfilePickerLayout.LABEL_HEIGHT) + (rows - 1) * layout.rowSpacing
                    assertTrue(used <= height, "$context: rows overflow without scrolling")
                }
                previous?.let {
                    assertTrue(layout.avatarSize <= it.avatarSize, "$context: avatars grew")
                    assertTrue(layout.perRow >= it.perRow, "$context: fewer per row")
                    assertTrue(layout.scrolls || !it.scrolls, "$context: stopped scrolling")
                }
                previous = layout
            }
        }
    }

    @Test
    fun smallHouseholdGetsTwoLargeAvatarsPerRow() {
        val layout = ProfilePickerLayout.of(tileCount = 3, width = 364f, height = 520f)
        assertEquals(2, layout.perRow)
        assertEquals(140f, layout.avatarSize)
        assertTrue(layout.addSize < layout.avatarSize, "a lone Add profile tile is drawn smaller")
    }

    @Test
    fun largeHouseholdScrollsFourPerRow() {
        val layout = ProfilePickerLayout.of(tileCount = 21, width = 364f, height = 520f)
        assertEquals(4, layout.perRow)
        assertTrue(layout.scrolls)
    }
}
