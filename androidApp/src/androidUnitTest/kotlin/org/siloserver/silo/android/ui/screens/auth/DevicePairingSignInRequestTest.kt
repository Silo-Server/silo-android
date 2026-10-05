package org.siloserver.silo.android.ui.screens.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import org.siloserver.silo.viewmodel.DeviceApprovalServer
import org.siloserver.silo.viewmodel.DevicePairingError
import org.siloserver.silo.viewmodel.DevicePairingUiState

/** "Sign in" after a 401 targets the server the code was looked up on, and keeps the code. */
class DevicePairingSignInRequestTest {
    private val home = DeviceApprovalServer("a", "https://home.example", "Home", isActive = true)
    private val cabin = DeviceApprovalServer("b", "https://cabin.example", "Cabin")

    @Test
    fun signInAfterA401OnAChosenNonActiveServerTargetsThatServerWithTheTypedCode() {
        val state = DevicePairingUiState(
            code = "48217730",
            servers = listOf(home, cabin),
            selectedServerId = "b",
            error = DevicePairingError.SignInFirst,
        )
        assertEquals("b" to "48217730", state.signInRequest())
    }

    @Test
    fun noTypedCodeCarriesNone() {
        val state = DevicePairingUiState(servers = listOf(home), selectedServerId = "a")
        assertEquals("a" to null, state.signInRequest())
    }
}
