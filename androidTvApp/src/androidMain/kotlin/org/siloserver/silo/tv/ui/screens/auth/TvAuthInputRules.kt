package org.siloserver.silo.tv.ui.screens.auth

internal fun canSubmitTvCredentialLogin(
    username: String,
    password: String,
    isLoading: Boolean,
): Boolean =
    !isLoading && username.isNotBlank() && password.isNotBlank()
