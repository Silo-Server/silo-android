package org.siloserver.silo.tv.ui.screens.profiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * TV "Edit profile" form. Mirrors the phone's EditProfileScreen and calls the
 * same [org.siloserver.silo.repository.ProfileRepository] through
 * [TvEditProfileViewModel]. The profile id is supplied as a Koin parameter
 * (mirrors how TvItemDetailViewModel takes contentId) so the VM can load on init.
 *
 * @param profileId The id of the profile to edit.
 * @param onSaved Called after a successful save (host pops back to selection).
 */
@Composable
fun TvEditProfileScreen(
    profileId: String,
    onSaved: () -> Unit,
    viewModel: TvEditProfileViewModel = koinViewModel(
        key = "tv-edit-profile-$profileId",
        parameters = { parametersOf(profileId) },
    ),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) {
            viewModel.onSaveSuccessConsumed()
            onSaved()
        }
    }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TvLoadingScreen()
        }
        return
    }

    TvProfileForm(
        state = TvProfileFormState(
            title = "Edit Profile",
            subtitle = "Pick a look and update this profile.",
            name = state.name,
            selectedAvatar = state.selectedAvatar,
            selectedAvatarUrl = state.selectedAvatarUrl,
            avatarStyleId = state.avatarStyleId,
            selectedAvatarSeed = state.selectedAvatarSeed,
            avatarBatch = state.avatarBatch,
            isChild = state.isChild,
            maxContentRating = state.maxContentRating,
            pinEnabled = state.pinEnabled,
            pin = state.pin,
            qualityPreference = state.qualityPreference,
            subtitleMode = state.subtitleMode,
            pinHelper = "New 4-Digit PIN (leave blank to keep)",
            submitLabel = "Save Changes",
            isSubmitting = state.isSaving,
            error = state.error,
        ),
        callbacks = TvProfileFormCallbacks(
            onNameChanged = viewModel::onNameChanged,
            onAvatarSelected = viewModel::onAvatarSelected,
            onAvatarStyleSelected = viewModel::onAvatarStyleSelected,
            onAvatarPresetSelected = viewModel::onAvatarPresetSelected,
            onAvatarShuffle = viewModel::onAvatarShuffle,
            onChildToggled = viewModel::onChildToggled,
            onContentRatingSelected = viewModel::onContentRatingSelected,
            onPinToggled = viewModel::onPinToggled,
            onPinChanged = viewModel::onPinChanged,
            onQualitySelected = viewModel::onQualitySelected,
            onSubtitleModeSelected = viewModel::onSubtitleModeSelected,
            onSubmit = viewModel::onSaveClick,
        ),
    )
}
