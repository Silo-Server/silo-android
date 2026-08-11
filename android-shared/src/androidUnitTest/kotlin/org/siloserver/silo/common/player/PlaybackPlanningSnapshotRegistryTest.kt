package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackPlanningSnapshotRegistryTest {
    @Test
    fun contextUsesTheRouteCapturedWithItsCapabilities() {
        val registry = PlaybackPlanningSnapshotRegistry(maxSize = 2)
        val plannedCapabilities = ClientCodecCapabilities(
            audioPassthrough = AudioPassthroughCapabilities(
                passthroughCodecs = listOf("truehd"),
                maxChannels = 8,
            ),
        )
        val plannedRoute = AudioPlaybackRouteSnapshot(
            sinkType = "hdmi",
            routeGeneration = 7,
            capabilities = requireNotNull(plannedCapabilities.audioPassthrough),
        )
        registry.remember(plannedCapabilities, plannedRoute)

        val routeAfterInterleavedUpdate = AudioPlaybackRouteSnapshot(
            sinkType = "bluetooth",
            routeGeneration = 8,
            capabilities = AudioPassthroughCapabilities(),
        )

        assertEquals(
            plannedRoute,
            registry.resolve(plannedCapabilities, routeAfterInterleavedUpdate),
        )
    }

    @Test
    fun anUnregisteredCapabilitySnapshotUsesTheCurrentRoute() {
        val registry = PlaybackPlanningSnapshotRegistry(maxSize = 1)
        val currentRoute = AudioPlaybackRouteSnapshot(
            sinkType = "speaker",
            routeGeneration = 3,
            capabilities = AudioPassthroughCapabilities(),
        )

        assertEquals(
            currentRoute,
            registry.resolve(ClientCodecCapabilities(), currentRoute),
        )
    }
}
