package org.siloserver.silo.android.ui.screens.requests

import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.rememberShimmerProgress
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.common.requests.RequestColors
import org.siloserver.silo.common.requests.rememberRequestRouter
import org.siloserver.silo.model.request.AdminRequestAction
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.viewmodel.RequestApprovalsViewModel

/**
 * Everyone's requests waiting on a decision, and failed ones that can be
 * retried. Only reachable for admins who can moderate. Only the tapped row
 * fades while its action runs; the button shows the result before the row
 * leaves, and a failure shakes the button and shows the reason on the row.
 */
@Composable
fun RequestApprovalsScreen(
    onBackClick: () -> Unit,
    onRequestDetailClick: (mediaType: String, tmdbId: Int) -> Unit,
    onLibraryItemClick: (String) -> Unit,
    viewModel: RequestApprovalsViewModel = koinViewModel(key = "approvals") { parametersOf(true) },
) {
    val state by viewModel.uiState.collectAsState()
    val router = rememberRequestRouter(koinInject(), onLibraryItemClick, onRequestDetailClick)
    var pendingDecline by remember { mutableStateOf<MediaRequest?>(null) }
    val view = LocalView.current

    // Counters the view model keeps; remembered so a return or rotation
    // doesn't replay the last action's haptic.
    var seenCompleted by rememberSaveable { mutableStateOf(state.completedActions) }
    var seenFailed by rememberSaveable { mutableStateOf(state.failedActions) }
    LaunchedEffect(state.completedActions) {
        if (state.completedActions > seenCompleted) view.performHapticFeedback(confirmHaptic())
        seenCompleted = state.completedActions
    }
    LaunchedEffect(state.failedActions) {
        if (state.failedActions > seenFailed) view.performHapticFeedback(rejectHaptic())
        seenFailed = state.failedActions
    }

    pendingDecline?.let { request ->
        SiloConfirmDialog(
            title = "Decline this request?",
            body = "The requester will see it as declined.",
            confirmLabel = "Decline ${request.title}",
            dismissLabel = "Keep",
            onConfirm = {
                pendingDecline = null
                viewModel.perform(AdminRequestAction.Decline, request)
            },
            onDismiss = { pendingDecline = null },
        )
    }

    RequestsLargeTitlePage(
        title = "Approvals",
        onBackClick = onBackClick,
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
        itemSpacing = 0.dp,
    ) {
        when {
            state.error != null && state.awaitingApproval.isEmpty() && state.failed.isEmpty() -> item(key = "error") {
                ErrorView(message = state.error.orEmpty(), onRetry = viewModel::load, modifier = Modifier.padding(top = 60.dp))
            }
            !state.hasLoaded -> item(key = "loading") {
                Box(modifier = Modifier.padding(top = RequestGroupGap)) { RequestRowsSkeleton(progress = rememberShimmerProgress()) }
            }
            state.isEmpty -> item(key = "empty") {
                EmptyStateView(
                    title = "Nothing waiting on you",
                    subtitle = "New requests that need approval will show up here",
                    icon = Icons.Outlined.CheckCircle,
                    modifier = Modifier.padding(top = 80.dp),
                )
            }
            else -> {
                state.actionErrorMessage?.let { message ->
                    item(key = "action-error") {
                        Text(
                            text = message,
                            fontSize = 12.sp,
                            color = SiloSecondaryText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = RequestGroupGap).padding(horizontal = 16.dp),
                        )
                    }
                }
                requestGroupedSection(key = "awaiting", title = "Waiting for approval", items = state.awaitingApproval, itemKey = { it.id }) { record, showDivider ->
                    RequestApprovalRow(
                        record = record,
                        isBusy = !state.canAct(record),
                        phase = state.phases[record.id],
                        actionError = state.rowErrors[record.id],
                        shakeTrigger = state.failureCounts[record.id] ?: 0,
                        showDivider = showDivider,
                        onOpen = { router.openModerationRecord(record) },
                        onApprove = { viewModel.perform(AdminRequestAction.Approve, record) },
                        onDecline = { pendingDecline = record },
                    )
                }
                requestGroupedSection(key = "failed", title = "Failed", items = state.failed, itemKey = { it.id }) { record, showDivider ->
                    MyRequestRow(
                        record = record,
                        isBusy = !state.canAct(record),
                        actionPhase = state.phases[record.id],
                        actionError = state.rowErrors[record.id]?.let { "Couldn't retry · $it" },
                        shakeTrigger = state.failureCounts[record.id] ?: 0,
                        showDivider = showDivider,
                        onOpen = { router.openModerationRecord(record) },
                        onRetry = { viewModel.perform(AdminRequestAction.Retry, record) },
                    )
                }
            }
        }
    }
}

internal fun confirmHaptic(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS

internal fun rejectHaptic(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
