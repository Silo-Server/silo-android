package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import org.siloserver.silo.common.ui.components.MachineTranslatedLabel
import org.siloserver.silo.metadata.DescriptionTranslationPhase
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * TV description-translation affordance (Apple tvOS parity): a focusable
 * button under the synopsis. Translating keeps a disabled Button (not a bare
 * Row) so pressing translate doesn't remove the focused node from the graph
 * and bounce D-pad focus up to the hero.
 */
@Composable
internal fun TvDescriptionTranslationSection(
    phase: DescriptionTranslationPhase,
    onTranslate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (phase) {
        DescriptionTranslationPhase.Translating -> androidx.tv.material3.Button(
            onClick = {},
            enabled = false,
            modifier = modifier,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Text(
                    text = "Translating description…",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
        DescriptionTranslationPhase.Failed -> androidx.tv.material3.Button(
            onClick = onTranslate,
            modifier = modifier,
        ) {
            Text("Translation failed — retry")
        }
        DescriptionTranslationPhase.Idle -> androidx.tv.material3.Button(
            onClick = onTranslate,
            modifier = modifier,
        ) {
            Text("Translate description")
        }
    }
}

/**
 * "Translated by AI" under a synopsis the server reports as machine-translated.
 * Passive text, never focusable; [compact] is the icon-only form for episode
 * cards, which still speaks the words to accessibility services.
 */
@Composable
internal fun TvMachineTranslatedLabel(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    iconSize: Dp = 14.dp,
) {
    MachineTranslatedLabel(
        color = Color.White.copy(alpha = 0.6f),
        compact = compact,
        fontSize = 14.sp,
        iconSize = iconSize,
        modifier = modifier,
    )
}
