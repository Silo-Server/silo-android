package org.siloserver.silo.android.ui.screens.settings.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.android.ui.components.SiloDialog
import org.siloserver.silo.android.ui.components.SiloDialogAction
import org.siloserver.silo.android.ui.components.SiloDialogActionStyle
import org.siloserver.silo.common.diagnostics.DiagnosticsPrompt
import org.siloserver.silo.common.diagnostics.diagnosticsReportsReadyText
import org.siloserver.silo.common.R as CommonR

@Composable
fun DiagnosticsPromptDialog(
    prompt: DiagnosticsPrompt,
    onReview: () -> Unit,
    onSend: () -> Unit,
    onAlwaysSend: () -> Unit,
    onDontSend: () -> Unit,
    allowAlwaysSend: Boolean = true,
) {
    var confirmAlways by remember { mutableStateOf(false) }
    if (confirmAlways && allowAlwaysSend) {
        SiloConfirmDialog(
            title = stringResource(CommonR.string.diagnostics_alwaysSend_title),
            body = stringResource(CommonR.string.diagnostics_alwaysSend_promptBody),
            confirmLabel = stringResource(CommonR.string.diagnostics_alwaysSend_action),
            destructive = false,
            onConfirm = onAlwaysSend,
            onDismiss = { confirmAlways = false },
        )
        return
    }
    val reportDescription = diagnosticsReportsReadyText(prompt)
    SiloDialog(
        title = stringResource(CommonR.string.diagnostics_prompt_title),
        message = if (allowAlwaysSend) {
            reportDescription
        } else {
            reportDescription + "\n\n" + stringResource(CommonR.string.diagnostics_prompt_privacyNotice)
        },
        onDismissRequest = onDontSend,
        actions = run {
            val review = stringResource(CommonR.string.diagnostics_prompt_review)
            val send = stringResource(CommonR.string.diagnostics_consent_confirm)
            val alwaysSend = stringResource(CommonR.string.diagnostics_alwaysSend_action)
            val dontSend = stringResource(CommonR.string.diagnostics_prompt_dontSend)
            buildList {
                add(SiloDialogAction(review, onReview, SiloDialogActionStyle.Primary))
                add(SiloDialogAction(send, onSend))
                if (allowAlwaysSend) add(SiloDialogAction(alwaysSend, { confirmAlways = true }))
                add(SiloDialogAction(dontSend, onDontSend))
            }
        },
    )
}
