package org.siloserver.silo.android.ui.components.aurora

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Aurora backdrop + a keyboard-friendly column capped to a comfortable reading
 * width and centered over the plum backdrop. Callers supply the wordmark +
 * content. Port of silo-apple's `AuroraScreen`.
 */
@Composable
fun AuroraScreen(
    variant: AuroraVariant,
    modifier: Modifier = Modifier,
    scrim: AuroraScrim = AuroraScrim.Soft,
    maxContentWidth: Dp = 480.dp,
    /**
     * Scrolls the content as one block and sizes it to its children — right for
     * the form screens, which are shorter than the display until the keyboard
     * opens.
     *
     * Screens that lay themselves out against the full height (a `weight`ed
     * body between fixed chrome) must pass `false`. A scrolling parent measures
     * its children with unbounded height, which leaves nothing for `weight` to
     * divide, so weighted children collapse to zero and vanish.
     */
    scrollable: Boolean = true,
    /**
     * Gutter between the content and the display edge. Screens that run a
     * full-bleed element (a pager whose neighbouring pages should peek past the
     * gutter rather than be clipped at it) set this to zero and pad their own
     * rows instead.
     */
    horizontalPadding: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AuroraBackdrop(variant = variant, scrim = scrim)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                // safeDrawing covers the status/navigation bars, the cutout and
                // the IME — the activity draws edge to edge, so without this the
                // first and last rows of a full-height screen sit under system
                // chrome.
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = maxContentWidth)
                    .then(if (scrollable) Modifier else Modifier.fillMaxHeight())
                    .padding(horizontal = horizontalPadding, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}
