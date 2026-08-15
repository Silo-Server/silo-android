package org.siloserver.silo.android.ui.screens.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.screens.auth.AuthColors
import org.siloserver.silo.android.ui.screens.auth.AuthErrorBanner
import org.siloserver.silo.android.ui.screens.auth.SiloButton
import org.siloserver.silo.android.ui.screens.auth.SiloTextField
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.ui.components.ProfileAvatarRef

/**
 * Form for creating a new profile.
 *
 * @param onNavigateBack Called when the user presses the back arrow.
 * @param onProfileCreated Called after the profile is successfully created.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateProfileScreen(
    onNavigateBack: () -> Unit,
    onProfileCreated: () -> Unit,
    viewModel: CreateProfileViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.createSuccess) {
        if (state.createSuccess) {
            viewModel.onCreateSuccessConsumed()
            onProfileCreated()
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
                    text = "New Profile",
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

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            state.error?.let { error ->
                AuthErrorBanner(message = error)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // -- Avatar selection --
            SectionHeader("Avatar")

            // Preview
            ProfileAvatar(
                avatar = ProfileAvatarRef(state.selectedAvatar),
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

            // -- Content rating picker (visible for child profiles) --
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

            // -- PIN toggle + entry --
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
                    label = "4-Digit PIN",
                    keyboardType = KeyboardType.NumberPassword,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

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
                text = "Create Profile",
                onClick = viewModel::onCreateClick,
                isLoading = state.isLoading,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ---- Reusable internal composables ----

@Composable
internal fun SectionHeader(text: String) {
    // iOS phone field labels use siloCaption (12pt regular, secondary).
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        color = AuthColors.OnSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // iOS phone Toggle title: siloBody (14pt), onSurface.
            Text(
                text = label,
                fontSize = 14.sp,
                color = AuthColors.OnBackground,
            )
            if (subtitle != null) {
                // iOS phone Toggle subtitle: siloCaption (12pt), secondary.
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = AuthColors.OnSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AuthColors.Primary,
                checkedTrackColor = AuthColors.Primary.copy(alpha = 0.4f),
                uncheckedThumbColor = AuthColors.OnSurfaceVariant,
                uncheckedTrackColor = AuthColors.Surface,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DropdownField(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AuthColors.OnSurface,
                unfocusedTextColor = AuthColors.OnSurface,
                focusedBorderColor = AuthColors.FieldBorderFocused,
                unfocusedBorderColor = AuthColors.FieldBorder,
                focusedLabelColor = AuthColors.Primary,
                unfocusedLabelColor = AuthColors.OnSurfaceVariant,
                cursorColor = AuthColors.Primary,
                focusedContainerColor = AuthColors.Surface,
                unfocusedContainerColor = AuthColors.Surface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
