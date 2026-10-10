package org.siloserver.silo.tv.ui.screens.profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.koinInject
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.tv.ui.components.TvPinEntryDialog

/**
 * Asks for the primary profile's PIN again when a manage-mode call finds its
 * token stale (see [org.siloserver.silo.repository.HouseholdManagementSession]).
 * App-wide because the call can come from the picker, add, or edit screen.
 */
@Composable
fun TvHouseholdReverifyHost(profileRepository: ProfileRepository = koinInject()) {
    val session = profileRepository.householdManagement
    val prompt by session.reverify.collectAsState()
    prompt?.let {
        TvPinEntryDialog(
            profile = it.profile,
            onPinEntered = session::submitPin,
            onDismiss = session::cancelReverify,
            errorMessage = it.error,
            isVerifying = it.isVerifying,
            prompt = "Enter this PIN to manage profiles",
        )
    }
}
