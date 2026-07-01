package com.continuum.app.tv.ui.screens.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvCredentialKeyboardStateTest {
    @Test
    fun credentialKeyboardSupportsUppercaseAndSymbols() {
        var value = ""

        for (key in listOf("A", "m", "s", "t", "e", "r", "d", "a", "m", "1", "2", "3", "!")) {
            value = applyTvCredentialKeyboardAction(value, TvCredentialKeyboardAction.Insert(key))
        }

        assertEquals("Amsterdam123!", value)
    }

    @Test
    fun credentialKeyboardBackspaceAndClearAreSafe() {
        assertEquals("", applyTvCredentialKeyboardAction("", TvCredentialKeyboardAction.Backspace))
        assertEquals("ji", applyTvCredentialKeyboardAction("jim", TvCredentialKeyboardAction.Backspace))
        assertEquals("", applyTvCredentialKeyboardAction("Amsterdam123!", TvCredentialKeyboardAction.Clear))
    }

    @Test
    fun loginSubmitRequiresBothFieldsAndIdleState() {
        assertFalse(canSubmitTvCredentialLogin("", "password", isLoading = false))
        assertFalse(canSubmitTvCredentialLogin("jim", "", isLoading = false))
        assertFalse(canSubmitTvCredentialLogin("jim", "password", isLoading = true))
        assertTrue(canSubmitTvCredentialLogin("jim", "password", isLoading = false))
    }

    @Test
    fun passwordDisplayMasksOnlyWhenPasswordIsHidden() {
        assertEquals("jim", credentialDisplayValue("jim", isPassword = false, passwordVisible = false))
        assertEquals("Amsterdam123!", credentialDisplayValue("Amsterdam123!", isPassword = true, passwordVisible = true))
        assertEquals("*************", credentialDisplayValue("Amsterdam123!", isPassword = true, passwordVisible = false))
    }
}
