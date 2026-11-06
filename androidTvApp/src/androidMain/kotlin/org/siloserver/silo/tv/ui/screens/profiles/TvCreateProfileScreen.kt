package org.siloserver.silo.tv.ui.screens.profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.text

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
            title = stringResource(CommonR.string.newProfile_title),
            subtitle = stringResource(CommonR.string.newProfile_pickLook),
            name = state.name,
            selectedAvatar = state.selectedAvatar,
            avatarStyleId = state.avatarStyleId,
            selectedAvatarSeed = state.selectedAvatarSeed,
            avatarBatch = state.avatarBatch,
            access = state.access,
            libraries = state.libraries,
            pinEnabled = state.pinEnabled,
            pin = state.pin,
            qualityPreference = null,
            subtitleMode = state.subtitleMode,
            pinHelper = stringResource(CommonR.string.profiles_form_pin),
            submitLabel = stringResource(CommonR.string.newProfile_create),
            isSubmitting = state.isLoading,
            error = state.error?.text(),
        ),
        callbacks = TvProfileFormCallbacks(
            onNameChanged = viewModel::onNameChanged,
            onAvatarSelected = viewModel::onAvatarSelected,
            onAvatarStyleSelected = viewModel::onAvatarStyleSelected,
            onAvatarPresetSelected = viewModel::onAvatarPresetSelected,
            onAvatarShuffle = viewModel::onAvatarShuffle,
            onChildToggled = viewModel::onChildToggled,
            onContentRatingSelected = viewModel::onContentRatingSelected,
            onLibraryRestrictionsToggled = viewModel::onLibraryRestrictionsToggled,
            onLibraryToggled = viewModel::onLibraryToggled,
            onRetryLibraries = viewModel::loadLibraries,
            onPinToggled = viewModel::onPinToggled,
            onPinChanged = viewModel::onPinChanged,
            onQualitySelected = {},
            onSubtitleModeSelected = viewModel::onSubtitleModeSelected,
            onSubmit = viewModel::onCreateClick,
            onCancel = onNavigateBack,
        ),
    )
}
