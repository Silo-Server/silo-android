package org.siloserver.silo.tv.ui.screens.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.tv.ui.components.TvFilterChip
import org.siloserver.silo.tv.ui.components.tvOutlinedTextFieldColors
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.viewmodel.ADMIN_USER_ROLES
import org.siloserver.silo.viewmodel.AdminUserEditViewModel
import org.siloserver.silo.viewmodel.roleDisplayName

/**
 * TV admin user create (userId == null) / edit form over the shared
 * [AdminUserEditViewModel] — the same form the phone uses. On create,
 * username/email/password are editable; on edit they're read-only except an
 * optional password reset. Role, enabled, library access and playback quotas
 * are editable in both modes. D-pad layout: a scrollable LazyColumn of focusable
 * text fields, role chips, toggle cards, and a Save button. Pops back via
 * [onSaved] when the save succeeds.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvAdminUserEditScreen(
    userId: Int?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: AdminUserEditViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val firstFieldFocus = remember { FocusRequester() }
    var editFormHasFocus by remember { mutableStateOf(false) }

    BackHandler(enabled = true) { onBack() }

    // Drive the UI from the ROUTE's userId, not VM state: on an edit-route load
    // failure the VM's userId stays null, and using state.isEditMode would
    // silently fall back to create-mode (and submit() would CREATE instead of
    // update). The route is the source of truth for which mode we're in.
    val isEdit = userId != null
    // In edit mode the user must finish loading before we let submit() run —
    // otherwise the VM (state.userId still null) would route to create.
    val editLoaded = state.userId != null

    LaunchedEffect(userId) { viewModel.load(userId) }
    LaunchedEffect(state.saveSuccess) { if (state.saveSuccess) onSaved() }
    // Focus the first EDITABLE control once content is ready: password in edit
    // (username/email are read-only there), username in create.
    LaunchedEffect(state.isLoading, isEdit) {
        if (!state.isLoading) {
            requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = firstFieldFocus::requestFocus,
                isFocused = { editFormHasFocus },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { editFormHasFocus = it.hasFocus }
            .background(MaterialTheme.colorScheme.background),
    ) {
        Text(
            text = if (isEdit) "Edit user" else "Create user",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                FormField(
                    label = "Username",
                    value = state.username,
                    onChange = viewModel::onUsernameChange,
                    enabled = !isEdit,
                    // Only the focus anchor when this field is editable (create).
                    focusRequester = firstFieldFocus.takeIf { !isEdit },
                )
            }
            item {
                FormField(
                    label = "Email",
                    value = state.email,
                    onChange = viewModel::onEmailChange,
                    enabled = !isEdit,
                    keyboardType = KeyboardType.Email,
                )
            }
            item {
                FormField(
                    label = if (isEdit) "Reset password (optional)" else "Password",
                    value = state.password,
                    onChange = viewModel::onPasswordChange,
                    isPassword = true,
                    keyboardType = KeyboardType.Password,
                    // First editable control in edit mode.
                    focusRequester = firstFieldFocus.takeIf { isEdit },
                )
            }

            item {
                Text(
                    text = "Role",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ADMIN_USER_ROLES.forEach { role ->
                        TvFilterChip(
                            text = roleDisplayName(role),
                            selected = state.role == role,
                            onClick = { viewModel.onRoleChange(role) },
                        )
                    }
                }
            }

            item {
                ToggleCard(
                    label = "Enabled",
                    subtitle = "Allow this account to sign in",
                    checked = state.enabled,
                    onToggle = { viewModel.onEnabledChange(!state.enabled) },
                )
            }

            item {
                FormField(
                    label = if (isEdit) {
                        "Library access ids (blank = unchanged)"
                    } else {
                        "Library access (comma-separated ids)"
                    },
                    value = state.libraryIdsText,
                    onChange = viewModel::onLibraryIdsChange,
                )
            }

            item {
                FormField(
                    label = "Max streams (blank = unlimited)",
                    value = state.maxStreamsText,
                    onChange = viewModel::onMaxStreamsChange,
                    keyboardType = KeyboardType.Number,
                )
            }
            item {
                FormField(
                    label = "Max transcodes (blank = unlimited)",
                    value = state.maxTranscodesText,
                    onChange = viewModel::onMaxTranscodesChange,
                    keyboardType = KeyboardType.Number,
                )
            }
            item {
                FormField(
                    label = "Max profiles (blank = unlimited)",
                    value = state.maxProfilesText,
                    onChange = viewModel::onMaxProfilesChange,
                    keyboardType = KeyboardType.Number,
                )
            }

            item {
                ToggleCard(
                    label = "Downloads allowed",
                    subtitle = "Permit offline downloads",
                    checked = state.downloadAllowed,
                    onToggle = { viewModel.onDownloadAllowedChange(!state.downloadAllowed) },
                )
            }
            item {
                ToggleCard(
                    label = "Download transcode allowed",
                    subtitle = "Permit transcoded downloads",
                    checked = state.downloadTranscodeAllowed,
                    onToggle = { viewModel.onDownloadTranscodeAllowedChange(!state.downloadTranscodeAllowed) },
                )
            }

            state.error?.let { error ->
                item {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            item {
                Button(
                    onClick = viewModel::submit,
                    // In edit mode, don't allow submit until the user has loaded
                    // — otherwise the VM (userId still null) would CREATE.
                    enabled = !state.isSaving && !state.isLoading && (!isEdit || editLoaded),
                    modifier = Modifier.widthIn(min = 240.dp),
                ) {
                    Text(
                        text = when {
                            state.isSaving -> "Saving…"
                            isEdit -> "Save changes"
                            else -> "Create user"
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
        ),
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 960.dp)
            .height(56.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        colors = tvOutlinedTextFieldColors(),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToggleCard(
    label: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        onClick = onToggle,
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 960.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (checked) "On" else "Off",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
