package org.siloserver.silo.android.ui.screens.auth

import org.siloserver.silo.model.auth.SignInOptions
import org.siloserver.silo.model.auth.SignInProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Not you?" on the approval card always shows; its wording follows what the
 * next sign-in offers (silo-apple `TVApprovalAccountSwitch`).
 */
class ApprovalAccountSwitchTest {
    private val keycloak = SignInProvider(
        id = "kc",
        displayName = "Keycloak",
        mode = SignInProvider.Mode.OAuth,
        isDefault = false,
        iconUrl = null,
        installationId = "5",
        nativeStartPath = "/api/v2/auth/oauth/5/native/start",
    )

    @Test
    fun theWordingFollowsTheServersSignInOptions() {
        assertEquals(ApprovalAccountSwitch.SignOut, ApprovalAccountSwitch.of(null), "options couldn't be read")
        assertEquals(ApprovalAccountSwitch.SwitchAccount, ApprovalAccountSwitch.of(SignInOptions.PasswordOnly))
        val provider = SignInOptions(oauthProviders = listOf(keycloak), showPasswordForm = true, directoryProvider = null)
        assertEquals(ApprovalAccountSwitch.SignOut, ApprovalAccountSwitch.of(provider))
        assertEquals(ApprovalAccountSwitch.ChooseAccount, ApprovalAccountSwitch.of(provider.copy(selectAccount = true)))
    }
}
