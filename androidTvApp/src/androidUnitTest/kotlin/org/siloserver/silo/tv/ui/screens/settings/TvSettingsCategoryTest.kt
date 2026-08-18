package org.siloserver.silo.tv.ui.screens.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class TvSettingsCategoryTest {
    @Test
    fun diagnosticsSitsBetweenSubtitlesAndServer() {
        // tvOS TVSettingsCategory order — the rail should read the same on both
        // platforms.
        assertEquals(
            listOf(
                TvSettingsCategory.General,
                TvSettingsCategory.Playback,
                TvSettingsCategory.Subtitles,
                TvSettingsCategory.Diagnostics,
                TvSettingsCategory.Server,
            ),
            TvSettingsCategory.entries,
        )
        assertEquals("SUPPORT", TvSettingsCategory.Diagnostics.eyebrow)
    }

    @Test
    fun anIneligibleProfileHidesTheCategoryEntirely() {
        assertEquals(
            listOf(
                TvSettingsCategory.General,
                TvSettingsCategory.Playback,
                TvSettingsCategory.Subtitles,
                TvSettingsCategory.Server,
            ),
            tvSettingsVisibleCategories(diagnosticsEligible = false),
        )
    }

    @Test
    fun losingEligibilityWhileShownFallsBackToGeneral() {
        assertEquals(
            TvSettingsCategory.General,
            tvSettingsCategoryForEligibility(
                current = TvSettingsCategory.Diagnostics,
                diagnosticsEligible = false,
            ),
        )
    }

    @Test
    fun anUnrelatedCategoryIsNeverDisturbed() {
        assertEquals(
            TvSettingsCategory.Server,
            tvSettingsCategoryForEligibility(
                current = TvSettingsCategory.Server,
                diagnosticsEligible = false,
            ),
        )
        assertEquals(
            TvSettingsCategory.Diagnostics,
            tvSettingsCategoryForEligibility(
                current = TvSettingsCategory.Diagnostics,
                diagnosticsEligible = true,
            ),
        )
    }
}
