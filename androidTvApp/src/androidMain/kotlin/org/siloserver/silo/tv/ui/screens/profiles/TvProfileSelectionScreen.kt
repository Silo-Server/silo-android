package org.siloserver.silo.tv.ui.screens.profiles

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeProfileAvatar
import org.siloserver.silo.common.ui.marquee.MarqueeScene
import org.siloserver.silo.common.ui.marquee.MarqueeScrim
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle
import org.siloserver.silo.common.ui.marquee.ProfileTilePalette
import org.siloserver.silo.common.ui.marquee.rememberReduceMotion
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.tv.ui.components.TvDialogOption
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.TvOptionDialog
import org.siloserver.silo.tv.ui.components.TvPinEntryDialog
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButton
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButtonKind
import org.siloserver.silo.tv.ui.components.marquee.rememberTvActiveServer
import org.siloserver.silo.tv.ui.focus.TvFocusTargetState
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.tvProfileFocusTarget

/** Six avatars and their gaps fit the 870dp safe width. */
private const val ProfileGridColumns = 6
private val ProfileAvatarSize = 110.dp
private val ProfileColumnWidth = 128.dp
private val ProfileColumnGap = 14.dp

/**
 * "Who's watching?" on Android TV (silo-apple `ProfileSelectionView` on
 * tvOS): round avatars centered over the brand light, "Signed in as", and
 * the utilities (change server, sign out, and for admins, manage) small and
 * below the people. A PIN prompt opens full screen over the same light,
 * tinted with that person's color.
 */
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
    rememberTvActiveServer()

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

    // One requester per profile ID; see the anchoring effect below.
    val tileFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var focusedProfileId by remember { mutableStateOf<String?>(null) }
    // Closing a PIN prompt puts focus back on the profile that opened it,
    // not the first one.
    var pinOpenedBy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.pinProfile?.id) {
        val opened = state.pinProfile?.id
        if (opened != null) {
            pinOpenedBy = opened
            return@LaunchedEffect
        }
        val returnTo = pinOpenedBy ?: return@LaunchedEffect
        pinOpenedBy = null
        requestFocusUntilObserved(
            maxAttempts = TvFrameRelocationMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = { tileFocusRequesters[returnTo]?.requestFocus() ?: false },
            isFocused = { focusedProfileId == returnTo },
        )
    }

    LaunchedEffect(Unit) { MarqueeScene.focus = MarqueeScene.Focus.Profiles }
    LaunchedEffect(state.pinProfile?.id) {
        MarqueeScene.personalTint = state.pinProfile?.id?.let(ProfileTilePalette::tint)
    }
    DisposableEffect(Unit) { onDispose { MarqueeScene.personalTint = null } }

    LaunchedEffect(state.openAddProfile) {
        if (state.openAddProfile) {
            viewModel.onAddProfileConsumed()
            onAddProfile()
        }
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
        // A one-profile household opening straight after sign-in: nothing to
        // pick, and nothing hidden left for the remote to press.
        state.openingOnlyProfile -> MarqueeScrim(MarqueeScrimStyle.Ambient)
        state.error != null && state.profiles.isEmpty() -> TvErrorScreen(
            message = state.error!!,
            onRetry = viewModel::loadProfiles,
        )
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Hidden behind the PIN prompt.
                    .alpha(if (state.pinProfile != null) 0f else 1f),
            ) {
                MarqueeScrim(MarqueeScrimStyle.Ambient)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 30.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Who's watching?",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.8).sp,
                        color = MarqueeColors.Ink,
                        maxLines = 1,
                    )
                    // Keeps its height while the account loads so the picker doesn't shift.
                    Text(
                        text = state.accountName?.let { "Signed in as $it" } ?: " ",
                        fontSize = 14.sp,
                        color = MarqueeColors.InkSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 7.dp),
                    )

                    Spacer(modifier = Modifier.height(36.dp))

                    // The screen reloads on every resume, so re-anchoring on
                    // the first tile overrode the viewer's position on every
                    // return. Keep a requester per profile ID and move focus
                    // only where tvProfileFocusTarget says it belongs.
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
                        onAddProfile = viewModel::requestAddProfile,
                    )

                    if (state.error != null) {
                        Text(
                            text = state.error!!,
                            fontSize = 14.sp,
                            color = MarqueeColors.Error,
                            modifier = Modifier.padding(top = 18.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Full width, so Down from any avatar column finds the row.
                    Row(
                        modifier = Modifier.fillMaxWidth().focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    ) {
                        if (state.canManageProfiles) {
                            TvMarqueeButton(
                                text = if (state.isManageMode) "Done" else "Manage",
                                onClick = viewModel::toggleManageMode,
                                kind = TvMarqueeButtonKind.Glass,
                                compact = true,
                                icon = if (state.isManageMode) null else Icons.Outlined.ManageAccounts,
                            )
                        }
                        TvMarqueeButton(
                            text = "Change server",
                            onClick = onChangeServer,
                            kind = TvMarqueeButtonKind.Glass,
                            compact = true,
                            icon = Icons.Outlined.Dns,
                        )
                        TvMarqueeButton(
                            text = "Sign out",
                            onClick = onSignOut,
                            kind = TvMarqueeButtonKind.Glass,
                            compact = true,
                            icon = Icons.AutoMirrored.Outlined.Logout,
                        )
                    }
                }
            }
        }
    }

    // PIN entry — shown when a PIN-protected profile is selected, or for the
    // primary profile's PIN before manage mode.
    val pinProfile = state.pinProfile
    if (pinProfile != null) {
        TvPinEntryDialog(
            profile = pinProfile,
            errorMessage = state.pinError,
            isVerifying = state.isVerifyingPin,
            prompt = if (state.pinForManagement) "Enter this PIN to manage profiles" else "Enter your PIN",
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
            .focusGroup()
            .onFocusChanged { if (!it.hasFocus) onProfileFocused(null) },
        verticalArrangement = Arrangement.spacedBy(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(rowCount) { rowIndex ->
            val firstItemIndex = rowIndex * ProfileGridColumns
            val itemsInRow = minOf(ProfileGridColumns, itemCount - firstItemIndex)
            Row(
                horizontalArrangement = Arrangement.spacedBy(ProfileColumnGap),
                verticalAlignment = Alignment.Top,
            ) {
                repeat(itemsInRow) { columnIndex ->
                    val itemIndex = firstItemIndex + columnIndex
                    Box(modifier = Modifier.width(ProfileColumnWidth), contentAlignment = Alignment.TopCenter) {
                        when {
                            itemIndex < profiles.size -> {
                                val profile = profiles[itemIndex]
                                TvProfileTile(
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
                            itemIndex == profiles.size && canAddProfile -> TvAddProfileTile(
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

/** A white ring around a focused avatar, drawn outside it. */
private fun Modifier.focusRing(focused: Boolean): Modifier = drawBehind {
    if (focused) {
        drawCircle(
            color = MarqueeColors.Ink,
            radius = size.minDimension / 2f + 6.dp.toPx(),
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvProfileTile(
    profile: Profile,
    manageMode: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val scale by animateFloatAsState(if (focused && !reduceMotion) 1.12f else 1f, label = "tvProfileTileScale")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = modifier
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .semantics {
                    contentDescription = profile.name
                    stateDescription = listOfNotNull(
                        "PIN protected".takeIf { profile.hasPin },
                        "Kids profile".takeIf { profile.isChild },
                        "Edit".takeIf { manageMode },
                    ).joinToString(", ")
                }
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(if (focused) 18.dp else 0.dp, CircleShape, clip = false)
                .focusRing(focused),
        ) {
            MarqueeProfileAvatar(
                profile,
                size = ProfileAvatarSize,
                baseSize = 92.dp,
                modifier = Modifier.alpha(if (manageMode) 0.6f else 1f),
            )
            if (manageMode) {
                // Pencil: the tile edits instead of selects.
                Box(Modifier.size(ProfileAvatarSize), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
        Text(
            text = profile.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) MarqueeColors.Ink else MarqueeColors.InkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (focused) 22.dp else 13.dp),
        )
        // The server never deletes the primary profile (409
        // primary_profile_protected), so don't offer it.
        if (manageMode && !profile.isPrimary) {
            TvMarqueeButton(
                text = "Delete",
                onClick = onDelete,
                kind = TvMarqueeButtonKind.Plain,
                compact = true,
                icon = Icons.Filled.Delete,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvAddProfileTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val scale by animateFloatAsState(if (focused && !reduceMotion) 1.12f else 1f, label = "tvAddTileScale")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = modifier
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .semantics { contentDescription = "Add profile" }
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .focusRing(focused)
                .size(ProfileAvatarSize)
                .background(Color.White.copy(alpha = if (focused) 0.14f else 0f), CircleShape)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    drawCircle(
                        color = Color.White.copy(alpha = if (focused) 0.7f else 0.25f),
                        radius = size.minDimension / 2f - stroke / 2f,
                        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MarqueeColors.Ink.copy(alpha = if (focused) 1f else 0.4f),
                modifier = Modifier.size(33.dp),
            )
        }
        Text(
            text = "Add profile",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MarqueeColors.Ink.copy(alpha = if (focused) 1f else 0.62f),
            modifier = Modifier.padding(top = if (focused) 22.dp else 13.dp),
        )
    }
}
