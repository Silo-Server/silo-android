package org.siloserver.silo.android.ui.screens.watchparty

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.siloserver.silo.android.ui.components.SiloDropdownMenu
import org.siloserver.silo.android.ui.theme.SiloDestructive
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.watchtogether.GuestControlPolicy
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.watchtogether.WatchPartyConnectionTone
import org.siloserver.silo.watchtogether.WatchPartyEligibility
import org.siloserver.silo.watchtogether.WatchPartyFeatures
import org.siloserver.silo.watchtogether.WatchPartyPrimaryAction
import org.siloserver.silo.watchtogether.WatchPartySecondaryAction
import org.siloserver.silo.watchtogether.canRemoveSuggestion
import org.siloserver.silo.watchtogether.roomVoteWinner
import org.siloserver.silo.watchtogether.watchPartyConnectionLabel
import org.siloserver.silo.watchtogether.watchPartyLobbyBanner
import org.siloserver.silo.watchtogether.watchPartyLobbyHero
import org.siloserver.silo.watchtogether.watchPartyLobbyHint
import org.siloserver.silo.watchtogether.watchPartyPrimaryAction
import org.siloserver.silo.watchtogether.watchPartySecondaryAction

/*
 * The room itself, shared by the lobby screen and the party panel over the
 * player (Apple's `WatchPartyLobbyView`, which `WatchPartyRoomPanel` reuses).
 * The staged film's artwork fills the page; the code pill, connection dot and
 * options sit on top, the hero and seats in the middle, and the one
 * foregrounded action is pinned to the bottom.
 */

/** Everything the room can ask its host screen to do. */
internal interface WatchPartyRoomActions {
    fun invite()
    fun chooseTitle() {}
    fun suggest() {}
    fun start() {}
    fun setLobbyReady(ready: Boolean) {}
    fun returnToPlayback()
    fun setMode(mode: RoomSelectionMode) {}
    fun setPolicy(policy: GuestControlPolicy)
    fun returnEveryoneToLobby() {}
    fun leave()
    fun end()
    fun vote(suggestion: Suggestion) {}
    fun promote(suggestion: Suggestion) {}
    fun queue(suggestion: Suggestion) {}
    fun remove(suggestion: Suggestion) {}
}

/** The staged title as the room shows it: a caller's preview until the catalog read lands. */
internal data class WatchPartyStagedTitle(
    val title: String?,
    val subtitle: String?,
    val posterUrl: String?,
    val posterThumbhash: String?,
    val backdropUrl: String?,
    val backdropThumbhash: String?,
    val facts: List<String>,
    val chips: List<String>,
    val overview: String?,
    /** The catalog refused this profile the title (403/404). */
    val unavailable: Boolean,
)

@Composable
internal fun rememberWatchPartyStagedTitle(room: RoomSnapshot?, suggestions: List<Suggestion>): WatchPartyStagedTitle? {
    val handoff: WatchPartyHandoff = koinInject()
    val catalog: CatalogRepository = koinInject()
    val stagedId = room?.selectedContentId?.takeIf { it.isNotBlank() }
    val libraryId = room?.selectedLibraryId
    var detail by remember(stagedId) { mutableStateOf<ItemDetail?>(null) }
    var unavailable by remember(stagedId) { mutableStateOf(false) }
    LaunchedEffect(stagedId, libraryId) {
        val id = stagedId ?: return@LaunchedEffect
        when (val result = catalog.getItemDetail(id, libraryId)) {
            is ApiResult.Success -> detail = result.data
            is ApiResult.Error -> unavailable = result.code == 403 || result.code == 404
            is ApiResult.NetworkError -> Unit
        }
    }
    stagedId ?: return null
    val preview = handoff.preview(stagedId) ?: suggestions.firstOrNull { it.contentId == stagedId }?.toPartyItem()
    val loaded = detail
    if (loaded == null) {
        return WatchPartyStagedTitle(
            title = preview?.title,
            subtitle = preview?.subtitle,
            posterUrl = preview?.posterUrl,
            posterThumbhash = null,
            backdropUrl = null,
            backdropThumbhash = null,
            facts = emptyList(),
            chips = emptyList(),
            overview = null,
            unavailable = unavailable,
        )
    }
    val episode = loaded.type == "episode"
    val versions = loaded.versions
    val version = versions.firstOrNull { it.fileId == room.selectedFileId } ?: versions.singleOrNull()
    return WatchPartyStagedTitle(
        title = loaded.title,
        subtitle = if (episode) loaded.episodeSubtitle() else null,
        posterUrl = loaded.posterUrl ?: preview?.posterUrl,
        posterThumbhash = loaded.posterThumbhash,
        backdropUrl = loaded.backdropUrl,
        backdropThumbhash = loaded.backdropThumbhash,
        facts = listOfNotNull(
            loaded.year.takeIf { it > 0 && !episode }?.toString(),
            watchPartyRuntime(loaded.runtime),
        ),
        chips = watchPartyQualityChips(version),
        overview = loaded.overview?.takeIf { it.isNotBlank() },
        unavailable = false,
    )
}

private fun ItemDetail.episodeSubtitle(): String? {
    val series = seriesTitle?.takeIf { it.isNotBlank() } ?: return null
    val season = seasonNumber
    val episode = episodeNumber
    return if (season != null && episode != null) "$series · S$season:E$episode" else series
}

internal fun Suggestion.toPartyItem() = WatchPartyItem(
    contentId = contentId,
    contentType = contentType,
    title = title,
    subtitle = subtitle.ifBlank { null },
    posterUrl = posterUrl.ifBlank { null },
)

@Composable
internal fun WatchPartyRoomContent(
    room: RoomSnapshot,
    staged: WatchPartyStagedTitle?,
    suggestions: List<Suggestion>,
    personalVotesKnown: Boolean,
    features: WatchPartyFeatures?,
    eligibility: WatchPartyEligibility,
    /** The room socket is writable. */
    connected: Boolean,
    /** A disconnection has lasted long enough to mention (two seconds). */
    showReconnect: Boolean,
    /** Still connecting for the first time rather than reconnecting. */
    firstConnect: Boolean,
    actions: WatchPartyRoomActions,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    extraBanner: (@Composable () -> Unit)? = null,
) {
    val lobby = room.phase == RoomPhase.Lobby
    val voteLobby = lobby && room.selectionMode == RoomSelectionMode.Vote
    val unavailable = staged?.unavailable == true
    val winner = roomVoteWinner(suggestions)
    // The film is the room: its backdrop, else its poster, else a voting
    // room's leader, blurred.
    val leaderPoster = if (room.selectionMode == RoomSelectionMode.Vote) {
        (winner ?: suggestions.firstOrNull())?.posterUrl?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    val backdrop = staged?.backdropUrl ?: staged?.posterUrl ?: leaderPoster
    val backdropIsPoster = staged?.backdropUrl == null

    val primary = watchPartyPrimaryAction(room, features, suggestions, unavailable)
    val secondary = watchPartySecondaryAction(room, primary)
    val hint = watchPartyLobbyHint(room, suggestions)
    val banner = watchPartyLobbyBanner(room, unavailable, reconnecting = showReconnect)?.let {
        if (showReconnect && firstConnect && it == "Reconnecting to the party…") "Connecting to the party…" else it
    }

    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            WatchPartyCodePill(code = room.code, onClick = actions::invite)
            Spacer(Modifier.width(10.dp))
            ConnectionDot(room, connected, reconnecting = !connected && !firstConnect)
            Spacer(Modifier.weight(1f))
            WatchPartyOptionsMenu(room, features, eligibility, actions)
        }
    }
    val middle: @Composable ColumnScope.(compact: Boolean) -> Unit = { compact ->
        RoomHero(room, staged, suggestions, compact)
        if (banner != null) {
            val warning = banner != "Reconnecting to the party…" && banner != "Connecting to the party…"
            WatchPartyBanner(banner, tone = if (warning) WatchPartyBannerTone.Warning else WatchPartyBannerTone.Neutral)
        }
        extraBanner?.invoke()
        if (voteLobby) {
            WatchPartyBallot(room, suggestions, personalVotesKnown, eligibility, connected, actions)
        } else if (lobby && room.selectionMode == RoomSelectionMode.HostPick && suggestions.isNotEmpty()) {
            WatchPartyHostPickSuggestions(room, suggestions, eligibility, connected, actions)
        }
        WatchPartySeatsRow(members = room.members, phase = room.phase, onInvite = actions::invite)
    }
    val buttons: @Composable ColumnScope.() -> Unit = {
        PrimaryButton(primary, room, winner, eligibility, connected, actions)
        when (secondary) {
            WatchPartySecondaryAction.ChangeTitle -> WatchPartyButton(
                text = "Change title",
                kind = WatchPartyButtonKind.Secondary,
                enabled = eligibility.canStage,
                onClick = actions::chooseTitle,
                modifier = Modifier.fillMaxWidth(),
            )
            WatchPartySecondaryAction.Suggest -> WatchPartyButton(
                text = if (primary is WatchPartyPrimaryAction.Start) "Suggest" else "Suggest a title",
                icon = Icons.Filled.Add,
                kind = WatchPartyButtonKind.Secondary,
                enabled = eligibility.canSuggest,
                onClick = actions::suggest,
                modifier = Modifier.fillMaxWidth(),
            )
            null -> Unit
        }
        if (hint != null) WatchPartyHint(hint)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        WatchPartyBackdrop(
            url = backdrop,
            thumbhash = if (backdropIsPoster) staged?.posterThumbhash else staged?.backdropThumbhash,
            isPoster = backdropIsPoster,
        )
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            if (!landscape) {
                WatchPartyTopBar(onBack = onBack)
                Column(
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = WatchPartyMetrics.pageInset)
                        .padding(top = 8.dp, bottom = 16.dp),
                ) {
                    header()
                    middle(false)
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WatchPartyBottomScrim)
                        .padding(horizontal = WatchPartyMetrics.pageInset)
                        .padding(top = 12.dp, bottom = 8.dp)
                        .navigationBarsPadding(),
                    content = buttons,
                )
            } else {
                // Over the landscape player: one bar across the top, the room on
                // the left, the actions bottom-right, as on tvOS.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = WatchPartyMetrics.pageInset, vertical = 10.dp),
                ) {
                    if (onBack != null) {
                        WatchPartyCircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Navigate back", onBack, size = 40.dp)
                        Spacer(Modifier.width(14.dp))
                    }
                    Text("Watch Party", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = SiloOnSurface)
                    Spacer(Modifier.width(14.dp))
                    ConnectionDot(room, connected, reconnecting = !connected && !firstConnect)
                    Spacer(Modifier.weight(1f))
                    WatchPartyCodePill(code = room.code, onClick = actions::invite)
                    Spacer(Modifier.width(12.dp))
                    WatchPartyOptionsMenu(room, features, eligibility, actions)
                }
                Row(Modifier.weight(1f).padding(horizontal = WatchPartyMetrics.pageInset)) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp)
                            .navigationBarsPadding(),
                    ) {
                        middle(true)
                    }
                    Spacer(Modifier.width(28.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight()
                            .padding(bottom = 16.dp)
                            .navigationBarsPadding(),
                        content = buttons,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionDot(room: RoomSnapshot, connected: Boolean, reconnecting: Boolean) {
    val (tone, label) = watchPartyConnectionLabel(room, connected, reconnecting)
    val color = when (tone) {
        WatchPartyConnectionTone.Good -> WatchPartyColors.emerald
        WatchPartyConnectionTone.Warning -> WatchPartyColors.amber
        WatchPartyConnectionTone.Neutral -> SiloSecondaryText
        WatchPartyConnectionTone.Bad -> WatchPartyColors.rose
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Box(Modifier.size((WatchPartyMetrics.CAPTION * 0.55f).dp).clip(CircleShape).background(color))
        Text(label, fontSize = WatchPartyMetrics.CAPTION.sp, color = SiloSecondaryText, maxLines = 1)
    }
}

/** Eyebrow + title + facts, with the staged poster beside it. A voting lobby asks the question instead. */
@Composable
private fun RoomHero(room: RoomSnapshot, staged: WatchPartyStagedTitle?, suggestions: List<Suggestion>, compact: Boolean) {
    val hero = watchPartyLobbyHero(room, suggestions, staged?.unavailable == true)
    val showsPoster = !room.selectedContentId.isNullOrBlank() && !hero.isVoteQuestion
    val titleSize = if (compact) 24f else WatchPartyMetrics.HERO_TITLE
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(top = if (compact) 4.dp else if (showsPoster) 120.dp else 40.dp),
    ) {
        if (showsPoster) {
            WatchPartyHeroPoster(staged?.posterUrl, staged?.posterThumbhash, if (compact) 72.dp else 112.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy((titleSize * 0.28f).dp), modifier = Modifier.weight(1f)) {
            WatchPartyEyebrow(hero.eyebrow)
            val title = hero.title ?: staged?.title
            WatchPartyHeroTitle(
                text = title ?: "Loading title…",
                color = if (title == null || staged?.unavailable == true) SiloSecondaryText else SiloOnSurface,
                size = titleSize,
            )
            val selected = !room.selectedContentId.isNullOrBlank() && !hero.isVoteQuestion
            if (selected && staged != null) FactsLine(staged)
            hero.supporting?.let {
                Text(
                    it,
                    fontSize = if (selected) WatchPartyMetrics.CAPTION.sp else WatchPartyMetrics.BODY.sp,
                    color = if (selected) SiloSecondaryText.copy(alpha = 0.7f) else SiloSecondaryText,
                )
            }
        }
    }
}

@Composable
private fun FactsLine(staged: WatchPartyStagedTitle) {
    val parts = listOfNotNull(staged.subtitle?.takeIf { it.isNotBlank() }) + staged.facts
    if (parts.isEmpty() && staged.chips.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (parts.isNotEmpty()) {
            Text(
                buildAnnotatedString {
                    parts.forEachIndexed { index, part ->
                        if (index > 0) withStyle(SpanStyle(color = SiloSecondaryText.copy(alpha = 0.5f))) { append("  ·  ") }
                        append(part)
                    }
                },
                fontSize = (WatchPartyMetrics.BODY - 1).sp,
                color = SiloSecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        staged.chips.forEach { chip ->
            Text(
                chip,
                fontSize = (WatchPartyMetrics.CAPTION * 0.85f).sp,
                fontWeight = FontWeight.SemiBold,
                color = SiloSecondaryText,
                maxLines = 1,
                modifier = Modifier
                    .border(1.dp, SiloSecondaryText.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun PrimaryButton(
    primary: WatchPartyPrimaryAction,
    room: RoomSnapshot,
    winner: Suggestion?,
    eligibility: WatchPartyEligibility,
    connected: Boolean,
    actions: WatchPartyRoomActions,
) {
    val full = Modifier.fillMaxWidth()
    when (primary) {
        WatchPartyPrimaryAction.ChooseTitle -> WatchPartyButton(
            text = if (room.selectedContentId.isNullOrBlank()) "Choose a title" else "Change title",
            enabled = eligibility.canStage,
            onClick = actions::chooseTitle,
            modifier = full,
        )
        is WatchPartyPrimaryAction.Start -> {
            // A voting room starts its leader; a host-pick room its staged title.
            val vote = room.selectionMode == RoomSelectionMode.Vote
            WatchPartyButton(
                text = primary.title?.let { "Start $it" } ?: "Start for everyone",
                icon = Icons.Filled.PlayArrow,
                enabled = connected && if (vote) eligibility.canPromote && winner != null else eligibility.canStart,
                onClick = { if (vote) winner?.let(actions::promote) else actions.start() },
                modifier = full,
            )
        }
        WatchPartyPrimaryAction.WaitingForVotes -> WatchPartyButton(
            text = "Suggest a title",
            icon = Icons.Filled.Add,
            enabled = eligibility.canSuggest,
            onClick = actions::suggest,
            modifier = full,
        )
        is WatchPartyPrimaryAction.Ready -> if (primary.isReady) {
            WatchPartyButton(
                text = "You're ready",
                icon = Icons.Filled.Check,
                kind = WatchPartyButtonKind.Outlined,
                enabled = connected && eligibility.canLobbyReady,
                onClick = { actions.setLobbyReady(false) },
                modifier = full,
                trailing = {
                    Text(
                        "  tap to undo",
                        fontSize = WatchPartyMetrics.CAPTION.sp,
                        color = SiloOnSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                },
            )
        } else {
            WatchPartyButton(
                text = "I'm ready",
                icon = Icons.Outlined.CheckCircle,
                enabled = connected && eligibility.canLobbyReady,
                onClick = { actions.setLobbyReady(true) },
                modifier = full,
            )
        }
        WatchPartyPrimaryAction.ReturnToPlayback -> WatchPartyButton(
            text = "Return to playback",
            icon = Icons.Filled.PlayArrow,
            onClick = actions::returnToPlayback,
            modifier = full,
        )
        WatchPartyPrimaryAction.None -> Unit
    }
}

private enum class OptionsPage { Main, Mode, Policy }

/**
 * The "···" menu. Settings open a second page that shows their choices, so
 * the top level reads as one aligned list with each setting's current value.
 */
@Composable
private fun WatchPartyOptionsMenu(
    room: RoomSnapshot,
    features: WatchPartyFeatures?,
    eligibility: WatchPartyEligibility,
    actions: WatchPartyRoomActions,
) {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(OptionsPage.Main) }
    val host = eligibility.isHost
    fun close() {
        open = false
        page = OptionsPage.Main
    }
    Box {
        WatchPartyCircleButton(
            icon = Icons.Filled.MoreHoriz,
            contentDescription = "Party options",
            onClick = { open = true },
        )
        SiloDropdownMenu(expanded = open, onDismissRequest = ::close) {
            val vote = room.selectionMode == RoomSelectionMode.Vote
            val guestsControl = room.guestControlPolicy == GuestControlPolicy.GuestPlayPause
            when (page) {
                OptionsPage.Main -> {
                    if (host) {
                        if (room.phase == RoomPhase.Lobby && features?.selectionModeSwitch == true) {
                            OptionRow(
                                "Who chooses",
                                icon = Icons.Outlined.PanTool,
                                value = if (vote) "Everyone votes" else "Host",
                                enabled = eligibility.canSwitchMode,
                            ) { page = OptionsPage.Mode }
                        }
                        OptionRow(
                            "Play & pause",
                            icon = Icons.Outlined.SmartDisplay,
                            value = if (guestsControl) "Host and guests" else "Host only",
                            enabled = eligibility.canSetPolicy,
                        ) { page = OptionsPage.Policy }
                        if (room.phase == RoomPhase.Playing && features?.stopPlayback == true) {
                            OptionRow(
                                "Return everyone to lobby",
                                icon = Icons.AutoMirrored.Filled.Undo,
                                enabled = eligibility.canStop,
                            ) {
                                close()
                                actions.returnEveryoneToLobby()
                            }
                        }
                        HorizontalDivider(color = WatchPartyColors.chromeBorder)
                    }
                    OptionRow("Leave party", icon = Icons.AutoMirrored.Filled.Logout) {
                        close()
                        actions.leave()
                    }
                    if (host) {
                        OptionRow("End party", icon = Icons.Outlined.HighlightOff, destructive = true, enabled = eligibility.canEnd) {
                            close()
                            actions.end()
                        }
                    }
                }
                OptionsPage.Mode -> {
                    OptionRow("Who chooses", icon = Icons.AutoMirrored.Filled.ArrowBack, header = true) { page = OptionsPage.Main }
                    OptionRow("Host", checked = !vote, enabled = eligibility.canSwitchMode) {
                        close()
                        if (vote) actions.setMode(RoomSelectionMode.HostPick)
                    }
                    OptionRow("Everyone votes", checked = vote, enabled = eligibility.canSwitchMode) {
                        close()
                        if (!vote) actions.setMode(RoomSelectionMode.Vote)
                    }
                }
                OptionsPage.Policy -> {
                    OptionRow("Play & pause", icon = Icons.AutoMirrored.Filled.ArrowBack, header = true) { page = OptionsPage.Main }
                    OptionRow("Host only", checked = !guestsControl, enabled = eligibility.canSetPolicy) {
                        close()
                        if (guestsControl) actions.setPolicy(GuestControlPolicy.HostOnly)
                    }
                    OptionRow("Host and guests", checked = guestsControl, enabled = eligibility.canSetPolicy) {
                        close()
                        if (!guestsControl) actions.setPolicy(GuestControlPolicy.GuestPlayPause)
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    icon: ImageVector? = null,
    value: String? = null,
    checked: Boolean? = null,
    destructive: Boolean = false,
    header: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = if (destructive) SiloDestructive else SiloOnSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = 240.dp)
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        when {
            checked != null -> Box(Modifier.size(20.dp)) {
                if (checked) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = color, modifier = Modifier.size(20.dp))
            }
            icon != null -> Icon(icon, contentDescription = null, tint = if (header) SiloSecondaryText else color, modifier = Modifier.size(20.dp))
        }
        if (checked != null || icon != null) Spacer(Modifier.width(12.dp))
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            color = if (header) SiloSecondaryText else color,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            Text(value, fontSize = 14.sp, color = SiloSecondaryText, maxLines = 1)
        }
    }
}

// MARK: Ballot (vote mode)

/** Candidates with vote counts and a vote toggle. The leader is highlighted; Suggest is the dashed row. */
@Composable
private fun WatchPartyBallot(
    room: RoomSnapshot,
    suggestions: List<Suggestion>,
    personalVotesKnown: Boolean,
    eligibility: WatchPartyEligibility,
    connected: Boolean,
    actions: WatchPartyRoomActions,
) {
    val leaderId = roomVoteWinner(suggestions)?.id
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        suggestions.forEach { suggestion ->
            CandidateRow(
                suggestion = suggestion,
                isLeader = suggestion.id == leaderId,
                votesKnown = personalVotesKnown,
                canVote = eligibility.canVote && connected,
                canPromote = eligibility.canPromote,
                canRemove = !eligibility.busy && canRemoveSuggestion(room, suggestion),
                actions = actions,
            )
        }
        if (suggestions.isEmpty()) {
            Text(
                "Nothing suggested yet. Anyone can add a title.",
                fontSize = WatchPartyMetrics.CAPTION.sp,
                color = SiloSecondaryText,
            )
        }
        val dashColor = SiloSecondaryText.copy(alpha = 0.5f)
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .alpha(if (eligibility.canSuggest) 1f else 0.45f)
                .clip(RoundedCornerShape(14.dp))
                .drawBehind {
                    drawRoundRect(
                        color = dashColor,
                        cornerRadius = CornerRadius(14.dp.toPx()),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                    )
                }
                .clickable(enabled = eligibility.canSuggest, role = Role.Button, onClick = actions::suggest),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = SiloSecondaryText, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Suggest a title", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = SiloSecondaryText)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CandidateRow(
    suggestion: Suggestion,
    isLeader: Boolean,
    votesKnown: Boolean,
    canVote: Boolean,
    canPromote: Boolean,
    canRemove: Boolean,
    actions: WatchPartyRoomActions,
) {
    var menu by remember { mutableStateOf(false) }
    val voted = suggestion.votedByMe
    val voteEnabled = votesKnown && canVote
    val hasMenu = canPromote || canRemove
    val shape = RoundedCornerShape(14.dp)
    val votes = "${suggestion.voteCount} ${if (suggestion.voteCount == 1) "vote" else "votes"}"
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (isLeader) WatchPartyColors.chromeSelectedFill else WatchPartyColors.chromeFill)
                .border(1.dp, if (isLeader) SiloOnSurface.copy(alpha = 0.35f) else WatchPartyColors.chromeBorder, shape)
                .combinedClickable(
                    enabled = voteEnabled || hasMenu,
                    onClick = { if (voteEnabled) actions.vote(suggestion) },
                    onLongClick = if (hasMenu) ({ menu = true }) else null,
                    onLongClickLabel = "More actions",
                )
                .semantics {
                    contentDescription = "${suggestion.title}, $votes" + if (isLeader) ", leading" else ""
                    customActions = buildList {
                        if (canPromote) add(CustomAccessibilityAction("Start this one") { actions.promote(suggestion); true })
                        if (canRemove) add(CustomAccessibilityAction("Remove suggestion") { actions.remove(suggestion); true })
                    }
                }
                .padding(8.dp),
        ) {
            WatchPartyPoster(suggestion.posterUrl, null, WatchPartyMetrics.ballotPosterWidth, cornerRadius = 6.dp)
            SuggestionText(suggestion, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 48.dp)) {
                Text(
                    "${suggestion.voteCount}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = SiloOnSurface,
                )
                Text(
                    if (suggestion.voteCount == 1) "VOTE" else "VOTES",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = SiloSecondaryText,
                )
            }
            val toggleShape = RoundedCornerShape(12.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .alpha(if (voteEnabled) 1f else 0.45f)
                    .clip(toggleShape)
                    .background(if (voted && votesKnown) SiloOnSurface else Color.Transparent)
                    .border(1.dp, if (voted && votesKnown) Color.Transparent else WatchPartyColors.outline, toggleShape)
                    .clickable(enabled = voteEnabled, role = Role.Button) { actions.vote(suggestion) }
                    .semantics {
                        contentDescription = when {
                            !votesKnown -> "Loading your vote"
                            voted -> "Remove vote for ${suggestion.title}"
                            else -> "Vote for ${suggestion.title}"
                        }
                    },
            ) {
                Icon(
                    when {
                        !votesKnown -> Icons.Outlined.Schedule
                        voted -> Icons.Filled.Check
                        else -> Icons.Filled.Add
                    },
                    contentDescription = null,
                    tint = if (voted && votesKnown) Color.Black else SiloSecondaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        SiloDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (canPromote) {
                OptionRow("Start this one", icon = Icons.Filled.PlayArrow) {
                    menu = false
                    actions.promote(suggestion)
                }
            }
            if (canRemove) {
                OptionRow("Remove suggestion", icon = Icons.Outlined.Delete, destructive = true) {
                    menu = false
                    actions.remove(suggestion)
                }
            }
        }
    }
}

@Composable
private fun SuggestionText(suggestion: Suggestion, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            suggestion.title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = SiloOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val detail = listOfNotNull(
            suggestion.subtitle.takeIf { it.isNotBlank() },
            suggestion.note.takeIf { it.isNotBlank() }?.let { "“$it”" },
        ).joinToString(" · ")
        if (detail.isNotEmpty()) {
            Text(detail, fontSize = 12.sp, color = SiloSecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// MARK: Suggestions (host-pick mode)

/**
 * Guests' suggestions in a host-pick lobby. The host queues one as the pick
 * ("Up next") rather than starting it; anyone can pull their own.
 */
@Composable
private fun WatchPartyHostPickSuggestions(
    room: RoomSnapshot,
    suggestions: List<Suggestion>,
    eligibility: WatchPartyEligibility,
    connected: Boolean,
    actions: WatchPartyRoomActions,
) {
    val manages = room.selfRole == MemberRole.Host && room.selfCanManageRoom
    Column(verticalArrangement = Arrangement.spacedBy((WatchPartyMetrics.CAPTION * 0.6f).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WatchPartyEyebrow("Suggestions · ${suggestions.size}")
            Spacer(Modifier.width(12.dp).weight(1f))
            Text(
                if (manages) "Queue one to put it up next." else "The host decides what plays.",
                fontSize = WatchPartyMetrics.CAPTION.sp,
                color = SiloSecondaryText,
                maxLines = 1,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { suggestion ->
                HostPickSuggestionRow(
                    suggestion = suggestion,
                    queued = suggestion.contentId == room.selectedContentId,
                    canQueue = eligibility.canQueue && connected,
                    canRemove = !eligibility.busy && canRemoveSuggestion(room, suggestion),
                    actions = actions,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostPickSuggestionRow(
    suggestion: Suggestion,
    queued: Boolean,
    canQueue: Boolean,
    canRemove: Boolean,
    actions: WatchPartyRoomActions,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(WatchPartyColors.chromeFill)
                .border(1.dp, WatchPartyColors.chromeBorder, shape)
                .combinedClickable(
                    enabled = canRemove,
                    onClick = {},
                    onLongClick = if (canRemove) ({ menu = true }) else null,
                    onLongClickLabel = "More actions",
                )
                .semantics {
                    contentDescription = suggestion.title
                    if (canRemove) {
                        customActions = listOf(CustomAccessibilityAction("Remove suggestion") { actions.remove(suggestion); true })
                    }
                }
                .padding(8.dp),
        ) {
            WatchPartyPoster(suggestion.posterUrl, null, WatchPartyMetrics.ballotPosterWidth, cornerRadius = 6.dp)
            SuggestionText(suggestion, Modifier.weight(1f))
            when {
                queued -> Text(
                    "Up next",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SiloSecondaryText,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                canQueue -> WatchPartyButton(
                    text = "Queue",
                    kind = WatchPartyButtonKind.Secondary,
                    onClick = { actions.queue(suggestion) },
                    modifier = Modifier.height(44.dp).semantics { contentDescription = "Queue ${suggestion.title}" },
                )
            }
        }
        SiloDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            OptionRow("Remove suggestion", icon = Icons.Outlined.Delete, destructive = true) {
                menu = false
                actions.remove(suggestion)
            }
        }
    }
}
