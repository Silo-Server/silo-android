package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Approving a device login is an irreversible grant of access to someone else's
 * session, so what makes it available is worth pinning down.
 */
class DevicePairingUiStateTest {

    private fun lookup() = DeviceLoginLookupResponse(
        status = "pending",
        userCode = "ABCD-1234",
        matchCode = "42",
        deviceName = "Living Room TV",
        devicePlatform = "tvOS",
        ipAddressHint = "192.0.2.10",
    )

    @Test
    fun `a deep link cannot be approved before its lookup resolves`() {
        // The regression this pins: a silo://device?token=… link arrives with
        // the token already set and starts its lookup automatically. Gating on
        // "there is an identifier to submit" made Approve live from the first
        // frame — before the device name, IP or match code existed to show, so
        // the viewer would have been approving a request they could not see.
        val state = DevicePairingUiState(token = "tok_abc")
        assertFalse(state.canDecide)
    }

    @Test
    fun `a typed code cannot be approved before its lookup resolves`() {
        assertFalse(DevicePairingUiState(code = "ABCD-1234").canDecide)
    }

    @Test
    fun `a resolved lookup can be approved`() {
        assertTrue(DevicePairingUiState(token = "tok_abc", lookup = lookup()).canDecide)
    }

    @Test
    fun `a failed lookup cannot be approved even though the token survives`() {
        // The error path clears the lookup and keeps the identifier, which is
        // exactly the state reached when the server calls the request invalid
        // or expired. Approving it anyway was possible before.
        val state = DevicePairingUiState(
            token = "tok_abc",
            lookup = null,
            error = DevicePairingError.Expired,
        )
        assertFalse(state.canDecide)
    }

    @Test
    fun `a decision already in flight cannot be submitted again`() {
        val state = DevicePairingUiState(
            token = "tok_abc",
            lookup = lookup(),
            isSubmitting = true,
        )
        assertFalse(state.canDecide)
    }

    @Test
    fun `an initial lookup cannot be approved while it is still running`() {
        assertFalse(DevicePairingUiState(token = "tok_abc", isLoading = true).canDecide)
    }

    @Test
    fun `a refresh over an already-resolved lookup stays approvable`() {
        // isLoading is not itself a gate. Pressing Check on a resolved request
        // keeps the details on screen, so the decision the viewer can see is
        // still the decision they would be making.
        val state = DevicePairingUiState(
            token = "tok_abc",
            lookup = lookup(),
            isLoading = true,
        )
        assertTrue(state.canDecide)
    }
}
