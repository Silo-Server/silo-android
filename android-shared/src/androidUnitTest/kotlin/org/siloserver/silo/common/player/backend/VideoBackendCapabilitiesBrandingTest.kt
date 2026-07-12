package org.siloserver.silo.common.player.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoBackendCapabilitiesBrandingTest {
    @Test
    fun `Media3 backend is presented as SiloPlayer`() {
        val capabilities = VideoBackendCapabilities.media3()

        assertEquals("SiloPlayer", capabilities.displayName)
        assertEquals("SiloPlayer", capabilities.route.displayName)
    }
}
