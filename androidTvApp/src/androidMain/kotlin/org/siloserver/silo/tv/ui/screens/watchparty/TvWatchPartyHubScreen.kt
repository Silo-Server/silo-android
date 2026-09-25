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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
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

private enum class HubFocus { Return, Rejoin, Host, Retry }

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
    // (a party ended, another identity signed in); reload it on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (first) first = false else viewModel.refresh()
            }
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
    val current = state.current
    val recent = state.recent.takeIf { available }
    val primary = when {
        current != null -> HubFocus.Return
        recent != null -> HubFocus.Rejoin
        available || availability == null -> HubFocus.Host
        else -> HubFocus.Retry
    }
    val primaryTarget = when (primary) {
        HubFocus.Return -> returnFocus
        HubFocus.Rejoin -> rejoinFocus
        HubFocus.Host -> hostFocus
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
                    TvPartyEyebrow("Watch Party")
                    Text(
                        text = "Watch something\ntogether",
                        fontSize = TvPartyMetrics.heroTitle,
                        lineHeight = 54.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.8).sp,
                        color = SiloOnSurface,
                    )
                    Text(
                        text = "Pick a title, share the code, and everyone's playback stays in step.",
                        fontSize = TvPartyMetrics.body,
                        lineHeight = 21.sp,
                        color = SiloSecondaryText,
                    )
                }
                val ended = state.ended
                if (showEnded && ended != null && current == null) {
                    TvPartyBanner(message = tvWatchPartyEndedMessage(ended.reason))
                }
                val error = state.error
                when {
                    error != null && !joinOpen -> TvPartyBanner(message = error, warning = true)
                    current != null -> Unit
                    availability == null -> TvPartyBanner(message = "Checking Watch Party support…")
                    availability is WatchPartyAvailability.Unsupported ->
                        TvPartyBanner(message = "This server doesn't support Watch Party yet.")
                    availability == WatchPartyAvailability.NotAllowed ->
                        TvPartyBanner(message = "Watch Party is not available for this profile on this server.")
                    availability is WatchPartyAvailability.ProbeFailed ->
                        TvPartyBanner(message = "Couldn't check whether this server supports Watch Party.", warning = true)
                    else -> Unit
                }
            }
            Spacer(modifier = Modifier.weight(1f))

            // ---- Host and Join --------------------------------------------
            Column(modifier = Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current != null) {
                        TvPartyEyebrow("Your party")
                        TvPartyButton(
                            label = "Return to party",
                            icon = Icons.Filled.PlayArrow,
                            onClick = viewModel::returnToParty,
                            state = TvControlState.transient(!busy),
                            modifier = Modifier.defaultTarget(HubFocus.Return, returnFocus),
                        )
                        Text(
                            text = "Party code ${tvWatchPartyDisplayCode(current.code)}",
                            fontSize = TvPartyMetrics.caption,
                            color = SiloSecondaryText,
                        )
                    } else {
                        TvPartyEyebrow("Host")
                        TvPartyButton(
                            label = if (state.busy == WatchPartyPendingAction.Create) "Starting your party…" else "Start a party",
                            icon = Icons.Filled.Add,
                            onClick = { viewModel.host() },
                            state = entryState,
                            modifier = Modifier.defaultTarget(HubFocus.Host, hostFocus),
                        )
                        TvPartyButton(
                            label = "Start a party and let everyone vote",
                            kind = TvPartyButtonKind.Secondary,
                            onClick = { viewModel.host(RoomSelectionMode.Vote) },
                            state = entryState,
                        )
                        if (recent != null) {
                            TvPartyButton(
                                label = if (state.busy == WatchPartyPendingAction.Join) {
                                    "Joining…"
                                } else {
                                    "Rejoin ${recent.title ?: "recent party"}"
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
                                label = "Try again",
                                kind = TvPartyButtonKind.Secondary,
                                onClick = viewModel::retryCreate,
                                state = entryState,
                            )
                            Text(
                                text = "Finish creating the party you started.",
                                fontSize = TvPartyMetrics.caption,
                                color = SiloSecondaryText,
                            )
                        }
                    }
                }
                if (current == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvPartyEyebrow("Join")
                        TvPartyButton(
                            label = "Join with a code",
                            kind = TvPartyButtonKind.Secondary,
                            onClick = {
                                viewModel.clearError()
                                joinOpen = true
                            },
                            state = entryState,
                            modifier = Modifier
                                .focusRequester(joinFocus)
                                .onFocusChanged { joinFocused = it.isFocused },
                        )
                        if (!available) {
                            TvPartyButton(
                                label = "Check again",
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
