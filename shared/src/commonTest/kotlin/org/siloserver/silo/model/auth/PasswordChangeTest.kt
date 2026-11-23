package org.siloserver.silo.model.auth

import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.WRONG_CURRENT_PASSWORD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText

class PasswordChangeTest {

    @Test
    fun eachRefusalHasItsOwnCopy() {
        val cases = listOf(
            ApiResult.Error(422, WRONG_CURRENT_PASSWORD, "The current password is incorrect.") to
                PasswordChangeError.WrongTemporary,
            ApiResult.Error(422, "validation_failed", "The new password must differ from the current one.") to
                PasswordChangeError.Rejected("The new password must differ from the current one."),
            ApiResult.Error(422, "validation_failed", "") to PasswordChangeError.Rejected(null),
            ApiResult.Error(429, "rate_limited", "Slow down.") to PasswordChangeError.RateLimited,
            ApiResult.Error(409, "conflict", "Password login is disabled.") to PasswordChangeError.NoSiloPassword,
            ApiResult.Error(403, "permission_denied", "No.") to PasswordChangeError.SessionCannotChange,
            ApiResult.Error(503, "service_unavailable", "Busy.") to PasswordChangeError.Failed(UiText.Raw("Busy.")),
            ApiResult.Error(503, "service_unavailable", "") to
                PasswordChangeError.Failed(UiText.Key(SharedString.PasswordChangeFailed)),
            ApiResult.NetworkError(IllegalStateException("offline")) to
                PasswordChangeError.Failed(UiText.Key(SharedString.NetworkError)),
        )
        for ((error, expected) in cases) {
            assertEquals(expected, PasswordChangeError.of(error), error.toString())
        }
    }

    @Test
    fun aRejectionShowsTheServersReasonOrTheGenericCopy() {
        assertEquals(UiText.Raw("Too common."), PasswordChangeError.Rejected("Too common.").text)
        assertEquals(UiText.Key(SharedString.PasswordRejected), PasswordChangeError.Rejected(null).text)
    }

    @Test
    fun onlyATemporaryPasswordRefusalAsksForTheChange() {
        assertTrue(ApiResult.Error(403, PASSWORD_CHANGE_REQUIRED, "").isPasswordChangeRequired())
        assertFalse(ApiResult.Error(403, "permission_denied", "").isPasswordChangeRequired())
        assertFalse(ApiResult.Error(403, "profile_verification_required", "").isPasswordChangeRequired())
        assertFalse(ApiResult.Error(401, PASSWORD_CHANGE_REQUIRED, "").isPasswordChangeRequired())
    }

    @Test
    fun theFormChecksTheNewPasswordBeforeSendingIt() {
        val rules = PasswordRequirements.ServerDefault
        assertEquals(PasswordChangeError.EmptyNew, rules.problem("", ""))
        assertEquals(PasswordChangeError.Mismatch, rules.problem("a-new-password", "something-else"))
        assertEquals(PasswordChangeError.TooShort(8), rules.problem("short", "short"))
        // 40 two-byte characters: long enough, but 80 bytes.
        val wide = "é".repeat(40)
        assertEquals(PasswordChangeError.TooLong, rules.problem(wide, wide))
        assertNull(rules.problem("a-new-password", "a-new-password"))
    }

    /** The server counts code points, so an emoji is one character, not two. */
    @Test
    fun lengthCountsCodePoints() {
        val rules = PasswordRequirements(minimumLength = 4, maximumBytes = 72)
        val emoji = "😀".repeat(4)
        assertNull(rules.problem(emoji, emoji))
        val three = "😀".repeat(3)
        assertEquals(PasswordChangeError.TooShort(4), rules.problem(three, three))
    }

    @Test
    fun limitsComeFromTheCapabilityWithServerDefaultsAsFallback() {
        assertEquals(
            PasswordRequirements(minimumLength = 12, maximumBytes = 64),
            PasswordRequirements.from(PasswordCapability("available", true, 12, 64)),
        )
        assertEquals(
            PasswordRequirements.ServerDefault,
            PasswordRequirements.from(PasswordCapability("not_configured", null, 0, null)),
        )
    }
}
