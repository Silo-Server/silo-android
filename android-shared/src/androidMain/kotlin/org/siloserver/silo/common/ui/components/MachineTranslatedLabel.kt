package org.siloserver.silo.common.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The words every machine-translation marker uses, shown or spoken. */
const val MachineTranslatedDescription = "Translated by AI"

/**
 * Marks text the server reports as machine-translated (the
 * `machine_translated_fields` of a detail, season, episode or card). The
 * compact form is an icon for dense rows such as episode cards; it keeps the
 * words for accessibility services. Never focusable, so it adds no D-pad stop.
 */
@Composable
fun MachineTranslatedLabel(
    color: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    fontSize: TextUnit = 12.sp,
    iconSize: Dp = 12.dp,
) {
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = MachineTranslatedDescription },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            imageVector = Icons.Outlined.Translate,
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = Modifier.size(iconSize),
        )
        if (!compact) {
            BasicText(
                text = MachineTranslatedDescription,
                style = TextStyle(color = color, fontSize = fontSize),
                maxLines = 1,
            )
        }
    }
}
