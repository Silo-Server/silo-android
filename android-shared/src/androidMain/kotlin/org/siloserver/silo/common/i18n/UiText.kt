package org.siloserver.silo.common.i18n

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Text a view model hands its screen, resolved in composition so it follows
 * an in-place language change: a catalog string with its arguments, or text
 * that arrives already worded (the server's message, a shared-module notice).
 */
sealed interface UiText {
    /**
     * A catalog string. Each of [args] is a [String] (a name, address or
     * server text, isolated in a right-to-left interface), another [UiText]
     * (resolved first), or a number, in the order its placeholders are declared.
     */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** Text that arrives already worded. */
    data class Raw(val text: String) : UiText

    companion object {
        /** The server's [message] when it sent one, otherwise [fallback]. */
        fun serverOr(message: String, @StringRes fallback: Int): UiText =
            if (message.isBlank()) Res(fallback) else Raw(message)
    }
}

/** [UiText] for a catalog string. */
fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

@Composable
fun UiText.text(): String = when (this) {
    is UiText.Res -> when {
        args.isEmpty() -> stringResource(id)
        args.all { it is String || it is UiText } ->
            stringResourceWithNames(id, *args.map { if (it is UiText) it.text() else it as String }.toTypedArray())
        else -> stringResource(id, *args.map { if (it is UiText) it.text() else it }.toTypedArray())
    }
    is UiText.Raw -> text
}
