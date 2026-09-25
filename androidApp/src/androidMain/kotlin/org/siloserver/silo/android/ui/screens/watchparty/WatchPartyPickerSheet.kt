package org.siloserver.silo.android.ui.screens.watchparty

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.watchtogether.ItemMemberState
import org.siloserver.silo.model.watchtogether.PickerEntry
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.viewmodel.WatchPartyPickerViewModel

/** What choosing a title in the picker does. */
internal enum class WatchPartyPickMode(val sheetTitle: String, val confirmLabel: String) {
    /** Host Picks host: stage it as the next title. */
    Stage("Add a title", "Add to party"),

    /** Everyone else, and vote rooms: suggest it. */
    Suggest("Suggest a title", "Suggest"),
}

/**
 * The room picker: the server's shared rows while the search field is empty,
 * catalog search otherwise. A series with a next-up episode offers that
 * episode; any other series opens its detail page, where the party action
 * applies. Picking never starts playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatchPartyPickerSheet(
    mode: WatchPartyPickMode,
    roomMembers: List<RoomMember>,
    memberState: suspend (String) -> ItemMemberState?,
    onPick: (WatchPartyItem) -> Unit,
    onOpenSeries: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: WatchPartyPickerViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var candidate by remember { mutableStateOf<WatchPartyItem?>(null) }
    LaunchedEffect(Unit) { viewModel.loadRows() }

    fun choose(item: BrowseItem, entry: PickerEntry? = null) {
        val nextUp = entry?.nextUp
        when {
            item.type == "series" && nextUp != null -> candidate = WatchPartyItem(
                contentId = nextUp.contentId,
                contentType = "episode",
                title = nextUp.title.ifBlank { "Episode ${nextUp.episodeNumber}" },
                subtitle = "${item.title} · S${nextUp.seasonNumber}·E${nextUp.episodeNumber}",
                posterUrl = item.posterUrl,
            )
            item.type == "series" -> {
                onDismiss()
                onOpenSeries(item.contentId)
            }
            else -> candidate = WatchPartyItem(
                contentId = item.contentId,
                contentType = item.type,
                title = item.title,
                subtitle = item.year.takeIf { it > 0 }?.toString(),
                posterUrl = item.posterUrl,
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(mode.sheetTitle, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQuery,
                placeholder = { Text("Search movies and shows") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.query.isBlank()) {
                    val rows = state.rows
                    if (state.rowsLoading) {
                        item { PickerProgress() }
                    }
                    rows?.continueTogether?.takeIf { it.isNotEmpty() }?.let { entries ->
                        item { PickerHeader("Continue together") }
                        items(entries) { entry ->
                            PickerRow(entry.item, pickerEntryDetail(entry, roomMembers)) { choose(entry.item, entry) }
                        }
                    }
                    rows?.watchlistUnion?.takeIf { it.isNotEmpty() }?.let { entries ->
                        item { PickerHeader("On your watchlists") }
                        items(entries) { entry ->
                            PickerRow(entry.item, pickerEntryDetail(entry, roomMembers)) { choose(entry.item, entry) }
                        }
                    }
                    if (!state.rowsLoading && rows?.continueTogether.isNullOrEmpty() && rows?.watchlistUnion.isNullOrEmpty()) {
                        item {
                            Text(
                                "Search for something to watch.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                } else {
                    if (state.searching) item { PickerProgress() }
                    items(state.results) { item ->
                        PickerRow(item, browseItemDetail(item)) { choose(item) }
                    }
                    if (!state.searching && state.results.isEmpty() && state.query.trim().length >= 2) {
                        item {
                            Text(
                                "No matches.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    candidate?.let { item ->
        CandidateDialog(
            item = item,
            mode = mode,
            roomMembers = roomMembers,
            memberState = memberState,
            onConfirm = {
                candidate = null
                onPick(item)
                onDismiss()
            },
            onDismiss = { candidate = null },
        )
    }
}

/** Confirms the chosen title and shows who in the room has seen it (one bounded read). */
@Composable
private fun CandidateDialog(
    item: WatchPartyItem,
    mode: WatchPartyPickMode,
    roomMembers: List<RoomMember>,
    memberState: suspend (String) -> ItemMemberState?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var loading by remember(item.contentId) { mutableStateOf(true) }
    var summary by remember(item.contentId) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.contentId) {
        summary = memberState(item.contentId)?.let { memberStateSummary(it, roomMembers) }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                when {
                    loading -> Text(
                        "Checking who has seen it…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    summary != null -> Text(summary.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(mode.confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun memberStateSummary(state: ItemMemberState, roomMembers: List<RoomMember>): String {
    fun name(userId: String, profileId: String): String =
        roomMembers.firstOrNull { it.userId == userId && it.profileId == profileId }
            ?.let(::watchPartyMemberName) ?: "Someone"
    val seen = state.members.filter { it.state == "watched" || it.state == "played" }
        .map { name(it.userId, it.profileId) }
    val partWay = state.members.filter { it.state == "in_progress" }
        .map { name(it.userId, it.profileId) }
    val lines = buildList {
        if (seen.isNotEmpty()) add("Seen by ${formatWatchPartyNames(seen)}.")
        if (partWay.isNotEmpty()) add("Part way through: ${formatWatchPartyNames(partWay)}.")
    }
    return lines.joinToString("\n").ifEmpty { "Nobody here has watched it yet." }
}

private fun pickerEntryDetail(entry: PickerEntry, roomMembers: List<RoomMember>): String? {
    entry.nextUp?.let { next ->
        return "Next: S${next.seasonNumber}·E${next.episodeNumber}" +
            next.title.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    }
    val partWay = entry.members.filter { it.positionSeconds != null }.map { member ->
        roomMembers.firstOrNull { it.userId == member.userId && it.profileId == member.profileId }
            ?.let(::watchPartyMemberName) ?: member.displayName.ifBlank { "Someone" }
    }
    return partWay.takeIf { it.isNotEmpty() }?.let { "Part way: ${formatWatchPartyNames(it)}" }
        ?: browseItemDetail(entry.item)
}

private fun browseItemDetail(item: BrowseItem): String? {
    val kind = when (item.type) {
        "movie" -> "Movie"
        "series" -> "Series"
        "episode" -> "Episode"
        else -> null
    }
    return listOfNotNull(kind, item.year.takeIf { it > 0 }?.toString()).joinToString(" · ").ifBlank { null }
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickerProgress() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun PickerRow(item: BrowseItem, detail: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        ThumbhashImage(
            url = item.posterUrl,
            thumbhash = item.posterThumbhash,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(40.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
