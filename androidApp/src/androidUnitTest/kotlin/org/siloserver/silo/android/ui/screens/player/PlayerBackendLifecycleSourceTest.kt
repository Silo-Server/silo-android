package org.siloserver.silo.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertTrue

class PlayerBackendLifecycleSourceTest {
    private val sourceFile = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt",
    )

    @Test
    fun backendOwnershipFollowsTheActualPlayerRatherThanPlaybackRouteState() {
        val source = sourceFile.readText()

        assertTrue(source.contains("val backendPlayer = sessionPlayer ?: mediaController"))
        assertTrue(source.contains("val videoBackend = remember(backendPlayer, backendFactory)"))
    }

    @Test
    fun subtitleRestorationWaitsForTheMatchingMediaMountGeneration() {
        val source = sourceFile.readText()

        assertTrue(
            source.contains("mountedMediaGeneration = uiState.mediaMountGeneration"),
            "the synchronous Media3 mount must publish the generation it applied",
        )
        assertTrue(
            source.contains("mountedMediaGeneration != uiState.mediaMountGeneration"),
            "subtitle restoration must not run against a predecessor media item",
        )
    }

    @Test
    fun v3ServerSidecarIsSelectedFromTheExistingMediaMount() {
        val source = sourceFile.readText()

        assertTrue(
            source.contains("this is SubtitleIdentity.ServerSidecar ||"),
            "a planned server sidecar must use stable mounted-track selection",
        )
        assertTrue(
            source.contains("subtitleIdentity = uiState.localSubtitleMountIdentity"),
            "the complete picker inventory must not be passed to Media3",
        )
    }
}
