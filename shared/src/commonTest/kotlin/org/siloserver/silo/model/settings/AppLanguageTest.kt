package org.siloserver.silo.model.settings

import org.siloserver.silo.model.settings.AppLocaleReconciler.Decision
import org.siloserver.silo.model.settings.AppLocaleReconciler.Ledger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "" = follow the device. The shipped list is `en`, `es`. */
class AppLocaleReconcilerTest {
    private val shipped = listOf("en", "es")

    private fun reconcile(current: String, override: String?, ledger: Ledger?) =
        AppLocaleReconciler.reconcile(current, override, ledger, shipped)

    @Test fun firstRunOnlyObserves() =
        assertEquals(Decision(null, Ledger("", null)), reconcile("", null, null))

    @Test fun overrideOnFollowDeviceAppliesAndRemembersDevice() =
        assertEquals(Decision("es", Ledger("es", "")), reconcile("", "es", Ledger("", null)))

    @Test fun profileWithoutOverrideRestoresFollowDevice() =
        assertEquals(Decision("", Ledger("", null)), reconcile("es", null, Ledger("es", "")))

    @Test fun overrideReplacesUsersOwnChoiceAndRestoresItLater() {
        val first = reconcile("en", "es", Ledger("en", null))
        assertEquals(Decision("es", Ledger("es", "en")), first)
        assertEquals(Decision("en", Ledger("en", null)), reconcile("es", null, first.ledger))
    }

    @Test fun switchBetweenOverridesKeepsTheOriginalBaseline() =
        assertEquals(Decision("en", Ledger("en", "")), reconcile("es", "en", Ledger("es", "")))

    @Test fun sameOverrideAgainMakesNoCall() =
        assertEquals(Decision(null, Ledger("es", "")), reconcile("es", "es", Ledger("es", "")))

    @Test fun regionalSystemValueAlreadyMatchingTheOverrideMakesNoCall() =
        assertEquals(Decision(null, Ledger("es-US", null)), reconcile("es-US", "es", Ledger("es-US", null)))

    @Test fun noOverrideLeavesTheUsersOwnChoiceAlone() =
        assertEquals(Decision(null, Ledger("en", null)), reconcile("en", null, Ledger("en", null)))

    // D18: a change made in system settings is the user's, and is written back.

    @Test fun systemChangeAfterAnOverrideIsWrittenBackAndNotOverwritten() =
        // The app applied es for the profile; the user then picked English.
        assertEquals(Decision(null, Ledger("en", ""), writeBack = "en"), reconcile("en", "es", Ledger("es", "")))

    @Test fun systemResetToDefaultIsWrittenBackAsMatchDevice() =
        assertEquals(Decision(null, Ledger("", null), writeBack = ""), reconcile("", "es", Ledger("es", "")))

    @Test fun systemChangeWithNoOverrideIsStillWrittenBack() =
        // The ledger keeps observing after the app stops owning the value.
        assertEquals(Decision(null, Ledger("es", ""), writeBack = "es"), reconcile("es", null, Ledger("", null)))

    @Test fun regionalSystemChoiceIsWrittenBackAsChosen() =
        assertEquals(Decision(null, Ledger("es-US", ""), writeBack = "es-US"), reconcile("es-US", null, Ledger("", null)))

    @Test fun writtenBackChoiceStaysWithItsProfile() {
        val wroteBack = reconcile("en", "es", Ledger("es", ""))
        // The same profile, once the server has the value: nothing to do.
        assertEquals(Decision(null, Ledger("en", "")), reconcile("en", "en", wroteBack.ledger))
        // The next profile without an override gets the device default back.
        assertEquals(Decision("", Ledger("", null)), reconcile("en", null, wroteBack.ledger))
    }

    @Test fun pseudoLocaleIsNeverWrittenBack() =
        assertEquals(Decision(null, Ledger("ar-XB", null)), reconcile("ar-XB", null, Ledger("", null)))

    @Test fun aSystemListIsJudgedByTheTagThatWouldBeSaved() {
        // fr first: saving it would leave the profile with a language the app can't show.
        assertEquals(Decision(null, Ledger("fr,es", null)), reconcile("fr,es", null, Ledger("es", "")))
        assertEquals(Decision(null, Ledger("es,fr", ""), writeBack = "es"), reconcile("es,fr", null, Ledger("", null)))
    }

    @Test fun unshippedSystemChoiceIsNotWrittenBack() =
        assertEquals(Decision(null, Ledger("fr", null)), reconcile("fr", null, Ledger("", null)))

    @Test fun anUnsavedSystemChoiceGivesWayToTheProfileAndComesBackAfter() {
        // es applied for the profile; the user then set the pseudo-locale ar-XB.
        val applied = reconcile("ar-XB", "es", Ledger("es", ""))
        assertEquals(Decision("es", Ledger("es", "ar-XB")), applied)
        // A profile without a value gets the user's ar-XB back.
        assertEquals(Decision("ar-XB", Ledger("ar-XB", null)), reconcile("es", null, applied.ledger))
    }

    @Test fun interruptedApplyIsRetriedRatherThanReadAsAUserReset() =
        // Crash after the ledger recorded es but before the platform took it.
        assertEquals(Decision("es", Ledger("es", "")), reconcile("", "es", Ledger("es", "", applying = true)))

    // The in-app picker: the same knob as system settings.

    @Test fun pickedLanguageIsAppliedSavedAndOwnedByTheProfile() =
        assertEquals(Decision("es", Ledger("es", ""), writeBack = "es"), AppLocaleReconciler.choose("es", "", Ledger("", null)))

    @Test fun pickedMatchDeviceClearsTheSystemValueRatherThanRestoringABaseline() =
        // The user had English before the profile's es; Match device means the device.
        assertEquals(Decision("", Ledger("", null), writeBack = ""), AppLocaleReconciler.choose("", "es", Ledger("es", "en")))

    @Test fun pickingAnotherLanguageKeepsTheOriginalBaseline() =
        assertEquals(Decision("en", Ledger("en", "fr"), writeBack = "en"), AppLocaleReconciler.choose("en", "es", Ledger("es", "fr")))

    @Test fun pickingALanguageAfterAPseudoLocaleRestoresTheDeviceNotThePseudoLocale() =
        // A debug en-XA check left the user's own value; another profile shouldn't get it back.
        assertEquals(Decision("en", Ledger("en", ""), writeBack = "en"), AppLocaleReconciler.choose("en", "en-XA", Ledger("en-XA", null)))

    @Test fun interruptedRestoreFinishesTheRestore() =
        assertEquals(Decision("", Ledger("", null)), reconcile("es", null, Ledger("", null, applying = true)))
}

class AppLanguageMatchTest {
    private val firstLanguages = listOf("en", "es", "de", "fr", "pt-BR", "ja", "zh-Hans", "ar")

    private fun match(tag: String) = AppLanguage.match(tag, firstLanguages)

    @Test fun exactAndRegionalTagsMatchTheirLanguage() {
        assertEquals("es", match("es"))
        assertEquals("es", match("es-MX"))
        assertEquals("es", match("es_419"))
        assertEquals("pt-BR", match("pt-br"))
        assertEquals("pt-BR", match("pt-PT"))
        assertEquals("en", match("en-GB"))
    }

    @Test fun chineseMatchesByScript() {
        assertEquals("zh-Hans", match("zh"))
        assertEquals("zh-Hans", match("zh-CN"))
        assertEquals("zh-Hans", match("zh-SG"))
        assertEquals("zh-Hans", match("zh-Hans-HK"))
        // A Unicode extension (numbering system) is not a script or a region.
        assertEquals("zh-Hans", match("zh-CN-u-nu-latn"))
        assertEquals("es", match("es-u-fw-mon"))
        assertNull(match("zh-TW"))
        assertNull(match("zh-Hant"))
        assertNull(match("zh-HK"))
    }

    @Test fun pseudoLocalesAndUnknownTagsDoNotMatch() {
        assertNull(match("en-XA"))
        assertNull(match("ar-XB"))
        assertNull(match("it"))
        assertNull(match(""))
    }

    @Test fun pseudoLocalesAreRecognised() {
        assertEquals(true, AppLanguage.isPseudo("ar-XB"))
        assertEquals(false, AppLanguage.isPseudo("ar"))
    }

    @Test fun aListMatchesItsFirstMatchableEntry() =
        assertEquals("es", AppLanguage.match(listOf("it-IT", "es-ES", "en-US"), firstLanguages))
}
