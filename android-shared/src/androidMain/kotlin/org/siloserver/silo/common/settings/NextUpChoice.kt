package org.siloserver.silo.common.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R

/**
 * The Show Next Up picker: one control over `playback.next_up_enabled` and
 * `playback.next_up_prompt_seconds`. A choice is the seconds before the end
 * (0 = at end), or null for Off.
 */
object NextUpChoice {
    private val promptSeconds: List<Int?> = listOf(0, 10, 30, 60, 120)

    /**
     * Off comes first while the server stores the setting, and while this
     * device's last choice is Off and the server's manifest is not read yet,
     * so that choice stays visible and can be changed.
     */
    fun options(offSupported: Boolean, enabled: Boolean): List<Int?> =
        if (offSupported || !enabled) listOf(null) + promptSeconds else promptSeconds

    fun selected(enabled: Boolean, seconds: Int): Int? = if (enabled) seconds.coerceAtLeast(0) else null

    /** Off leaves the seconds alone; a time turns Next Up back on if it was off. */
    suspend fun apply(store: PlayerSettingsStore, choice: Int?, currentlyEnabled: Boolean) {
        if (choice == null) {
            store.setNextUpEnabled(false)
            return
        }
        if (!currentlyEnabled) store.setNextUpEnabled(true)
        store.setNextUpPromptSeconds(choice)
    }
}

/** The phone and TV label for a [NextUpChoice] choice. */
@Composable
fun nextUpChoiceLabel(choice: Int?): String = when {
    choice == null -> stringResource(R.string.settings_off)
    choice <= 0 -> stringResource(R.string.settings_nextUp_atEnd)
    choice < 60 -> pluralStringResource(R.plurals.settings_nextUp_secondsBeforeEnd, choice, choice)
    else -> (choice / 60).let { pluralStringResource(R.plurals.settings_nextUp_minutesBeforeEnd, it, it) }
}
