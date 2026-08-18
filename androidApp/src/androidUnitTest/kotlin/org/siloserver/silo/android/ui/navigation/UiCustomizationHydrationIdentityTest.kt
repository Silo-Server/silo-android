package org.siloserver.silo.android.ui.navigation

import androidx.lifecycle.Lifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiCustomizationHydrationIdentityTest {
    @Test
    fun sameProfileOnDifferentServersProducesDifferentHydrationKeys() {
        val serverA = uiCustomizationHydrationIdentity("server-a", "profile-1")
        val serverB = uiCustomizationHydrationIdentity("server-b", "profile-1")

        assertNotEquals(serverA, serverB)
        assertEquals(
            UiCustomizationHydrationIdentity("server-a", "profile-1"),
            serverA,
        )
    }

    @Test
    fun incompleteIdentityDoesNotHydrateAuthenticatedPreferences() {
        assertNull(uiCustomizationHydrationIdentity(null, "profile-1"))
        assertNull(uiCustomizationHydrationIdentity("server-a", null))
        assertNull(uiCustomizationHydrationIdentity("", "profile-1"))
        assertNull(uiCustomizationHydrationIdentity("server-a", " "))
    }

    @Test
    fun onlyResumeWithCompleteIdentityRefreshesCustomization() {
        val identity = uiCustomizationHydrationIdentity("server-a", "profile-1")

        assertTrue(shouldRefreshUiCustomization(Lifecycle.Event.ON_RESUME, identity))
        assertFalse(shouldRefreshUiCustomization(Lifecycle.Event.ON_START, identity))
        assertFalse(shouldRefreshUiCustomization(Lifecycle.Event.ON_RESUME, null))
    }

    @Test
    fun everyNewCompleteIdentityPlansImmediateHydration() {
        val first = uiCustomizationHydrationIdentity("server-a", "profile-1")
        val switched = uiCustomizationHydrationIdentity("server-a", "profile-2")

        assertNotEquals(first, switched)
        assertEquals(
            UiCustomizationHydrationAction.REFRESH,
            uiCustomizationHydrationAction(first),
        )
        assertEquals(
            UiCustomizationHydrationAction.REFRESH,
            uiCustomizationHydrationAction(switched),
        )
        assertEquals(
            UiCustomizationHydrationAction.CLEAR,
            uiCustomizationHydrationAction(null),
        )
    }

    @Test
    fun resumedObserverAttachmentDoesNotDuplicateImmediateHydration() {
        val identity = uiCustomizationHydrationIdentity("server-a", "profile-1")
        val gate = UiCustomizationResumeRefreshGate(skipSynchronizedResume = true)

        assertFalse(gate.shouldRefresh(Lifecycle.Event.ON_RESUME, identity))
        assertFalse(gate.shouldRefresh(Lifecycle.Event.ON_PAUSE, identity))
        assertTrue(gate.shouldRefresh(Lifecycle.Event.ON_RESUME, identity))
        assertFalse(gate.shouldRefresh(Lifecycle.Event.ON_RESUME, null))
    }
}
