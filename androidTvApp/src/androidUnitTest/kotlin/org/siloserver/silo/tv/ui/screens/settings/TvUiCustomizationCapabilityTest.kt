package org.siloserver.silo.tv.ui.screens.settings

import org.siloserver.silo.domain.settings.ProfileSettingsController
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvUiCustomizationCapabilityTest {
    @Test
    fun liveSupportOwnsControlVisibilityAndMutationGuardAcrossTransitions() {
        var state = TvSettingsViewModel.UiState()
            .withObservedUiCustomizationSupport(false)
        assertFalse(state.uiCustomizationAvailable)
        assertFalse(state.canAuthorUiCustomization)

        state = state.withObservedUiCustomizationSupport(true)
        assertTrue(state.uiCustomizationAvailable)
        assertTrue(state.canAuthorUiCustomization)

        state = state.withObservedUiCustomizationSupport(false)
        assertFalse(state.uiCustomizationAvailable)
        assertFalse(state.canAuthorUiCustomization)

        state = state.withObservedUiCustomizationSupport(true)
        assertTrue(state.uiCustomizationAvailable)
        assertTrue(state.canAuthorUiCustomization)

        // A profile/server switch publishes UNKNOWN before the replacement
        // probe completes. Cached presentation remains in state, but writes
        // and authoring controls must close immediately.
        state = state.copy(posterSize = org.siloserver.silo.model.settings.PosterSizePreset.LARGE)
            .withObservedUiCustomizationSupport(null)
        assertEquals(
            org.siloserver.silo.model.settings.PosterSizePreset.LARGE,
            state.posterSize,
        )
        assertFalse(state.uiCustomizationAvailable)
        assertFalse(state.canAuthorUiCustomization)

        state = state.withObservedUiCustomizationSupport(false)
        assertFalse(state.uiCustomizationAvailable)
        assertFalse(state.canAuthorUiCustomization)
    }

    @Test
    fun olderAndErroringServersFailClosed() {
        assertEquals(
            false,
            tvUiCustomizationSupport(
                ProfileSettingsController.LoadResult(
                    ProfileSettingsController.Availability.SERVER_UPGRADE_REQUIRED,
                    snapshot = null,
                ),
            ),
        )
        assertNull(
            tvUiCustomizationSupport(
                ProfileSettingsController.LoadResult(
                    ProfileSettingsController.Availability.UNAVAILABLE,
                    snapshot = null,
                ),
            ),
        )
        assertFalse(
            tvUiCustomizationAvailable(
                ProfileSettingsController.LoadResult(
                    ProfileSettingsController.Availability.SERVER_UPGRADE_REQUIRED,
                    snapshot = null,
                ),
            ),
        )
        assertFalse(
            tvUiCustomizationAvailable(
                ProfileSettingsController.LoadResult(
                    ProfileSettingsController.Availability.UNAVAILABLE,
                    snapshot = null,
                ),
            ),
        )
        assertFalse(
            available(
                SettingsContractCapabilities(
                    revision = 4,
                    supportsBatchedEffective = true,
                    supportsIdempotentWrites = true,
                    supportsAtomicShortcuts = true,
                ),
            ),
        )
        assertFalse(
            available(
                SettingsContractCapabilities(
                    revision = 5,
                    supportsBatchedEffective = true,
                    supportsIdempotentWrites = true,
                    supportsAtomicShortcuts = false,
                ),
            ),
        )
        assertFalse(
            available(
                SettingsContractCapabilities(
                    revision = 5,
                    supportsBatchedEffective = true,
                    supportsAtomicShortcuts = true,
                ),
            ),
        )
        assertFalse(
            available(
                SettingsContractCapabilities(
                    revision = 5,
                    supportsIdempotentWrites = true,
                    supportsAtomicShortcuts = true,
                ),
            ),
        )
    }

    @Test
    fun revisionFiveWithEveryRequiredCapabilityEnablesControls() {
        assertTrue(
            available(
                SettingsContractCapabilities(
                    revision = 5,
                    supportsBatchedEffective = true,
                    supportsIdempotentWrites = true,
                    supportsAtomicShortcuts = true,
                ),
            ),
        )
    }

    private fun available(capabilities: SettingsContractCapabilities): Boolean =
        tvUiCustomizationAvailable(
            ProfileSettingsController.LoadResult(
                ProfileSettingsController.Availability.AVAILABLE,
                snapshot = null,
                capabilities = capabilities,
            ),
        )
}
