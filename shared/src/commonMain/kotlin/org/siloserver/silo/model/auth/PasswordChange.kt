package org.siloserver.silo.model.auth

import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.WRONG_CURRENT_PASSWORD
import org.siloserver.silo.network.errorMessage

/*
 * Temporary passwords: an admin can make an account's password temporary.
 * Until the account replaces it, the server refuses everything but the
 * account read, the password capability, the change itself and sign-out with
 * `403 password_change_required`. See silo-server `docs/auth-api.md`,
 * "Temporary passwords".
 */

/** The problem a session holding a temporary password gets from everything else. */
const val PASSWORD_CHANGE_REQUIRED = "password_change_required"

/**
 * Copy for a screen that hits [PASSWORD_CHANGE_REQUIRED] outside the hand-off.
 * The server's detail is a protocol instruction, and a generic permission
 * error would send the person to an admin who sees nothing wrong.
 */
const val PASSWORD_CHANGE_REQUIRED_MESSAGE =
    "An admin set a temporary password for this account. Sign out, then sign in again to choose your own."

/** Whether [result] says the account must replace a temporary password first. */
fun ApiResult<*>.isPasswordChangeRequired(): Boolean =
    this is ApiResult.Error && code == 403 && error == PASSWORD_CHANGE_REQUIRED

/** `GET /api/v2/account/password/capability`. */
data class PasswordCapability(
    val state: String,
    /** Absent unless [state] is `available`. */
    val allowed: Boolean? = null,
    val minimumPasswordLength: Int? = null,
    val maximumPasswordBytes: Int? = null,
)

/**
 * A new password's limits. The server enforces them; the form checks them
 * first so a typo doesn't spend an attempt from the password-change budget.
 */
data class PasswordRequirements(
    /** Fewest characters, counted in Unicode code points as the server does. */
    val minimumLength: Int,
    /** Most UTF-8 bytes. */
    val maximumBytes: Int,
) {
    /** Why [password] and its [confirmation] can't be sent, or null. */
    fun problem(password: String, confirmation: String): String? = when {
        password.isEmpty() -> "Enter a new password."
        password != confirmation -> "The new passwords don't match."
        password.codePointCount() < minimumLength -> "Use at least $minimumLength characters."
        password.encodeToByteArray().size > maximumBytes -> "That password is too long. Use a shorter one."
        else -> null
    }

    companion object {
        /** silo-server's limits, for when the capability can't be read. */
        val ServerDefault = PasswordRequirements(minimumLength = 8, maximumBytes = 72)

        /** The capability's limits; a missing or non-positive one keeps the default. */
        fun from(capability: PasswordCapability) = PasswordRequirements(
            minimumLength = capability.minimumPasswordLength?.takeIf { it > 0 } ?: ServerDefault.minimumLength,
            maximumBytes = capability.maximumPasswordBytes?.takeIf { it > 0 } ?: ServerDefault.maximumBytes,
        )
    }
}

private fun String.codePointCount(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/** Copy for a failed password change. A rejected new password shows the server's reason. */
fun passwordChangeErrorMessage(result: ApiResult<*>): String {
    if (result is ApiResult.Error) {
        when {
            result.code == 422 && result.error == WRONG_CURRENT_PASSWORD -> return "That temporary password is incorrect."
            result.code == 422 -> return result.message.ifBlank { "Choose a different password, then try again." }
            result.code == 429 -> return "Too many attempts. Wait a moment, then try again."
            result.code == 409 -> return "This account doesn't sign in with a Silo password. Ask your server admin for help."
            result.code == 403 -> return "This session can't change the password. Sign in with your own account and try again."
        }
    }
    return result.errorMessage("Couldn't change your password. Try again.")
}
