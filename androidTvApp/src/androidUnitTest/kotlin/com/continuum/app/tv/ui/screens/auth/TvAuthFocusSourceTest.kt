package com.continuum.app.tv.ui.screens.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvAuthFocusSourceTest {
    private val serverSetupSource = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/auth/TvServerSetupScreen.kt",
    ).readText()
    private val loginSource = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/auth/TvLoginScreen.kt",
    ).readText()
    private val setupSource = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/auth/TvSetupScreen.kt",
    ).readText()
    private val signupSource = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/auth/TvSignupScreen.kt",
    ).readText()

    @Test
    fun serverSetupAnchorsInitialFocusSafelyOnManualEntry() {
        assertTrue(serverSetupSource.contains("LaunchedEffect(isActivePairing)"))
        assertTrue(serverSetupSource.contains("if (!isActivePairing) runCatching { focusRequester.requestFocus() }"))
    }

    @Test
    fun loginPasswordToggleFocusRequestsAreCrashGuarded() {
        assertTrue(loginSource.contains("runCatching { usernameFocus.requestFocus() }"))
        assertTrue(loginSource.contains("runCatching { usePasswordFocus.requestFocus() }"))
    }

    @Test
    fun setupAndSignupInitialFieldFocusRequestsAreCrashGuarded() {
        assertTrue(setupSource.contains("LaunchedEffect(Unit) { runCatching { usernameFocus.requestFocus() } }"))
        assertTrue(signupSource.contains("LaunchedEffect(Unit) { runCatching { usernameFocus.requestFocus() } }"))
    }
}
