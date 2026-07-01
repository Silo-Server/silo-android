package com.continuum.app.tv.ui.screens.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvAuthFocusSourceTest {
    private val serverSetupSource = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/auth/TvServerSetupScreen.kt",
    ).readText()
    private val loginSource = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/auth/TvLoginScreen.kt",
    ).readText()
    private val credentialKeyboardSource = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/auth/TvCredentialKeyboard.kt",
    ).readText()
    private val ansiKeyboardSource = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/components/TvAnsiKeyboard.kt",
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
    fun loginUsesSiloOwnedCredentialKeyboardInsteadOfPlatformIme() {
        assertTrue(loginSource.contains("CredentialDisplayField("))
        assertTrue(loginSource.contains("SiloCredentialKeyboard("))
        assertFalse(loginSource.contains("OutlinedTextField("))
    }

    @Test
    fun credentialFieldsOpenKeyboardOnRemoteKeyDownOrUp() {
        assertTrue(credentialKeyboardSource.contains("event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp"))
    }

    @Test
    fun credentialFieldsDoNotTreatDownAsActivation() {
        assertFalse(credentialKeyboardSource.contains("Key.DirectionDown"))
    }

    @Test
    fun credentialFieldsInspectRemoteKeysBeforeFocusableCanConsumeCenter() {
        val keyHandlerIndex = credentialKeyboardSource.indexOf(".onPreviewKeyEvent { event ->")
        val focusableIndex = credentialKeyboardSource.indexOf(
            ".focusable(enabled = enabled, interactionSource = interactionSource)",
        )

        assertTrue(keyHandlerIndex in 0 until focusableIndex)
    }

    @Test
    fun credentialFieldsAlsoHandleFocusedKeyEventsForCenterPresses() {
        assertTrue(credentialKeyboardSource.contains(".onKeyEvent { event ->"))
        assertTrue(credentialKeyboardSource.contains("AndroidKeyEvent.KEYCODE_DPAD_CENTER"))
    }

    @Test
    fun loginResetsScrollWhenCredentialKeyboardOpens() {
        assertTrue(loginSource.contains("loginScrollState.animateScrollTo(0)"))
    }

    @Test
    fun credentialKeyboardUsesSharedAnsiKeyboard() {
        assertTrue(credentialKeyboardSource.contains("TvAnsiKeyboard("))
        assertTrue(credentialKeyboardSource.contains("primaryLabel = if (field == TvCredentialField.Username) \"Next\" else \"Sign In\""))
        assertTrue(credentialKeyboardSource.contains("showPasswordVisibilityKey = field == TvCredentialField.Password"))
        assertTrue(ansiKeyboardSource.contains("private val TvAnsiKeyboardKeyHeight = 28.dp"))
        assertTrue(ansiKeyboardSource.contains("private val TvAnsiKeyboardVerticalPadding = 8.dp"))
        assertTrue(ansiKeyboardSource.contains(".height(TvAnsiKeyboardKeyHeight)"))
    }

    @Test
    fun credentialKeyboardGetsSymbolsFromAnsiShiftedPairs() {
        assertTrue(ansiKeyboardSource.contains("insertKey(\"1\", shifted = \"!\")"))
        assertTrue(ansiKeyboardSource.contains("insertKey(\",\", shifted = \"<\")"))
        assertTrue(ansiKeyboardSource.contains("insertKey(\".\", shifted = \">\")"))
        assertTrue(ansiKeyboardSource.contains("insertKey(\"/\", shifted = \"?\")"))
        assertTrue(ansiKeyboardSource.contains("if (shifted && key.type == TvAnsiKeyType.Insert) shifted = false"))
        assertFalse(credentialKeyboardSource.contains("TvCredentialSpecialKey.Symbols"))
        assertFalse(credentialKeyboardSource.contains("#+="))
    }

    @Test
    fun setupAndSignupInitialFieldFocusRequestsAreCrashGuarded() {
        assertTrue(setupSource.contains("LaunchedEffect(Unit) { runCatching { usernameFocus.requestFocus() } }"))
        assertTrue(signupSource.contains("LaunchedEffect(Unit) { runCatching { usernameFocus.requestFocus() } }"))
    }
}
