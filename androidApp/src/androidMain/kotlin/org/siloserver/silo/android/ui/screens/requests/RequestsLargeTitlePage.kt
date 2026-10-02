package org.siloserver.silo.android.ui.screens.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.theme.siloPageBackdrop
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling

/** Height of the pinned bar below the status bar. */
private val BarHeight = 56.dp

/** Scroll distance over which the large title hands off to the bar's title. */
private val TitleHandoff = 44.dp

/**
 * A pushed page with a large title, as the Apple clients draw Requests: the
 * title scrolls away under a pinned bar that keeps the back disc and gains the
 * compact title. Pull to refresh reloads the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RequestsLargeTitlePage(
    title: String,
    onBackClick: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** Gap between list items; grouped-row pages pass zero and space their own sections. */
    itemSpacing: Dp = 24.dp,
    actions: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + BarHeight
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val titleProgress by remember(listState, density) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                val handoff = with(density) { TitleHandoff.toPx() }
                (listState.firstVisibleItemScrollOffset / handoff).coerceIn(0f, 1f)
            }
        }
    }
    val pullState = rememberPullToRefreshState()
    Box(modifier = modifier.fillMaxSize().siloPageBackdrop()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topInset),
                )
            },
        ) {
            DeferImagePresentationWhileScrolling(listState) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = topInset, bottom = 24.dp + bottomInset),
                    verticalArrangement = Arrangement.spacedBy(itemSpacing),
                ) {
                    item(key = "large-title", contentType = "large-title") {
                        RequestsLargeTitle(title = title)
                    }
                    content()
                }
            }
        }
        RequestsLargeTitleBar(
            title = title,
            titleProgress = titleProgress,
            onBackClick = onBackClick,
            actions = actions,
        )
    }
}
