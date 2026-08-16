package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import java.text.DateFormat
import java.util.Date
import org.siloserver.silo.tv.ui.screens.settings.SettingsBackground
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent

/**
 * Chrome shared by the standalone diagnostics screens — the pending-report
 * detail route, the crash prompt, and its confirmation.
 *
 * The diagnostics *settings* surface no longer uses any of this: it renders
 * inside the Settings detail pane out of the shared settings row primitives
 * (see [TvDiagnosticsSettingsPane]). What is left here is modal/full-screen
 * chrome, where a taller row and a bigger focus scale are appropriate.
 */
@Composable
internal fun TvDiagnosticsPage(title: String, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SettingsBackground)
                .padding(horizontal = 64.dp, vertical = 38.dp),
        ) {
            Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(22.dp))
            Column(Modifier.widthIn(max = 760.dp), content = { content() })
        }
    }
}

@Composable
internal fun TvDiagnosticsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvDiagnosticsAction(
    label: String,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // A disabled TV Surface still takes focus, so a dead row that paints its
    // label at full white reads as live. Say so in the color.
    val labelColor = (if (focused) FocusedContent else Color.White)
        .copy(alpha = if (enabled) 1f else 0.45f)
    val valueColor = (if (focused) FocusedContent else MaterialTheme.colorScheme.onSurfaceVariant)
        .copy(alpha = if (enabled) 1f else 0.45f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        modifier = modifier.fillMaxWidth().height(52.dp),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), color = labelColor)
            value?.let { Text(it, color = valueColor) }
        }
    }
}

/** tvOS `typeTitle(for:)` — used by the prompt and the report detail screen. */
internal fun org.siloserver.silo.model.diagnostics.DiagnosticsReportType.tvDisplayName(): String =
    tvDiagnosticsReportTypeTitle(this)

internal fun tvFormatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

internal fun tvFormatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

/** Compact form for list rows, where the full medium date will not fit. */
internal fun tvFormatShortDateTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

/**
 * Never handed to a browser: an Android TV box is not guaranteed to have one.
 * Shown as footer text, and as a QR the viewer can scan from the pane's
 * Privacy Policy row.
 */
internal const val PRIVACY_POLICY_URL = "https://siloserver.org/privacy"
