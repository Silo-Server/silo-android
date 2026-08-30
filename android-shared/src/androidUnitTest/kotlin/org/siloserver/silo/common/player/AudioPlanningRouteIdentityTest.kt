package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.PlaybackOutputContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioPlanningRouteIdentityTest {
    @Test
    fun spatializerCallbacksDoNotCreateANewPhysicalRoute() {
        val base = AudioPassthroughCapabilities(
            passthroughCodecs = emptyList(),
            spatializerEnabled = false,
            maxChannels = 10,
        )

        assertEquals(
            audioPlanningRouteIdentity("bluetooth", listOf("headset"), base),
            audioPlanningRouteIdentity(
                "bluetooth",
                listOf("headset"),
                base.copy(spatializerEnabled = true),
            ),
        )
    }

    @Test
    fun physicalDeviceOrPlanningCapabilityChangesCreateANewRoute() {
        val base = AudioPassthroughCapabilities(maxChannels = 2)
        val original = audioPlanningRouteIdentity("bluetooth", listOf("headset-a"), base)

        assertFalse(original == audioPlanningRouteIdentity("bluetooth", listOf("headset-b"), base))
        assertFalse(
            original == audioPlanningRouteIdentity(
                "bluetooth",
                listOf("headset-a"),
                base.copy(maxChannels = 6),
            ),
        )
    }

    @Test
    fun generationOnlyOutputChangePreservesFallbackHistoryIdentity() {
        val original = PlaybackOutputContext(
            sinkType = "bluetooth",
            outputContextId = "68",
            audioPassthrough = AudioPassthroughCapabilities(
                spatializerEnabled = true,
                maxChannels = 10,
            ),
        )

        assertTrue(
            original.hasSamePlanningRouteAs(
                original.copy(
                    outputContextId = "69",
                    audioPassthrough = original.audioPassthrough?.copy(spatializerEnabled = false),
                ),
            ),
        )
        assertFalse(original.hasSamePlanningRouteAs(original.copy(sinkType = "built_in")))
    }
}
