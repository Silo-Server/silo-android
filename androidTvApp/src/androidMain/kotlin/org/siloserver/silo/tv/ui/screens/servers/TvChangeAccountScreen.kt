package org.siloserver.silo.tv.ui.screens.servers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.GlobalContext
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeScene
import org.siloserver.silo.common.ui.marquee.MarqueeScrim
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle
import org.siloserver.silo.common.ui.marquee.MarqueeServerMark
import org.siloserver.silo.common.ui.marquee.ServerBrandingLoader
import org.siloserver.silo.common.ui.marquee.rememberSignInServer
import org.siloserver.silo.model.server.AccountCard
import org.siloserver.silo.model.server.AccountServerGroup
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved

// The rail (Roku AccountsScreen, scaled to 960x540dp): rows 280 wide and 52
// tall, a 32dp mark; Add server under the rows. The accounts 28dp to its
// right: cards 440 wide and 64 tall. Each column scrolls; an arrow under it
// says when more rows are above or below.
private val RailWidth = 280.dp
private val RailRowHeight = 52.dp
private val RailMark = 32.dp
private val PaneGap = 28.dp
private val PaneWidth = 440.dp
private val CardHeight = 64.dp
private val CardAvatar = 40.dp
private val RowShape = RoundedCornerShape(12.dp)
private val FocusFill = MarqueeColors.Ink
private val OnFocus = Color(0xFF111114)

private const val AddServerKey = "add-server"
private const val RailKeyPrefix = "server/"

private fun AccountCard.key() = "account/${account.localAccountId}"
private fun railKey(serverId: String) = "$RailKeyPrefix$serverId"
private fun addAccountKey(serverId: String) = "add/$serverId"

/**
 * Change Account on Android TV (owner decision 2026-11-02; Roku spec §7.5,
 * `AccountsScreen`): a rail of the saved servers, each with its mark and
 * name, the active server first and then by last use, with Add server under
 * them; beside it, the server focused on the rail's accounts, each with its
 * username's initial, the username, how many profiles it has, and a badge,
 * Current for the open account and Sign in again for one whose session
 * expired; then "Add an account" on that server.
 *
 * Left and Right move between the rail and the accounts, Up and Down within
 * them. Focus starts on the open account, or with [addAccountOn] on that
 * server's "Add an account". On the root screen ([isRoot], no account open)
 * Back goes from the accounts to the rail first.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvChangeAccountScreen(
    addAccountOn: String?,
    isRoot: Boolean,
    onOpenPicker: (switched: Boolean) -> Unit,
    onSignIn: (username: String?, switched: Boolean) -> Unit,
    onAddServer: () -> Unit,
    viewModel: TvChangeAccountViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val groups = state.groups

    LaunchedEffect(Unit) { MarqueeScene.focus = MarqueeScene.Focus.Account }

    LaunchedEffect(state.navigation) {
        val navigation = state.navigation ?: return@LaunchedEffect
        viewModel.onNavigationConsumed()
        when (navigation) {
            is TvChangeAccountNavigation.Picker -> onOpenPicker(navigation.switched)
            is TvChangeAccountNavigation.SignIn -> onSignIn(navigation.username, navigation.switched)
            TvChangeAccountNavigation.AddServer -> onAddServer()
        }
    }

    var shownServerId by rememberSaveable { mutableStateOf(addAccountOn) }
    val shown = groups.firstOrNull { it.server.id == shownServerId } ?: groups.firstOrNull()

    val railRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val paneRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun railRequester(key: String) = railRequesters.getOrPut(key) { FocusRequester() }
    fun paneRequester(key: String) = paneRequesters.getOrPut(key) { FocusRequester() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val paneFocused = focusedKey?.let { it != AddServerKey && !it.startsWith(RailKeyPrefix) } == true

    /** Where Right from the rail lands: the open account, else the first card. */
    fun paneEntryKey(group: AccountServerGroup): String =
        group.accounts.firstOrNull { it.isCurrent }?.key()
            ?: group.accounts.firstOrNull()?.key()
            ?: addAccountKey(group.server.id)

    // Focus starts on the open account, or on "Add an account" when asked,
    // once the groups reflect the registry (the initial empty state would
    // anchor on Add server and keep it there).
    var anchored by remember { mutableStateOf(false) }
    LaunchedEffect(state.loaded) {
        if (!state.loaded || anchored) return@LaunchedEffect
        val group = shown
        val target = when {
            group == null -> AddServerKey
            addAccountOn != null && group.server.id == addAccountOn -> addAccountKey(group.server.id)
            else -> paneEntryKey(group)
        }
        requestFocusUntilObserved(
            maxAttempts = TvFrameRelocationMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = {
                val requester = if (target == AddServerKey) railRequesters[AddServerKey] else paneRequesters[target]
                requester?.requestFocus() ?: false
            },
            isFocused = { focusedKey == target },
        )
        anchored = true
    }

    // On the root screen there is nothing behind it: Back steps from the
    // accounts to the rail first (Roku), and leaves Silo from the rail.
    BackHandler(enabled = isRoot && paneFocused && shown != null) {
        shown?.let { railRequester(railKey(it.server.id)).requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        MarqueeScrim(MarqueeScrimStyle.Ambient)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 56.dp, end = 56.dp, top = 36.dp, bottom = 24.dp),
        ) {
            Text(
                text = "Change account",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp,
                color = MarqueeColors.Ink,
                maxLines = 1,
            )
            Text(
                text = "Choose a server, then the account to use on it.",
                fontSize = 14.sp,
                color = MarqueeColors.InkSecondary,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // The rail.
                ScrollingColumn(
                    width = RailWidth,
                    spacing = 6.dp,
                    footer = {
                        Spacer(Modifier.height(10.dp))
                        RailRow(
                            name = "Add server",
                            server = null,
                            selected = false,
                            modifier = Modifier
                                .focusRequester(railRequester(AddServerKey))
                                .focusProperties {
                                    shown?.let { right = paneRequester(paneEntryKey(it)) }
                                }
                                .onFocusChanged { if (it.isFocused) focusedKey = AddServerKey },
                            onClick = viewModel::onAddServer,
                        )
                    },
                ) {
                    groups.forEach { group ->
                        val key = railKey(group.server.id)
                        RailRow(
                            name = group.server.displayName,
                            server = group.server,
                            selected = group.server.id == shown?.server?.id,
                            modifier = Modifier
                                .focusRequester(railRequester(key))
                                .focusProperties { right = paneRequester(paneEntryKey(group)) }
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        focusedKey = key
                                        shownServerId = group.server.id
                                    }
                                },
                            onClick = { paneRequester(paneEntryKey(group)).requestFocus() },
                        )
                    }
                }
                Spacer(Modifier.width(PaneGap))
                // The shown server's accounts, then "Add an account" on it.
                val group = shown
                if (group != null) {
                    val serverRailKey = railKey(group.server.id)
                    ScrollingColumn(
                        width = PaneWidth,
                        spacing = 10.dp,
                        scrollKey = group.server.id,
                    ) {
                        group.accounts.forEach { card ->
                            val key = card.key()
                            AccountCardRow(
                                card = card,
                                modifier = Modifier
                                    .focusRequester(paneRequester(key))
                                    .focusProperties {
                                        left = railRequester(serverRailKey)
                                        right = FocusRequester.Cancel
                                    }
                                    .onFocusChanged { if (it.isFocused) focusedKey = key },
                                onClick = { viewModel.onAccountChosen(card) },
                            )
                        }
                        val addKey = addAccountKey(group.server.id)
                        AddAccountCardRow(
                            serverName = group.server.displayName,
                            modifier = Modifier
                                .focusRequester(paneRequester(addKey))
                                .focusProperties {
                                    left = railRequester(serverRailKey)
                                    right = FocusRequester.Cancel
                                }
                                .onFocusChanged { if (it.isFocused) focusedKey = addKey },
                            onClick = { viewModel.onAddAccount(group.server.id) },
                        )
                    }
                }
            }
            state.error?.let {
                Text(
                    text = it,
                    fontSize = 14.sp,
                    color = MarqueeColors.Error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * A column of rows that scrolls when they don't fit, one focus group, with
 * an arrow under it while rows wait above or below. [footer] stays under the
 * scrolling rows.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ScrollingColumn(
    width: Dp,
    spacing: Dp,
    scrollKey: Any? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = remember(scrollKey) { ScrollState(0) }
    Column(modifier = Modifier.width(width).fillMaxHeight().focusGroup()) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        footer?.invoke(this)
        // Always takes its height, so the columns don't move when it shows.
        Row(
            modifier = Modifier.fillMaxWidth().height(26.dp).padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            ScrollArrow(Icons.Filled.KeyboardArrowUp, visible = scroll.canScrollBackward, label = "More above")
            ScrollArrow(Icons.Filled.KeyboardArrowDown, visible = scroll.canScrollForward, label = "More below")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ScrollArrow(icon: ImageVector, visible: Boolean, label: String) {
    if (!visible) return
    Icon(
        icon,
        contentDescription = label,
        tint = MarqueeColors.InkSecondary,
        modifier = Modifier.size(22.dp),
    )
}

/** A server on the rail (or Add server): the focused row fills, the shown server keeps a dim fill. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RailRow(
    name: String,
    server: ServerEntry?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val ink = if (focused) OnFocus else MarqueeColors.Ink
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(RailRowHeight)
            .clip(RowShape)
            .background(
                when {
                    focused -> FocusFill
                    selected -> Color.White.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (server != null) {
            MarqueeServerMark(
                name = server.displayName,
                imageUrl = rememberServerMarkUrl(server),
                size = RailMark,
                siloIconFallback = true,
            )
        } else {
            Box(Modifier.size(RailMark), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One account: its initial, username, profile count, and Current or Sign in again. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AccountCardRow(
    card: AccountCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = card.account.username?.takeIf { it.isNotBlank() } ?: "Account"
    val badge = when {
        card.account.needsSignIn -> "Sign in again"
        card.isCurrent -> "Current"
        else -> null
    }
    val count = when (card.profileCount) {
        0 -> null
        1 -> "1 profile"
        else -> "${card.profileCount} profiles"
    }
    CardSurface(
        onClick = onClick,
        modifier = modifier,
    ) { focused ->
        val ink = if (focused) OnFocus else MarqueeColors.Ink
        Box(
            modifier = Modifier
                .size(CardAvatar)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF5B6270), Color(0xFF2C3038)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().first().uppercase(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                Text(
                    text = count,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.62f),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (badge != null) Badge(badge, warning = card.account.needsSignIn, focused = focused)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddAccountCardRow(serverName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CardSurface(onClick = onClick, modifier = modifier) { focused ->
        val ink = if (focused) OnFocus else MarqueeColors.Ink
        Box(
            modifier = Modifier
                .size(CardAvatar)
                .border(2.dp, ink.copy(alpha = if (focused) 0.6f else 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Add an account", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
            Text(
                text = "Sign in on $serverName",
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** A pane card: white 7% at rest, filled when focused. */
@Composable
private fun CardSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CardHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) FocusFill else Color.White.copy(alpha = 0.07f))
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(focused)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Badge(text: String, warning: Boolean, focused: Boolean) {
    val color = when {
        warning && focused -> Color(0xFF8A5A00)
        warning -> MarqueeColors.Warning
        focused -> OnFocus
        else -> MarqueeColors.Ink
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * [server]'s branded `mark_url`, without retinting the backdrop; null when it
 * has none (the mark then shows the Silo project icon).
 */
@Composable
internal fun rememberServerMarkUrl(server: ServerEntry): String? {
    val loader = remember { GlobalContext.getOrNull()?.getOrNull<ServerBrandingLoader>() } ?: return null
    return rememberSignInServer(
        serverUrl = server.url,
        savedName = server.fetchedName,
        loader = loader,
        tintsBackdrop = false,
    ).branding?.markUrl
}
