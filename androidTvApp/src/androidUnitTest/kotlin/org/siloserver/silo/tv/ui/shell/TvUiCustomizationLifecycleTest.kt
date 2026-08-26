package org.siloserver.silo.tv.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvUiCustomizationLifecycleTest {
    @Test
    fun resumeRefreshRequiresCompleteServerAndProfileIdentity() {
        assertTrue(shouldRefreshTvUiCustomization("server-a", "profile-1"))
        assertFalse(shouldRefreshTvUiCustomization(null, "profile-1"))
        assertFalse(shouldRefreshTvUiCustomization("server-a", null))
        assertFalse(shouldRefreshTvUiCustomization("", "profile-1"))
        assertFalse(shouldRefreshTvUiCustomization("server-a", " "))
    }

    @Test
    fun legacyAudiobookVisibilityStartsHiddenUntilTheFlowResolves() {
        val initial = TvLegacyAudiobookVisibility()
        assertFalse(initial.show)
        assertFalse(initial.isResolved)

        assertEquals(
            TvLegacyAudiobookVisibility(show = true, isResolved = true),
            resolvedTvLegacyAudiobookVisibility(show = true),
        )
        assertEquals(
            TvLegacyAudiobookVisibility(show = false, isResolved = true),
            resolvedTvLegacyAudiobookVisibility(show = false),
        )
    }
}
