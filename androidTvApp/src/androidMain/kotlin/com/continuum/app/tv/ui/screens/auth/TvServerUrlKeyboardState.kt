package com.continuum.app.tv.ui.screens.auth

internal const val TV_SERVER_URL_MAX_LENGTH = 200

internal sealed interface TvServerUrlKeyboardAction {
    data class Insert(val text: String) : TvServerUrlKeyboardAction
    data object Backspace : TvServerUrlKeyboardAction
    data object Clear : TvServerUrlKeyboardAction
}

internal fun applyTvServerUrlKeyboardAction(
    value: String,
    action: TvServerUrlKeyboardAction,
    maxLength: Int = TV_SERVER_URL_MAX_LENGTH,
): String =
    when (action) {
        is TvServerUrlKeyboardAction.Insert -> (value + action.text).take(maxLength)
        TvServerUrlKeyboardAction.Backspace -> value.dropLast(1)
        TvServerUrlKeyboardAction.Clear -> ""
    }

internal fun canSubmitTvServerUrl(value: String, isLoading: Boolean): Boolean =
    !isLoading && value.isNotBlank()
