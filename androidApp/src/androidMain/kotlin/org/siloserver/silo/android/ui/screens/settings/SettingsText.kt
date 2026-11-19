package org.siloserver.silo.android.ui.screens.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.common.i18n.text

/**
 * Text a settings view model hands its screen. A catalog message is resolved
 * where it is shown, so it follows the app language even if that changes
 * while the message is up. [Plain] carries text written elsewhere: the server,
 * or sign-in helpers outside Settings.
 */
sealed interface SettingsText {
    /**
     * Catalog message [id] with [args] in the message's placeholder order:
     * names as `String` or [Plain] (isolated in a right-to-left interface),
     * counts as `Int`, or a nested catalog [Res].
     */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : SettingsText

    data class Plain(val text: String) : SettingsText

    /** Catalog text built by a helper outside Settings, such as the sign-in messages. */
    data class Ui(val text: UiText) : SettingsText
}

fun settingsText(@StringRes id: Int, vararg args: Any): SettingsText = SettingsText.Res(id, args.toList())

/** This text in [context]'s language; use an activity context, which follows the app language. */
fun SettingsText.resolve(context: Context): String = when (this) {
    is SettingsText.Plain -> text
    is SettingsText.Ui -> text.text(context)
    is SettingsText.Res ->
        if (args.isEmpty()) {
            context.getString(id)
        } else {
            context.getString(id, *args.map { if (it is SettingsText) it.resolve(context) else it }.toTypedArray())
        }
}

@Composable
fun SettingsText.resolve(): String = when (this) {
    is SettingsText.Plain -> text
    is SettingsText.Ui -> text.text()
    is SettingsText.Res -> {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        if (args.isEmpty()) {
            stringResource(id)
        } else {
            val resolved = args.map { arg ->
                when (arg) {
                    is SettingsText.Res -> arg.resolve()
                    is SettingsText.Plain -> if (rtl) "\u2068${arg.text}\u2069" else arg.text
                    is String -> if (rtl) "\u2068$arg\u2069" else arg
                    else -> arg
                }
            }
            stringResource(id, *resolved.toTypedArray())
        }
    }
}
