package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import org.siloserver.silo.android.R
import org.siloserver.silo.model.profile.Profile

// Height of the floating top bar's body, excluding the status-bar inset
// (4dp top + 40dp action row + 8dp bottom — iOS headerTopInset / smallPadding,
// same as Home's chrome). Callers add WindowInsets.statusBars so tab content
// clears the bar regardless of status-bar height.
val MainAppHeaderBodyHeight = 52.dp

/**
 * Shared floating header for the tabs that do not paint their own chrome
 * (For You, Calendar, Downloads). Same recipe as Home: glass over the tab
 * content (registered on [hazeState]) capped with a hairline, a leading
 * title or wordmark, and the shared trailing action cluster.
 */
@Composable
fun MainAppTopBar(
    activeProfile: Profile?,
    isProfileLoading: Boolean,
    hazeState: HazeState,
    onSearchClick: () -> Unit,
    onRequestsClick: (() -> Unit)? = null,
    onWatchTogetherClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    onSignOutClick: () -> Unit,
    leadingContent: @Composable () -> Unit = {
        SiloWordmark()
    },
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E)),
    ) {
        Box(
            modifier = Modifier
                .padding(
                    top = statusBarPadding.calculateTopPadding() + 4.dp,
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 8.dp,
                )
                .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier.align(Alignment.CenterStart),
                contentAlignment = Alignment.CenterStart,
            ) {
                leadingContent()
            }

            TabTopBarActions(
                modifier = Modifier.align(Alignment.CenterEnd),
                activeProfile = activeProfile,
                onSearchClick = onSearchClick,
                onRequestsClick = onRequestsClick,
                onWatchTogetherClick = onWatchTogetherClick,
                onSettingsClick = onSettingsClick,
                onSwitchProfileClick = onSwitchProfileClick,
                onSwitchServerClick = onSwitchServerClick,
                onSignOutClick = onSignOutClick,
                opaque = true,
            )
        }

        // Bottom hairline (iOS 0.75pt, white 0.10).
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(0.75.dp)
                .drawBehind { drawRect(color = Color.White, alpha = 0.10f) },
        )
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
