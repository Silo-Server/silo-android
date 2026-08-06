package org.siloserver.silo.common.player.cast

import org.siloserver.silo.model.playback.playbackClientFeaturesV3
import kotlin.test.Test
import kotlin.test.assertFalse

class CastPlaybackPreparerTest {
    @Test
    fun castContextDoesNotAdvertiseThePreNeutralSidecarFeature() {
        assertFalse(
            "external_text_sidecar_set_v1" in
                playbackClientFeaturesV3(chromecastPlaybackContext("test")),
        )
    }
}
