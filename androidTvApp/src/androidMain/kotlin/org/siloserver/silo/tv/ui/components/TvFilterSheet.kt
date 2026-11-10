package org.siloserver.silo.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.tv.ui.focus.tvModalFocusBoundary
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus
import org.siloserver.silo.tv.ui.theme.Spacing

/**
 * Bottom-anchored slide-up filter sheet for the library detail screen.
 * Mirrors the tvOS TVLibraryFilterSheet pattern: a 60%-height surface
 * with Genre / Year / Sort / Alphabet sections, Back to dismiss.
 *
 * The sheet is a modal focus owner: D-pad movement cannot leave it for the
 * page still composed behind the scrim. Callers are responsible for handing
 * focus back to the control that opened it, via TvRestoreFocusOnModalDismiss —
 * the sheet cannot do that itself because its exit animation outlives its own
 * dismissal.
 *
 * Sections are slotted by the caller via [content] so this component
 * stays generic; the library detail screen composes the actual filter
 * sections inside it.
 */
@Composable
fun TvFilterSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 220)),
        exit = fadeOut(animationSpec = tween(durationMillis = 180)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Top scrim — dims the content above the sheet. Click is not
            // captured (TV has no click) — Back dismisses.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.40f)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .align(Alignment.TopStart),
            )

            // Sheet surface — bottom 60%, slides up from below.
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(durationMillis = 280),
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(durationMillis = 220),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.60f)
                    .align(Alignment.BottomStart),
            ) {
                val focusRequester = remember { FocusRequester() }
                // The sheet slides in, so the first request lands before its
                // controls are placed. Retry until focus is actually observed
                // inside the sheet rather than firing once and hoping.
                val sheetFocus = rememberTvContentInitialFocus(
                    target = focusRequester,
                    contentKey = visible.takeIf { it },
                )

                BackHandler(enabled = visible, onBack = onDismiss)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(
                            horizontal = Spacing.safeArea,
                            vertical = Spacing.xl,
                        )
                        .then(sheetFocus)
                        .tvModalFocusBoundary()
                        .focusRequester(focusRequester),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    Text(
                        text = stringResource(CommonR.string.filters_title),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    content()
                }
            }
        }
    }
}
