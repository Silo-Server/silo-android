package org.siloserver.silo.tv.ui.screens.watchtogether

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvWatchTogetherMenuEntrySourceTest {
    private val shell = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt",
    ).readText()
    private val dialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherMenuEntryDialog.kt",
    ).readText()

    @Test
    fun profileRowIsImmediatelyAfterRequestsAndBeforeSettings() {
        val profile = shell.substringAfter("private fun TvProfileDropdown(")
        val watch = profile.indexOf("label = \"Watch Together\"")
        val requests = profile.indexOf("label = \"Requests\"")
        val settings = profile.indexOf("label = \"Settings\"")
        val watchRow = profile.lastIndexOf("ProfileDropdownRow(", startIndex = watch)
        assertTrue(watch >= 0)
        assertTrue(requests >= 0)
        assertTrue(settings >= 0)
        assertTrue(watchRow >= 0)
        assertTrue(requests < watch)
        assertTrue(watch < settings)
        assertFalse(
            profile.substring(
                startIndex = requests + "label = \"Requests\"".length,
                endIndex = watchRow,
            ).contains("ProfileDropdownRow("),
        )
        assertTrue(shell.contains("CLIENT_WATCH_TOGETHER_SURFACE_ENABLED"))
    }

    @Test
    fun popupOwnsFocusAndBackRestoresProfileFocus() {
        assertTrue(dialog.contains("PopupProperties("))
        assertTrue(dialog.contains("focusable = true"))
        assertTrue(dialog.contains("rememberTvDialogInitialFocus(initialFocus)"))
        assertTrue(shell.contains("focusState.closeProfileMenuForContent()"))
        assertTrue(shell.contains("focusState.dismissProfileMenu()"))
    }

    @Test
    fun menuSurfaceUsesExistingControllerAndNoCredentials() {
        assertTrue(shell.contains("watchTogetherViewModel.createEmptyVoteRoom()"))
        assertTrue(shell.contains("watchTogetherViewModel.resumeCurrentRoom()"))
        assertTrue(shell.contains("TvJoinCodeDialog("))
        listOf("Resume current room", "Host a room", "Join by code").forEach { label ->
            assertTrue(dialog.contains(label))
        }
        assertFalse(dialog.contains("WatchTogetherApi"))
        assertFalse(dialog.contains("room_token"))
        assertFalse(dialog.contains("roomAccessToken"))
        assertFalse(dialog.contains("Authorization"))
        assertFalse(dialog.contains("CleartextOriginConsent"))
        assertFalse(dialog.contains("HttpClient"))
        assertTrue(shell.contains("error = watchTogetherState.error"))
        assertTrue(dialog.contains("error?.let"))
    }

    @Test
    fun initialActionPrefersResumeOnlyWhenAvailable() {
        assertEquals(
            TvWatchTogetherMenuInitialAction.Resume,
            tvWatchTogetherMenuInitialAction(canResume = true),
        )
        assertEquals(
            TvWatchTogetherMenuInitialAction.Host,
            tvWatchTogetherMenuInitialAction(canResume = false),
        )
    }
}
