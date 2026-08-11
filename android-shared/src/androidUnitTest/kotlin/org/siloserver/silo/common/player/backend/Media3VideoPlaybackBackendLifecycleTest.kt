package org.siloserver.silo.common.player.backend

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Media3VideoPlaybackBackendLifecycleTest {
    private val sourceFile = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/common/player/backend/Media3VideoPlaybackBackend.kt",
    )

    @Test
    fun externalSubtitleBeforeMountIsARecoverableNotReadyResult() {
        val methodBody = sourceFile.readText()
            .substringAfter("override fun selectSubtitle(")
            .substringBefore("override fun selectMountedSubtitle(")

        assertTrue(methodBody.contains("track?.subtitle != null && mountedSpec == null"))
        assertTrue(methodBody.contains("return false"))
        assertFalse(methodBody.contains("error("))
    }
}
