package com.continuum.app.android.ui.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientSurfaceVisibilitySourceTest {
    private val mainScreen = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/MainScreen.kt",
    ).readText()
    private val settingsScreen = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/settings/SettingsScreen.kt",
    ).readText()
    private val accountSection = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/settings/AccountSection.kt",
    ).readText()
    private val itemDetailScreen = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/detail/ItemDetailScreen.kt",
    ).readText()

    @Test
    fun requestsRemainRoutableButHiddenFromMobileMenus() {
        assertTrue(mainScreen.contains("CLIENT_REQUESTS_SURFACE_ENABLED"))
        assertTrue(settingsScreen.contains("CLIENT_REQUESTS_SURFACE_ENABLED"))
        assertTrue(accountSection.contains("isRequestsVisible: Boolean = false"))
        assertTrue(accountSection.contains("if (isRequestsVisible)"))
        assertFalse(mainScreen.contains("onRequestsClick = { navController.navigate(Route.Requests.route) }"))
    }

    @Test
    fun watchTogetherCodeRemainsRoutableButHiddenFromMobileDetailOverflow() {
        assertTrue(itemDetailScreen.contains("CLIENT_WATCH_TOGETHER_SURFACE_ENABLED"))
        assertFalse(itemDetailScreen.contains("onWatchTogether = { onWatchTogether(detail.contentId, explicitFileId) }"))
    }
}
