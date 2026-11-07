package org.siloserver.silo.common.i18n

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.common.R
import org.siloserver.silo.model.auth.PasswordChangeError
import org.siloserver.silo.model.auth.PasswordRequirements
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The shared module's catalog names and how [UiText] resolves them on Android. */
@RunWith(RobolectricTestRunner::class)
class SharedTextTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val english: Resources = app.resources
    // ar-XB: a right-to-left interface. A real right-to-left language with no catalog
    // shows English, which keeps its own direction (see SharedPresentationTextTest).
    private val rtl: Resources = app.createConfigurationContext(
        Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag("ar-XB")) },
    ).resources

    @Test
    fun everyEntryMapsToTheResourceItsKeyGenerates() {
        for (entry in SharedString.entries) {
            assertEquals(entry.key.replace('.', '_'), english.getResourceEntryName(entry.resId), entry.name)
            assertEquals("string", english.getResourceTypeName(entry.resId), entry.name)
        }
        for (entry in SharedPlural.entries) {
            assertEquals(entry.key.replace('.', '_'), english.getResourceEntryName(entry.resId), entry.name)
            assertEquals("plurals", english.getResourceTypeName(entry.resId), entry.name)
        }
    }

    @Test
    fun keysResolveWithTheirArgumentsAndNestedText() {
        assertEquals("Network error. Check your connection.", UiText.Key(SharedString.NetworkError).text(english))
        assertEquals("Can watch titles rated PG-13 and below.", UiText.Key(SharedString.ProfileCeiling, listOf("PG-13")).text(english))
        val reason = UiText.Key(SharedString.NetworkError)
        assertEquals(
            "Couldn't load libraries. Network error. Check your connection.",
            UiText.Key(SharedString.ProfileLibrariesFailedReason, listOf(reason)).text(english),
        )
        assertEquals("Library gone.", UiText.Raw("Library gone.").text(english))
    }

    @Test
    fun pluralsPickTheFormAndPrintTheCountAsWritten() {
        assertEquals("At least 1 character", UiText.Plural(SharedPlural.PasswordRequirement, 1).text(english))
        assertEquals("At least 8 characters", UiText.Plural(SharedPlural.PasswordRequirement, 8).text(english))
        assertEquals("Use at least 1200 characters.", UiText.Plural(SharedPlural.PasswordTooShort, 1200).text(english))
    }

    @Test
    fun namesAreIsolatedOnlyInARightToLeftInterface() {
        val ceiling = UiText.Key(SharedString.ProfileCeiling, listOf("PG-13"))
        assertEquals("Can watch titles rated PG-13 and below.", ceiling.text(english))
        assertEquals("Can watch titles rated \u2068PG-13\u2069 and below.", ceiling.text(rtl))
        // A number among the arguments leaves the strings as they are, as before.
        val mixed = UiText.Res(R.string.downloads_bulk_deleteButton, listOf(2, "1 GB"))
        assertEquals("Delete 2 · Free 1 GB", mixed.text(english))
        assertFalse("\u2068" in mixed.text(rtl))
    }

    /** The password form's messages keep the English they had as plain strings. */
    @Test
    fun passwordChangeErrorsKeepTheirEnglish() {
        val rules = PasswordRequirements.ServerDefault
        val cases = mapOf(
            PasswordChangeError.EmptyNew to "Enter a new password.",
            PasswordChangeError.EmptyTemporary to "Enter the temporary password.",
            PasswordChangeError.Mismatch to "The new passwords don't match.",
            PasswordChangeError.TooShort(8) to "Use at least 8 characters.",
            PasswordChangeError.TooLong to "That password is too long. Use a shorter one.",
            PasswordChangeError.WrongTemporary to "That temporary password is incorrect.",
            PasswordChangeError.Rejected(null) to "Choose a different password, then try again.",
            PasswordChangeError.Rejected("Too common.") to "Too common.",
            PasswordChangeError.RateLimited to "Too many attempts. Wait a moment, then try again.",
            PasswordChangeError.NoSiloPassword to "This account doesn't sign in with a Silo password. Ask your server admin for help.",
            PasswordChangeError.SessionCannotChange to "This session can't change the password. Sign in with your own account and try again.",
            PasswordChangeError.NotChanged to "Your password wasn't changed. Try again.",
            PasswordChangeError.Failed(UiText.Key(SharedString.PasswordChangeFailed)) to "Couldn't change your password. Try again.",
        )
        for ((error, expected) in cases) assertEquals(expected, error.text.text(english), error.toString())
        assertEquals("At least 8 characters", rules.hint.text(english))
    }
}
