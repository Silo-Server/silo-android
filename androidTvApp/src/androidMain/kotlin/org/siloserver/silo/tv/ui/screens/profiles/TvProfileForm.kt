package org.siloserver.silo.tv.ui.screens.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.ui.components.ProfileAvatarRef
import org.siloserver.silo.common.ui.components.isEmojiAvatar
import org.siloserver.silo.common.ui.components.profileAvatarDisplayText
import org.siloserver.silo.common.ui.components.rememberProfileAvatarImage
import org.siloserver.silo.tv.ui.components.TvAuroraBackdrop
import org.siloserver.silo.tv.ui.components.TvAuroraVariant
import org.siloserver.silo.tv.ui.components.TvHeroActionPill
import org.siloserver.silo.tv.ui.components.TvPillVariant
import org.siloserver.silo.tv.ui.components.TvTextInputDialog
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport

private val ProfileEditorHeaderPadding = 80.dp
private val ProfileEditorContentPadding = 80.dp
private val ProfileEditorPreviewWidth = 180.dp
private val ProfileEditorColumnGap = 40.dp
private val ProfileEditorColumnWidth = 584.dp
private val ProfileEditorGridHeight = 154.dp
private val ProfileEditorGridGap = 6.dp
private val ProfileStyleChipGap = 4.dp
private val ProfileEditorSectionGap = 8.dp
private val ProfileAvatarCellHeight = 46.dp
private val ProfileAvatarImageSize = 42.dp
private val ProfileTextEntryHeight = 52.dp

private enum class ProfileTextField {
    Name,
    Pin,
}

/**
 * Snapshot of the editable profile fields shared by create + edit. The TV
 * layout intentionally mirrors tvOS: profile creation is identity-first, with
 * avatar style/preset selection in the main flow rather than mobile-only
 * preference rows.
 */
data class TvProfileFormState(
    val title: String,
    val subtitle: String = "Pick a look and give it a name.",
    val name: String,
    val selectedAvatar: String?,
    /**
     * Server-supplied URL for [selectedAvatar] as it was LOADED, so an uploaded
     * avatar shows in the preview tile. Null for create, and ignored once the
     * picker moves the selection off the stored ref.
     */
    val selectedAvatarUrl: String? = null,
    val avatarStyleId: String,
    val selectedAvatarSeed: String?,
    val avatarBatch: Int,
    val isChild: Boolean,
    val maxContentRating: String?,
    val pinEnabled: Boolean,
    val pin: String,
    val qualityPreference: String?,
    val subtitleMode: String?,
    val pinHelper: String,
    val submitLabel: String,
    val isSubmitting: Boolean,
    val error: String?,
)

data class TvProfileFormCallbacks(
    val onNameChanged: (String) -> Unit,
    val onAvatarSelected: (String) -> Unit,
    val onAvatarStyleSelected: (String) -> Unit,
    val onAvatarPresetSelected: (TvProfileAvatarPresets.Preset) -> Unit,
    val onAvatarShuffle: () -> Unit,
    val onChildToggled: (Boolean) -> Unit,
    val onContentRatingSelected: (String) -> Unit,
    val onPinToggled: (Boolean) -> Unit,
    val onPinChanged: (String) -> Unit,
    val onQualitySelected: (String) -> Unit,
    val onSubtitleModeSelected: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onCancel: (() -> Unit)? = null,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvProfileForm(
    state: TvProfileFormState,
    callbacks: TvProfileFormCallbacks,
) {
    val previewAvatar = state.previewAvatarRef()
    val presets = remember(state.avatarStyleId, state.avatarBatch) {
        TvProfileAvatarPresets.batch(state.avatarStyleId, state.avatarBatch)
    }
    val activePresetSeed = state.selectedAvatarSeed
        ?: TvProfileAvatarPresets.parseRef(state.selectedAvatar)?.seed
        ?: presets.firstOrNull()?.seed
    val isInitialsStyle = state.avatarStyleId == "initials"
    val nameFocusRequester = remember { FocusRequester() }
    val pinFocusRequester = remember { FocusRequester() }
    val childFocusRequester = remember { FocusRequester() }
    val submitFocusRequester = remember { FocusRequester() }
    val formScrollState = rememberScrollState()
    var editingTextField by remember { mutableStateOf<ProfileTextField?>(null) }
    // Local validation feedback: without it, Save on a blank name is a silent
    // no-op. Keyed on the name so editing it clears the message.
    var blankNameError by remember(state.name) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        TvAuroraBackdrop(variant = TvAuroraVariant.Profile)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 60.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ProfileEditorHeaderPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state.title,
                        fontSize = 36.sp,
                        lineHeight = 40.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Text(
                        text = state.subtitle,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                callbacks.onCancel?.let { onCancel ->
                    TvHeroActionPill(
                        label = "Cancel",
                        icon = Icons.Filled.Close,
                        variant = TvPillVariant.Hollow,
                        heightOverride = 22.dp,
                        horizontalPaddingOverride = 10.dp,
                        labelStyle = MaterialTheme.typography.labelSmall,
                        onClick = onCancel,
                    )
                }
            }

            Spacer(modifier = Modifier.height(0.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = ProfileEditorContentPadding),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top,
            ) {
                TvProfilePreviewColumn(
                    avatar = previewAvatar,
                    name = state.name.ifBlank { "Your Name" },
                    hasPin = state.pinEnabled,
                    isChild = state.isChild,
                )

                Spacer(modifier = Modifier.width(ProfileEditorColumnGap))

                Column(
                    modifier = Modifier
                        .width(ProfileEditorColumnWidth)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(ProfileEditorSectionGap),
                ) {
                    TvProfileFormSection(title = "Style") {
                        Row(horizontalArrangement = Arrangement.spacedBy(ProfileStyleChipGap)) {
                            TvProfileAvatarPresets.styles.forEach { style ->
                                TvAvatarStyleChip(
                                    style = style,
                                    isSelected = style.id == state.avatarStyleId,
                                    onSelect = { callbacks.onAvatarStyleSelected(style.id) },
                                )
                            }
                        }
                    }

                    if (isInitialsStyle) {
                        TvProfileFormSection(title = "Avatar") {
                            TvInitialsHint()
                        }
                    } else {
                        TvProfileFormSection(
                            title = "Avatar",
                            trailing = {
                                TvHeroActionPill(
                                    label = "Shuffle",
                                    icon = Icons.Filled.Refresh,
                                    variant = TvPillVariant.Hollow,
                                    heightOverride = 24.dp,
                                    horizontalPaddingOverride = 10.dp,
                                    labelStyle = MaterialTheme.typography.labelSmall,
                                    onClick = callbacks.onAvatarShuffle,
                                )
                            },
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(6),
                                modifier = Modifier
                                    .width(ProfileEditorColumnWidth)
                                    .height(ProfileEditorGridHeight),
                                horizontalArrangement = Arrangement.spacedBy(ProfileEditorGridGap),
                                verticalArrangement = Arrangement.spacedBy(ProfileEditorGridGap),
                                userScrollEnabled = false,
                            ) {
                                itemsIndexed(presets, key = { _, preset -> preset.id }) { index, preset ->
                                    val isBottomRow = index >= 12
                                    TvPresetAvatarCell(
                                        preset = preset,
                                        isSelected = preset.seed == activePresetSeed,
                                        onSelect = { callbacks.onAvatarPresetSelected(preset) },
                                        modifier = if (isBottomRow) {
                                            Modifier
                                                .focusProperties { down = nameFocusRequester }
                                                .onPreviewKeyEvent { event ->
                                                    if (
                                                        event.type == KeyEventType.KeyDown &&
                                                        event.key == Key.DirectionDown
                                                    ) {
                                                        nameFocusRequester.claimFocusOrReport(target = "profile_name", action = "dpad_down")
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                }
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(formScrollState),
                        verticalArrangement = Arrangement.spacedBy(ProfileEditorSectionGap),
                    ) {
                        TvProfileFormSection(title = "Name") {
                            TvProfileTextEntryField(
                                value = state.name,
                                placeholder = "Profile name",
                                onClick = { editingTextField = ProfileTextField.Name },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(nameFocusRequester)
                                    .focusProperties { down = pinFocusRequester }
                                    .onPreviewKeyEvent { event ->
                                        if (
                                            event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionDown
                                        ) {
                                            pinFocusRequester.claimFocusOrReport(target = "profile_pin", action = "dpad_down")
                                            true
                                        } else {
                                            false
                                        }
                                    },
                            )
                        }

                        TvProfileFormSection(
                            title = "PIN (optional)",
                            trailing = if (state.pinEnabled) {
                                {
                                    TvHeroActionPill(
                                        label = "Remove",
                                        icon = Icons.Filled.Close,
                                        variant = TvPillVariant.Hollow,
                                        heightOverride = 24.dp,
                                        horizontalPaddingOverride = 10.dp,
                                        labelStyle = MaterialTheme.typography.labelSmall,
                                        onClick = { callbacks.onPinToggled(false) },
                                    )
                                }
                            } else {
                                null
                            },
                        ) {
                            TvProfileTextEntryField(
                                value = state.pin,
                                placeholder = state.pinHelper,
                                onClick = { editingTextField = ProfileTextField.Pin },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(pinFocusRequester)
                                    .focusProperties {
                                        up = nameFocusRequester
                                        down = childFocusRequester
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (
                                            event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionDown
                                        ) {
                                            childFocusRequester.claimFocusOrReport(target = "profile_child", action = "dpad_down")
                                            true
                                        } else {
                                            false
                                        }
                                    },
                            )
                        }

                        TvChildProfileRow(
                            isOn = state.isChild,
                            onToggle = { callbacks.onChildToggled(!state.isChild) },
                            modifier = Modifier
                                .focusRequester(childFocusRequester)
                                .focusProperties {
                                    up = pinFocusRequester
                                    down = submitFocusRequester
                                },
                        )

                        val errorText = state.error
                            ?: "Enter a profile name".takeIf { blankNameError }
                        if (errorText != null) {
                            Text(
                                text = errorText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        TvHeroActionPill(
                            label = if (state.isSubmitting) "Saving..." else state.submitLabel,
                            icon = Icons.Filled.Check,
                            variant = TvPillVariant.Filled,
                            focusRequester = submitFocusRequester,
                            upFocusRequester = childFocusRequester,
                            onClick = {
                                when {
                                    state.isSubmitting -> Unit
                                    state.name.isBlank() -> blankNameError = true
                                    else -> callbacks.onSubmit()
                                }
                            },
                        )
                    }
                }
            }
        }

        editingTextField?.let { field ->
            TvTextInputDialog(
                title = when (field) {
                    ProfileTextField.Name -> "Profile Name"
                    ProfileTextField.Pin -> "Profile PIN"
                },
                label = when (field) {
                    ProfileTextField.Name -> "Profile name"
                    ProfileTextField.Pin -> "4-digit PIN"
                },
                confirmLabel = "Done",
                initialValue = when (field) {
                    ProfileTextField.Name -> state.name
                    ProfileTextField.Pin -> state.pin
                },
                allowBlank = field == ProfileTextField.Pin,
                keyboardType = when (field) {
                    ProfileTextField.Name -> KeyboardType.Text
                    ProfileTextField.Pin -> KeyboardType.NumberPassword
                },
                onConfirm = { text ->
                    when (field) {
                        ProfileTextField.Name -> callbacks.onNameChanged(text.trim())
                        ProfileTextField.Pin -> {
                            // Entering a PIN implies the profile should be
                            // PIN-protected. A blank confirmation is a no-op on
                            // the enabled flag: on edit that means "leave blank
                            // to keep" the existing PIN; the Remove action is the
                            // deliberate way to turn a PIN off.
                            val digits = text.filter(Char::isDigit).take(4)
                            callbacks.onPinChanged(digits)
                            if (digits.isNotBlank()) {
                                callbacks.onPinToggled(true)
                            }
                        }
                    }
                    editingTextField = null
                },
                onDismiss = { editingTextField = null },
            )
        }
    }
}

@Composable
private fun TvProfileFormSection(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title.uppercase(),
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
                color = Color.White.copy(alpha = 0.50f),
            )
            Spacer(modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}

@Composable
private fun TvProfilePreviewColumn(
    avatar: ProfileAvatarRef,
    name: String,
    hasPin: Boolean,
    isChild: Boolean,
) {
    Column(
        modifier = Modifier
            .width(ProfileEditorPreviewWidth)
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TvProfileTilePreview(
            avatar = avatar,
            name = name,
            hasPin = hasPin,
            isChild = isChild,
        )
        if (name == "Your Name") {
            Text(
                text = "Your tile will look like this.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun TvProfileTilePreview(
    avatar: ProfileAvatarRef,
    name: String,
    hasPin: Boolean,
    isChild: Boolean,
) {
    val avatarText = remember(avatar, name) { profileAvatarDisplayText(avatar, name) }
    val avatarImage = rememberProfileAvatarImage(avatar)
    val shape = RoundedCornerShape(18.dp)
    val tint = remember(name, avatar) { profilePreviewTint("$name-${avatar.avatar}") }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .background(tint, shape)
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarImage != null) {
                ThumbhashImage(
                    url = avatarImage.url,
                    thumbhash = null,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    transparent = true,
                    cacheKey = avatarImage.cacheKey,
                    onError = avatarImage.onLoadFailed,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.22f),
                            ),
                        ),
                )
            } else {
                Text(
                    text = avatarText,
                    fontSize = if (isEmojiAvatar(avatar)) 70.sp else 60.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.94f),
                )
            }

            if (isChild || hasPin) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isChild) TvProfileBadge(Icons.Filled.ChildCare, "Child profile")
                    if (hasPin) TvProfileBadge(Icons.Filled.Lock, "PIN protected")
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvProfileTextEntryField(
    value: String,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.20f),
            contentColor = Color.White,
            focusedContainerColor = Color.Black.copy(alpha = 0.24f),
            focusedContentColor = Color.White,
            pressedContainerColor = Color.Black.copy(alpha = 0.30f),
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                shape = shape,
            ),
            focusedBorder = Border(
                border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.62f)),
                shape = shape,
            ),
        ),
        modifier = modifier.height(ProfileTextEntryHeight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = value.ifBlank { placeholder },
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = if (value.isBlank()) 0.42f else 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvAvatarStyleChip(
    style: TvProfileAvatarPresets.Style,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onSelect,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Color.White else Color.White.copy(alpha = if (isFocused) 0.12f else 0.06f),
            contentColor = if (isSelected) Color.Black else Color.White.copy(alpha = if (isFocused) 1f else 0.75f),
            focusedContainerColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.12f),
            focusedContentColor = if (isSelected) Color.Black else Color.White,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.18f),
                ),
                shape = shape,
            ),
            focusedBorder = Border(
                border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.75f)),
                shape = shape,
            ),
        ),
    ) {
        Text(
            text = style.label,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPresetAvatarCell(
    preset: TvProfileAvatarPresets.Preset,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onSelect,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.04f),
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.04f),
            focusedContentColor = Color.White,
            pressedContainerColor = Color.White.copy(alpha = 0.08f),
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = if (isFocused) 0.50f else 0.12f),
                ),
                shape = shape,
            ),
            focusedBorder = Border(
                border = BorderStroke(if (isSelected) 2.dp else 1.dp, Color.White.copy(alpha = 0.50f)),
                shape = shape,
            ),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = Color.White.copy(alpha = 0.18f),
                elevation = 16.dp,
            ),
        ),
        modifier = modifier.height(ProfileAvatarCellHeight),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = preset.previewUrl,
                contentDescription = "Avatar ${preset.seed}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(ProfileAvatarImageSize)
                    .padding(2.dp),
            )
        }
    }
}

@Composable
private fun TvInitialsHint() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.TextFields,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.50f),
            modifier = Modifier.size(22.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Uses your profile name",
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                text = "The first letters of the name below appear on your tile.",
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = Color.White.copy(alpha = 0.50f),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvChildProfileRow(
    isOn: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onToggle,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isFocused) Color.White.copy(alpha = 0.08f) else Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.08f),
            focusedContentColor = Color.White,
            pressedContainerColor = Color.White.copy(alpha = 0.10f),
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.Transparent),
                shape = shape,
            ),
            focusedBorder = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                shape = shape,
            ),
        ),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Child Profile",
                    fontSize = 20.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
                Text(
                    text = "Restricts content to kid-friendly ratings",
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
            Box(
                modifier = Modifier
                    .width(58.dp)
                    .height(34.dp)
                    .background(
                        color = if (isOn) Color.White else Color.White.copy(alpha = 0.20f),
                        shape = CircleShape,
                    )
                    .padding(4.dp),
                contentAlignment = if (isOn) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(if (isOn) Color.Black else Color.White, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun TvProfileBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun TvProfileFormState.previewAvatarRef(): ProfileAvatarRef {
    val ref = TvProfileAvatarPresets.effectiveAvatarRef(
        styleId = avatarStyleId,
        selectedSeed = selectedAvatarSeed,
        batch = avatarBatch,
        name = name,
        fallbackAvatar = selectedAvatar,
    )
    // The stored URL describes the stored ref only. Once the picker has moved
    // the preview onto a preset, pairing it with the old URL would show the
    // upload while the form is about to save something else.
    return ProfileAvatarRef(ref, selectedAvatarUrl?.takeIf { ref == selectedAvatar })
}

private fun profilePreviewTint(key: String): Color {
    val palette = listOf(
        Color(0xFFB56F5F),
        Color(0xFF6B7C93),
        Color(0xFF8A785E),
        Color(0xFF607C6A),
        Color(0xFF92656E),
        Color(0xFF6F668B),
        Color(0xFF577B83),
        Color(0xFFA28453),
    )
    return palette[kotlin.math.abs(key.hashCode()) % palette.size]
}
