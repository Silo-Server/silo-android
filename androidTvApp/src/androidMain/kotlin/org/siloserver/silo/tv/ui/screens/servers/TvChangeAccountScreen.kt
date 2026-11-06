package org.siloserver.silo.tv.ui.screens.servers

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.GlobalContext
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.stringResourceWithNames
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
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeBody
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeHeadline
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.InterFamily

// Roku AccountsScreen and ServerCard at half scale (1920x1080 px to
// 960x540dp). The rail: rows 260 wide and 44 tall, 4 apart, a 28dp mark 8 in
// and the name 9 after it; Add server 8 under the last row. Text below the
// TV app's 14sp floor rises to it, except the compact badge. The accounts 20
// to its right: cards 380 wide and 64 tall, 8 apart, each a 40dp warm tile
// with the username's initial, the username over its profile count, and a
// badge. Each column scrolls; an arrow under it says when more rows are
// above or below.
private val RailWidth = 260.dp
private val RailRowHeight = 44.dp
private val RailRowGap = 4.dp
private val RailMark = 28.dp
private val RailInset = 8.dp
private val RailTextGap = 9.dp
private val AddServerGap = 8.dp
private val PaneGap = 20.dp
private val PaneWidth = 380.dp
private val CardHeight = 64.dp
private val CardGap = 8.dp
private val CardPadding = 12.dp
private val CardMark = 40.dp
private val CardTextGap = 11.dp
private val CardFocusScale = 1.03f
private val RailShape = RoundedCornerShape(8.dp)
private val CardShape = RoundedCornerShape(15.dp)
private val FocusFill = MarqueeColors.Ink
private val OnFocus = Color.Black
private val OnFocusFaint = Color.Black.copy(alpha = 0.40f)

private const val AddServerKey = "add-server"
private const val RailKeyPrefix = "server/"

private fun AccountCard.key() = "account/${account.localAccountId}"
private fun railKey(serverId: String) = "$RailKeyPrefix$serverId"
private fun addAccountKey(serverId: String) = "add/$serverId"

/**
 * Change Account on Android TV (owner decision 2026-11-02; Roku spec §7.5,
 * `AccountsScreen`, drawn as Roku draws it): under the Silo wordmark and the
 * headline, a rail of the saved servers, each with its mark and
 * name, the active server first and then by last use, with Add server under
 * them; beside it, the server focused on the rail's accounts, each with its
 * username's initial on a warm tile, the username, how many profiles it has,
 * and a badge, CURRENT for the open account and SIGN IN AGAIN for one whose session
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
        // Roku's first-run column without its card: the wordmark, the
        // headline and its line, then the rail and the accounts under them.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 45.dp, vertical = 30.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.silo_wordmark),
                contentDescription = "Silo", // i18n-ignore
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(32.dp).width(63.dp),
            )
            TvMarqueeHeadline(
                text = stringResource(CommonR.string.accounts_headline),
                maxLines = 1,
                modifier = Modifier.padding(top = 26.dp),
            )
            TvMarqueeBody(
                text = stringResource(CommonR.string.accounts_body),
                modifier = Modifier.padding(top = 10.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // The rail.
                ScrollingColumn(
                    width = RailWidth,
                    spacing = RailRowGap,
                    footer = {
                        Spacer(Modifier.height(AddServerGap))
                        RailRow(
                            name = stringResource(CommonR.string.accounts_addServer),
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
                        spacing = CardGap,
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
            if (state.changeFailed) {
                Text(
                    text = stringResource(CommonR.string.accounts_error_change),
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
                .verticalScroll(scroll)
                // Room for a focused card's 3% growth inside the scroll clip.
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        footer?.invoke(this)
        // Always takes its height, so the columns don't move when it shows.
        Row(
            modifier = Modifier.fillMaxWidth().height(26.dp).padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            ScrollArrow(Icons.Filled.KeyboardArrowUp, visible = scroll.canScrollBackward, label = stringResource(CommonR.string.servers_scroll_moreAbove))
            ScrollArrow(Icons.Filled.KeyboardArrowDown, visible = scroll.canScrollForward, label = stringResource(CommonR.string.servers_scroll_moreBelow))
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

/**
 * A server on the rail (or Add server): the focused row fills #EDEDED with
 * black text, the shown server keeps a dim fill (Roku CHROME_SELECTED_FILL).
 */
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
            .clip(RailShape)
            .background(
                when {
                    focused -> FocusFill
                    selected -> Color.White.copy(alpha = 0.14f)
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = RailInset),
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
                Icon(Icons.Filled.Add, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(RailTextGap))
        Text(
            text = name,
            fontFamily = InterFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One account: its initial, username, profile count, and CURRENT or SIGN IN AGAIN. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AccountCardRow(
    card: AccountCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = card.account.username?.takeIf { it.isNotBlank() } ?: stringResource(CommonR.string.settings_accounts_account)
    val badge = when {
        card.account.needsSignIn -> stringResource(CommonR.string.account_signInAgain)
        card.isCurrent -> stringResource(CommonR.string.changeAccount_current)
        else -> null
    }
    val count = card.profileCount.takeIf { it > 0 }?.let {
        pluralStringResource(CommonR.plurals.account_profiles_count, it, it)
    }
    ServerCardSurface(
        title = name,
        subtitle = count,
        initial = name.trim().first().uppercase(),
        onClick = onClick,
        modifier = modifier,
    ) { focused ->
        if (badge != null) Badge(badge, warning = card.account.needsSignIn, focused = focused)
    }
}

@Composable
private fun AddAccountCardRow(serverName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ServerCardSurface(
        title = stringResource(CommonR.string.accounts_addAccount),
        subtitle = stringResourceWithNames(CommonR.string.accounts_addAccountOn, serverName),
        initial = "+",
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * Roku's ServerCard: a warm tile with an initial, a bold title over a faint
 * line, and an optional trailing badge, on white 7% under a white 14%
 * outline. Focused, it fills #EDEDED with black text and grows 3%.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServerCardSurface(
    title: String,
    subtitle: String?,
    initial: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.(focused: Boolean) -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) CardFocusScale else 1f, label = "cardScale")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CardHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CardShape)
            .background(if (focused) FocusFill else Color.White.copy(alpha = 0.07f))
            .then(if (focused) Modifier else Modifier.border(1.dp, MarqueeColors.Hairline, CardShape))
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = CardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialTile(initial)
        Spacer(Modifier.width(CardTextGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = InterFamily,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = if (focused) OnFocus else MarqueeColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = if (focused) OnFocusFaint else MarqueeColors.InkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        trailing(focused)
    }
}

/** Roku's server_mark.png: the warm square, its initial in cream. */
@Composable
private fun InitialTile(initial: String) {
    Box(
        modifier = Modifier
            .size(CardMark)
            .clip(RoundedCornerShape(CardMark * 0.22f))
            .background(Brush.linearGradient(listOf(Color(0xFFEAA65C), Color(0xFFA8521D), Color(0xFF5E290D)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            fontFamily = InterFamily,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFF6EA),
        )
    }
}

/**
 * The card's badge, uppercased and tracked: neutral on black 45% under the
 * hairline, or the warning colour on itself at 8% under it at 35%. On the
 * focused card its fill turns near-opaque black so the text keeps contrast.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Badge(text: String, warning: Boolean, focused: Boolean) {
    val shape = RoundedCornerShape(50)
    val ink = if (warning) MarqueeColors.Warning else MarqueeColors.Ink
    val fill = when {
        focused -> Color.Black.copy(alpha = 0.85f)
        warning -> MarqueeColors.Warning.copy(alpha = 0.08f)
        else -> Color.Black.copy(alpha = 0.45f)
    }
    val line = if (warning) MarqueeColors.Warning.copy(alpha = 0.35f) else MarqueeColors.Hairline
    Text(
        text = text.uppercase(),
        fontFamily = InterFamily,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = ink,
        maxLines = 1,
        modifier = Modifier
            .padding(start = CardTextGap)
            .clip(shape)
            .background(fill)
            .border(1.dp, line, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
