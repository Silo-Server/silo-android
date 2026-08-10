package org.siloserver.silo.tv.ui.screens.notifications

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.repository.NotificationsRepository
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.SiloBlue
import org.siloserver.silo.tv.ui.theme.ElevatedSurface
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.sectionEyebrow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant

/**
 * TV notifications inbox: a focusable, D-pad-traversable newest-first list of
 * delivery rows. Episode notifications render as rich poster cards; every other
 * (and unrecognized) type renders a posterless fallback card. The first row is a
 * focusable "Mark all read" card shown whenever there are unread notifications.
 *
 * OK on a notification optimistically marks it read (the repository owns the
 * optimistic-with-revert) and deep-links via [onOpenItemDetail]
 * (series → episode → library id). Scrolling near the end pages in the next
 * cursor.
 *
 * State mirrors the mobile inbox (M3): the repository has no loading/error flow,
 * so the screen owns only a first-load spinner while the list is empty. After a
 * refresh an empty list is the genuine empty state — there is no failure signal,
 * so no error/retry path is wired here.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvInboxScreen(
    onOpenItemDetail: (contentId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: NotificationsRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val rows by repository.rows.collectAsState()
    val unreadCount by repository.unreadCount.collectAsState()
    val nextCursor by repository.nextCursor.collectAsState()

    var isRefreshing by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isRefreshing = true
        repository.refresh()
        isRefreshing = false
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
            // try/finally so a key change that cancels this coroutine mid-load
            // can't strand isLoadingMore=true and permanently wedge pagination.
            try {
                repository.loadMore(cursor)
            } finally {
                isLoadingMore = false
            }
        }
    }

    val now = remember(rows) { Instant.now() }
    val cards = remember(rows, now) { rows.map { tvInboxCardModel(it, now) } }
    val hasUnread = unreadCount > 0

    // Focus the first actionable row once data is present. The Mark-all card is
    // the first focusable when unread; otherwise the first notification card —
    // both wear [firstRowFocusRequester], so re-requesting it after the Mark-all
    // card disappears lands focus on the first notification row.
    val firstRowFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }
    // Set by the Mark-all card's onClick: clicking it removes the focused card
    // from composition and drops focus, so we refocus the first notification row
    // once the unread state clears. An explicit flag — rather than inferring a
    // hasUnread true→false transition — so an incoming notification being read
    // elsewhere can't yank focus mid-browse.
    var pendingMarkAllRefocus by remember { mutableStateOf(false) }
    var firstRowHasFocus by remember { mutableStateOf(false) }

    // Both claims are observed rather than fire-and-forget. The first is
    // acquisition — the inbox has just populated and nothing is focused yet, so
    // dropping it leaves a dead D-pad on a full screen of notifications. The
    // second is a relocation after Mark-all removed the focused card from
    // composition, where focus is already gone and a short budget is right.
    LaunchedEffect(cards.isNotEmpty(), hasUnread) {
        if (cards.isNotEmpty() && !initialFocusRequested) {
            initialFocusRequested = true
            requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = firstRowFocusRequester::requestFocus,
                isFocused = { firstRowHasFocus },
            )
        }
        if (pendingMarkAllRefocus && !hasUnread && cards.isNotEmpty()) {
            pendingMarkAllRefocus = false
            requestFocusUntilObserved(
                maxAttempts = TvFrameRelocationMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = firstRowFocusRequester::requestFocus,
                isFocused = { firstRowHasFocus },
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Success is "focus is inside the inbox", not "the first row
            // specifically" — a claim that lands anywhere in the list leaves a
            // working D-pad, which is what the retry is protecting.
            .onFocusChanged { firstRowHasFocus = it.hasFocus }
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier.padding(
                start = Spacing.safeArea,
                end = Spacing.safeArea,
                top = TvTopMenuLayout.contentTopInset,
                bottom = Spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "NOTIFICATIONS",
                style = sectionEyebrow,
                color = SiloBlue.copy(alpha = 0.92f),
            )
            Text(
                text = "Your Inbox",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        when {
            isRefreshing && cards.isEmpty() -> TvLoadingScreen()
            cards.isEmpty() -> InboxEmptyState()
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusGroup(),
                contentPadding = PaddingValues(
                    start = Spacing.safeArea,
                    end = Spacing.safeArea,
                    bottom = 56.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (hasUnread) {
                    item(key = "mark-all-read") {
                        MarkAllReadCard(
                            focusRequester = firstRowFocusRequester,
                            onClick = {
                                pendingMarkAllRefocus = true
                                scope.launch { repository.markAllRead() }
                            },
                        )
                    }
                }
                itemsIndexed(cards, key = { _, card -> card.id }) { index, card ->
                    TvInboxRow(
                        card = card,
                        focusRequester = firstRowFocusRequester.takeIf { !hasUnread && index == 0 },
                        onClick = {
                            scope.launch { repository.markRead(card.id) }
                            card.targetContentId?.let(onOpenItemDetail)
                        },
                    )
                }
            }
        }
    }
}

private val cardShape = RoundedCornerShape(14.dp)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MarkAllReadCard(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = cardShape),
        scale = CardDefaults.scale(focusedScale = 1.02f),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = cardShape),
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.25f), elevation = 16.dp),
        ),
        colors = CardDefaults.colors(containerColor = ElevatedSurface),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "Mark all read",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvInboxRow(
    card: TvInboxCardModel,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = cardShape),
        scale = CardDefaults.scale(focusedScale = 1.02f),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = cardShape),
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.25f), elevation = 16.dp),
        ),
        colors = CardDefaults.colors(
            containerColor = ElevatedSurface.copy(alpha = if (card.isUnread) 0.92f else 0.6f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (card.isRich) {
                ThumbhashImage(
                    url = card.posterUrl,
                    thumbhash = card.posterThumbhash,
                    contentDescription = card.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(56.dp)
                        .height(84.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = if (card.isUnread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                card.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (card.relativeTime.isNotBlank()) {
                    Text(
                        text = card.relativeTime,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.56f),
                    )
                }
                if (card.isUnread) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(SiloBlue),
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxEmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            androidx.tv.material3.Icon(
                imageVector = Icons.Filled.NotificationsNone,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.size(30.dp),
            )
            Text(
                text = "You're all caught up",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                text = "New episode and request notifications will show up here.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.62f),
            )
        }
    }
}
