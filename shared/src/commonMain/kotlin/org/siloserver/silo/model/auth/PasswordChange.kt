package org.siloserver.silo.model.auth

import org.siloserver.silo.common.i18n.SharedPlural
import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.common.i18n.errorText
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.WRONG_CURRENT_PASSWORD

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
 * error would send the person to an admin who sees nothing wrong. English,
 * for logs; screens show [PASSWORD_CHANGE_REQUIRED_TEXT].
 */
const val PASSWORD_CHANGE_REQUIRED_MESSAGE =
    "An admin set a temporary password for this account. Sign out, then sign in again to choose your own."

/** What a screen shows for [PASSWORD_CHANGE_REQUIRED] (see [PASSWORD_CHANGE_REQUIRED_MESSAGE]). */
val PASSWORD_CHANGE_REQUIRED_TEXT: UiText = UiText.Key(SharedString.PasswordChangeRequired)

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
    fun problem(password: String, confirmation: String): PasswordChangeError? = when {
        password.isEmpty() -> PasswordChangeError.EmptyNew
        password != confirmation -> PasswordChangeError.Mismatch
        password.codePointCount() < minimumLength -> PasswordChangeError.TooShort(minimumLength)
        password.encodeToByteArray().size > maximumBytes -> PasswordChangeError.TooLong
        else -> null
    }

    /** The hint under the new password field. */
    val hint: UiText get() = UiText.Plural(SharedPlural.PasswordRequirement, minimumLength)

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

/**
 * Why "Choose a new password" can't continue: the form's own checks, then the
 * server's refusals. [text] is what the form shows.
 */
sealed class PasswordChangeError(val text: UiText) {
    data object EmptyNew : PasswordChangeError(UiText.Key(SharedString.PasswordNewRequired))
    data object EmptyTemporary : PasswordChangeError(UiText.Key(SharedString.PasswordTemporaryRequired))
    data object Mismatch : PasswordChangeError(UiText.Key(SharedString.PasswordMismatch))
    data class TooShort(val minimum: Int) : PasswordChangeError(UiText.Plural(SharedPlural.PasswordTooShort, minimum))
    data object TooLong : PasswordChangeError(UiText.Key(SharedString.PasswordTooLong))

    /** 422 naming the temporary password. */
    data object WrongTemporary : PasswordChangeError(UiText.Key(SharedString.PasswordTemporaryIncorrect))

    /** Another 422: the server refused the new password, with its reason when it gave one. */
    data class Rejected(val server: String?) :
        PasswordChangeError(server?.let(UiText::Raw) ?: UiText.Key(SharedString.PasswordRejected))

    /** 429. */
    data object RateLimited : PasswordChangeError(UiText.Key(SharedString.PasswordRateLimited))

    /** 409: the account signs in through another provider. */
    data object NoSiloPassword : PasswordChangeError(UiText.Key(SharedString.PasswordNoSiloPassword))

    /** 403: this session may not change the password. */
    data object SessionCannotChange : PasswordChangeError(UiText.Key(SharedString.PasswordSessionCannotChange))

    /** Checking again found the temporary password still in place. */
    data object NotChanged : PasswordChangeError(UiText.Key(SharedString.PasswordNotChanged))

    /** Anything else: the server's message, the network or timeout text, or the generic failure. */
    data class Failed(val reason: UiText) : PasswordChangeError(reason)

    companion object {
        /** The refusal [result] carries, from its status first, as `PasswordLoginFailure.of` reads one. */
        fun of(result: ApiResult<*>): PasswordChangeError {
            if (result is ApiResult.Error) {
                when {
                    result.code == 422 && result.error == WRONG_CURRENT_PASSWORD -> return WrongTemporary
                    result.code == 422 -> return Rejected(result.message.takeIf { it.isNotBlank() })
                    result.code == 429 -> return RateLimited
                    result.code == 409 -> return NoSiloPassword
                    result.code == 403 -> return SessionCannotChange
                }
            }
            return Failed(result.errorText(SharedString.PasswordChangeFailed))
        }
    }
}
