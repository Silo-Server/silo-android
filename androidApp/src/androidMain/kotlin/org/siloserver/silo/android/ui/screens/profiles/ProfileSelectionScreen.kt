package org.siloserver.silo.android.ui.screens.profiles

import org.siloserver.silo.android.R
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.android.ui.components.SignOutConfirmDialog
import org.siloserver.silo.android.ui.components.SiloDialogColors
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.marquee.MarqueeButton
import org.siloserver.silo.android.ui.components.marquee.MarqueeButtonKind
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorHaptic
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorText
import org.siloserver.silo.android.ui.components.marquee.MarqueeHeadline
import org.siloserver.silo.android.ui.components.marquee.MarqueeMetrics
import org.siloserver.silo.android.ui.components.marquee.MarqueeServerChip
import org.siloserver.silo.android.ui.components.marquee.MarqueeTopBarSpacer
import org.siloserver.silo.android.ui.components.marquee.MarqueeWordmark
import org.siloserver.silo.android.ui.components.marquee.marqueePressHaptic
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeProfileAvatar
import org.siloserver.silo.common.ui.marquee.MarqueeScene
import org.siloserver.silo.common.ui.marquee.MarqueeScrim
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle
import org.siloserver.silo.common.ui.marquee.ProfileTilePalette
import org.siloserver.silo.common.ui.marquee.ServerBrandingLoader
import org.siloserver.silo.common.ui.marquee.rememberSignInServer
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.ServerRegistry

private val TopGap = 24.dp
private val GridGap = 36.dp
private val FooterGap = 32.dp
private val BottomGap = 20.dp

/**
 * "Who's watching?" (silo-apple `ProfileSelectionView`): round avatars sized
 * to the household, centered between the title and small utilities (change
 * account, sign out, and manage profiles). Pressing a profile tints the
 * backdrop with that person's color; a PIN prompt opens full screen over the
 * same light.
 *
 * It shows the open account's profiles only (owner decision 2026-11-02, Roku
 * spec §7.5), from the account's last list when the server can't be reached,
 * then Add: a sheet offering another profile, another account, or another
 * server. Change account reaches the other saved accounts.
 *
 * @param onNavigateToHome Called after a profile is selected.
 * @param onNavigateToCreateProfile Called when "Add a profile" is chosen.
 * @param onNavigateToEditProfile Called when a profile is tapped in manage mode.
 * @param onChangeAccount "Change account" from the footer or the server chip.
 * @param onAddAccount "Add an account" from Add: Change Account at the active server's Add an account.
 * @param onAddServer "Add a server" from Add.
 * @param onSignOut "Sign out" from the footer, once confirmed; true to also remove the server.
 * @param onPasswordChangeRequired The account holds a temporary password; it must choose its own first.
 */
@Composable
fun ProfileSelectionScreen(
    onNavigateToHome: () -> Unit,
    onNavigateToCreateProfile: () -> Unit,
    onNavigateToEditProfile: (profileId: String) -> Unit,
    onChangeAccount: () -> Unit,
    onAddAccount: () -> Unit,
    onAddServer: () -> Unit,
    onSignOut: (removeServer: Boolean) -> Unit,
    /** The open account's sign-in expired: sign in to it again instead of choosing a profile. */
    onSignInAgain: () -> Unit = {},
    onPasswordChangeRequired: () -> Unit,
    viewModel: ProfileSelectionViewModel = koinViewModel(),
    brandingLoader: ServerBrandingLoader = koinInject(),
    serverRegistry: ServerRegistry = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val activeEntry by serverRegistry.activeEntry.collectAsState()
    val server = rememberSignInServer(
        serverUrl = activeEntry?.url.orEmpty(),
        savedName = activeEntry?.fetchedName,
        loader = brandingLoader,
    )
    var pressedProfileId by remember { mutableStateOf<String?>(null) }
    var choosingAdd by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

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

    LaunchedEffect(Unit) { MarqueeScene.focus = MarqueeScene.Focus.Profiles }
    LaunchedEffect(state.signInRequired) { if (state.signInRequired) onSignInAgain() }
    // The profile under a finger, then the one asking for a PIN, tints the light.
    val tintedProfileId = state.pinDialogProfile?.id ?: pressedProfileId
    LaunchedEffect(tintedProfileId) {
        MarqueeScene.personalTint = tintedProfileId?.let(ProfileTilePalette::tint)
    }
    DisposableEffect(Unit) { onDispose { MarqueeScene.personalTint = null } }

    // Navigate after a profile is selected.
    LaunchedEffect(state.openAddProfile) {
        if (state.openAddProfile) {
            viewModel.onAddProfileConsumed()
            onNavigateToCreateProfile()
        }
    }

    // Only the picker on screen hands off. One covered by Change Account, or
    // leaving, must not pull the person away; it reloads when it's shown
    // again and hands off from that answer.
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val onScreen = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(state.requiresPasswordChange, onScreen) {
        if (state.requiresPasswordChange && onScreen) {
            viewModel.onPasswordChangeConsumed()
            onPasswordChangeRequired()
        }
    }

    LaunchedEffect(state.selectedProfileId) {
        if (state.selectedProfileId != null) {
            viewModel.onProfileSelectedConsumed()
            onNavigateToHome()
        }
    }
    MarqueeErrorHaptic(state.pinErrorCount)

    state.deleteDialogProfile?.let { profile ->
        val deletingActive = profile.id == state.activeProfileId
        SiloConfirmDialog(
            title = "Delete \"${profile.name}\"?",
            body = if (deletingActive) {
                "You're signed in as this profile. Deleting it removes its " +
                    "watch history and preferences, and you'll pick another " +
                    "profile to continue."
            } else {
                "This removes the profile's watch history and preferences. " +
                    "This can't be undone."
            },
            confirmLabel = "Delete",
            onConfirm = viewModel::confirmDeleteProfile,
            onDismiss = viewModel::dismissDeleteDialog,
        )
    }

    if (choosingAdd) {
        AddChoiceSheet(
            canAddProfile = state.canManageProfiles,
            onAddProfile = {
                choosingAdd = false
                viewModel.requestAddProfile()
            },
            onAddAccount = {
                choosingAdd = false
                onAddAccount()
            },
            onAddServer = {
                choosingAdd = false
                onAddServer()
            },
            onDismiss = { choosingAdd = false },
        )
    }

    SignOutConfirmDialog(
        visible = confirmSignOut,
        onConfirm = {
            confirmSignOut = false
            onSignOut(false)
        },
        onDismiss = { confirmSignOut = false },
        onConfirmAndRemoveServer = {
            confirmSignOut = false
            onSignOut(true)
        },
    )

    val pinOpen = state.pinDialogProfile != null
    val busy = pinOpen || state.openingOnlyProfile
    val pickerAlpha by animateFloatAsState(if (busy) 0f else 1f, label = "pickerAlpha")

    Box(Modifier.fillMaxSize()) {
        MarqueeScrim(MarqueeScrimStyle.Ambient)
        Column(
            Modifier
                .fillMaxSize()
                .alpha(pickerAlpha)
                // Behind the PIN prompt the picker is gone for TalkBack too.
                .then(if (pinOpen) Modifier.clearAndSetSemantics {} else Modifier)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(start = 24.dp, end = 24.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MarqueeWordmark()
                MarqueeTopBarSpacer()
                MarqueeServerChip(
                    name = server.name,
                    hostLabel = server.hostLabel,
                    markUrl = server.branding?.markUrl,
                    actionLabel = stringResource(R.string.picker_change_account),
                    onAction = onChangeAccount,
                    enabled = !busy,
                )
            }
            Picker(
                state = state,
                enabled = !busy,
                onPressChange = { profile, pressed ->
                    pressedProfileId = if (pressed) profile.id else pressedProfileId.takeIf { it != profile.id }
                },
                onItemTap = { item ->
                    when (item) {
                        is PickerItem.Person ->
                            if (state.isManageMode) onNavigateToEditProfile(item.profile.id) else viewModel.onShownProfileTapped(item.profile)
                        PickerItem.Add -> choosingAdd = true
                        PickerItem.AddProfile -> viewModel.requestAddProfile()
                    }
                },
                onProfileDelete = viewModel::requestDeleteProfile,
                onChangeAccount = onChangeAccount,
                onSignOut = { confirmSignOut = true },
                onToggleManage = viewModel::toggleManageMode,
                modifier = Modifier.weight(1f).windowInsetsPadding(WindowInsets.navigationBars),
            )
        }

        AnimatedVisibility(visible = pinOpen, enter = fadeIn(), exit = fadeOut()) {
            // Keep the last profile while the prompt fades out.
            var shown by remember { mutableStateOf(state.pinDialogProfile) }
            state.pinDialogProfile?.let { shown = it }
            shown?.let { profile ->
                PINEntryOverlay(
                    profile = profile,
                    isVerifying = state.pinIsVerifying,
                    error = state.pinError,
                    errorCount = state.pinErrorCount,
                    onPinComplete = viewModel::onPinEntered,
                    onDismiss = viewModel::dismissPinDialog,
                    prompt = if (state.pinForManagement) "Enter this PIN to manage profiles" else "Enter your PIN",
                )
            }
        }
    }
}

/**
 * Add (Roku spec §7.5): an app-styled bottom sheet of three choices, each
 * with a line saying what it does, then Cancel. Add a profile shows only
 * when the household has a primary profile to manage it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddChoiceSheet(
    canAddProfile: Boolean,
    onAddProfile: () -> Unit,
    onAddAccount: () -> Unit,
    onAddServer: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SiloDialogColors.Surface,
        contentColor = MarqueeColors.Ink,
        scrimColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.picker_add),
                color = MarqueeColors.Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp).semantics { heading() },
            )
            if (canAddProfile) {
                AddChoiceRow(
                    icon = Icons.Outlined.PersonAdd,
                    title = stringResource(R.string.picker_add_profile),
                    detail = stringResource(R.string.picker_add_profile_detail),
                    onClick = onAddProfile,
                )
            }
            AddChoiceRow(
                icon = Icons.Outlined.ManageAccounts,
                title = stringResource(R.string.picker_add_account),
                detail = stringResource(R.string.picker_add_account_detail),
                onClick = onAddAccount,
            )
            AddChoiceRow(
                icon = Icons.Outlined.Dns,
                title = stringResource(R.string.picker_add_server),
                detail = stringResource(R.string.picker_add_server_detail),
                onClick = onAddServer,
            )
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SiloDialogColors.DefaultButton)
                    .clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.action_cancel), color = MarqueeColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun AddChoiceRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MarqueeColors.Ink, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MarqueeColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MarqueeColors.InkSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

private sealed interface PickerItem {
    data class Person(val profile: Profile) : PickerItem
    data object Add : PickerItem
    data object AddProfile : PickerItem
}

/**
 * The open account's profiles, then Add (a profile, an account or a
 * server); in manage mode, Add profile.
 */
private fun ProfileSelectionUiState.pickerItems(): List<PickerItem> =
    shownProfiles.map(PickerItem::Person) + if (isManageMode && canManageProfiles) PickerItem.AddProfile else PickerItem.Add

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Picker(
    state: ProfileSelectionUiState,
    enabled: Boolean,
    onPressChange: (Profile, Boolean) -> Unit,
    onItemTap: (PickerItem) -> Unit,
    onProfileDelete: (Profile) -> Unit,
    onChangeAccount: () -> Unit,
    onSignOut: () -> Unit,
    onToggleManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0) }
    var footerHeight by remember { mutableStateOf(0) }
    val items = state.pickerItems()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val viewportHeight = maxHeight
        // The room between the title and the footer decides how many avatars
        // share a row and how large they are. Nothing draws until it's
        // measured, so tiles never render at a placeholder size and jump.
        val layout = if (headerHeight > 0 && footerHeight > 0) {
            with(density) {
                ProfilePickerLayout.of(
                    tileCount = items.size,
                    width = minOf(maxWidth.value, 440f) - 48f,
                    height = viewportHeight.value - headerHeight.toDp().value - footerHeight.toDp().value -
                        (TopGap + GridGap + FooterGap + BottomGap).value,
                )
            }
        } else {
            null
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .heightIn(min = viewportHeight)
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = BottomGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(TopGap))
                Column(
                    Modifier.onSizeChanged { headerHeight = it.height },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MarqueeHeadline("Who's watching?", centered = true)
                    // Keeps its height while the account loads so the picker doesn't shift.
                    Text(
                        state.accountName?.let { "Signed in as $it" } ?: " ",
                        color = MarqueeColors.InkSecondary,
                        fontSize = MarqueeMetrics.LeadFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.error?.let { MarqueeErrorText(it) }
                }
                Spacer(Modifier.height(GridGap))
                if (state.isLoading && state.shownProfiles.isEmpty()) {
                    CircularProgressIndicator(color = MarqueeColors.Ink, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                } else {
                    TileRows(
                        state = state,
                        items = items,
                        layout = layout,
                        enabled = enabled,
                        onPressChange = onPressChange,
                        onItemTap = onItemTap,
                        onProfileDelete = onProfileDelete,
                    )
                }
                Spacer(Modifier.height(FooterGap))
                Spacer(Modifier.weight(1f))
                // Changing account and signing out are utilities, kept small and
                // below the people; they wrap rather than cut a label short.
                FlowRow(
                    Modifier.onSizeChanged { footerHeight = it.height },
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                ) {
                    MarqueeButton(
                        text = stringResource(R.string.picker_change_account),
                        onClick = onChangeAccount,
                        kind = MarqueeButtonKind.Plain,
                        fullWidth = false,
                        compact = true,
                        icon = Icons.Outlined.SwitchAccount,
                        enabled = enabled,
                    )
                    MarqueeButton(
                        text = "Sign out",
                        onClick = onSignOut,
                        kind = MarqueeButtonKind.Plain,
                        fullWidth = false,
                        compact = true,
                        icon = Icons.AutoMirrored.Outlined.Logout,
                        enabled = enabled,
                    )
                    if (state.canManageProfiles) {
                        MarqueeButton(
                            text = if (state.isManageMode) "Done" else "Manage",
                            onClick = onToggleManage,
                            kind = MarqueeButtonKind.Plain,
                            fullWidth = false,
                            compact = true,
                            icon = if (state.isManageMode) null else Icons.Outlined.ManageAccounts,
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
}

/** Rows are centered, so a short last row sits in the middle instead of hanging off the left. */
@Composable
private fun TileRows(
    state: ProfileSelectionUiState,
    items: List<PickerItem>,
    layout: ProfilePickerLayout?,
    enabled: Boolean,
    onPressChange: (Profile, Boolean) -> Unit,
    onItemTap: (PickerItem) -> Unit,
    onProfileDelete: (Profile) -> Unit,
) {
    val perRow = layout?.perRow ?: 3
    val avatar = (layout?.avatarSize ?: 0f).dp
    val addSize = (layout?.addSize ?: 0f).dp
    Column(
        Modifier.fillMaxWidth().alpha(if (layout == null) 0f else 1f),
        verticalArrangement = Arrangement.spacedBy((layout?.rowSpacing ?: 0f).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items.chunked(perRow).forEach { row ->
            // Top-aligned so a "Last used" caption doesn't lift its avatar above the rest.
            Row(verticalAlignment = Alignment.Top) {
                row.forEach { item ->
                    Box(Modifier.width((layout?.columnWidth ?: 0f).dp), contentAlignment = Alignment.TopCenter) {
                        when (item) {
                            is PickerItem.Person -> ProfileTile(
                                profile = item.profile,
                                size = avatar,
                                isLastUsed = item.profile.id == state.activeProfileId,
                                isManageMode = state.isManageMode,
                                enabled = enabled,
                                onPressChange = { onPressChange(item.profile, it) },
                                onTap = { onItemTap(item) },
                                onDelete = { onProfileDelete(item.profile) },
                            )
                            PickerItem.Add -> AddProfileTile(
                                size = addSize,
                                label = stringResource(R.string.picker_add),
                                enabled = enabled,
                                onClick = { onItemTap(item) },
                            )
                            PickerItem.AddProfile -> AddProfileTile(
                                size = addSize,
                                label = "Add profile",
                                enabled = enabled,
                                onClick = { onItemTap(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Names grow more slowly than avatars so large avatars keep short labels. */
private fun nameSize(avatar: Float) = 15f * (1f + (avatar / 92f - 1f) * 0.35f)

@Composable
private fun ProfileTile(
    profile: Profile,
    size: androidx.compose.ui.unit.Dp,
    isLastUsed: Boolean,
    isManageMode: Boolean,
    enabled: Boolean,
    onPressChange: (Boolean) -> Unit,
    onTap: () -> Unit,
    onDelete: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) { onPressChange(pressed) }
    Column(
        Modifier
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onTap)
            .marqueePressHaptic(interaction)
            .alpha(if (pressed) 0.8f else 1f)
            .alpha(if (enabled) 1f else 0.45f)
            .semantics(mergeDescendants = true) {
                contentDescription = profile.name
                // The tile merges its children, so the delete badge is
                // reached as an action rather than its own node.
                if (isManageMode && !profile.isPrimary) {
                    customActions = listOf(CustomAccessibilityAction("Delete ${profile.name}") { onDelete(); true })
                }
                stateDescription = listOfNotNull(
                    "Last used".takeIf { isLastUsed },
                    "PIN protected".takeIf { profile.hasPin },
                    "Kids profile".takeIf { profile.isChild },
                    "Edit".takeIf { isManageMode },
                ).joinToString(", ")
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            MarqueeProfileAvatar(profile, size = size, modifier = Modifier.alpha(if (isManageMode) 0.6f else 1f))
            if (isManageMode) {
                Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.28f))
                }
                // The server never deletes the primary profile (409
                // primary_profile_protected), so don't offer it.
                if (!profile.isPrimary) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MarqueeColors.Error)
                            .clickable(role = Role.Button, onClick = onDelete)
                            .semantics { contentDescription = "Delete ${profile.name}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Text(
            profile.name,
            color = MarqueeColors.Ink,
            fontSize = nameSize(size.value).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
        )
        if (isLastUsed) {
            Text("Last used", color = MarqueeColors.InkTertiary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun AddProfileTile(
    size: androidx.compose.ui.unit.Dp,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .marqueePressHaptic(interaction)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(size)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    drawCircle(
                        color = Color.White.copy(alpha = 0.25f),
                        radius = this.size.minDimension / 2f - stroke / 2f,
                        center = Offset(this.size.width / 2f, this.size.height / 2f),
                        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MarqueeColors.InkTertiary, modifier = Modifier.size(size * 0.3f))
        }
        Text(
            label,
            color = MarqueeColors.InkSecondary,
            fontSize = nameSize(size.value).sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
