package org.siloserver.silo.android.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The on-disk native sign-in state that must survive process death: the
 * system can kill the app while the browser is open, and a fresh store over
 * the same preferences must still finish the flow. [SharedPrefsPendingNativeSignInStore.load]
 * drops anything it can't decode, so a serialization regression would lose
 * the callback silently; these tests catch that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NativeSignInPersistenceTest {
    private lateinit var prefs: SharedPreferences

    @BeforeTest
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("native_sign_in_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun aPendingLinkSurvivesANewStoreWithEveryField() {
        SharedPrefsPendingNativeSignInStore(prefs).save(LINK)

        // A new process builds a new store over the same preferences.
        assertEquals(LINK, SharedPrefsPendingNativeSignInStore(prefs).load())
    }

    @Test
    fun aPendingSignInSurvivesWithoutTheLinkFields() {
        val signIn = LINK.copy(purpose = NativeSignInPurpose.SignIn, loginSessionId = null)
        SharedPrefsPendingNativeSignInStore(prefs).save(signIn)

        assertEquals(signIn, SharedPrefsPendingNativeSignInStore(prefs).load())
    }

    /** A flow saved by the current shape, as it sits on disk, still decodes after an update. */
    @Test
    fun theStoredShapeDecodes() {
        prefs.edit().putString(PENDING_KEY, STORED_LINK_JSON).commit()

        assertEquals(LINK, SharedPrefsPendingNativeSignInStore(prefs).load())
    }

    @Test
    fun clearRemovesThePendingFlow() {
        val store = SharedPrefsPendingNativeSignInStore(prefs)
        store.save(LINK)
        store.clear()

        assertNull(store.load())
        assertNull(SharedPrefsPendingNativeSignInStore(prefs).load())
        assertFalse(prefs.contains(PENDING_KEY))
    }

    @Test
    fun accountChoicesSurviveANewStoreAndClearOneServerAtATime() {
        val store = SharedPrefsAccountChoiceStore(prefs)
        store.request("entry-1")
        store.request("entry-2")

        val reloaded = SharedPrefsAccountChoiceStore(prefs)
        assertTrue(reloaded.isRequested("entry-1"))
        assertTrue(reloaded.isRequested("entry-2"))
        assertFalse(reloaded.isRequested("entry-3"))

        reloaded.clear("entry-1")
        assertFalse(SharedPrefsAccountChoiceStore(prefs).isRequested("entry-1"))
        assertTrue(SharedPrefsAccountChoiceStore(prefs).isRequested("entry-2"))

        reloaded.clear("entry-2")
        assertFalse(SharedPrefsAccountChoiceStore(prefs).isRequested("entry-2"))
        assertFalse(prefs.contains(ACCOUNT_CHOICE_KEY))
    }

    private companion object {
        const val PENDING_KEY = "native_sign_in_pending"
        const val ACCOUNT_CHOICE_KEY = "native_sign_in_account_choice"

        val LINK = PendingNativeSignIn(
            purpose = NativeSignInPurpose.Link,
            serverEntryId = "entry-1",
            verifiedServerId = "server-1",
            startOrigin = "https://silo.test",
            appState = "app-state",
            codeVerifier = "code-verifier",
            providerName = "Keycloak",
            startedAtEpochMs = 1_759_300_000_000,
            loginSessionId = "login-1",
        )

        /** [LINK] as the current serializer writes it. */
        const val STORED_LINK_JSON =
            """{"purpose":"Link","serverEntryId":"entry-1","verifiedServerId":"server-1",""" +
                """"startOrigin":"https://silo.test","appState":"app-state","codeVerifier":"code-verifier",""" +
                """"providerName":"Keycloak","startedAtEpochMs":1759300000000,"loginSessionId":"login-1"}"""
    }
}
