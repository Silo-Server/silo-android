package com.continuum.app.tv.ui.screens.auth

internal const val TV_CREDENTIAL_MAX_LENGTH = 200

internal enum class TvCredentialField {
    Username,
    Password,
}

internal sealed interface TvCredentialKeyboardAction {
    data class Insert(val text: String) : TvCredentialKeyboardAction
    data object Backspace : TvCredentialKeyboardAction
    data object Clear : TvCredentialKeyboardAction
}

internal fun applyTvCredentialKeyboardAction(
    value: String,
    action: TvCredentialKeyboardAction,
    maxLength: Int = TV_CREDENTIAL_MAX_LENGTH,
): String =
    when (action) {
        is TvCredentialKeyboardAction.Insert -> (value + action.text).take(maxLength)
        TvCredentialKeyboardAction.Backspace -> value.dropLast(1)
        TvCredentialKeyboardAction.Clear -> ""
    }

internal fun canSubmitTvCredentialLogin(
    username: String,
    password: String,
    isLoading: Boolean,
): Boolean =
    !isLoading && username.isNotBlank() && password.isNotBlank()

internal fun credentialDisplayValue(
    value: String,
    isPassword: Boolean,
    passwordVisible: Boolean,
): String =
    if (isPassword && !passwordVisible) {
        "*".repeat(value.length)
    } else {
        value
    }
