package org.siloserver.silo.android.ui.screens.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.screens.auth.AuthColors
import org.siloserver.silo.android.ui.screens.auth.AuthErrorBanner
import org.siloserver.silo.android.ui.screens.auth.SiloButton
import org.siloserver.silo.android.ui.screens.auth.SiloTextField
import org.siloserver.silo.common.ui.components.ProfileAvatarRef
import org.siloserver.silo.model.profile.displayProfileQualityPreference
import org.koin.compose.viewmodel.koinViewModel

/**
 * Form for editing an existing profile.
 *
 * @param profileId The ID of the profile to edit.
 * @param onNavigateBack Called when the user presses the back arrow or save succeeds.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen(
    profileId: String,
    onNavigateBack: () -> Unit,
    viewModel: EditProfileViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Load the profile data once.
    LaunchedEffect(profileId) {
        viewModel.loadProfile(profileId)
    }

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) {
            viewModel.onSaveSuccessConsumed()
            onNavigateBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AuthColors.Background),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Edit Profile",
                    fontWeight = FontWeight.SemiBold,
                    color = AuthColors.OnBackground,
                )
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = AuthColors.OnBackground,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AuthColors.Background),
        )

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = AuthColors.Primary)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 24.dp),
            ) {
                state.error?.let { error ->
                    AuthErrorBanner(message = error)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // -- Avatar selection --
                SectionHeader("Avatar")

                ProfileAvatar(
                    avatar = ProfileAvatarRef(state.selectedAvatar, state.selectedAvatarUrl),
                    name = state.name.ifBlank { "?" },
                    size = 80.dp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                Spacer(modifier = Modifier.height(12.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (avatarRef in AvatarOptions.presets) {
                        AvatarPickerItem(
                            avatarRef = avatarRef,
                            isSelected = state.selectedAvatar == avatarRef,
                            onClick = { viewModel.onAvatarSelected(avatarRef) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // -- Name --
                SiloTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChanged,
                    label = "Profile Name",
                )

                Spacer(modifier = Modifier.height(24.dp))

                // -- Child profile toggle --
                SwitchRow(
                    label = "Child Profile",
                    checked = state.isChild,
                    onCheckedChange = viewModel::onChildToggled,
                    subtitle = "Restricts content to kid-friendly ratings",
                )

                if (state.isChild) {
                    Spacer(modifier = Modifier.height(16.dp))
                    DropdownField(
                        label = "Max Content Rating",
                        selected = state.maxContentRating ?: "PG",
                        options = CONTENT_RATINGS,
                        onSelected = viewModel::onContentRatingSelected,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // -- PIN --
                SwitchRow(
                    label = "Require PIN",
                    checked = state.pinEnabled,
                    onCheckedChange = viewModel::onPinToggled,
                )

                if (state.pinEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SiloTextField(
                        value = state.pin,
                        onValueChange = viewModel::onPinChanged,
                        label = "New 4-Digit PIN (leave blank to keep current)",
                        keyboardType = KeyboardType.NumberPassword,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // -- Quality preference --
                DropdownField(
                    label = "Quality Preference",
                    selected = displayProfileQualityPreference(state.qualityPreference),
                    options = QUALITY_OPTIONS,
                    onSelected = viewModel::onQualitySelected,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // -- Subtitle mode --
                DropdownField(
                    label = "Subtitles",
                    selected = state.subtitleMode?.replace("_", " ")
                        ?.replaceFirstChar { it.uppercaseChar() }
                        ?: "Off",
                    options = SUBTITLE_MODES,
                    onSelected = viewModel::onSubtitleModeSelected,
                )

                Spacer(modifier = Modifier.height(32.dp))

                SiloButton(
                    text = "Save Changes",
                    onClick = viewModel::onSaveClick,
                    isLoading = state.isSaving,
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
