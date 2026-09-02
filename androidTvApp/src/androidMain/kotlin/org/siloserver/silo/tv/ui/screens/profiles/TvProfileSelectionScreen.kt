package org.siloserver.silo.tv.ui.screens.profiles

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.ui.components.avatarRef
import org.siloserver.silo.common.ui.components.isEmojiAvatar
import org.siloserver.silo.common.ui.components.profileAvatarDisplayText
import org.siloserver.silo.common.ui.components.rememberProfileAvatarImage
import org.siloserver.silo.model.profile.Profile
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.TvFocusTargetState
import org.siloserver.silo.tv.ui.focus.tvProfileFocusTarget
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.components.TvAuroraBackdrop
import org.siloserver.silo.tv.ui.components.AuroraJourneyProgress
import org.siloserver.silo.tv.ui.components.TvAuroraVariant
import org.siloserver.silo.tv.ui.components.TvDialogOption
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvHeroActionPill
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.TvOptionDialog
import org.siloserver.silo.tv.ui.components.TvPillVariant
import org.siloserver.silo.tv.ui.components.TvPinEntryDialog
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.navRailLabel
import org.siloserver.silo.tv.ui.theme.siloCardDefaults
import org.koin.compose.viewmodel.koinViewModel

private const val ProfileGridColumns = 4
private val ProfileTileSize = 140.dp
private val ProfileTileCornerRadius = 18.dp
private val AddProfilePlusSize = 44.dp
private val AddProfilePlusStrokeWidth = 2.dp
private val ProfileUtilityChromeTop = 40.dp
private val ProfileUtilityChromeEnd = 64.dp
// The utility pills size to their own labels. Fixed widths clipped them:
// "Sign Out" rendered as "Sign" at 100dp, and the label a control shows is the
// one thing about it that cannot be allowed to be wrong.
private val ProfileUtilityChipHeight = 28.dp
private val ProfileHeaderTop = 92.dp
private val ProfileGridWidth = 772.dp
private val ProfileGridGap = 70.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvProfileSelectionScreen(
    onProfileSelected: () -> Unit,
    onAddProfile: () -> Unit = {},
    onEditProfile: (String) -> Unit = {},
    onChangeServer: () -> Unit = {},
    onSignOut: () -> Unit = {},
    viewModel: TvProfileSelectionViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Reload on resume so a profile created/edited on a pushed screen is
    // reflected when we return (the VM otherwise only loads in init).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.loadProfiles()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.selectedProfileId) {
        if (state.selectedProfileId != null) {
            viewModel.onSelectionConsumed()
            onProfileSelected()
        }
    }

    when {
        // Gate the full-screen spinner on an empty grid (true first load).
        // An ON_RESUME reload sets isLoading=true too; showing the spinner
        // then would unmount the existing grid and warp/lose focus, so
        // stale-while-revalidate — keep the grid up during a refresh, like
        // the error branch already does.
        state.isLoading && state.profiles.isEmpty() -> TvLoadingScreen()
        state.error != null && state.profiles.isEmpty() -> TvErrorScreen(
            message = state.error!!,
            onRetry = viewModel::loadProfiles,
        )
        else -> {
            Box(modifier = Modifier.fillMaxSize()) {
                TvAuroraBackdrop(variant = TvAuroraVariant.Profile)

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = ProfileUtilityChromeTop, end = ProfileUtilityChromeEnd),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.canManageProfiles) {
                        TvHeroActionPill(
                            label = if (state.isManageMode) "Done" else "Manage",
                            icon = Icons.Filled.Edit,
                            variant = TvPillVariant.Hollow,
                            heightOverride = ProfileUtilityChipHeight,
                            horizontalPaddingOverride = 10.dp,
                            labelStyle = MaterialTheme.typography.labelMedium,
                            onClick = viewModel::toggleManageMode,
                        )
                    }
                    TvHeroActionPill(
                        label = "Change Server",
                        icon = Icons.Filled.Dns,
                        variant = TvPillVariant.Hollow,
                        heightOverride = ProfileUtilityChipHeight,
                        horizontalPaddingOverride = 10.dp,
                        labelStyle = MaterialTheme.typography.labelMedium,
                        onClick = onChangeServer,
                    )
                    TvHeroActionPill(
                        label = "Sign Out",
                        icon = Icons.Filled.Logout,
                        variant = TvPillVariant.Hollow,
                        heightOverride = ProfileUtilityChipHeight,
                        horizontalPaddingOverride = 10.dp,
                        labelStyle = MaterialTheme.typography.labelMedium,
                        onClick = onSignOut,
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        // No journey row here any more, so the title band sits
                        // at its own top inset rather than offsetting one.
                        .padding(
                            top = ProfileHeaderTop,
                            start = Spacing.safeArea,
                            end = Spacing.safeArea,
                        ),
                ) {
                    Text(
                        text = "Who's watching?",
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Select your profile",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // The screen reloads on every resume, so re-anchoring on
                    // the first tile overrode the viewer's position on every
                    // return. Keep a requester per profile ID and move focus
                    // only where tvProfileFocusTarget says it belongs.
                    val tileFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
                    var focusedProfileId by remember { mutableStateOf<String?>(null) }
                    var previousProfileIds by remember { mutableStateOf(emptyList<String>()) }
                    var hasAnchored by remember { mutableStateOf(false) }
                    val profileIds = state.profiles.map { it.id }

                    LaunchedEffect(profileIds) {
                        val target = tvProfileFocusTarget(
                            previousIds = previousProfileIds,
                            currentIds = profileIds,
                            focusedId = focusedProfileId,
                            hasMaterialized = hasAnchored,
                        )
                        previousProfileIds = profileIds
                        // Requesters are keyed by ID, so a deleted profile's
                        // would otherwise be retained for the screen's lifetime.
                        tileFocusRequesters.keys.retainAll(profileIds.toSet())
                        if (target == null) return@LaunchedEffect
                        // Placement can trail the data by a frame or two.
                        val result = requestFocusUntilObserved(
                            maxAttempts = TvFrameRelocationMaxAttempts,
                            awaitAttempt = { withFrameNanos { } },
                            requestFocus = {
                                tileFocusRequesters[target]?.requestFocus() ?: false
                            },
                            isFocused = { focusedProfileId == target },
                            targetState = {
                                // The viewer moved to another tile themselves
                                // while we were retrying. Stop chasing rather
                                // than fight them for the rest of the budget.
                                val focused = focusedProfileId
                                if (focused != null && focused != target) {
                                    TvFocusTargetState.Disposed
                                } else {
                                    TvFocusTargetState.Ready
                                }
                            },
                        )
                        // Only a landed anchor counts. Setting this up front
                        // meant a second list arriving mid-retry cancelled the
                        // first pass and left the replacement believing the
                        // screen was already anchored, so it never anchored.
                        if (result == TvObservedFocusResult.Focused) hasAnchored = true
                    }
                    ProfileTileGrid(
                        profiles = state.profiles,
                        canAddProfile = state.canManageProfiles,
                        focusRequesterFor = { id ->
                            tileFocusRequesters.getOrPut(id) { FocusRequester() }
                        },
                        onProfileFocused = { focusedProfileId = it },
                        isManageMode = state.isManageMode,
                        onProfileSelected = viewModel::onProfileSelected,
                        onEditProfile = { onEditProfile(it.id) },
                        onDeleteProfile = viewModel::requestDelete,
                        onAddProfile = onAddProfile,
                    )

                    if (state.error != null) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = state.error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    // PIN entry dialog — shown when a PIN-protected profile is selected.
    val pinProfile = state.pinProfile
    if (pinProfile != null) {
        TvPinEntryDialog(
            profileName = pinProfile.name,
            profileAvatar = pinProfile.avatarRef(),
            errorMessage = state.pinError,
            isVerifying = state.isVerifyingPin,
            onPinEntered = viewModel::onPinEntered,
            onDismiss = { viewModel.onPinDialogDismissed() },
        )
    }

    // Delete confirmation — modeled as a two-option dialog (TV-native).
    val deleteCandidate = state.deleteCandidate
    if (deleteCandidate != null) {
        TvOptionDialog(
            title = "Delete ${deleteCandidate.name}?",
            options = listOf(
                TvDialogOption(
                    key = "delete",
                    title = "Delete profile",
                    subtitle = "This cannot be undone.",
                    onClick = { viewModel.confirmDelete() },
                ),
                TvDialogOption(
                    key = "cancel",
                    title = "Cancel",
                    onClick = { viewModel.dismissDelete() },
                ),
            ),
            onDismiss = { viewModel.dismissDelete() },
        )
    }
}

@Composable
private fun ProfileTileGrid(
    profiles: List<Profile>,
    canAddProfile: Boolean,
    focusRequesterFor: (String) -> FocusRequester,
    // Null means no profile tile owns focus — the Add tile has it, or focus
    // left the grid entirely. Restoration must not re-request a tile then.
    onProfileFocused: (String?) -> Unit,
    isManageMode: Boolean,
    onProfileSelected: (Profile) -> Unit,
    onEditProfile: (Profile) -> Unit,
    onDeleteProfile: (Profile) -> Unit,
    onAddProfile: () -> Unit,
) {
    val itemCount = profiles.size + (if (canAddProfile) 1 else 0)
    val rowCount = (itemCount + ProfileGridColumns - 1) / ProfileGridColumns
    Column(
        modifier = Modifier
            .width(ProfileGridWidth)
            .onFocusChanged { if (!it.hasFocus) onProfileFocused(null) },
        verticalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        repeat(rowCount) { rowIndex ->
            val firstItemIndex = rowIndex * ProfileGridColumns
            val itemsInRow = minOf(ProfileGridColumns, itemCount - firstItemIndex)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    ProfileGridGap,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.Top,
            ) {
                repeat(itemsInRow) { columnIndex ->
                    val itemIndex = firstItemIndex + columnIndex
                    Box(modifier = Modifier.width(ProfileTileSize), contentAlignment = Alignment.TopCenter) {
                        when {
                            itemIndex < profiles.size -> {
                                val profile = profiles[itemIndex]
                                TvProfileCard(
                                    profile = profile,
                                    manageMode = isManageMode,
                                    onClick = {
                                        if (isManageMode) {
                                            onEditProfile(profile)
                                        } else {
                                            onProfileSelected(profile)
                                        }
                                    },
                                    onDelete = { onDeleteProfile(profile) },
                                    modifier = Modifier
                                        .focusRequester(focusRequesterFor(profile.id))
                                        .onFocusChanged {
                                            if (it.isFocused) onProfileFocused(profile.id)
                                        },
                                )
                            }
                            itemIndex == profiles.size && canAddProfile -> TvAddProfileCard(
                                onClick = onAddProfile,
                                modifier = Modifier.onFocusChanged {
                                    if (it.isFocused) onProfileFocused(null)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvProfileCard(
    profile: Profile,
    manageMode: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val avatar = profile.avatarRef()
    val avatarText = remember(avatar, profile.name) {
        profileAvatarDisplayText(avatar, profile.name)
    }
    val avatarImage = rememberProfileAvatarImage(avatar)

    val shape = RoundedCornerShape(ProfileTileCornerRadius)
    val cardFocus = siloCardDefaults(shape = shape)
    val tileTint = profile.tintColor()
    // Per-profile tinted focus halo ("this profile is alive"), mirroring tvOS
    // ProfileTile's colored glow.
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val haloElevation by animateDpAsState(
        targetValue = if (isFocused) 20.dp else 0.dp,
        label = "profileTileHalo",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = CardDefaults.shape(shape = shape),
            scale = cardFocus.scale,
            border = cardFocus.border,
            glow = cardFocus.glow,
            modifier = modifier
                .size(ProfileTileSize)
                .shadow(
                    elevation = haloElevation,
                    shape = shape,
                    clip = false,
                    ambientColor = tileTint,
                    spotColor = tileTint,
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(tileTint)
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.14f),
                        shape = shape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarImage != null) {
                    ThumbhashImage(
                        url = avatarImage.url,
                        thumbhash = null,
                        contentDescription = profile.name,
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
                        fontSize = if (isEmojiAvatar(avatar)) {
                            70.sp
                        } else {
                            60.sp
                        },
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.94f),
                    )
                }

                // Primary (crown) + child (leaf) + PIN (lock) indicator badges,
                // top-trailing — tvOS ProfileTile surfaces these so a household
                // reads at a glance.
                if (profile.isPrimary || profile.isChild || profile.hasPin) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (profile.isPrimary) ProfileBadge(Icons.Filled.WorkspacePremium, "Primary profile")
                        if (profile.isChild) ProfileBadge(Icons.Filled.ChildCare, "Child profile")
                        if (profile.hasPin) ProfileBadge(Icons.Filled.Lock, "PIN protected")
                    }
                }

                if (manageMode) {
                    // Pencil badge signals the card edits instead of selects.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(26.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = profile.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f),
        )
        // The server never deletes the primary profile (409
        // primary_profile_protected), so don't offer it.
        if (manageMode && !profile.isPrimary) {
            Spacer(modifier = Modifier.height(8.dp))
            TvHeroActionPill(
                label = "Delete",
                icon = Icons.Filled.Delete,
                variant = TvPillVariant.Ghost,
                onClick = onDelete,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvAddProfileCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(ProfileTileCornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val surfaceAlpha = if (isFocused) 0.14f else 0.06f
    val strokeAlpha = if (isFocused) 0.70f else 0.28f
    val plusAlpha = if (isFocused) 1.0f else 0.60f
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                focusedContainerColor = Color.Transparent,
                focusedContentColor = Color.White,
                pressedContainerColor = Color.Transparent,
                pressedContentColor = Color.White,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.10f),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(0.dp, Color.Transparent),
                    shape = shape,
                ),
                focusedBorder = Border(
                    border = BorderStroke(0.dp, Color.Transparent),
                    shape = shape,
                ),
            ),
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = Glow(
                    elevationColor = Color.Black.copy(alpha = 0.50f),
                    elevation = 22.dp,
                ),
            ),
            modifier = Modifier.size(ProfileTileSize),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = surfaceAlpha), shape)
                    .border(
                        width = if (isFocused) 4.dp else 0.dp,
                        color = if (isFocused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(ProfileTileCornerRadius + 4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val dashColor = Color.White.copy(alpha = strokeAlpha)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = dashColor,
                        cornerRadius = CornerRadius(
                            ProfileTileCornerRadius.toPx(),
                            ProfileTileCornerRadius.toPx(),
                        ),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                            ),
                        ),
                    )
                }
                Canvas(modifier = Modifier.size(AddProfilePlusSize)) {
                    val color = Color.White.copy(alpha = plusAlpha)
                    val stroke = AddProfilePlusStrokeWidth.toPx()
                    val center = size.width / 2f
                    drawLine(
                        color = color,
                        start = Offset(center, 0f),
                        end = Offset(center, size.height),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = color,
                        start = Offset(0f, center),
                        end = Offset(size.width, center),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Add Profile",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun ProfileBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(17.dp)),
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

private fun Profile.tintColor(): Color {
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
    return palette[kotlin.math.abs(id.hashCode()) % palette.size]
}
