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
    private val topBarActions = source("org/siloserver/silo/android/ui/components/TopBarActions.kt")
    private val home = source("org/siloserver/silo/android/ui/screens/home/HomeScreen.kt")
    private val libraries = source("org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt")
    private val main = source("org/siloserver/silo/android/ui/screens/MainScreen.kt")
    private val profileMenu = source("org/siloserver/silo/android/ui/components/ProfileMenu.kt")
    private val menuSheet = source(
        "org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherMenuEntrySheet.kt",
    )

    /**
     * The three anchors — the floating top bar, Home's own chrome, Libraries'
     * own chrome — used to carry a hand-rolled copy of this menu each, which
     * is what this test originally had to check three times over. They now
     * all delegate to the one shared trailing cluster ([TabTopBarActions]),
     * which owns the single [ProfileMenu] anchor, so the ordering is asserted
     * once and the delegation is asserted here, which is what stops a fourth
     * copy drifting back in.
     */
    @Test
    fun everyPhoneProfileMenuPlacesWatchTogetherAfterRequestsAndBeforeSettings() {
        listOf(topBar, home, libraries).forEach { text ->
            assertTrue(text.contains("TabTopBarActions("))
        }
        assertTrue(topBarActions.contains("ProfileMenu("))
        listOf(topBar, topBarActions, home, libraries).forEach { text ->
            assertFalse(text.contains("\"Watch Together\""))
            assertFalse(text.contains("\"Watch together\""))
            assertFalse(text.contains("\"Switch Profile\""))
            assertFalse(text.contains("\"Switch profile\""))
        }

        val watch = profileMenu.indexOf("label = \"Watch together\"")
        val requests = profileMenu.indexOf("label = \"Requests\"")
        val settings = profileMenu.indexOf("label = \"Settings\"")
        assertTrue(watch >= 0)
        assertTrue(requests in 0 until watch)
        assertTrue(watch < settings)

        val watchMenuItem = profileMenu.lastIndexOf("SiloMenuItem(", watch)
        assertTrue(watchMenuItem > requests)
        assertFalse(
            profileMenu.substring(
                startIndex = requests + "label = \"Requests\"".length,
                endIndex = watchMenuItem,
            ).contains("SiloMenuItem("),
        )

        // Both entries stay behind their gate: `requests_enabled` on the
        // server for one, the client-side surface flag for the other.
        assertTrue(profileMenu.contains("if (onRequestsClick != null)"))
        assertTrue(profileMenu.contains("if (onWatchTogetherClick != null)"))
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
