package com.continuum.app.tv.ui.screens.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvServerUrlKeyboardStateTest {
    @Test
    fun urlKeyboardAppendsUrlCharacters() {
        var value = "https://"

        for (key in listOf("l", "i", "b", ".", "s", "t", "r", "m", ".", "c", "a", "f", "e")) {
            value = applyTvServerUrlKeyboardAction(value, TvServerUrlKeyboardAction.Insert(key))
        }

        assertEquals("https://lib.strm.cafe", value)
    }

    @Test
    fun urlKeyboardBackspaceAndClearAreSafeOnEmptyValues() {
        assertEquals("", applyTvServerUrlKeyboardAction("", TvServerUrlKeyboardAction.Backspace))
        assertEquals("abc", applyTvServerUrlKeyboardAction("abcd", TvServerUrlKeyboardAction.Backspace))
        assertEquals("", applyTvServerUrlKeyboardAction("https://lib.strm.cafe", TvServerUrlKeyboardAction.Clear))
    }

    @Test
    fun urlKeyboardConnectRequiresNonBlankAddressAndIdleState() {
        assertFalse(canSubmitTvServerUrl("", isLoading = false))
        assertFalse(canSubmitTvServerUrl("   ", isLoading = false))
        assertFalse(canSubmitTvServerUrl("lib.strm.cafe", isLoading = true))
        assertTrue(canSubmitTvServerUrl("lib.strm.cafe", isLoading = false))
    }
}
