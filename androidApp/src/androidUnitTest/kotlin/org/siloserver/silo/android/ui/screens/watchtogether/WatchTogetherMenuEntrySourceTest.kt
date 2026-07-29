package org.siloserver.silo.android.ui.screens.watchtogether

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTogetherMenuEntrySourceTest {
    private fun source(path: String): String {
        val moduleRelative = File("src/androidMain/kotlin/$path")
        val projectRelative = File("androidApp/src/androidMain/kotlin/$path")
        return (moduleRelative.takeIf(File::exists) ?: projectRelative).readText()
    }

    private val topBar = source("org/siloserver/silo/android/ui/components/MainAppTopBar.kt")
    private val home = source("org/siloserver/silo/android/ui/screens/home/HomeScreen.kt")
    private val libraries = source("org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt")
    private val main = source("org/siloserver/silo/android/ui/screens/MainScreen.kt")
    private val menuSheet = source(
        "org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherMenuEntrySheet.kt",
    )

    @Test
    fun everyPhoneProfileMenuPlacesWatchTogetherAfterRequestsAndBeforeSettings() {
        listOf(topBar, home, libraries).forEach { text ->
            val watch = text.indexOf("Text(\"Watch Together\")")
            val requests = text.indexOf("Text(\"Requests\")")
            val settings = text.indexOf("Text(\"Settings\")")
            assertTrue(watch >= 0)
            assertTrue(requests < 0 || requests < watch)
            assertTrue(watch < settings)
            if (requests >= 0) {
                val watchMenuItem = text.lastIndexOf("DropdownMenuItem(", watch)
                assertTrue(watchMenuItem > requests)
                assertFalse(
                    text.substring(
                        startIndex = requests + "Text(\"Requests\")".length,
                        endIndex = watchMenuItem,
                    ).contains("DropdownMenuItem("),
                )
            }
        }
    }

    @Test
    fun mainShellOwnsOneTransientEntrySheet() {
        assertTrue(main.contains("var showWatchTogetherEntry by rememberSaveable"))
        assertTrue(main.contains("WatchTogetherMenuEntrySheet("))
        assertTrue(main.contains("CLIENT_WATCH_TOGETHER_SURFACE_ENABLED"))
        assertTrue(main.contains("onWatchTogetherClick = watchTogetherMenuAction"))
    }

    @Test
    fun sheetUsesOnlyTheExistingControllerAndNeverHandlesCredentials() {
        assertTrue(menuSheet.contains("viewModel.hostEmptyVoteRoom()"))
        assertTrue(menuSheet.contains("viewModel.resumeCurrentRoom()"))
        assertTrue(menuSheet.contains("viewModel.joinByCode(code)"))
        listOf("Resume current room", "Host a room", "Join by code").forEach { label ->
            assertTrue(menuSheet.contains(label))
        }
        assertFalse(menuSheet.contains("WatchTogetherApi"))
        assertFalse(menuSheet.contains("room_token"))
        assertFalse(menuSheet.contains("roomAccessToken"))
        assertFalse(menuSheet.contains("Authorization"))
        assertFalse(menuSheet.contains("CleartextOriginConsent"))
        assertFalse(menuSheet.contains("HttpClient"))
        assertTrue(menuSheet.contains("state.error"))
    }
}
