package org.siloserver.silo.tv.ui.screens.profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.viewmodel.koinViewModel

/**
 * TV "Create profile" form. Mirrors the phone's CreateProfileScreen and calls
 * the same [org.siloserver.silo.repository.ProfileRepository] through
 * [TvCreateProfileViewModel].
 *
 * @param onNavigateBack Called when the user backs out without creating.
 * @param onProfileCreated Called after the profile is successfully created.
 */
@Composable
fun TvCreateProfileScreen(
    onNavigateBack: () -> Unit,
    onProfileCreated: () -> Unit,
    viewModel: TvCreateProfileViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.createSuccess) {
        if (state.createSuccess) {
            viewModel.onCreateSuccessConsumed()
            onProfileCreated()
        }
    }

    TvProfileForm(
        state = TvProfileFormState(
            title = "New Profile",
            name = state.name,
            selectedAvatar = state.selectedAvatar,
            avatarStyleId = state.avatarStyleId,
            selectedAvatarSeed = state.selectedAvatarSeed,
            avatarBatch = state.avatarBatch,
            isChild = state.isChild,
            maxContentRating = state.maxContentRating,
            pinEnabled = state.pinEnabled,
            pin = state.pin,
            qualityPreference = null,
            subtitleMode = state.subtitleMode,
            pinHelper = "4-Digit PIN",
            submitLabel = "Create Profile",
            isSubmitting = state.isLoading,
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
            onQualitySelected = {},
            onSubtitleModeSelected = viewModel::onSubtitleModeSelected,
            onSubmit = viewModel::onCreateClick,
            onCancel = onNavigateBack,
        ),
    )
}
