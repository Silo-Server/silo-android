package org.siloserver.silo.tv.ui.screens.watchparty

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.repository.WatchPartyPendingAction
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.TvRestoreFocusOnModalDismiss
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.SiloSecondaryText
import org.siloserver.silo.viewmodel.WatchPartyHubViewModel
import org.siloserver.silo.watchtogether.WatchPartyAvailability
import org.siloserver.silo.watchtogether.WatchPartyDestination

private enum class HubFocus { Return, Rejoin, Host, Join, Retry }

/**
 * The Watch Party entry, reached from the profile menu, laid out as the tvOS
 * entry: the pitch on the left; Host (start a party, start one where everyone
 * votes, Return or Rejoin) and Join (by code, or Check again when this server
 * can't host) on the right. Navigation follows the hub ViewModel's
 * destination only.
 *
 * [showEnded] shows why the last party ended (the lobby and player open the
 * hub this way when a party ends under them).
 */
@Composable
fun TvWatchPartyHubScreen(
    showEnded: Boolean,
    onDestination: (WatchPartyDestination) -> Unit,
    onBack: () -> Unit,
    viewModel: WatchPartyHubViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var joinOpen by rememberSaveable { mutableStateOf(false) }
    val returnFocus = remember { FocusRequester() }
    val rejoinFocus = remember { FocusRequester() }
    val hostFocus = remember { FocusRequester() }
    val joinFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    var joinFocused by remember { mutableStateOf(false) }

    LaunchedEffect(state.destination) {
        val destination = state.destination ?: return@LaunchedEffect
        viewModel.consumeDestination()
        joinOpen = false
        onDestination(destination)
    }

    // The recent party can change while this screen is in the back stack
    // (a party ended, another identity signed in), and the server can turn
    // Watch Party off at any time. Probe again whenever the hub is shown, as
    // Apple does; a failed probe keeps the last known answer.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh(force = true)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = !joinOpen) { onBack() }

    val availability = state.availability
    val available = availability is WatchPartyAvailability.Available
    val busy = state.busy != null
    // Still checking is a wait, not a dead end: the buttons stay focusable
    // and dimmed. A server that can't host drops them from the focus path.
    val entryState = when {
        available -> TvControlState.transient(!busy)
        availability == null -> TvControlState.transient(false)
        else -> TvControlState.structural(false)
    }
    // Hosting needs staged selection; a server without it can still be joined.
    val canHost = (availability as? WatchPartyAvailability.Available)?.features?.stagedSelection == true
    val current = state.current
    val recent = state.recent.takeIf { available }
    val primary = when {
        current != null -> HubFocus.Return
        recent != null -> HubFocus.Rejoin
        canHost || availability == null -> HubFocus.Host
        available -> HubFocus.Join
        else -> HubFocus.Retry
    }
    val primaryTarget = when (primary) {
        HubFocus.Return -> returnFocus
        HubFocus.Rejoin -> rejoinFocus
        HubFocus.Host -> hostFocus
        HubFocus.Join -> joinFocus
        HubFocus.Retry -> retryFocus
    }
    val contentFocus = rememberTvContentInitialFocus(target = primaryTarget, contentKey = primary)
    // The probe and the recent party land a moment apart, so Start can take
    // focus just before Rejoin appears. While the viewer still sits on the
    // old default, the new default takes over; once they move, it doesn't.
    var focusedDefault by remember { mutableStateOf<HubFocus?>(null) }
    var previousPrimary by remember { mutableStateOf(primary) }
    LaunchedEffect(primary) {
        if (focusedDefault != null && focusedDefault == previousPrimary && primary != previousPrimary) {
            requestFocusUntilObserved(
                maxAttempts = 10,
                awaitAttempt = { delay(60) },
                requestFocus = primaryTarget::requestFocus,
                isFocused = { focusedDefault == primary },
            )
        }
        previousPrimary = primary
    }
    fun Modifier.defaultTarget(which: HubFocus, requester: FocusRequester): Modifier = this
        .focusRequester(requester)
        .onFocusChanged {
            if (it.isFocused) focusedDefault = which else if (focusedDefault == which) focusedDefault = null
        }

    Box(modifier = Modifier.fillMaxSize()) {
        TvPartyBackdrop(url = null)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 70.dp, end = 70.dp, top = 110.dp)
                .then(contentFocus),
        ) {
            // ---- The pitch and whatever the viewer should know --------------
            Column(modifier = Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvPartyEyebrow(stringResource(CommonR.string.watchParty_title))
                    Text(
                        text = stringResource(CommonR.string.watchParty_page_headline),
                        fontSize = TvPartyMetrics.heroTitle,
                        lineHeight = 54.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.8).sp,
                        color = SiloOnSurface,
                    )
                    Text(
                        text = stringResource(CommonR.string.watchParty_page_body),
                        fontSize = TvPartyMetrics.body,
                        lineHeight = 21.sp,
                        color = SiloSecondaryText,
                    )
                }
                val ended = state.ended
                if (showEnded && ended != null && current == null) {
                    TvPartyBanner(message = stringResource(tvWatchPartyEndedMessage(ended.reason)))
                }
                val error = state.error
                when {
                    error != null && !joinOpen -> TvPartyBanner(message = error, warning = true)
                    current != null -> Unit
                    availability == null -> TvPartyBanner(message = stringResource(CommonR.string.watchParty_page_checking))
                    availability is WatchPartyAvailability.Unsupported ->
                        TvPartyBanner(message = stringResource(CommonR.string.watchParty_hub_unsupported))
                    availability == WatchPartyAvailability.NotAllowed ->
                        TvPartyBanner(message = stringResource(CommonR.string.watchParty_page_unavailable))
                    availability is WatchPartyAvailability.ProbeFailed ->
                        TvPartyBanner(message = stringResource(CommonR.string.watchParty_hub_probeFailed), warning = true)
                    else -> Unit
                }
            }
            Spacer(modifier = Modifier.weight(1f))

            // ---- Host and Join --------------------------------------------
            Column(modifier = Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current != null) {
                        TvPartyEyebrow(stringResource(CommonR.string.watchParty_hub_yourParty))
                        TvPartyButton(
                            label = stringResource(CommonR.string.watchParty_hub_returnToParty),
                            icon = Icons.Filled.PlayArrow,
                            onClick = viewModel::returnToParty,
                            state = TvControlState.transient(!busy),
                            modifier = Modifier.defaultTarget(HubFocus.Return, returnFocus),
                        )
                        Text(
                            text = stringResource(CommonR.string.watchParty_partyCode, tvWatchPartyDisplayCode(current.code)),
                            fontSize = TvPartyMetrics.caption,
                            color = SiloSecondaryText,
                        )
                    } else {
                        TvPartyEyebrow(stringResource(CommonR.string.watchParty_hub_hostSection))
                        if (available && !canHost) {
                            Text(
                                text = stringResource(CommonR.string.watchParty_hub_cannotHost),
                                fontSize = TvPartyMetrics.caption,
                                color = SiloSecondaryText,
                            )
                        } else {
                            TvPartyButton(
                                label = stringResource(
                                    if (state.busy == WatchPartyPendingAction.Create) {
                                        CommonR.string.watchParty_hub_starting
                                    } else {
                                        CommonR.string.watchParty_page_start
                                    },
                                ),
                                icon = Icons.Filled.Add,
                                onClick = { viewModel.host() },
                                state = entryState,
                                modifier = Modifier.defaultTarget(HubFocus.Host, hostFocus),
                            )
                            TvPartyButton(
                                label = stringResource(CommonR.string.watchParty_page_startVote),
                                kind = TvPartyButtonKind.Secondary,
                                onClick = { viewModel.host(RoomSelectionMode.Vote) },
                                state = entryState,
                            )
                        }
                        if (recent != null) {
                            TvPartyButton(
                                label = when {
                                    state.busy == WatchPartyPendingAction.Join -> stringResource(CommonR.string.watchParty_code_joining)
                                    recent.title == null -> stringResource(CommonR.string.watchParty_rejoin_recent)
                                    else -> stringResourceWithNames(CommonR.string.watchParty_rejoin_title, recent.title.orEmpty())
                                },
                                icon = Icons.Filled.Refresh,
                                kind = TvPartyButtonKind.Outlined,
                                onClick = viewModel::rejoinRecent,
                                state = entryState,
                                modifier = Modifier.defaultTarget(HubFocus.Rejoin, rejoinFocus),
                            )
                        }
                        if (state.createRetryable) {
                            TvPartyButton(
                                label = stringResource(CommonR.string.common_tryAgain),
                                kind = TvPartyButtonKind.Secondary,
                                onClick = viewModel::retryCreate,
                                state = entryState,
                            )
                            Text(
                                text = stringResource(CommonR.string.watchParty_hub_finishCreating),
                                fontSize = TvPartyMetrics.caption,
                                color = SiloSecondaryText,
                            )
                        }
                    }
                }
                if (current == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvPartyEyebrow(stringResource(CommonR.string.watchParty_hub_joinSection))
                        TvPartyButton(
                            label = stringResource(CommonR.string.watchParty_page_joinWithCode),
                            kind = TvPartyButtonKind.Secondary,
                            onClick = {
                                viewModel.clearError()
                                joinOpen = true
                            },
                            state = entryState,
                            modifier = Modifier
                                .defaultTarget(HubFocus.Join, joinFocus)
                                .onFocusChanged { joinFocused = it.isFocused },
                        )
                        if (!available) {
                            TvPartyButton(
                                label = stringResource(CommonR.string.watchParty_page_checkAgain),
                                kind = TvPartyButtonKind.Secondary,
                                onClick = { viewModel.refresh(force = true) },
                                state = TvControlState.transient(availability != null),
                                modifier = Modifier.defaultTarget(HubFocus.Retry, retryFocus),
                            )
                        }
                    }
                }
            }
        }
    }

    if (joinOpen) {
        TvJoinCodeDialog(
            isBusy = state.busy == WatchPartyPendingAction.Join,
            error = state.error,
            onJoin = viewModel::join,
            onDismiss = {
                viewModel.clearError()
                joinOpen = false
            },
        )
    }
    TvRestoreFocusOnModalDismiss(
        visible = joinOpen,
        opener = joinFocus,
        isOpenerFocused = { joinFocused },
    )
}
