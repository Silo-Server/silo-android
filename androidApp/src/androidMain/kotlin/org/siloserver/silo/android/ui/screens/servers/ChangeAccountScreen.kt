package org.siloserver.silo.android.ui.screens.servers

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.marquee.MarqueeHeadline
import org.siloserver.silo.android.ui.components.marquee.MarqueeIconButton
import org.siloserver.silo.android.ui.components.marquee.MarqueeTopBarSpacer
import org.siloserver.silo.android.ui.components.marquee.MarqueeWordmark
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeProfileAvatar
import org.siloserver.silo.common.ui.marquee.MarqueeScrim
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle
import org.siloserver.silo.common.ui.marquee.MarqueeServerMark
import org.siloserver.silo.common.ui.marquee.ServerBranding
import org.siloserver.silo.common.ui.marquee.ServerBrandingLoader
import org.siloserver.silo.common.ui.marquee.rememberSignInServer
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.server.AccountCard
import org.siloserver.silo.model.server.AccountServerGroup
import org.siloserver.silo.model.server.ServerEntry

private val CardShape = RoundedCornerShape(20.dp)
private val CardFill = Color.White.copy(alpha = 0.06f)

/**
 * Change Account (owner decision 2026-11-02, Roku spec §7.5): the saved
 * servers, the active one first and then by last use, each under a header
 * with its mark and name, its accounts in a card below ("alex · 3 profiles",
 * badged Current or Sign in again), and "Add an account" (or "Sign in on
 * {server}" when it has none) closing the card; "Add a server" last. One
 * grouped list on phones and tablets alike (the app has no two-pane idiom),
 * held to a readable width. A "More accounts below" pill shows while the
 * list continues past the screen.
 *
 * @param addAccountOn Scroll to (and focus) this server's Add an account row.
 * @param onBack Null when this is the root screen (no account open): Back leaves the app.
 */
@Composable
fun ChangeAccountScreen(
    addAccountOn: String?,
    onOpenPicker: () -> Unit,
    onReturnToPicker: () -> Unit,
    onSignIn: () -> Unit,
    onAddServer: () -> Unit,
    onBack: (() -> Unit)?,
    viewModel: ChangeAccountViewModel = koinViewModel(),
    brandingLoader: ServerBrandingLoader = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.destination) {
        val destination = state.destination ?: return@LaunchedEffect
        viewModel.onDestinationConsumed()
        when (destination) {
            ChangeAccountDestination.Picker -> onOpenPicker()
            ChangeAccountDestination.CurrentPicker -> onReturnToPicker()
            ChangeAccountDestination.SignIn -> onSignIn()
        }
    }
    BackHandler(enabled = onBack != null && !state.isWorking) { onBack?.invoke() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val addFocus = remember { FocusRequester() }
    // Once, when the groups first arrive: Add › Add an account lands on the
    // active server's row (any saved server's row is a scroll away).
    var scrolledToAdd by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(addAccountOn, state.groups.isNotEmpty()) {
        if (scrolledToAdd || addAccountOn == null || state.groups.isEmpty()) return@LaunchedEffect
        val index = addRowIndex(state.groups, addAccountOn) ?: return@LaunchedEffect
        scrolledToAdd = true
        listState.scrollToItem(index, scrollOffset = -listState.layoutInfo.viewportSize.height / 3)
        runCatching { addFocus.requestFocus() }
    }
    val moreBelow by remember { derivedStateOf { listState.canScrollForward } }
    val moreBelowAlpha by animateFloatAsState(if (moreBelow) 1f else 0f, label = "moreBelow")

    Box(Modifier.fillMaxSize()) {
        MarqueeScrim(MarqueeScrimStyle.BottomDeep)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    MarqueeIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(CommonR.string.common_navigateBack),
                        onClick = onBack,
                        enabled = !state.isWorking,
                    )
                } else {
                    MarqueeWordmark()
                }
                MarqueeTopBarSpacer()
                if (state.isWorking) {
                    CircularProgressIndicator(color = MarqueeColors.Ink, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 8.dp,
                        bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    item(key = "title") {
                        MarqueeHeadline(
                            stringResource(CommonR.string.changeAccount_title),
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                    }
                    state.groups.forEach { group ->
                        serverGroup(
                            group = group,
                            brandingLoader = brandingLoader,
                            enabled = !state.isWorking,
                            addFocus = addFocus.takeIf { group.server.id == addAccountOn },
                            onAccount = viewModel::onAccount,
                            onAddAccount = { viewModel.onAddAccount(group.server.id) },
                        )
                    }
                    item(key = "add-server") {
                        Spacer(Modifier.height(28.dp))
                        AddRow(
                            label = stringResource(CommonR.string.accounts_addAServer),
                            shape = CardShape,
                            enabled = !state.isWorking,
                            onClick = onAddServer,
                        )
                    }
                }
                // There is more below: a fade and a pill that scrolls on.
                if (moreBelowAlpha > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .alpha(moreBelowAlpha)
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        MoreBelowPill(
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem(
                                        (listState.firstVisibleItemIndex + 3).coerceAtMost(listState.layoutInfo.totalItemsCount - 1),
                                    )
                                }
                            },
                            modifier = Modifier
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(bottom = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The lazy index of [serverId]'s "Add an account" row: title, then per group a header, its accounts and Add. */
private fun addRowIndex(groups: List<AccountServerGroup>, serverId: String): Int? {
    var index = 1
    for (group in groups) {
        index += 1 + group.accounts.size
        if (group.server.id == serverId) return index
        index += 1
    }
    return null
}

private fun LazyListScope.serverGroup(
    group: AccountServerGroup,
    brandingLoader: ServerBrandingLoader,
    enabled: Boolean,
    addFocus: FocusRequester?,
    onAccount: (AccountCard) -> Unit,
    onAddAccount: () -> Unit,
) {
    val server = group.server
    item(key = "server-${server.id}") {
        ServerHeader(server, brandingLoader)
    }
    val rows = group.accounts.size + 1
    group.accounts.forEachIndexed { index, card ->
        item(key = "account-${card.account.localAccountId}") {
            AccountRow(
                card = card,
                shape = segmentShape(index, rows),
                showDivider = index > 0,
                enabled = enabled,
                onClick = { onAccount(card) },
            )
        }
    }
    item(key = "add-${server.id}") {
        AddRow(
            label = if (group.accounts.isEmpty()) {
                stringResource(CommonR.string.changeAccount_signInOn, server.displayName)
            } else {
                stringResource(CommonR.string.changeAccount_addAccount)
            },
            shape = segmentShape(rows - 1, rows),
            showDivider = rows > 1,
            enabled = enabled,
            onClick = onAddAccount,
            modifier = if (addFocus != null) Modifier.focusRequester(addFocus) else Modifier,
        )
    }
}

/** One card drawn across several lazy rows: only its ends are rounded. */
private fun segmentShape(index: Int, count: Int): Shape {
    val r = 20.dp
    val top = if (index == 0) r else 0.dp
    val bottom = if (index == count - 1) r else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** A server's header: its mark (the Silo icon when it has none) and name, over its address. */
@Composable
private fun ServerHeader(server: ServerEntry, loader: ServerBrandingLoader) {
    val branding = rememberSignInServer(serverUrl = server.url, savedName = server.fetchedName, loader = loader, tintsBackdrop = false)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 10.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MarqueeServerMark(
            name = server.displayName,
            imageUrl = branding.branding?.markUrl,
            size = 36.dp,
            siloIconFallback = true,
        )
        Column(Modifier.weight(1f)) {
            Text(
                server.displayName,
                color = MarqueeColors.Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                ServerBranding.hostLabel(server.url),
                color = MarqueeColors.InkTertiary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AccountRow(card: AccountCard, shape: Shape, showDivider: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val account = card.account
    val name = account.username?.takeIf { it.isNotBlank() } ?: stringResource(CommonR.string.account_fallbackName)
    val count = card.profileCount.takeIf { it > 0 }?.let { pluralStringResource(CommonR.plurals.account_profiles_count, it, it) }
    val badge = when {
        account.needsSignIn -> stringResource(CommonR.string.picker_signInAgain) to MarqueeColors.Warning
        card.isCurrent -> stringResource(CommonR.string.changeAccount_current) to MarqueeColors.Live
        else -> null
    }
    CardSegment(shape = shape, showDivider = showDivider, enabled = enabled, onClick = onClick, stateLabel = badge?.first) {
        MarqueeProfileAvatar(Profile(id = account.localAccountId, name = name), size = 40.dp, showsBadges = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = MarqueeColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count != null) {
                Text(count, color = MarqueeColors.InkSecondary, fontSize = 13.sp, maxLines = 1)
            }
        }
        if (badge != null) {
            Text(
                badge.first,
                color = badge.second,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(badge.second.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MarqueeColors.InkTertiary)
    }
}

@Composable
private fun AddRow(
    label: String,
    shape: Shape,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
) {
    CardSegment(shape = shape, showDivider = showDivider, enabled = enabled, onClick = onClick, modifier = modifier) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).border(1.dp, MarqueeColors.Hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = MarqueeColors.InkSecondary, modifier = Modifier.size(20.dp))
        }
        Text(label, color = MarqueeColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CardSegment(
    shape: Shape,
    showDivider: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stateLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().clip(shape).background(CardFill)) {
        if (showDivider) HorizontalDivider(Modifier.padding(start = 72.dp), thickness = 1.dp, color = MarqueeColors.Separator)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { if (stateLabel != null) stateDescription = stateLabel }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun MoreBelowPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, MarqueeColors.Hairline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(CommonR.string.changeAccount_moreBelow), color = MarqueeColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = MarqueeColors.Ink, modifier = Modifier.size(18.dp))
    }
}
