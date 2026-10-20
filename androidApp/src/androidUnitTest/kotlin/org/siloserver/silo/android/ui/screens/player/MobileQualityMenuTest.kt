package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.playback.PlaybackAvailableQualityV3
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackEffectiveRecipeV3
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackRouteFamily
import org.siloserver.silo.model.playback.PlaybackSourceDescriptorV3
import org.siloserver.silo.model.playback.playbackQualityMenu
import org.siloserver.silo.model.playback.playbackQualityValueLabel
import org.siloserver.silo.watchtogether.WatchPartyPlaybackContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileQualityMenuTest {
    private val ladder = listOf(
        PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true),
        PlaybackAvailableQualityV3("1080p-medium", 1080, 6_000),
        PlaybackAvailableQualityV3("720p-medium", 720, 4_000),
    )

    private val sourcePlays = PlaybackEffectiveRecipeV3(width = 1920, height = 1080, bitrateKbps = 8_000)

    private fun state(
        recipe: PlaybackEffectiveRecipeV3?,
        sessionPreference: String? = "1080p",
        picked: String? = null,
        reportedQuality: String? = null,
        reported: Boolean = false,
    ) = PlayerViewModel.PlayerUiState(
        playbackPlan = PlaybackExecutionPlan(
            planId = "plan",
            delivery = PlaybackDelivery.SERVER_TRANSCODE_HLS,
            routeFamily = PlaybackRouteFamily.SERVER_ADAPTIVE,
            availableQualities = ladder,
            effectiveRecipe = recipe,
            sourceFile = PlaybackSourceDescriptorV3(width = 1920, height = 1080, bitrateKbps = 8_000),
            activeQuality = reportedQuality,
            activeQualityReported = reported,
        ),
        committedQualityPreference = picked,
        sessionQualityPreference = sessionPreference,
    )

    @Test
    fun storedResolutionPreferenceMarksTheEntryTheCappedPlanDelivers() {
        val menu = playbackQualityMenu(ladder)

        val capped = state(PlaybackEffectiveRecipeV3(width = 1280, height = 720, bitrateKbps = 4_000))
        assertEquals("720p-medium", capped.activeQualityId(menu))

        // The source plays as-is under the saved 1080p: no row is lit, so the
        // viewer can still choose Auto, and the control names the setting.
        // The source entry an older server still lists is not in the menu.
        val uncapped = state(sourcePlays)
        assertNull(uncapped.activeQualityId(menu))
        assertEquals("1080p", playbackQualityValueLabel(menu, null, uncapped.qualityPreference))
        assertEquals(listOf("auto", "1080p-medium", "720p-medium"), menu.map { it.id })
    }

    @Test
    fun autoLightsOnlyForAnAutoPreferenceAndAPickLightsItsOwnRow() {
        val menu = playbackQualityMenu(ladder)

        assertEquals("auto", state(sourcePlays, sessionPreference = "auto").activeQualityId(menu))
        // Auto picked in the player over a saved 1080p.
        assertEquals("auto", state(sourcePlays, picked = "auto").activeQualityId(menu))
        assertEquals("720p-medium", state(sourcePlays, picked = "720p-medium").activeQualityId(menu))
    }

    @Test
    fun aPlanThatReportsItsEntryOverridesTheInferredRow() {
        val menu = playbackQualityMenu(ladder)
        val capped = PlaybackEffectiveRecipeV3(width = 1280, height = 720, bitrateKbps = 3_000)

        assertEquals("720p-medium", state(capped).activeQualityId(menu))
        // An encode between two rungs: no row, and the control names the setting.
        val between = state(capped, reported = true)
        assertNull(between.activeQualityId(menu))
        assertEquals("1080p", playbackQualityValueLabel(menu, null, between.qualityPreference))
        assertEquals("auto", state(capped, sessionPreference = "auto", reported = true).activeQualityId(menu))
    }

    @Test
    fun pickedQualityCarriesIntoSameTitleReloadsOnly() {
        assertTrue(keepsPickedQuality("movie", "movie", routeNamesQuality = false, room = null, currentRoom = null))
        assertFalse(keepsPickedQuality("other", "movie", routeNamesQuality = false, room = null, currentRoom = null))
        assertFalse(keepsPickedQuality("movie", null, routeNamesQuality = false, room = null, currentRoom = null))
        // A route that names its own quality wins over an earlier pick.
        assertFalse(keepsPickedQuality("movie", "movie", routeNamesQuality = true, room = null, currentRoom = null))
    }

    @Test
    fun pickedQualityCarriesIntoRestartsOfTheSameRoomEpochOnly() {
        val room = WatchPartyPlaybackContext(
            roomId = "room",
            selectionRevision = 3,
            contentId = "movie",
            fileId = 41,
            libraryId = null,
            positionSeconds = 0.0,
            paused = true,
        )

        assertTrue(keepsPickedQuality("movie", "movie", false, room = room.copy(positionSeconds = 90.0), currentRoom = room))
        assertFalse(keepsPickedQuality("movie", "movie", false, room = room.copy(selectionRevision = 4), currentRoom = room))
        assertFalse(keepsPickedQuality("movie", "movie", false, room = room, currentRoom = null))
    }
}
