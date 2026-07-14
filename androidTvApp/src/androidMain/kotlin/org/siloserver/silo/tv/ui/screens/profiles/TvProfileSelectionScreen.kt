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
import org.siloserver.silo.common.ui.components.isImageAvatar
import org.siloserver.silo.common.ui.components.profileAvatarDisplayText
import org.siloserver.silo.common.ui.components.rememberProfileServerUrl
import org.siloserver.silo.common.ui.components.resolveAvatarUrl
import org.siloserver.silo.model.profile.Profile
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
private val ProfileUtilityChangeServerWidth = 164.dp
private val ProfileUtilityManageWidth = 104.dp
private val ProfileUtilitySignOutWidth = 100.dp
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
                    TvHeroActionPill(
                        label = if (state.isManageMode) "Done" else "Manage",
                        icon = Icons.Filled.Edit,
                        variant = TvPillVariant.Hollow,
                        modifier = Modifier.width(ProfileUtilityManageWidth),
                        heightOverride = ProfileUtilityChipHeight,
                        horizontalPaddingOverride = 10.dp,
                        labelStyle = MaterialTheme.typography.labelMedium,
                        onClick = viewModel::toggleManageMode,
                    )
                    TvHeroActionPill(
                        label = "Change Server",
                        icon = Icons.Filled.Dns,
                        variant = TvPillVariant.Hollow,
                        modifier = Modifier.width(ProfileUtilityChangeServerWidth),
                        heightOverride = ProfileUtilityChipHeight,
                        horizontalPaddingOverride = 10.dp,
                        labelStyle = MaterialTheme.typography.labelMedium,
                        onClick = onChangeServer,
                    )
                    TvHeroActionPill(
                        label = "Sign Out",
                        icon = Icons.Filled.Logout,
                        variant = TvPillVariant.Hollow,
                        modifier = Modifier.width(ProfileUtilitySignOutWidth),
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
                        // The journey row adds 40dp above the legacy title band;
                        // offset that addition so the title/grid keep their
                        // established vertical footprint and utility chips stay
                        // isolated in the top chrome.
                        .padding(
                            top = ProfileHeaderTop - 40.dp,
                            start = Spacing.safeArea,
                            end = Spacing.safeArea,
                        ),
                ) {
                    AuroraJourneyProgress(
                        currentStep = 3,
                        modifier = Modifier.width(230.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
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

                    val firstCardFocus = remember { FocusRequester() }
                    LaunchedEffect(state.profiles) {
                        if (state.profiles.isNotEmpty()) {
                            runCatching { firstCardFocus.requestFocus() }
                        }
                    }
                    ProfileTileGrid(
                        profiles = state.profiles,
                        firstCardFocus = firstCardFocus,
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
            profileAvatar = pinProfile.avatar,
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
    firstCardFocus: FocusRequester,
    isManageMode: Boolean,
    onProfileSelected: (Profile) -> Unit,
    onEditProfile: (Profile) -> Unit,
    onDeleteProfile: (Profile) -> Unit,
    onAddProfile: () -> Unit,
) {
    val itemCount = profiles.size + 1
    val rowCount = (itemCount + ProfileGridColumns - 1) / ProfileGridColumns
    Column(
        modifier = Modifier.width(ProfileGridWidth),
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
                                    modifier = if (itemIndex == 0) {
                                        Modifier.focusRequester(firstCardFocus)
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                            itemIndex == profiles.size -> TvAddProfileCard(onClick = onAddProfile)
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
    val serverUrl = rememberProfileServerUrl()
    val avatarText = remember(profile.avatar, profile.name) {
        profileAvatarDisplayText(profile.avatar, profile.name)
    }
    val avatarUrl = remember(profile.avatar, serverUrl) {
        profile.avatar
            ?.takeIf(::isImageAvatar)
            ?.let { resolveAvatarUrl(serverUrl, it) }
    }

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
                if (avatarUrl != null) {
                    ThumbhashImage(
                        url = avatarUrl,
                        thumbhash = null,
                        contentDescription = profile.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        transparent = true,
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
                        fontSize = if (!profile.avatar.isNullOrBlank() && !isImageAvatar(profile.avatar)) {
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
private fun TvAddProfileCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(ProfileTileCornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val surfaceAlpha = if (isFocused) 0.14f else 0.06f
    val strokeAlpha = if (isFocused) 0.70f else 0.28f
    val plusAlpha = if (isFocused) 1.0f else 0.60f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
