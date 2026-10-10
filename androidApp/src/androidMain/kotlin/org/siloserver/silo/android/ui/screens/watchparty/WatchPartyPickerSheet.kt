package org.siloserver.silo.android.ui.screens.watchparty

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.BackdropCard
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.android.ui.navigation.LocalHeroSourceHandoff
import org.siloserver.silo.android.ui.navigation.LocalSharedTransitionScope
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloPageBackground
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.watchtogether.ItemMemberState
import org.siloserver.silo.model.watchtogether.MemberWatchState
import org.siloserver.silo.model.watchtogether.PickerEntry
import org.siloserver.silo.model.watchtogether.PickerNextUp
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.viewmodel.WatchPartyPickerViewModel

/** What choosing a title in the picker does. */
internal enum class WatchPartyPickMode(val title: String, val eyebrow: String, val confirmLabel: String) {
    /** Host Picks host: stage it as the next title. */
    Stage("Choose a title", "Watch together", "Choose for the party"),

    /** Everyone else, and vote rooms: suggest it. */
    Suggest("Suggest a title", "Suggest to the party", "Add suggestion"),
}

/**
 * The room picker, after Apple's `WatchPartyMediaPicker`: full screen, search
 * on top, then Home-style shelves (the group's rows, this profile's resume
 * row, Home's discovery rows, and the newest movies and series). Choosing a
 * title opens a confirmation page with who in the room has seen it. A series
 * with a group next-up episode offers that episode; any other series opens
 * its detail page, where the party action applies. Picking never starts
 * playback.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun WatchPartyPicker(
    mode: WatchPartyPickMode,
    roomMembers: List<RoomMember>,
    memberState: suspend (String) -> ItemMemberState?,
    onPick: (WatchPartyItem) -> Unit,
    onOpenSeries: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: WatchPartyPickerViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val shelves by rememberPickerHomeShelves()
    var choice by remember { mutableStateOf<WatchPartyChoice?>(null) }
    LaunchedEffect(Unit) { viewModel.loadRows() }

    val entries = remember(state.rows) {
        state.rows?.let { it.continueTogether + it.watchlistUnion }.orEmpty().associateBy { it.item.contentId }
    }

    fun openSeriesPage(seriesId: String) {
        onDismiss()
        onOpenSeries(seriesId)
    }

    fun open(item: SectionItem) {
        when (item.type) {
            "episode" -> choice = WatchPartyChoice.episode(item)
            "series" -> {
                val nextUp = entries[item.contentId]?.nextUp
                if (nextUp != null) choice = WatchPartyChoice.nextUp(item, nextUp) else openSeriesPage(item.contentId)
            }
            else -> choice = WatchPartyChoice.title(item)
        }
    }

    Dialog(
        // Back on the confirmation page returns to the shelves.
        onDismissRequest = { if (choice != null) choice = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Cards here have no detail hero to morph into.
        CompositionLocalProvider(LocalSharedTransitionScope provides null, LocalHeroSourceHandoff provides null) {
            val current = choice
            if (current == null) {
                PickerShelvesPage(
                    mode = mode,
                    state = state,
                    shelves = shelves,
                    roomMembers = roomMembers,
                    onQuery = viewModel::onQuery,
                    onOpen = ::open,
                    onClose = onDismiss,
                )
            } else {
                PickerChoicePage(
                    mode = mode,
                    choice = current,
                    roomMembers = roomMembers,
                    memberState = memberState,
                    onConfirm = {
                        onPick(current.lobbyItem)
                        onDismiss()
                    },
                    onSeeAllEpisodes = current.seriesId?.let { id -> { openSeriesPage(id) } },
                    onBack = { choice = null },
                )
            }
        }
    }
}

// MARK: Data

/** A title on the confirmation page, and how the lobby should show it once chosen. */
private data class WatchPartyChoice(
    val contentId: String,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val posterThumbhash: String?,
    val backdropUrl: String?,
    val backdropThumbhash: String?,
    val overview: String?,
    val facts: List<String>,
    /** The series to browse from here, for an episode. */
    val seriesId: String?,
    val lobbyItem: WatchPartyItem,
) {
    companion object {
        fun title(item: SectionItem) = WatchPartyChoice(
            contentId = item.contentId,
            title = item.title,
            subtitle = null,
            posterUrl = item.posterUrl,
            posterThumbhash = item.posterThumbhash,
            backdropUrl = item.backdropUrl,
            backdropThumbhash = item.backdropThumbhash,
            overview = item.overview,
            facts = listOfNotNull(
                item.year.takeIf { it > 0 }?.toString(),
                watchPartyRuntime(item.runtime),
                item.contentRating?.takeIf { it.isNotBlank() },
                item.genres.firstOrNull(),
            ),
            seriesId = null,
            lobbyItem = WatchPartyItem(
                contentId = item.contentId,
                contentType = item.type,
                title = item.title,
                subtitle = item.year.takeIf { it > 0 }?.toString(),
                posterUrl = item.posterUrl,
            ),
        )

        /** A resume-row episode: series title on top, the episode underneath. */
        fun episode(item: SectionItem): WatchPartyChoice {
            val series = item.seriesTitle?.takeIf { it.isNotBlank() }
            val season = item.seasonNumber
            val number = item.episodeNumber
            val code = if (season != null && number != null) "S$season · E$number" else null
            return WatchPartyChoice(
                contentId = item.contentId,
                title = series ?: item.title,
                subtitle = listOfNotNull(code, item.title.takeIf { series != null }).joinToString(" · ").ifBlank { null },
                posterUrl = item.posterUrl,
                posterThumbhash = item.posterThumbhash,
                backdropUrl = item.backdropUrl,
                backdropThumbhash = item.backdropThumbhash,
                overview = item.overview,
                facts = listOfNotNull(watchPartyRuntime(item.runtime)),
                seriesId = item.seriesId?.takeIf { it.isNotBlank() },
                lobbyItem = WatchPartyItem(
                    contentId = item.contentId,
                    contentType = "episode",
                    title = item.title,
                    subtitle = if (series != null && season != null && number != null) {
                        "$series · S$season:E$number"
                    } else {
                        series
                    },
                    posterUrl = item.posterUrl,
                ),
            )
        }

        /** A series whose group next-up episode the server named. */
        fun nextUp(series: SectionItem, next: PickerNextUp): WatchPartyChoice {
            val episodeTitle = next.title.ifBlank { "Episode ${next.episodeNumber}" }
            return WatchPartyChoice(
                contentId = next.contentId,
                title = series.title,
                subtitle = "S${next.seasonNumber} · E${next.episodeNumber}" +
                    next.title.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                posterUrl = series.posterUrl,
                posterThumbhash = series.posterThumbhash,
                backdropUrl = series.backdropUrl,
                backdropThumbhash = series.backdropThumbhash,
                overview = null,
                facts = emptyList(),
                seriesId = series.contentId,
                lobbyItem = WatchPartyItem(
                    contentId = next.contentId,
                    contentType = "episode",
                    title = episodeTitle,
                    subtitle = "${series.title} · S${next.seasonNumber}:E${next.episodeNumber}",
                    posterUrl = series.posterUrl,
                ),
            )
        }
    }
}

private fun BrowseItem.toSectionItem() = SectionItem(
    contentId = contentId,
    type = type,
    title = title,
    year = year,
    genres = genres,
    contentRating = contentRating,
    runtime = runtime,
    overview = overview,
    posterUrl = posterUrl,
    posterThumbhash = posterThumbhash,
    backdropUrl = backdropUrl,
    backdropThumbhash = backdropThumbhash,
    userState = userState,
    overlaySummary = overlaySummary,
)

private val playableTypes = setOf("movie", "series", "episode")

/** Home's rows for the shelf, loaded once per open. */
private data class PickerHomeShelves(
    val loading: Boolean = true,
    val resume: List<SectionItem> = emptyList(),
    val discovery: List<Pair<String, List<SectionItem>>> = emptyList(),
    val recentMovies: List<SectionItem> = emptyList(),
    val recentSeries: List<SectionItem> = emptyList(),
)

/**
 * Home rows the shelf skips, as on Apple and the web picker: personal rows are
 * covered by the group rows or would leak one member's history, and
 * per-library recently-added rows are replaced by the shelf's own.
 */
private val skippedHomeSectionTypes = setOf(
    "recently_added", "recently_released", "new_to_library", "continue_watching", "in_progress", "next_up",
    "next_in_series", "watchlist", "favorites", "profile_activity_feed", "because_you_watched",
    "recommended_for_you", "similar_users_liked", "taste_match", "forgotten_favorites",
)
private const val MAX_HOME_SECTIONS = 4

@Composable
private fun rememberPickerHomeShelves(): androidx.compose.runtime.State<PickerHomeShelves> {
    val sections: SectionRepository = koinInject()
    val catalog: CatalogRepository = koinInject()
    return produceState(PickerHomeShelves()) {
        suspend fun recent(type: String): List<SectionItem> =
            when (val result = catalog.browse(mediaType = type, sort = "added_at", order = "desc", limit = 24)) {
                is ApiResult.Success -> result.data.items.filter { it.type in playableTypes }.map { it.toSectionItem() }
                else -> emptyList()
            }
        coroutineScope {
            val home = async {
                val owner = sections.captureHomeAuthority() ?: return@async null
                (sections.getHomeSections(owner) as? ApiResult.Success)?.data?.sections
            }
            val movies = async { recent("movie") }
            val series = async { recent("series") }
            val homeSections = home.await().orEmpty()
            value = PickerHomeShelves(
                loading = false,
                resume = homeSections
                    .firstOrNull { it.sectionType == "continue_watching" || it.sectionType == "in_progress" }
                    ?.items?.filter { it.type == "movie" || it.type == "episode" }.orEmpty(),
                discovery = homeSections
                    .filter { it.sectionType.lowercase() !in skippedHomeSectionTypes }
                    .mapNotNull { section ->
                        val items = section.items.filter { it.type == "movie" || it.type == "series" }
                        if (items.isEmpty()) null else section.title to items
                    }
                    .take(MAX_HOME_SECTIONS),
                recentMovies = movies.await(),
                recentSeries = series.await(),
            )
        }
    }
}

private data class PickerShelf(
    val id: String,
    val title: String,
    val items: List<SectionItem>,
    val stills: Boolean = false,
    val captions: Map<String, String> = emptyMap(),
    val progress: Map<String, Float> = emptyMap(),
)

private fun memberNames(entry: PickerEntry): String? =
    entry.members.map { it.displayName.ifBlank { "Someone" } }.takeIf { it.isNotEmpty() }?.let(::formatWatchPartyNames)

// MARK: Shelves page

@Composable
private fun PickerShelvesPage(
    mode: WatchPartyPickMode,
    state: WatchPartyPickerViewModel.PickerState,
    shelves: PickerHomeShelves,
    roomMembers: List<RoomMember>,
    onQuery: (String) -> Unit,
    onOpen: (SectionItem) -> Unit,
    onClose: () -> Unit,
) {
    val rows = state.rows
    val list = buildList {
        rows?.continueTogether?.takeIf { it.isNotEmpty() }?.let { entries ->
            add(
                PickerShelf(
                    id = "together",
                    title = "Continue Together",
                    items = entries.map { it.item.toSectionItem() },
                    captions = entries.mapNotNull { entry ->
                        val caption = entry.nextUp?.let { "Next: S${it.seasonNumber} · E${it.episodeNumber}" } ?: memberNames(entry)
                        caption?.let { entry.item.contentId to it }
                    }.toMap(),
                    progress = entries.mapNotNull { entry ->
                        val position = entry.members.mapNotNull { it.positionSeconds }.maxOrNull()
                        val duration = entry.members.mapNotNull { it.durationSeconds }.maxOrNull()
                        if (position != null && duration != null && duration > 0) {
                            entry.item.contentId to (position / duration).toFloat().coerceIn(0f, 1f)
                        } else {
                            null
                        }
                    }.toMap(),
                ),
            )
        }
        if (shelves.resume.isNotEmpty()) add(PickerShelf("resume", "Continue Watching", shelves.resume, stills = true))
        rows?.watchlistUnion?.takeIf { it.isNotEmpty() }?.let { entries ->
            add(
                PickerShelf(
                    id = "watchlist",
                    title = "On Your Watchlists",
                    items = entries.map { it.item.toSectionItem() },
                    captions = entries.mapNotNull { entry -> memberNames(entry)?.let { entry.item.contentId to it } }.toMap(),
                ),
            )
        }
        shelves.discovery.forEachIndexed { index, (title, items) -> add(PickerShelf("home-$index", title, items)) }
        if (shelves.recentMovies.isNotEmpty()) add(PickerShelf("movies", "Recently Added Movies", shelves.recentMovies))
        if (shelves.recentSeries.isNotEmpty()) add(PickerShelf("series", "Recently Added Series", shelves.recentSeries))
    }
    val searching = state.query.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(SiloPageBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)) {
            WatchPartyCircleButton(
                icon = Icons.Filled.Close,
                contentDescription = "Close",
                onClick = onClose,
                size = 44.dp,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        Text(
            mode.title,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = SiloOnSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        PickerSearchField(
            query = state.query,
            onQuery = onQuery,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        state.error?.let {
            WatchPartyBanner(it, tone = WatchPartyBannerTone.Warning, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        when {
            searching -> PickerSearchResults(state, onOpen)
            list.isEmpty() && (shelves.loading || state.rowsLoading) -> PickerProgress()
            list.isEmpty() -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 80.dp, start = 32.dp, end = 32.dp),
            ) {
                Text("Nothing to pick from yet", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = SiloOnSurface)
                Text(
                    "Search for a title, or add something to a watchlist.",
                    fontSize = WatchPartyMetrics.BODY.sp,
                    color = SiloSecondaryText,
                )
            }
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            ) {
                items(list, key = { it.id }) { shelf -> PickerShelfRow(shelf, onOpen) }
            }
        }
    }
}

@Composable
private fun PickerSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val style = TextStyle(fontSize = 16.sp, color = SiloOnSurface)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(SiloOnSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(WatchPartyColors.chromeFill)
            .border(1.dp, WatchPartyColors.chromeBorder, shape),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = SiloSecondaryText, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Search movies and series", style = style.copy(color = SiloSecondaryText), maxLines = 1)
                    }
                    inner()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = SiloSecondaryText, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun PickerShelfRow(shelf: PickerShelf, onOpen: (SectionItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Home's row heading: 20sp, with the resume row's play glyph.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            if (shelf.stills) {
                Icon(Icons.Filled.PlayCircle, contentDescription = null, tint = SiloOnSurface, modifier = Modifier.size(20.dp))
            }
            Text(shelf.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = SiloOnSurface)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.items.distinctBy { it.contentId }, key = { it.contentId }) { item ->
                if (shelf.stills) {
                    val position = item.positionSeconds
                    val duration = item.durationSeconds
                    BackdropCard(
                        title = item.title,
                        backdropUrl = item.backdropUrl ?: item.posterUrl,
                        backdropThumbhash = item.backdropThumbhash ?: item.posterThumbhash,
                        seriesTitle = item.seriesTitle,
                        seasonNumber = item.seasonNumber,
                        episodeNumber = item.episodeNumber,
                        progress = if (position != null && duration != null && duration > 0) {
                            (position / duration).toFloat().coerceIn(0f, 1f)
                        } else {
                            null
                        },
                        userState = item.userState,
                        onClick = { onOpen(item) },
                    )
                } else {
                    MediaCard(
                        title = item.title,
                        posterUrl = item.posterUrl,
                        posterThumbhash = item.posterThumbhash,
                        year = item.year,
                        subtitle = shelf.captions[item.contentId],
                        type = item.type,
                        userState = item.userState,
                        progress = shelf.progress[item.contentId],
                        onClick = { onOpen(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerSearchResults(state: WatchPartyPickerViewModel.PickerState, onOpen: (SectionItem) -> Unit) {
    when {
        state.results.isNotEmpty() -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            items(state.results, key = { it.contentId }) { result ->
                val item = result.toSectionItem()
                MediaCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    posterThumbhash = item.posterThumbhash,
                    year = item.year,
                    type = item.type,
                    userState = item.userState,
                    onClick = { onOpen(item) },
                    modifier = Modifier.fillMaxWidth(),
                    width = androidx.compose.ui.unit.Dp.Unspecified,
                )
            }
        }
        state.searching || state.query.trim().length < 2 -> PickerProgress()
        else -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        ) {
            Text("No results", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = SiloOnSurface)
            Text("Try a different search term.", fontSize = WatchPartyMetrics.BODY.sp, color = SiloSecondaryText)
        }
    }
}

@Composable
private fun PickerProgress() {
    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.TopCenter) {
        CircularProgressIndicator(color = SiloSecondaryText, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

// MARK: Confirmation page

@Composable
private fun PickerChoicePage(
    mode: WatchPartyPickMode,
    choice: WatchPartyChoice,
    roomMembers: List<RoomMember>,
    memberState: suspend (String) -> ItemMemberState?,
    onConfirm: () -> Unit,
    onSeeAllEpisodes: (() -> Unit)?,
    onBack: () -> Unit,
) {
    var loadingHistory by remember(choice.contentId) { mutableStateOf(true) }
    var history by remember(choice.contentId) { mutableStateOf<ItemMemberState?>(null) }
    LaunchedEffect(choice.contentId) {
        history = memberState(choice.contentId)
        loadingHistory = false
    }
    Box(Modifier.fillMaxSize()) {
        WatchPartyBackdrop(
            url = choice.backdropUrl ?: choice.posterUrl,
            thumbhash = if (choice.backdropUrl != null) choice.backdropThumbhash else choice.posterThumbhash,
            isPoster = choice.backdropUrl == null,
        )
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)) {
                WatchPartyCircleButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    size = 44.dp,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy((WatchPartyMetrics.BODY * 1.4f).dp),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = WatchPartyMetrics.pageInset)
                    // Let the backdrop show above the poster, as in the lobby.
                    .padding(top = 150.dp, bottom = WatchPartyMetrics.pageInset),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(WatchPartyMetrics.BODY.dp)) {
                    WatchPartyHeroPoster(choice.posterUrl, choice.posterThumbhash, 120.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        WatchPartyEyebrow(mode.eyebrow)
                        WatchPartyHeroTitle(choice.title, size = WatchPartyMetrics.HERO_TITLE * 0.9f)
                        val facts = listOfNotNull(choice.subtitle) + choice.facts
                        if (facts.isNotEmpty()) {
                            Text(facts.joinToString(" · "), fontSize = WatchPartyMetrics.BODY.sp, color = SiloSecondaryText)
                        }
                    }
                }
                choice.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = WatchPartyMetrics.BODY.sp, color = SiloSecondaryText, maxLines = 6)
                }
                WatchPartyButton(
                    text = mode.confirmLabel,
                    icon = if (mode == WatchPartyPickMode.Suggest) Icons.Filled.Add else Icons.Filled.Check,
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onSeeAllEpisodes != null) {
                    WatchPartyButton(
                        text = "See all episodes",
                        icon = Icons.AutoMirrored.Filled.List,
                        kind = WatchPartyButtonKind.Secondary,
                        onClick = onSeeAllEpisodes,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Hidden when the server has no member-state read.
                if (loadingHistory || history != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        WatchPartyEyebrow("Who's seen it")
                        if (loadingHistory) {
                            Text("Checking viewing history…", fontSize = WatchPartyMetrics.CAPTION.sp, color = SiloSecondaryText)
                        } else {
                            val states = history?.members.orEmpty()
                            roomMembers.forEach { member ->
                                val watch = states.firstOrNull { it.userId == member.userId && it.profileId == member.profileId }
                                Row {
                                    Text(
                                        watchPartyMemberName(member),
                                        fontSize = WatchPartyMetrics.BODY.sp,
                                        color = SiloOnSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(watchHistoryLabel(watch), fontSize = WatchPartyMetrics.BODY.sp, color = SiloSecondaryText)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun watchHistoryLabel(state: MemberWatchState?): String {
    state ?: return "No viewing history"
    val label = when (state.state) {
        "watched", "completed", "played" -> "Watched"
        "in_progress", "inprogress" -> "In progress"
        else -> "Not watched"
    }
    return if (state.onWatchlist) "$label · Watchlist" else label
}
