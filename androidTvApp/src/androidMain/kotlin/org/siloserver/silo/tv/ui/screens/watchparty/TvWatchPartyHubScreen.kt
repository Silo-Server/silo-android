package org.siloserver.silo.tv.ui.screens.watchparty

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.repository.WatchPartyPendingAction
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.TvRestoreFocusOnModalDismiss
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus
import org.siloserver.silo.viewmodel.WatchPartyHubViewModel
import org.siloserver.silo.watchtogether.WatchPartyAvailability
import org.siloserver.silo.watchtogether.WatchPartyDestination

private enum class HubFocus { Return, Rejoin, Host, Retry, Back }

/**
 * The Watch Party hub, reached from the profile menu: whether this server
 * supports Watch Party, Return to Party while engaged, Rejoin for the recent
 * party, Host (Host Picks), and Join with a code. Navigation follows the hub
 * ViewModel's destination only.
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
    val backFocus = remember { FocusRequester() }
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
    val busy = state.busy != null
    val actionState = TvControlState.transient(!busy)
    val primary = when {
        state.current != null -> HubFocus.Return
        availability is WatchPartyAvailability.Available && state.recent != null -> HubFocus.Rejoin
        availability is WatchPartyAvailability.Available -> HubFocus.Host
        availability is WatchPartyAvailability.ProbeFailed -> HubFocus.Retry
        else -> HubFocus.Back
    }
    val contentFocus = rememberTvContentInitialFocus(
        target = when (primary) {
            HubFocus.Return -> returnFocus
            HubFocus.Rejoin -> rejoinFocus
            HubFocus.Host -> hostFocus
            HubFocus.Retry -> retryFocus
            HubFocus.Back -> backFocus
        },
        contentKey = primary,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 40.dp)
                .then(contentFocus),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Watch Party",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = "Watch with friends and family on their own screens. Everyone stays in sync.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.72f),
            )
            val ended = state.ended
            if (showEnded && ended != null && state.current == null) {
                TvPartyNotice(title = tvWatchPartyEndedMessage(ended.reason))
            }
            val error = state.error
            if (error != null && !joinOpen) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            val current = state.current
            if (current != null) {
                TvPartyActionRow(
                    title = "Return to Party",
                    subtitle = "Party code ${tvWatchPartyDisplayCode(current.code)}",
                    onClick = viewModel::returnToParty,
                    state = actionState,
                    modifier = Modifier.focusRequester(returnFocus),
                )
            } else {
                when (availability) {
                    null -> {
                        HubStatus("Checking whether this server supports Watch Party…")
                        BackRow(backFocus, onBack)
                    }
                    is WatchPartyAvailability.Unsupported -> {
                        HubStatus("This server doesn't support Watch Party yet.")
                        BackRow(backFocus, onBack)
                    }
                    WatchPartyAvailability.NotAllowed -> {
                        HubStatus("Your account can't use Watch Party.")
                        BackRow(backFocus, onBack)
                    }
                    is WatchPartyAvailability.ProbeFailed -> {
                        HubStatus("Couldn't check whether this server supports Watch Party.")
                        TvPartyActionRow(
                            title = "Retry",
                            onClick = { viewModel.refresh(force = true) },
                            modifier = Modifier.focusRequester(retryFocus),
                        )
                        BackRow(backFocus, onBack)
                    }
                    is WatchPartyAvailability.Available -> {
                        val recent = state.recent
                        if (recent != null) {
                            TvPartyActionRow(
                                title = if (state.busy == WatchPartyPendingAction.Join) {
                                    "Joining…"
                                } else {
                                    "Rejoin ${tvWatchPartyDisplayCode(recent.code)}"
                                },
                                subtitle = recent.title ?: "Your recent party",
                                onClick = viewModel::rejoinRecent,
                                state = actionState,
                                modifier = Modifier.focusRequester(rejoinFocus),
                            )
                        }
                        TvPartyActionRow(
                            title = if (state.busy == WatchPartyPendingAction.Create) "Creating party…" else "Host a party",
                            subtitle = "You pick what everyone watches.",
                            onClick = { viewModel.host() },
                            state = actionState,
                            modifier = Modifier.focusRequester(hostFocus),
                        )
                        TvPartyActionRow(
                            title = "Join with code",
                            subtitle = "Enter the code the host sees.",
                            onClick = {
                                viewModel.clearError()
                                joinOpen = true
                            },
                            state = actionState,
                            modifier = Modifier
                                .focusRequester(joinFocus)
                                .onFocusChanged { joinFocused = it.isFocused },
                        )
                        if (state.createRetryable) {
                            TvPartyActionRow(
                                title = "Try again",
                                subtitle = "Finish creating the party you started.",
                                onClick = viewModel::retryCreate,
                                state = actionState,
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

@Composable
private fun HubStatus(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = 0.86f),
    )
}

@Composable
private fun BackRow(focus: FocusRequester, onBack: () -> Unit) {
    TvPartyActionRow(title = "Back", onClick = onBack, modifier = Modifier.focusRequester(focus))
}
