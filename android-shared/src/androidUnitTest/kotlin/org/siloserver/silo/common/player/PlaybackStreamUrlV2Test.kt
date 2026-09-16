package org.siloserver.silo.common.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackStreamUrlV2Test {
    @Test fun resolvesOnlyExplicitServerMountOrAbsoluteDelivery() {
        assertEquals("https://example.invalid/api/v2/stream/session/file", resolvePlaybackStreamUrl("https://example.invalid/", "/api/v2/stream/session/file"))
        assertEquals("https://delivery.invalid/signed/stream", resolvePlaybackStreamUrl("https://example.invalid", "https://delivery.invalid/signed/stream"))
        assertEquals("content://downloads/1", resolvePlaybackStreamUrl("https://example.invalid", "content://downloads/1"))
        assertEquals("https://example.invalid/stream/session/file", resolvePlaybackStreamUrl("https://example.invalid", "/stream/session/file"))
    }
}
