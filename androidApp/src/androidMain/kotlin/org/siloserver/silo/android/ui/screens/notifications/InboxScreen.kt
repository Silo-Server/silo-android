package org.siloserver.silo.android.ui.screens.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.LoadingIndicator
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.repository.NotificationsRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant

/**
 * Notifications inbox: paginated newest-first list of delivery rows rendered as
 * rich (poster) cards for episode notifications and posterless fallback cards
 * for everything else. Tapping a card optimistically marks it read and
 * deep-links to the item detail (series → episode → library id). A "Mark all
 * read" top-bar action appears whenever there are unread rows.
 *
 * State (loading / error / empty / list) mirrors CalendarScreen. The repository
 * is the source of truth and owns optimistic-with-revert mark-read, so the
 * screen does not manage revert. REST drives content; the realtime client folds
 * in updates behind the same flows when the socket is up.
 */
@Composable
fun InboxScreen(
    onBackClick: () -> Unit,
    onItemClick: (String) -> Unit,
    repository: NotificationsRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val rows by repository.rows.collectAsState()
    val unreadCount by repository.unreadCount.collectAsState()
    val nextCursor by repository.nextCursor.collectAsState()

    // The repo has no loading/error flow (refresh() swallows API errors and the
    // realtime client folds late results in behind `rows`), so the screen owns
    // only a first-load spinner shown while the list is still empty. After a
    // refresh, an empty list is the genuine empty state, not an error — there is
    // no failure signal to surface, so no ErrorView path is wired here.
    var isRefreshing by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }

    suspend fun doRefresh() {
        isRefreshing = true
        repository.refresh()
        isRefreshing = false
    }

    LaunchedEffect(Unit) {
        doRefresh()
    }

    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = layout.totalItemsCount
            total > 0 && lastVisible >= total - 4
        }
    }

    LaunchedEffect(shouldLoadMore, nextCursor) {
        val cursor = nextCursor
        if (shouldLoadMore && cursor != null && !isLoadingMore) {
            isLoadingMore = true
            repository.loadMore(cursor)
            isLoadingMore = false
        }
    }

    val now = remember(rows) { Instant.now() }
    val cards = remember(rows, now) { rows.map { inboxCardModel(it, now) } }

    Scaffold(
        topBar = {
            SiloTopBar(
                title = "Notifications",
                onBackClick = onBackClick,
                actions = {
                    if (unreadCount > 0) {
                        TextButton(onClick = { scope.launch { repository.markAllRead() } }) {
                            Text("Mark all read")
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                isRefreshing && cards.isEmpty() -> LoadingIndicator()
                cards.isEmpty() -> EmptyStateView(
                    title = "You're all caught up",
                    subtitle = "New episode and request notifications will show up here.",
                )
                else -> DeferImagePresentationWhileScrolling(listState) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(cards, key = { it.id }) { card ->
                        InboxCard(
                            card = card,
                            onClick = {
                                scope.launch { repository.markRead(card.id) }
                                card.targetContentId?.let(onItemClick)
                            },
                        )
                    }
                    if (isLoadingMore) {
                        item(key = "loading-more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun InboxCard(
    card: InboxCardModel,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(
            alpha = if (card.isUnread) 0.5f else 0.3f,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (card.isRich) {
                ThumbhashImage(
                    url = card.posterUrl,
                    thumbhash = card.posterThumbhash,
                    contentDescription = card.title,
                    modifier = Modifier
                        .width(48.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (card.isUnread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                card.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (card.relativeTime.isNotBlank()) {
                    Text(
                        text = card.relativeTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.isUnread) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}
