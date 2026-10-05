package org.siloserver.silo.android.ui.components.marquee

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.R
import org.siloserver.silo.common.ui.marquee.MarqueeScrim
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle

/**
 * A first-run screen on the phone: transparent over the shared backdrop, a
 * scrim for legibility, a top bar, and content anchored to the bottom like a
 * streaming app's welcome screen. The column scrolls, so large text and the
 * keyboard push the title up instead of clipping the controls.
 *
 * @param onBack what system Back does; null leaves Back to the navigation
 *   host. Pass null while a sign-in is in flight so leaving can't race it.
 */
@Composable
fun MarqueeStage(
    modifier: Modifier = Modifier,
    scrim: MarqueeScrimStyle = MarqueeScrimStyle.Bottom,
    frostStart: Float? = 0.42f,
    maxWidth: Dp = 440.dp,
    onBack: (() -> Unit)? = null,
    blockBack: Boolean = false,
    topBar: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = onBack != null || blockBack) { if (!blockBack) onBack?.invoke() }
    Box(modifier.fillMaxSize()) {
        MarqueeScrim(scrim, frostStart = frostStart)
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = topBar,
            )
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            ) {
                val viewport = maxHeight
                Column(
                    Modifier
                        .fillMaxSize()
                        // Keep the primary action in view when the room changes.
                        .verticalScroll(rememberScrollState(), reverseScrolling = true),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier
                            .heightIn(min = viewport)
                            .widthIn(max = maxWidth)
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.Bottom,
                        content = content,
                    )
                }
            }
        }
    }
}

/** The Silo wordmark for a first-run top bar. */
@Composable
fun MarqueeWordmark(width: Dp = 92.dp) {
    Image(
        painter = painterResource(R.drawable.silo_wordmark),
        contentDescription = "Silo",
        contentScale = ContentScale.Fit,
        modifier = Modifier.width(width),
    )
}

/** Pushes the trailing top-bar item to the end. */
@Composable
fun RowScope.MarqueeTopBarSpacer() = Spacer(Modifier.weight(1f))
