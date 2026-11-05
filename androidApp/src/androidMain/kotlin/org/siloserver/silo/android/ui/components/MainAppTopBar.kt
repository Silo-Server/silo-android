package org.siloserver.silo.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import org.siloserver.silo.android.R
import org.siloserver.silo.model.profile.Profile

// Height of the floating top bar's body, excluding the status-bar inset: the
// header row plus the gap above the pill row. Tab content placed at this
// offset puts its pill row on the same line as Libraries' subtabs.
val MainAppHeaderBodyHeight = TabHeaderRowHeight + TabHeaderPillGap

/**
 * Shared floating header for the tabs that do not paint their own chrome
 * (For You, Downloads). It matches Libraries: no fill at rest, and progressive
 * glass over the tab content (registered on [hazeState]) that fades in by
 * [scrimProgress] as content scrolls beneath it.
 */
@Composable
fun MainAppTopBar(
    activeProfile: Profile?,
    remoteControl: TopBarRemoteControl,
    isProfileLoading: Boolean,
    hazeState: HazeState,
    scrimProgress: Float,
    onSearchClick: () -> Unit,
    onRequestsClick: (() -> Unit)? = null,
    onWatchPartyClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onChangeAccountClick: () -> Unit,
    onSignOutClick: (removeServer: Boolean) -> Unit,
    leadingContent: @Composable () -> Unit = {
        SiloWordmark()
    },
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val animatedFill by animateFloatAsState(
        targetValue = scrimProgress,
        label = "mainAppTopBarFill",
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = animatedFill }
                .topBarGlass(hazeState, progressive = true),
        )
        Row(
            modifier = Modifier
                .padding(
                    top = statusBarPadding.calculateTopPadding() + TopBarRowTopInset,
                    start = TopBarEdgeMargin,
                    end = TopBarEdgeMargin,
                    bottom = TabHeaderPillGap,
                )
                .fillMaxWidth()
                .height(TabHeaderRowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                leadingContent()
            }

            TabTopBarActions(
                activeProfile = activeProfile,
                remoteControl = remoteControl,
                onSearchClick = onSearchClick,
                onRequestsClick = onRequestsClick,
                onWatchPartyClick = onWatchPartyClick,
                onSettingsClick = onSettingsClick,
                onSwitchProfileClick = onSwitchProfileClick,
                onChangeAccountClick = onChangeAccountClick,
                onSignOutClick = onSignOutClick,
            )
        }
    }
}

@Composable
fun SiloWordmark(
    modifier: Modifier = Modifier,
    width: Dp = 72.dp,
) {
    androidx.compose.foundation.Image(
        painter = painterResource(id = R.drawable.silo_wordmark),
        contentDescription = "Silo",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .width(width)
            .height(width * 0.52f),
    )
}
