package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertTrue

class TvPlayerBackendLifecycleSourceTest {
    private val sourceFile = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt",
    )

    @Test
    fun backendOwnershipFollowsTheActualPlayerRatherThanPlaybackRouteState() {
        val source = sourceFile.readText()

        assertTrue(source.contains("val backendPlayer = sessionPlayer ?: mediaController"))
        assertTrue(source.contains("val videoBackend = remember(backendPlayer, backendFactory)"))
    }

    @Test
    fun v3MountDoesNotAttachTheCompleteSubtitlePickerInventory() {
        val source = sourceFile.readText()

        assertTrue(source.contains("subtitleIdentity = state.pendingSubtitleIdentity"))
    }
}
