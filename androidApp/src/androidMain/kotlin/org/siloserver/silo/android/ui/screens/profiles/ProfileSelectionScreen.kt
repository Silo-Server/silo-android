package org.siloserver.silo.android.ui.screens.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.screens.auth.AuthColors
import org.siloserver.silo.android.ui.screens.auth.AuthErrorBanner
import org.siloserver.silo.android.ui.components.aurora.AuroraBackdrop
import org.siloserver.silo.android.ui.components.aurora.AuroraScrim
import org.siloserver.silo.android.ui.components.aurora.AuroraVariant
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.ui.components.avatarRef
import org.siloserver.silo.common.ui.components.isEmojiAvatar
import org.siloserver.silo.common.ui.components.profileAvatarDisplayText
import org.siloserver.silo.common.ui.components.rememberProfileAvatarImage
import org.siloserver.silo.model.profile.Profile
import androidx.compose.runtime.remember
import org.koin.compose.viewmodel.koinViewModel

/**
 * Grid of profile avatars shown after login.
 *
 * @param onNavigateToHome Called after a profile is selected.
 * @param onNavigateToCreateProfile Called when the "Add Profile" card is tapped.
 * @param onNavigateToEditProfile Called when the edit icon is tapped in manage mode.
 */
@Composable
fun ProfileSelectionScreen(
    onNavigateToHome: () -> Unit,
    onNavigateToCreateProfile: () -> Unit,
    onNavigateToEditProfile: (profileId: String) -> Unit,
    viewModel: ProfileSelectionViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Reload on resume so a profile created/edited on a pushed screen is
    // reflected when we return (the VM otherwise only loads in init) — the
    // TvProfileSelectionScreen pattern.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.loadProfiles()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Navigate after a profile is selected.
    LaunchedEffect(state.selectedProfileId) {
        if (state.selectedProfileId != null) {
            viewModel.onProfileSelectedConsumed()
            onNavigateToHome()
        }
    }

    state.deleteDialogProfile?.let { profile ->
        val deletingActive = profile.id == state.activeProfileId
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteDialog,
            title = { Text("Delete \"${profile.name}\"?") },
            text = {
                Text(
                    if (deletingActive) {
                        "You're signed in as this profile. Deleting it removes its " +
                            "watch history and preferences, and you'll pick another " +
                            "profile to continue."
                    } else {
                        "This removes the profile's watch history and preferences. " +
                            "This can't be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeleteProfile) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteDialog) { Text("Cancel") }
            },
        )
    }

    // PIN entry dialog
    state.pinDialogProfile?.let { profile ->
        PINEntryDialog(
            profileName = profile.name,
            profileAvatar = profile.avatarRef(),
            isLoading = state.pinIsVerifying,
            error = state.pinError,
            onPinComplete = viewModel::onPinEntered,
            onDismiss = viewModel::dismissPinDialog,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackdrop(variant = AuroraVariant.Profile, scrim = AuroraScrim.Soft)
        // Vertical scroll on the whole page, mirroring iOS phone's
        // `ScrollView { LazyVGrid(adaptive(min: 140, max: 180)) }` pattern at
        // `ProfileSelectionView.swift:119`. The previous implementation
        // capped the grid at a fixed 220.dp, which scrolled internally as
        // soon as profiles spilled to a third row. Now the grid is a
        // non-lazy FlowRow that grows naturally; only the page scrolls if
        // content exceeds the viewport.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .statusBarsPadding()
                .padding(top = 24.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: title + subtitle, spacing 6 (iOS titleBlock).
            Text(
                text = "Who's watching?",
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
                color = AuthColors.OnBackground,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Select your profile",
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = AuthColors.OnSurfaceVariant,
            )

            state.error?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                AuthErrorBanner(message = error)
            }

            if (state.isLoading) {
                Spacer(modifier = Modifier.height(36.dp))
                CircularProgressIndicator(color = AuthColors.Primary)
            } else {
                // VStack(spacing: 36) between header and tile grid.
                Spacer(modifier = Modifier.height(36.dp))

                ProfileFlow(
                    profiles = state.profiles,
                    canAddProfile = state.canManageProfiles,
                    isManageMode = state.isManageMode,
                    onProfileTap = { viewModel.onProfileTapped(it) },
                    onProfileEdit = { onNavigateToEditProfile(it.id) },
                    onProfileDelete = { viewModel.requestDeleteProfile(it) },
                    onAddProfile = onNavigateToCreateProfile,
                )

                if (state.canManageProfiles) {
                    Spacer(modifier = Modifier.height(24.dp))

                    TextButton(onClick = viewModel::toggleManageMode) {
                        Text(
                            text = if (state.isManageMode) "Done" else "Manage Profiles",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = AuthColors.OnBackground,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Non-lazy wrapping grid of profile tiles + the AddProfile tile. Uses
 * [FlowRow] so the row count grows naturally with the profile count —
 * the parent scroll handles overflow only when content exceeds the
 * viewport, matching iOS phone's `ScrollView { LazyVGrid }` pattern.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileFlow(
    profiles: List<Profile>,
    canAddProfile: Boolean,
    isManageMode: Boolean,
    onProfileTap: (Profile) -> Unit,
    onProfileEdit: (Profile) -> Unit,
    onProfileDelete: (Profile) -> Unit,
    onAddProfile: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        // iOS adaptive grid: tileSpacing 16, rowSpacing 28.
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        profiles.forEach { profile ->
            ProfileCard(
                profile = profile,
                isManageMode = isManageMode,
                onTap = { onProfileTap(profile) },
                onEdit = { onProfileEdit(profile) },
                onDelete = { onProfileDelete(profile) },
            )
        }
        if (canAddProfile) {
            AddProfileCard(onClick = onAddProfile)
        }
    }
}

// iOS phone ProfileTile sizing.
private val TileSize = 140.dp
private val TileCornerRadius = 18.dp
private const val TileEmojiSize = 72f
private const val TileInitialSize = 56f
private val TileNameSize = 17.sp

@Composable
private fun ProfileCard(
    profile: Profile,
    isManageMode: Boolean,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val tint = ProfileTilePalette.tint(profile.id)

    Box(contentAlignment = Alignment.TopEnd) {
        // VStack(spacing: 20) tile + name.
        Column(
            modifier = Modifier
                .clickable(onClick = if (isManageMode) onEdit else onTap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ProfileTileBody(profile = profile, tint = tint)

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = profile.name,
                fontSize = TileNameSize,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        // Manage mode overlays (Android management affordance).
        if (isManageMode) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit profile",
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp),
                )
            }

            // The server never deletes the primary profile (409
            // primary_profile_protected), so don't offer it.
            if (!profile.isPrimary) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(AuthColors.Error),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Delete profile",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Square tinted profile tile body matching iOS phone `ProfileTile`:
 * tinted fill, top-edge highlight stroke, avatar (image fills the tile,
 * emoji/initials sit on the tint), and lock/child badges top-right.
 */
@Composable
private fun ProfileTileBody(profile: Profile, tint: Color) {
    val shape = RoundedCornerShape(TileCornerRadius)
    val avatar = profile.avatarRef()
    val avatarImage = rememberProfileAvatarImage(avatar)

    Box(
        modifier = Modifier
            .size(TileSize)
            .clip(shape)
            .background(tint)
            // Top-edge highlight so the tile reads as a physical surface.
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.04f),
                    ),
                ),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarImage != null) {
            ThumbhashImage(
                url = avatarImage.url,
                thumbhash = null,
                contentDescription = "${profile.name} avatar",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
                contentScale = ContentScale.Crop,
                transparent = true,
                cacheKey = avatarImage.cacheKey,
                onError = avatarImage.onLoadFailed,
            )
        } else if (isEmojiAvatar(avatar)) {
            Text(
                text = avatar.avatar.orEmpty().trim(),
                fontSize = TileEmojiSize.sp,
            )
        } else {
            Text(
                text = profileAvatarDisplayText(avatar = avatar, name = profile.name),
                fontSize = TileInitialSize.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.92f),
            )
        }

        // Primary / child / lock badges ride the top-right corner.
        if (profile.isPrimary || profile.hasPin || profile.isChild) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (profile.isPrimary) {
                    TileBadge(Icons.Filled.WorkspacePremium, contentDescription = "Primary profile")
                }
                if (profile.isChild) {
                    TileBadge(Icons.Filled.Eco)
                }
                if (profile.hasPin) {
                    TileBadge(Icons.Filled.Lock)
                }
            }
        }
    }
}

@Composable
private fun TileBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String? = null,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun AddProfileCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(TileCornerRadius)
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(TileSize)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.06f))
                .border(width = 2.dp, color = Color.White.copy(alpha = 0.28f), shape = shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add profile",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(84.dp),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Add Profile",
            fontSize = TileNameSize,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Warm tile palette mirroring iOS `ProfileTilePalette`. Tint is derived
 * from the profile id via DJB2 so a profile always gets the same color.
 */
private object ProfileTilePalette {
    private val colors = listOf(
        Color(red = 0.850f, green = 0.460f, blue = 0.380f), // coral
        Color(red = 0.400f, green = 0.560f, blue = 0.720f), // slate blue
        Color(red = 0.690f, green = 0.540f, blue = 0.400f), // warm tan
        Color(red = 0.460f, green = 0.620f, blue = 0.520f), // sage
        Color(red = 0.780f, green = 0.480f, blue = 0.520f), // dusty rose
        Color(red = 0.560f, green = 0.480f, blue = 0.720f), // lavender
        Color(red = 0.360f, green = 0.580f, blue = 0.620f), // teal
        Color(red = 0.780f, green = 0.640f, blue = 0.380f), // amber
    )

    fun tint(profileId: String): Color {
        var h = 5381UL
        for (byte in profileId.encodeToByteArray()) {
            h = ((h shl 5) + h) + byte.toUByte().toULong()
        }
        return colors[(h % colors.size.toULong()).toInt()]
    }
}
