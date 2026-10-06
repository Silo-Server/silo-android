package org.siloserver.silo.android.ui.screens.profiles

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How "Who's watching?" arranges its tiles (profiles plus "Add profile") in
 * the room it has, in dp: two, three or four per row, whichever gives the
 * largest avatars that still fit without scrolling. When even four per row
 * would shrink avatars below a comfortable tap size, the picker keeps four
 * per row at that size and scrolls. Port of silo-apple's `ProfilePickerLayout`,
 * except that a scrolling grid stays at the minimum size: going back up to
 * full size would make avatars grow as the household grows.
 */
data class ProfilePickerLayout(
    val perRow: Int,
    val avatarSize: Float,
    /** Alone on the last row, "Add profile" is a utility, not a person, so it is drawn smaller. */
    val addSize: Float,
    val rowSpacing: Float,
    /** Every row uses the full rows' column width, so a short last row is centered rather than spread out. */
    val columnWidth: Float,
    val scrolls: Boolean,
) {
    private data class Option(val perRow: Int, val maxAvatar: Float, val gutter: Float, val rowSpacing: Float)

    companion object {
        /** Name, optional "Last used" caption and their spacing under an avatar. */
        const val LABEL_HEIGHT = 48f
        const val MINIMUM_AVATAR = 64f

        private val options = listOf(
            Option(perRow = 2, maxAvatar = 140f, gutter = 36f, rowSpacing = 28f),
            Option(perRow = 3, maxAvatar = 104f, gutter = 16f, rowSpacing = 26f),
            Option(perRow = 4, maxAvatar = 76f, gutter = 10f, rowSpacing = 22f),
        )

        fun of(tileCount: Int, width: Float, height: Float): ProfilePickerLayout {
            val count = tileCount.coerceAtLeast(1)
            fun widthFit(option: Option) = min(option.maxAvatar, width / option.perRow - option.gutter)
            fun fit(option: Option): Float {
                val rows = ((count + option.perRow - 1) / option.perRow).toFloat()
                val heightFit = (height - (rows - 1) * option.rowSpacing) / rows - LABEL_HEIGHT
                return min(widthFit(option), heightFit)
            }

            var best: Pair<Option, Float>? = null
            for (option in options) {
                val size = fit(option)
                if (size >= MINIMUM_AVATAR && size > (best?.second ?: 0f)) best = option to size
            }
            val chosen = best ?: options.last().let { it to min(widthFit(it), MINIMUM_AVATAR) }
            val avatar = floor(chosen.second).coerceAtLeast(0f)
            val perRow = chosen.first.perRow
            return ProfilePickerLayout(
                perRow = perRow,
                avatarSize = avatar,
                addSize = if (count > 1 && count % perRow == 1) (avatar * 0.6f).roundToInt().toFloat() else avatar,
                rowSpacing = chosen.first.rowSpacing,
                columnWidth = floor(width / perRow),
                scrolls = best == null,
            )
        }
    }
}
