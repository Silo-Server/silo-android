package com.continuum.app.tv.ui.screens.search

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSearchLayoutReadabilityTest {
    private val source = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/search/TvSearchScreen.kt",
    ).readText()
    private val keyboardSource = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/search/TvSearchKeyboard.kt",
    ).takeIf { it.exists() }?.readText().orEmpty()

    @Test
    fun searchHeaderStartsBelowTopMenuChrome() {
        assertTrue(source.contains("top = TvTopMenuLayout.contentTopInset"))
    }

    @Test
    fun searchSupportsPredictableDpadHandoffFromFieldToResults() {
        assertTrue(source.contains(".focusProperties { down = firstFilterChipFocusRequester }"))
        assertTrue(source.contains("Modifier.focusProperties { down = firstResultFocusRequester }"))
        assertTrue(source.contains("up = firstFilterChipFocusRequester"))
        assertTrue(source.contains("firstResultFocusRequester.requestFocus()"))
    }

    @Test
    fun searchUsesSiloOwnedKeyboardInsteadOfPlatformIme() {
        assertTrue(source.contains("SearchDisplayField("))
        assertTrue(source.contains("SiloSearchKeyboard("))
        assertFalse(source.contains("OutlinedTextField("))
        assertFalse(source.contains("KeyboardOptions("))
        assertFalse(source.contains("KeyboardActions("))
        assertFalse(source.contains("LocalSoftwareKeyboardController"))
    }

    @Test
    fun searchFieldKeepsDownForFocusNavigation() {
        assertFalse(keyboardSource.contains("Key.DirectionDown"))
    }

    @Test
    fun searchFieldOpensKeyboardOnlyFromActivationKeys() {
        assertTrue(keyboardSource.contains("shouldOpenSearchKeyboard(event)"))
        assertTrue(keyboardSource.contains("event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp"))
        assertTrue(keyboardSource.contains("AndroidKeyEvent.KEYCODE_DPAD_CENTER"))
        assertTrue(keyboardSource.contains("AndroidKeyEvent.KEYCODE_ENTER"))
    }
}
