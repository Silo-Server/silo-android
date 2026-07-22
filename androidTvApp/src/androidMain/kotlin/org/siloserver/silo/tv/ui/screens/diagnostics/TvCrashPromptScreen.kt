package org.siloserver.silo.tv.ui.screens.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.siloserver.silo.common.diagnostics.DiagnosticsCoordinator
import org.siloserver.silo.common.diagnostics.consent.PendingReportStore
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import org.siloserver.silo.tv.ui.screens.settings.TvSettingsConfirmDialog
import org.siloserver.silo.tv.ui.screens.settings.reportTypeTitle
import org.siloserver.silo.tv.ui.screens.settings.shortDate
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent

/**
 * Full-screen crash-report consent prompt, shown at the TV nav root whenever
 * the diagnostics coordinator has pending reports for an Ask binding
 * ([DiagnosticsCoordinator.prompt] non-null).
 *
 * Consent-first design: default focus is ALWAYS on "Don't Send", and Back is
 * treated as Don't Send (after collapsing the View Report expansion first).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvCrashPromptScreen(
    prompt: DiagnosticsCoordinator.Prompt,
    serverName: String?,
    onSend: () -> Unit,
    onAlwaysSend: () -> Unit,
    onDecline: () -> Unit,
) {
    val plural = prompt.reports.size > 1
    var showReports by remember { mutableStateOf(false) }
    var showAlwaysConfirm by remember { mutableStateOf(false) }
    val dontSendFocus = remember { FocusRequester() }

    // Same retry pattern as TvSettingsScreen's entry focus: the overlay can
    // compose before the focus system is ready to accept the request.
    LaunchedEffect(Unit) {
        for (attempt in 0 until 4) {
            if (runCatching { dontSendFocus.requestFocus() }.getOrDefault(false)) break
            delay(50)
        }
    }

    BackHandler {
        if (showReports) showReports = false else onDecline()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CrashPromptBackground)
            .focusGroup()
            // Contain D-pad focus: the shell underneath must not be reachable
            // while the consent prompt is up.
            .focusProperties { exit = { FocusRequester.Cancel } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Text(
                text = if (plural) "Send diagnostics reports?" else "Send a diagnostics report?",
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 24.sp, lineHeight = 28.sp),
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = crashPromptMessage(prompt.reports),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            CrashPromptButton(
                label = when {
                    showReports && plural -> "Hide Reports"
                    showReports -> "Hide Report"
                    plural -> "View Reports"
                    else -> "View Report"
                },
                onClick = { showReports = !showReports },
            )

            if (showReports) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    prompt.reports.forEach { report ->
                        CrashPromptReportCard(report = report, serverName = serverName)
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                CrashPromptButton(label = "Send", onClick = onSend)
                CrashPromptButton(label = "Always Send", onClick = { showAlwaysConfirm = true })
                CrashPromptButton(
                    label = "Don't Send",
                    onClick = onDecline,
                    focusRequester = dontSendFocus,
                )
            }
        }
    }

    if (showAlwaysConfirm) {
        TvSettingsConfirmDialog(
            title = "Always Send Crash Reports?",
            message = "Any pending reports and future crash or hang reports for this server " +
                "account will be sent automatically.",
            confirmLabel = "Always Send",
            onConfirm = {
                showAlwaysConfirm = false
                onAlwaysSend()
            },
            onDismiss = { showAlwaysConfirm = false },
        )
    }
}

/** Honest, type-aware body copy — never overstates what happened. */
private fun crashPromptMessage(reports: List<PendingReportStore.PendingReport>): String {
    if (reports.size > 1) {
        return "Silo had ${reports.size} problems recently. Send diagnostic reports to your server?"
    }
    return when (reports.firstOrNull()?.binding?.type) {
        DiagnosticsReportType.ANR, DiagnosticsReportType.HANG ->
            "Silo stopped responding. Send a diagnostic report to your server?"
        DiagnosticsReportType.CRASH,
        DiagnosticsReportType.NATIVE_CRASH,
        DiagnosticsReportType.ABNORMAL_EXIT,
        ->
            "Silo closed unexpectedly. Send a diagnostic report to your server?"
        else -> "Silo hit a problem recently. Send a diagnostic report to your server?"
    }
}

/** Non-focusable per-report summary card behind the View Report toggle. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CrashPromptReportCard(
    report: PendingReportStore.PendingReport,
    serverName: String?,
) {
    Column(
        modifier = Modifier
            .width(520.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = "${reportTypeTitle(report.binding.type)} — captured " +
                shortDate(report.binding.capturedAtEpochMs),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        report.manifestDraft.crash?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "Destination: ${serverName ?: "your server"}",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CrashPromptButton(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
                shape = shape,
            ),
            focusedBorder = androidx.tv.material3.Border.None,
            pressedBorder = androidx.tv.material3.Border.None,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
            color = if (isFocused) FocusedContent else Color.White,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/** Same surface tone as the settings screen background — fully opaque so the shell can't bleed through. */
private val CrashPromptBackground = Color(0xFF17181A)
