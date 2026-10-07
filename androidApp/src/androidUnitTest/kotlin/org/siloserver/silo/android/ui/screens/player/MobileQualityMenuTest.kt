package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.playback.PlaybackAvailableQualityV3
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackEffectiveRecipeV3
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackRouteFamily
import org.siloserver.silo.model.playback.playbackQualityMenu
import org.siloserver.silo.watchtogether.WatchPartyPlaybackContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileQualityMenuTest {
    private val ladder = listOf(
        PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true),
        PlaybackAvailableQualityV3("1080p-medium", 1080, 6_000),
        PlaybackAvailableQualityV3("720p-medium", 720, 4_000),
    )

    private fun state(recipe: PlaybackEffectiveRecipeV3?) = PlayerViewModel.PlayerUiState(
        playbackPlan = PlaybackExecutionPlan(
            planId = "plan",
            delivery = PlaybackDelivery.SERVER_TRANSCODE_HLS,
            routeFamily = PlaybackRouteFamily.SERVER_ADAPTIVE,
            availableQualities = ladder,
            effectiveRecipe = recipe,
        ),
        committedQualityPreference = "1080p",
    )

    @Test
    fun storedResolutionPreferenceMarksTheEntryTheCappedPlanDelivers() {
        val menu = playbackQualityMenu(ladder)

        val capped = state(PlaybackEffectiveRecipeV3(width = 1280, height = 720, bitrateKbps = 4_000))
        assertEquals("720p-medium", capped.activeQualityId(menu))

        val uncapped = state(PlaybackEffectiveRecipeV3(width = 1920, height = 1080, bitrateKbps = 8_000))
        assertEquals("original", uncapped.activeQualityId(menu))
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
