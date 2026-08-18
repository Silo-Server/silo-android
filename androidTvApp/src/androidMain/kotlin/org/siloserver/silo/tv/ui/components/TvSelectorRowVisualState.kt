package org.siloserver.silo.tv.ui.components

import androidx.compose.ui.graphics.Color
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.SiloOnSurface

internal data class TvSelectorRowVisualState(
    val container: Color,
    val content: Color,
    val border: Color,
)

internal fun tvSelectorRowVisualState(
    focused: Boolean,
    selected: Boolean,
    enabled: Boolean,
): TvSelectorRowVisualState = when {
    // Rows sit on the Skyline glass panel (tvSkylinePanelChrome), so idle and
    // disabled rows are transparent like the cascade's; only focus (inverted
    // capsule) and the current selection (soft tint) paint a fill.
    !enabled -> TvSelectorRowVisualState(
        Color.Transparent,
        SiloOnSurface.copy(alpha = 0.38f),
        Color.Transparent,
    )
    focused -> TvSelectorRowVisualState(
        FocusedContainer,
        FocusedContent,
        FocusedContent.copy(alpha = 0.22f),
    )
    selected -> TvSelectorRowVisualState(
        SiloOnSurface.copy(alpha = 0.14f),
        SiloOnSurface,
        SiloOnSurface.copy(alpha = 0.28f),
    )
    else -> TvSelectorRowVisualState(Color.Transparent, SiloOnSurface, Color.Transparent)
}
