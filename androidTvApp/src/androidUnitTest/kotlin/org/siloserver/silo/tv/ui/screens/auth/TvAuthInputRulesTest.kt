package org.siloserver.silo.tv.ui.screens.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvAuthInputRulesTest {
    @Test
    fun credentialSubmitRequiresBothFieldsAndIdleState() {
        assertFalse(canSubmitTvCredentialLogin("", "password", isLoading = false))
        assertFalse(canSubmitTvCredentialLogin("jim", "", isLoading = false))
        assertFalse(canSubmitTvCredentialLogin("jim", "password", isLoading = true))
        assertTrue(canSubmitTvCredentialLogin("jim", "password", isLoading = false))
    }
}
