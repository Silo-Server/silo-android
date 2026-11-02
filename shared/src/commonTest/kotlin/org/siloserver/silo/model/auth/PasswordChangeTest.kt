package org.siloserver.silo.model.auth

import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.WRONG_CURRENT_PASSWORD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordChangeTest {

    @Test
    fun eachRefusalHasItsOwnCopy() {
        val cases = listOf(
            ApiResult.Error(422, WRONG_CURRENT_PASSWORD, "The current password is incorrect.") to
                "That temporary password is incorrect.",
            ApiResult.Error(422, "validation_failed", "The new password must differ from the current one.") to
                "The new password must differ from the current one.",
            ApiResult.Error(422, "validation_failed", "") to "Choose a different password, then try again.",
            ApiResult.Error(429, "rate_limited", "Slow down.") to "Too many attempts. Wait a moment, then try again.",
            ApiResult.Error(409, "conflict", "Password login is disabled.") to
                "This account doesn't sign in with a Silo password. Ask your server admin for help.",
            ApiResult.Error(403, "permission_denied", "No.") to
                "This session can't change the password. Sign in with your own account and try again.",
        )
        for ((error, expected) in cases) {
            assertEquals(expected, passwordChangeErrorMessage(error), error.toString())
        }
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
        assertEquals("Enter a new password.", rules.problem("", ""))
        assertEquals("The new passwords don't match.", rules.problem("a-new-password", "something-else"))
        assertEquals("Use at least 8 characters.", rules.problem("short", "short"))
        // 40 two-byte characters: long enough, but 80 bytes.
        val wide = "é".repeat(40)
        assertEquals("That password is too long. Use a shorter one.", rules.problem(wide, wide))
        assertNull(rules.problem("a-new-password", "a-new-password"))
    }

    /** The server counts code points, so an emoji is one character, not two. */
    @Test
    fun lengthCountsCodePoints() {
        val rules = PasswordRequirements(minimumLength = 4, maximumBytes = 72)
        val emoji = "😀".repeat(4)
        assertNull(rules.problem(emoji, emoji))
        val three = "😀".repeat(3)
        assertEquals("Use at least 4 characters.", rules.problem(three, three))
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
