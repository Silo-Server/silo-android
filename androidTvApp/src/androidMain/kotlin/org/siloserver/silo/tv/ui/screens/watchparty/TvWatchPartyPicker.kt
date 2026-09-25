package org.siloserver.silo.tv.ui.screens.watchparty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.watchtogether.PickerEntry
import org.siloserver.silo.tv.ui.components.TvPoster
import org.siloserver.silo.tv.ui.components.TvTextInputDialog
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import org.siloserver.silo.tv.ui.focus.TvRestoreFocusOnModalDismiss
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.tvModalFocusBoundary
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.viewmodel.WatchPartyPickerViewModel

/** One pickable row: a server picker entry or a search result. */
private data class TvPickerRow(
    val key: String,
    val item: BrowseItem,
    val entry: PickerEntry?,
)

/**
 * "Add a title": the server's shared rows (Continue Together, members'
 * watchlists; empty rows hidden) and catalog search. Movies and episodes are
 * picked directly; a series row with a next-up episode picks that episode; a
 * series search result opens the normal series page, where the party action
 * applies.
 *
 * Focus: the Search row always exists, so loading, error, and empty states
 * keep a focus owner. If the focused result disappears (a newer search), focus
 * returns to Search instead of dropping.
 */
@Composable
internal fun TvWatchPartyPicker(
    /** Host Picks host: the pick is staged. Otherwise it is suggested. */
    stages: Boolean,
    onPick: (WatchPartyItem) -> Unit,
    onOpenSeries: (contentId: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: WatchPartyPickerViewModel = koinViewModel(key = "tv-watch-party-picker"),
) {
    val state by viewModel.state.collectAsState()
    var searchOpen by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var pickerHasFocus by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(viewModel) { viewModel.loadRows() }

    val searching = state.query.isNotBlank()
    val rows: List<TvPickerRow> = if (searching) {
        // Lazy keys must be unique; a repeated result would crash the list.
        state.results.map { TvPickerRow("search:${it.contentId}", it, null) }.distinctBy { it.key }
    } else {
        emptyList()
    }
    val continueTogether: List<TvPickerRow> = if (searching) emptyList() else state.rows?.continueTogether.orEmpty()
        .map { TvPickerRow("together:${it.item.contentId}", it.item, it) }.distinctBy { it.key }
    val watchlists: List<TvPickerRow> = if (searching) emptyList() else state.rows?.watchlistUnion.orEmpty()
        .map { TvPickerRow("watchlist:${it.item.contentId}", it.item, it) }.distinctBy { it.key }

    val pick: (TvPickerRow) -> Unit = { row ->
        val item = row.item
        val nextUp = row.entry?.nextUp
        when {
            item.type == "series" && nextUp != null -> onPick(
                WatchPartyItem(
                    contentId = nextUp.contentId,
                    contentType = "episode",
                    title = nextUp.title.ifBlank { "Episode ${nextUp.episodeNumber}" },
                    subtitle = "${item.title} · S${nextUp.seasonNumber}E${nextUp.episodeNumber}",
                    posterUrl = item.posterUrl,
                ),
            )
            item.type == "series" -> onOpenSeries(item.contentId)
            else -> onPick(
                WatchPartyItem(
                    contentId = item.contentId,
                    contentType = item.type,
                    title = item.title,
                    subtitle = item.year.takeIf { it > 0 }?.toString(),
                    posterUrl = item.posterUrl,
                ),
            )
        }
    }

    // A result that vanished under focus (a newer search, reloaded rows)
    // would otherwise leave the picker with no focus owner.
    LaunchedEffect(pickerHasFocus, searchOpen) {
        if (pickerHasFocus || searchOpen) return@LaunchedEffect
        delay(PICKER_FOCUS_RESCUE_DELAY_MS)
        requestFocusUntilObserved(
            maxAttempts = 20,
            awaitAttempt = { delay(60) },
            requestFocus = searchFocus::requestFocus,
            isFocused = { pickerHasFocus },
        )
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(640.dp)
                    .fillMaxHeight()
                    .padding(vertical = 28.dp)
                    .onFocusChanged { pickerHasFocus = it.hasFocus }
                    .tvModalFocusBoundary()
                    .then(rememberTvDialogInitialFocus(searchFocus)),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (stages) "Add a title" else "Suggest a title",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = if (stages) {
                        "Everyone watches what you add. Start when you're ready."
                    } else {
                        "Your suggestion goes to the party's list."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvPartyActionRow(
                        title = if (searching) "Search: ${state.query.trim()}" else "Search titles",
                        onClick = { searchOpen = true },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFocus)
                            .onFocusChanged { searchFocused = it.isFocused },
                    )
                    if (searching) {
                        TvPartyChip(text = "Clear", onClick = { viewModel.onQuery("") })
                    }
                    TvPartyChip(text = "Close", onClick = onDismiss)
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (searching) {
                        when {
                            state.error != null && rows.isEmpty() -> item(key = "search-error") {
                                PickerStatus(state.error ?: "Search failed.")
                            }
                            state.searching && rows.isEmpty() -> item(key = "searching") { PickerStatus("Searching…") }
                            rows.isEmpty() -> item(key = "no-results") {
                                PickerStatus("No titles match “${state.query.trim()}”.")
                            }
                        }
                        items(rows, key = { it.key }) { row -> PickerItemRow(row, onClick = { pick(row) }) }
                    } else {
                        when {
                            state.rowsLoading -> item(key = "rows-loading") { PickerStatus("Loading…") }
                            state.error != null -> {
                                item(key = "rows-error") { PickerStatus(state.error ?: "Couldn't load suggestions.") }
                                item(key = "rows-retry") {
                                    TvPartyChip(text = "Retry", onClick = viewModel::loadRows)
                                }
                            }
                            continueTogether.isEmpty() && watchlists.isEmpty() -> item(key = "rows-empty") {
                                PickerStatus("Search for a movie, show, or episode.")
                            }
                        }
                        if (continueTogether.isNotEmpty()) {
                            item(key = "together-label") { TvPartySectionLabel("Continue together") }
                            items(continueTogether, key = { it.key }) { row -> PickerItemRow(row, onClick = { pick(row) }) }
                        }
                        if (watchlists.isNotEmpty()) {
                            item(key = "watchlist-label") { TvPartySectionLabel("On the party's watchlists") }
                            items(watchlists, key = { it.key }) { row -> PickerItemRow(row, onClick = { pick(row) }) }
                        }
                    }
                }
            }
        }
    }

    if (searchOpen) {
        TvTextInputDialog(
            title = "Search titles",
            label = "Title",
            confirmLabel = "Search",
            initialValue = state.query,
            allowBlank = true,
            onConfirm = { text ->
                viewModel.onQuery(text)
                searchOpen = false
            },
            onDismiss = { searchOpen = false },
        )
    }
    TvRestoreFocusOnModalDismiss(
        visible = searchOpen,
        opener = searchFocus,
        isOpenerFocused = { searchFocused },
    )
}

@Composable
private fun PickerStatus(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White.copy(alpha = 0.72f),
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PickerItemRow(row: TvPickerRow, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val item = row.item
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.05f),
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = BorderStroke(2.dp, DarkBackground.copy(alpha = 0.82f)), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.14f), elevation = 10.dp),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (isFocused) Modifier.border(2.dp, Color.White.copy(alpha = 0.98f), shape) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvPoster(imageUrl = item.posterUrl, contentDescription = null, modifier = Modifier.size(width = 36.dp, height = 54.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = if (isFocused) FocusedContent else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tvPickerRowDetail(row),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun tvPickerRowDetail(row: TvPickerRow): String {
    val item = row.item
    val nextUp = row.entry?.nextUp
    val type = when (item.type) {
        "movie" -> "Movie"
        "series" -> "Series"
        "episode" -> "Episode"
        else -> item.type.replaceFirstChar { it.uppercase() }
    }
    val parts = mutableListOf(type)
    item.year.takeIf { it > 0 }?.let { parts += it.toString() }
    when {
        item.type == "series" && nextUp != null -> parts += "Next: S${nextUp.seasonNumber}E${nextUp.episodeNumber}"
        item.type == "series" -> parts += "Choose an episode"
    }
    val members = row.entry?.members.orEmpty().map { it.displayName.trim() }.filter { it.isNotEmpty() }
    if (members.isNotEmpty()) parts += tvWatchPartyNameList(members)
    return parts.joinToString(" · ")
}

private const val PICKER_FOCUS_RESCUE_DELAY_MS = 150L
