package org.siloserver.silo.tv.ui.screens.watchparty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.model.watchtogether.GuestControlPolicy
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.repository.WatchTogetherConnectionState
import org.siloserver.silo.tv.ui.components.TvPoster
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.tvControlSemantics
import org.siloserver.silo.tv.ui.focus.tvModalFocusBoundary
import org.siloserver.silo.tv.ui.screens.auth.QrCodePanel
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.SiloSecondaryText
import org.siloserver.silo.watchtogether.WatchPartyEligibility
import org.siloserver.silo.watchtogether.WatchPartyFeatures
import org.siloserver.silo.watchtogether.WatchPartyLobbyHero
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

/** What a room screen can do. The lobby and the in-player panel each supply their own. */
internal class TvPartyRoomActions(
    val onInvite: () -> Unit,
    val onOptions: () -> Unit,
    val onChooseTitle: () -> Unit = {},
    val onSuggest: () -> Unit = {},
    /** Host-pick: start the staged title. Voting: start the leader. */
    val onStart: () -> Unit = {},
    val onReady: (Boolean) -> Unit = {},
    val onReturnToPlayback: () -> Unit = {},
    val onVote: (Suggestion) -> Unit = {},
    val onQueue: (Suggestion) -> Unit = {},
    /** "Start this one", "Remove suggestion": long-press, Menu, or Select on a card with no other action. */
    val onSuggestionMenu: (Suggestion) -> Unit = {},
)

internal const val PARTY_KEY_PRIMARY = "primary"
internal const val PARTY_KEY_SECONDARY = "secondary"
internal const val PARTY_KEY_OPTIONS = "options"
internal const val PARTY_KEY_CODE = "code"
internal const val PARTY_KEY_INVITE_SEAT = "invite-seat"
internal const val PARTY_KEY_SUGGEST_CARD = "suggest-card"

/** The focus key of a suggestion card. */
internal fun tvPartySuggestionKey(id: String) = "s:$id"

/** The control that owns focus on entry and when focus is lost: the primary action, else the next best. */
internal fun tvPartyAnchorKey(room: RoomSnapshot, features: WatchPartyFeatures?, suggestions: List<Suggestion>): String {
    val primary = watchPartyPrimaryAction(room, features, suggestions)
    return when {
        primary != WatchPartyPrimaryAction.None -> PARTY_KEY_PRIMARY
        watchPartySecondaryAction(room, primary) != null -> PARTY_KEY_SECONDARY
        else -> PARTY_KEY_OPTIONS
    }
}

/** Whether a suggestion card is shown and has something to do for this member. */
internal fun tvPartySuggestionFocusable(room: RoomSnapshot, suggestion: Suggestion): Boolean = when {
    room.phase != RoomPhase.Lobby -> false
    room.selectionMode == RoomSelectionMode.Vote -> true
    room.selectionMode == RoomSelectionMode.HostPick ->
        room.isManagedBySelf || canRemoveSuggestion(room, suggestion)
    else -> false
}

private val RoomSnapshot.isManagedBySelf: Boolean
    get() = selfRole == MemberRole.Host && selfCanManageRoom

/**
 * The room, laid out as the tvOS lobby: the staged film fills the screen, the
 * hero names it, members sit as seats along the bottom, and the one action
 * that matters now is the white pill. Voting rooms put the ballot where the
 * overview would be. Play/pause on the remote starts when Start is showing.
 *
 * Which buttons exist comes from the shared lobby policy, never from a
 * request in flight: busy gating dims a control but keeps it (and focus) put.
 */
@Composable
internal fun TvWatchPartyRoomView(
    room: RoomSnapshot,
    suggestions: List<Suggestion>,
    features: WatchPartyFeatures?,
    eligibility: WatchPartyEligibility,
    personalVotesKnown: Boolean,
    connection: WatchTogetherConnectionState,
    staged: TvStagedPreview?,
    focus: TvPartyFocus,
    actions: TvPartyRoomActions,
    modifier: Modifier = Modifier,
    /** A neutral notice (reconnecting, the host stopped playback) when no warning outranks it. */
    notice: String? = null,
    /** "Seen by Ana" for the staged title. */
    memberStateLine: String? = null,
    /** Extra content under the banner (the suggestion retry row). */
    footer: (@Composable () -> Unit)? = null,
) {
    val connected = connection.writable
    val reconnecting = !connected && connection.epoch != 0L
    val (tone, connectionText) = watchPartyConnectionLabel(room, connected, reconnecting)
    val primary = watchPartyPrimaryAction(room, features, suggestions)
    val secondary = watchPartySecondaryAction(room, primary)
    val hero = watchPartyLobbyHero(room, suggestions)
    val hint = watchPartyLobbyHint(room, suggestions)
    val voting = room.selectionMode == RoomSelectionMode.Vote && room.phase == RoomPhase.Lobby
    val winner = if (room.selectionMode == RoomSelectionMode.Vote) roomVoteWinner(suggestions) else null
    val warning = watchPartyLobbyBanner(room, selectionUnavailable = false, reconnecting = false)
    val startState = TvControlState.transient(
        connected && if (room.selectionMode == RoomSelectionMode.Vote) eligibility.canPromote else eligibility.canStart,
    )

    // The staged film's backdrop, else its poster blurred, else (voting) the leader's poster.
    val leaderPoster = if (voting) (winner ?: suggestions.firstOrNull())?.posterUrl?.ifBlank { null } else null
    val backdropUrl = staged?.backdropUrl ?: staged?.posterUrl ?: leaderPoster
    val backdropIsPoster = staged?.backdropUrl == null

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // Same predicate as the Start button, so the remote cannot start what the button refuses.
                val playKey = event.key == Key.MediaPlayPause || event.key == Key.MediaPlay
                if (playKey && event.type == KeyEventType.KeyDown && primary is WatchPartyPrimaryAction.Start) {
                    startState.perform(actions.onStart)
                    true
                } else {
                    false
                }
            },
    ) {
        TvPartyBackdrop(
            url = backdropUrl,
            thumbhash = if (backdropIsPoster) staged?.posterThumbhash else staged?.backdropThumbhash,
            isPoster = backdropIsPoster,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = TvPartyMetrics.pageInsetX),
        ) {
            // ---- Header ------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (voting) 30.dp else TvPartyMetrics.headerTop),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Watch Party", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = SiloOnSurface)
                Spacer(modifier = Modifier.width(10.dp))
                TvPartyConnectionDot(tone = tone, text = connectionText)
                Spacer(modifier = Modifier.weight(1f))
                TvPartyCodePill(
                    code = room.code,
                    onClick = actions.onInvite,
                    modifier = Modifier.partyFocus(focus, PARTY_KEY_CODE),
                )
                Spacer(modifier = Modifier.width(11.dp))
                TvPartyOptionsButton(
                    onClick = actions.onOptions,
                    modifier = Modifier.partyFocus(focus, PARTY_KEY_OPTIONS),
                )
            }

            // ---- Hero --------------------------------------------------------
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
            ) {
                if (voting) {
                    Column(
                        modifier = Modifier.padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HeroText(room = room, hero = hero, staged = staged, titleSize = 34.sp, memberStateLine = null)
                        TvPartyBallot(
                            room = room,
                            suggestions = suggestions,
                            leaderId = winner?.id,
                            personalVotesKnown = personalVotesKnown,
                            voteState = TvControlState.transient(eligibility.canVote && connected),
                            suggestState = TvControlState.transient(eligibility.canSuggest),
                            focus = focus,
                            actions = actions,
                        )
                        RoomBanner(warning = warning, notice = notice, footer = footer)
                    }
                } else {
                    // Suggestions take the empty side of the screen so the hero
                    // keeps its height and the seats stay put.
                    Row(
                        modifier = Modifier.padding(top = 30.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Column(
                            modifier = Modifier.width(380.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HeroText(
                                room = room,
                                hero = hero,
                                staged = staged,
                                titleSize = TvPartyMetrics.heroTitle,
                                memberStateLine = memberStateLine,
                            )
                            staged?.overview?.takeIf { !room.selectedContentId.isNullOrBlank() }?.let { overview ->
                                Text(
                                    text = overview,
                                    fontSize = TvPartyMetrics.overview,
                                    lineHeight = 20.sp,
                                    color = SiloSecondaryText,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            RoomBanner(warning = warning, notice = notice, footer = footer)
                        }
                        if (room.phase == RoomPhase.Lobby && room.selectionMode == RoomSelectionMode.HostPick &&
                            suggestions.isNotEmpty()
                        ) {
                            Spacer(modifier = Modifier.weight(1f))
                            TvPartyHostPickSuggestions(
                                room = room,
                                suggestions = suggestions,
                                queueState = TvControlState.transient(eligibility.canQueue && connected),
                                focus = focus,
                                actions = actions,
                                modifier = Modifier.widthIn(max = 410.dp),
                            )
                        }
                    }
                }
            }

            // ---- Seats and actions -----------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = if (voting) 26.dp else 36.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TvPartySeatsRow(
                    members = room.members,
                    phase = room.phase,
                    onInvite = actions.onInvite,
                    inviteModifier = Modifier.partyFocus(focus, PARTY_KEY_INVITE_SEAT),
                    seatSize = if (voting) 40.dp else TvPartyMetrics.seat,
                )
                Spacer(modifier = Modifier.weight(1f).width(20.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvPartyButtonRow {
                        SecondaryButton(
                            secondary = secondary,
                            primary = primary,
                            eligibility = eligibility,
                            actions = actions,
                            modifier = Modifier.partyFocus(focus, PARTY_KEY_SECONDARY),
                        )
                        PrimaryButton(
                            primary = primary,
                            hasSelection = !room.selectedContentId.isNullOrBlank(),
                            startState = startState,
                            eligibility = eligibility,
                            connected = connected,
                            actions = actions,
                            modifier = Modifier.partyFocus(focus, PARTY_KEY_PRIMARY),
                        )
                    }
                    if (hint != null) {
                        Text(
                            text = hint,
                            fontSize = TvPartyMetrics.caption,
                            color = SiloSecondaryText.copy(alpha = 0.7f),
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(max = 360.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Staged titles longer than this step the hero type down a size. */
private const val LONG_TITLE_CHARS = 16

@Composable
private fun RoomBanner(warning: String?, notice: String?, footer: (@Composable () -> Unit)?) {
    when {
        warning != null -> TvPartyBanner(message = warning, warning = true)
        notice != null -> TvPartyBanner(message = notice)
    }
    footer?.invoke()
}

/** Eyebrow, title, facts. In a voting lobby the question is the title. */
@Composable
private fun HeroText(
    room: RoomSnapshot,
    hero: WatchPartyLobbyHero,
    staged: TvStagedPreview?,
    titleSize: TextUnit,
    memberStateLine: String?,
) {
    val selected = !room.selectedContentId.isNullOrBlank()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvPartyEyebrow(hero.eyebrow)
        val title = hero.title ?: staged?.title ?: "Loading title…"
        val size = if (!hero.isVoteQuestion && hero.title == null && title.length > LONG_TITLE_CHARS) titleSize * 0.8f else titleSize
        Text(
            text = title,
            fontSize = size,
            lineHeight = size * 1.06f,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = if (staged == null && selected && hero.title == null) SiloSecondaryText else SiloOnSurface,
            maxLines = if (hero.isVoteQuestion) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected && !hero.isVoteQuestion) {
            if (staged != null) FactsLine(staged)
            val chose = listOfNotNull(hero.supporting, memberStateLine).joinToString(" · ")
            if (chose.isNotBlank()) {
                Text(
                    text = chose,
                    fontSize = TvPartyMetrics.caption,
                    color = SiloSecondaryText.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            hero.supporting?.let {
                Text(text = it, fontSize = TvPartyMetrics.body, lineHeight = 21.sp, color = SiloSecondaryText)
            }
        }
    }
}

/** "S1:E2 · 2021 · 2h 35m [4K] [HDR]". */
@Composable
private fun FactsLine(staged: TvStagedPreview) {
    val parts = listOfNotNull(staged.subtitle) + staged.facts
    if (parts.isEmpty() && staged.chips.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { index, part ->
            if (index > 0) Text(text = "·", fontSize = 14.sp, color = SiloSecondaryText.copy(alpha = 0.5f))
            Text(text = part, fontSize = 14.sp, color = SiloSecondaryText, maxLines = 1)
        }
        staged.chips.forEach { chip ->
            Text(
                text = chip,
                fontSize = TvPartyMetrics.caption,
                fontWeight = FontWeight.SemiBold,
                color = SiloSecondaryText,
                modifier = Modifier
                    .border(1.dp, SiloSecondaryText.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun PrimaryButton(
    primary: WatchPartyPrimaryAction,
    hasSelection: Boolean,
    startState: TvControlState,
    eligibility: WatchPartyEligibility,
    connected: Boolean,
    actions: TvPartyRoomActions,
    modifier: Modifier,
) {
    when (primary) {
        WatchPartyPrimaryAction.ChooseTitle -> TvPartyButton(
            label = if (hasSelection) "Change title" else "Choose a title",
            onClick = actions.onChooseTitle,
            state = TvControlState.transient(eligibility.canStage),
            modifier = modifier,
        )
        is WatchPartyPrimaryAction.Start -> TvPartyButton(
            label = primary.title?.let { "Start $it" } ?: "Start for everyone",
            icon = Icons.Filled.PlayArrow,
            onClick = actions.onStart,
            state = startState,
            modifier = modifier.widthIn(max = 380.dp),
        )
        WatchPartyPrimaryAction.WaitingForVotes -> TvPartyButton(
            label = "Suggest a title",
            icon = Icons.Filled.Add,
            onClick = actions.onSuggest,
            state = TvControlState.transient(eligibility.canSuggest),
            modifier = modifier,
        )
        is WatchPartyPrimaryAction.Ready -> TvPartyButton(
            label = if (primary.isReady) "You're ready" else "I'm ready",
            icon = if (primary.isReady) Icons.Filled.Check else Icons.Filled.CheckCircle,
            kind = if (primary.isReady) TvPartyButtonKind.Outlined else TvPartyButtonKind.Primary,
            trailing = if (primary.isReady) "select to undo" else null,
            onClick = { actions.onReady(!primary.isReady) },
            state = TvControlState.transient(eligibility.canLobbyReady && connected),
            modifier = modifier,
        )
        WatchPartyPrimaryAction.ReturnToPlayback -> TvPartyButton(
            label = "Return to playback",
            icon = Icons.Filled.PlayArrow,
            onClick = actions.onReturnToPlayback,
            modifier = modifier,
        )
        WatchPartyPrimaryAction.None -> Unit
    }
}

@Composable
private fun SecondaryButton(
    secondary: WatchPartySecondaryAction?,
    primary: WatchPartyPrimaryAction,
    eligibility: WatchPartyEligibility,
    actions: TvPartyRoomActions,
    modifier: Modifier,
) {
    when (secondary) {
        WatchPartySecondaryAction.ChangeTitle -> TvPartyButton(
            label = "Change title",
            kind = TvPartyButtonKind.Secondary,
            onClick = actions.onChooseTitle,
            state = TvControlState.transient(eligibility.canStage),
            modifier = modifier,
        )
        WatchPartySecondaryAction.Suggest -> TvPartyButton(
            label = if (primary is WatchPartyPrimaryAction.Start) "Suggest" else "Suggest a title",
            icon = Icons.Filled.Add,
            kind = TvPartyButtonKind.Secondary,
            onClick = actions.onSuggest,
            state = TvControlState.transient(eligibility.canSuggest),
            modifier = modifier,
        )
        null -> Unit
    }
}

// ---- Ballot (voting) ---------------------------------------------------------

/** Candidates with their vote count as a badge; the leader's badge inverts. Suggest is the dashed card at the end. */
@Composable
private fun TvPartyBallot(
    room: RoomSnapshot,
    suggestions: List<Suggestion>,
    leaderId: String?,
    personalVotesKnown: Boolean,
    voteState: TvControlState,
    suggestState: TvControlState,
    focus: TvPartyFocus,
    actions: TvPartyRoomActions,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        items(suggestions, key = { it.id }) { suggestion ->
            TvPartySuggestionCard(
                suggestion = suggestion,
                posterWidth = TvPartyMetrics.ballotPosterWidth,
                titleSize = 15.sp,
                // Personal votes are unknown until an HTTP read confirms them.
                voted = personalVotesKnown && suggestion.votedByMe,
                badge = { VoteBadge(count = suggestion.voteCount, isLeader = suggestion.id == leaderId) },
                state = voteState,
                focusable = tvPartySuggestionFocusable(room, suggestion),
                onClick = { actions.onVote(suggestion) },
                onMenu = { actions.onSuggestionMenu(suggestion) },
                modifier = Modifier.partyFocus(focus, tvPartySuggestionKey(suggestion.id)),
            )
        }
        item(key = PARTY_KEY_SUGGEST_CARD) {
            TvPartySuggestCard(
                posterWidth = TvPartyMetrics.ballotPosterWidth,
                state = suggestState,
                onClick = actions.onSuggest,
                modifier = Modifier.partyFocus(focus, PARTY_KEY_SUGGEST_CARD),
            )
        }
    }
}

@Composable
private fun VoteBadge(count: Int, isLeader: Boolean) {
    val shape = RoundedCornerShape(100.dp)
    Row(
        modifier = Modifier
            .background(if (isLeader) SiloOnSurface else Color.Black.copy(alpha = 0.7f), shape)
            .border(1.dp, if (isLeader) Color.Transparent else PartyChromeBorder, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val color = if (isLeader) Color.Black else SiloOnSurface
        Text(text = "$count", fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color)
        Text(
            text = if (count == 1) "VOTE" else "VOTES",
            fontSize = TvPartyMetrics.caption,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = color.copy(alpha = 0.6f),
        )
    }
}

// ---- Suggestions (host-pick) -----------------------------------------------

/**
 * Guests' suggestions in a host-pick lobby. The host still decides, so Select
 * queues a suggestion as the pick rather than starting it; a guest's own card
 * stays focusable so they can remove it. Other guests' cards only display.
 */
@Composable
private fun TvPartyHostPickSuggestions(
    room: RoomSnapshot,
    suggestions: List<Suggestion>,
    queueState: TvControlState,
    focus: TvPartyFocus,
    actions: TvPartyRoomActions,
    modifier: Modifier = Modifier,
) {
    val manages = room.isManagedBySelf
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvPartyEyebrow("Suggestions · ${suggestions.size}")
            Spacer(modifier = Modifier.weight(1f).width(12.dp))
            Text(
                text = if (manages) "Queue one to put it up next." else "The host decides what plays.",
                fontSize = TvPartyMetrics.caption,
                color = SiloSecondaryText,
                maxLines = 1,
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(suggestions, key = { it.id }) { suggestion ->
                val queued = suggestion.contentId == room.selectedContentId
                TvPartySuggestionCard(
                    suggestion = suggestion,
                    posterWidth = 76.dp,
                    titleSize = 14.sp,
                    voted = false,
                    badge = if (queued) {
                        { UpNextBadge() }
                    } else {
                        null
                    },
                    state = if (manages && !queued) queueState else TvControlState.structural(true),
                    focusable = tvPartySuggestionFocusable(room, suggestion),
                    onClick = {
                        if (manages && !queued) actions.onQueue(suggestion) else actions.onSuggestionMenu(suggestion)
                    },
                    onMenu = { actions.onSuggestionMenu(suggestion) },
                    showSubtitle = false,
                    modifier = Modifier.partyFocus(focus, tvPartySuggestionKey(suggestion.id)),
                )
            }
        }
    }
}

@Composable
private fun UpNextBadge() {
    Text(
        text = "UP NEXT",
        fontSize = TvPartyMetrics.caption,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = Color.Black,
        modifier = Modifier
            .background(SiloOnSurface, RoundedCornerShape(100.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * A poster card for a suggestion. Select runs [onClick]; long-press or the
 * remote's Menu key runs [onMenu]. Focus lifts and outlines the poster.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPartySuggestionCard(
    suggestion: Suggestion,
    posterWidth: Dp,
    titleSize: TextUnit,
    voted: Boolean,
    badge: (@Composable () -> Unit)?,
    state: TvControlState,
    focusable: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    showSubtitle: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val controlState = if (focusable) state else TvControlState.structural(false)
    Column(modifier = Modifier.width(posterWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = { controlState.perform(onClick) },
            onLongClick = if (focusable) onMenu else null,
            enabled = controlState.focusable,
            interactionSource = interactionSource,
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, PartyChromeBorder), shape = shape),
                focusedBorder = Border(BorderStroke(3.dp, SiloOnSurface), shape = shape),
            ),
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.22f), elevation = 16.dp),
            ),
            modifier = modifier
                .onPreviewKeyEvent { event ->
                    if (focusable && event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                        onMenu()
                        true
                    } else {
                        event.key == Key.Menu && focusable
                    }
                }
                .tvControlSemantics(controlState),
        ) {
            Box(modifier = Modifier.size(width = posterWidth, height = posterWidth * 1.5f)) {
                TvPoster(
                    imageUrl = suggestion.posterUrl.ifBlank { null },
                    contentDescription = suggestion.title,
                    cornerRadius = 6.dp,
                    modifier = Modifier.fillMaxSize(),
                )
                if (badge != null) {
                    Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) { badge() }
                }
                if (voted) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .background(SiloOnSurface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Your vote", tint = Color.Black, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = suggestion.title,
                fontSize = titleSize,
                fontWeight = FontWeight.SemiBold,
                color = if (isFocused) SiloOnSurface else SiloOnSurface.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showSubtitle) {
                val line = suggestion.note.takeIf { it.isNotBlank() }?.let { "“$it”" } ?: suggestion.subtitle.ifBlank { " " }
                Text(text = line, fontSize = TvPartyMetrics.caption, color = SiloSecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The dashed "+" card at the end of the ballot. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPartySuggestCard(
    posterWidth: Dp,
    state: TvControlState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Column(modifier = Modifier.width(posterWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = { state.perform(onClick) },
            enabled = state.focusable,
            interactionSource = interactionSource,
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White.copy(alpha = 0.14f),
                pressedContainerColor = Color.White.copy(alpha = 0.14f),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
                focusedBorder = Border(BorderStroke(2.dp, SiloOnSurface), shape = shape),
            ),
            modifier = modifier.tvControlSemantics(state),
        ) {
            Box(
                modifier = Modifier.size(width = posterWidth, height = posterWidth * 1.5f),
                contentAlignment = Alignment.Center,
            ) {
                if (!isFocused) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 1.dp.toPx()
                        drawRoundRect(
                            color = SiloSecondaryText.copy(alpha = 0.5f),
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Suggest a title",
                    tint = if (isFocused) SiloOnSurface else SiloSecondaryText,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(text = "Suggest a title", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = SiloSecondaryText, maxLines = 1)
            Text(text = "Anyone can add one", fontSize = TvPartyMetrics.caption, color = SiloSecondaryText.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

// ---- Options -----------------------------------------------------------------

/**
 * Party options behind the "···" button, so settings never share a row with
 * Start. Hosts get the mode, the playback policy, Return everyone to lobby
 * while playing, End, and Leave; guests get Leave. Close has focus on open,
 * and Back closes. Rows stay focusable while a change is in flight.
 */
@Composable
internal fun TvWatchPartyOptionsOverlay(
    room: RoomSnapshot,
    features: WatchPartyFeatures?,
    eligibility: WatchPartyEligibility,
    onSetMode: (RoomSelectionMode) -> Unit,
    onSetPolicy: (GuestControlPolicy) -> Unit,
    onStopPlayback: () -> Unit,
    onEnd: () -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    TvPartyMenuOverlay(title = "Party options", onDismiss = onDismiss) {
        if (room.isManagedBySelf) {
            if (room.phase == RoomPhase.Lobby && features?.selectionModeSwitch == true) {
                val vote = room.selectionMode == RoomSelectionMode.Vote
                TvPartyOptionRow(
                    title = "Who chooses",
                    value = if (vote) "Everyone votes" else "Host chooses",
                    state = TvControlState.transient(eligibility.canSwitchMode),
                    onClick = { onSetMode(if (vote) RoomSelectionMode.HostPick else RoomSelectionMode.Vote) },
                )
            }
            val guestsPlay = room.guestControlPolicy == GuestControlPolicy.GuestPlayPause
            TvPartyOptionRow(
                title = "Playback controls",
                value = if (guestsPlay) "Guests can play and pause" else "Host only",
                state = TvControlState.transient(eligibility.canSetPolicy),
                onClick = { onSetPolicy(if (guestsPlay) GuestControlPolicy.HostOnly else GuestControlPolicy.GuestPlayPause) },
            )
            if (room.phase == RoomPhase.Playing && features?.stopPlayback == true) {
                TvPartyOptionRow(
                    title = "Return everyone to lobby",
                    state = TvControlState.transient(eligibility.canStop),
                    onClick = onStopPlayback,
                )
            }
            TvPartyOptionRow(
                title = "End party for everyone",
                destructive = true,
                state = TvControlState.transient(eligibility.canEnd),
                onClick = onEnd,
            )
        }
        TvPartyOptionRow(title = "Leave party", destructive = true, onClick = onLeave)
    }
}

// ---- Invitation ----------------------------------------------------------------

/**
 * The invitation: a large code, and for the host a QR of the invitation link
 * for a phone. Nothing scrolls and Done is the only control, so the page
 * always has a focus owner. Back dismisses.
 */
@Composable
internal fun TvWatchPartyInvitePage(
    code: String,
    inviteUrl: String?,
    backdropUrl: String?,
    backdropThumbhash: String?,
    onDismiss: () -> Unit,
) {
    val doneFocus = remember { FocusRequester() }
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            TvPartyBackdrop(url = backdropUrl, thumbhash = backdropThumbhash)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 60.dp)
                    .tvModalFocusBoundary()
                    .then(rememberTvDialogInitialFocus(doneFocus)),
                horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 450.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TvPartyEyebrow("Invite friends")
                        Text(
                            text = "Join with this code",
                            fontSize = 40.sp,
                            lineHeight = 46.sp,
                            fontWeight = FontWeight.Bold,
                            color = SiloOnSurface,
                        )
                        Text(
                            text = "Anyone with a profile on this Silo server can enter it from Watch Party.",
                            fontSize = TvPartyMetrics.body,
                            lineHeight = 21.sp,
                            color = SiloSecondaryText,
                        )
                    }
                    Text(
                        text = code,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 8.sp,
                        color = SiloOnSurface,
                        maxLines = 1,
                    )
                    if (inviteUrl != null) {
                        Text(
                            text = "Or scan to open the invitation on a phone.",
                            fontSize = TvPartyMetrics.caption,
                            color = SiloSecondaryText,
                        )
                    }
                    TvPartyButton(
                        label = "Done",
                        kind = TvPartyButtonKind.Secondary,
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(doneFocus),
                    )
                }
                if (inviteUrl != null) {
                    Box(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .padding(15.dp),
                    ) {
                        QrCodePanel(content = inviteUrl, size = 250.dp)
                    }
                }
            }
        }
    }
}
