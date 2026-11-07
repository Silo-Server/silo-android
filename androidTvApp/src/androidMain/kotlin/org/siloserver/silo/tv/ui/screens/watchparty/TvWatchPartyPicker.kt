package org.siloserver.silo.tv.ui.screens.watchparty

import org.siloserver.silo.common.i18n.isRightToLeftText
import org.siloserver.silo.common.i18n.text
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.ui.components.DefaultArtworkKind
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.watchtogether.ItemMemberState
import org.siloserver.silo.model.watchtogether.MemberWatchState
import org.siloserver.silo.model.watchtogether.PickerEntry
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.tv.ui.components.TvMediaRow
import org.siloserver.silo.tv.ui.components.TvPoster
import org.siloserver.silo.tv.ui.components.TvTextInputDialog
import org.siloserver.silo.tv.ui.components.bidiIsolated
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import org.siloserver.silo.tv.ui.focus.TvRestoreFocusOnModalDismiss
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.tvModalFocusBoundary
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.SiloSecondaryText
import org.siloserver.silo.tv.ui.theme.TvSkyline
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.viewmodel.WatchPartyPickerViewModel
import org.siloserver.silo.viewmodel.WatchPartySpoilers
import org.siloserver.silo.common.cards.LocalEpisodeSpoilerPrefs
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.watchparty.watchPartyDisplayName
import org.siloserver.silo.common.watchparty.watchPartyEpisodeLine
import org.siloserver.silo.common.watchparty.watchPartyHistoryLabel
import org.siloserver.silo.common.watchparty.watchPartyRuntimeText
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.currentNumberLocale

/** One Home-style shelf of the picker. */
private data class TvPickerShelf(
    val key: String,
    val title: String,
    val entries: List<Pair<BrowseItem, PickerEntry?>>,
    val showProgress: Boolean = false,
) {
    val items: List<SectionItem> = entries.map { (item, entry) -> item.toSectionItem(entry) }

    /** The items carry the room's progress; spoiler protection uses this profile's own. */
    val unwatchedById: Map<String, Boolean> =
        entries.associate { (item, _) -> item.contentId to WatchPartySpoilers.of(item).unwatchedEpisode }
}

/**
 * What the confirmation page shows, and the item it stages or suggests. The
 * page's episode line and facts are kept as parts and worded where they are
 * shown; [item] is sent to the server and stays English.
 */
private data class TvPickerChoice(
    val item: WatchPartyItem,
    val title: String,
    /** "S1 · E2", then [episodeTitle]; null for a movie. */
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val posterUrl: String?,
    val posterThumbhash: String?,
    val backdropUrl: String?,
    val backdropThumbhash: String?,
    val overview: String?,
    val year: String? = null,
    val runtimeMinutes: Int? = null,
    /** Rating and genre, after the runtime. */
    val otherFacts: List<String> = emptyList(),
    val spoilers: WatchPartySpoilers = item.spoilers,
)

/**
 * Choose or suggest a title, as the tvOS picker does it: a full-screen page of
 * Home-style shelves (Continue Together, On Your Watchlists, or search
 * results) under a slim strip with the page name, Search, and Close. Picking a
 * movie or episode opens a confirmation page (artwork, overview, "Who's seen
 * it") whose primary action stages or suggests it. A series with a party
 * next-up episode confirms that episode; any other series opens its normal
 * page, where the party action applies.
 *
 * Focus: the first card of the first shelf, else Search. If the focused card
 * disappears (a newer search), focus returns to Search instead of dropping.
 */
@Composable
internal fun TvWatchPartyPicker(
    /** Host Picks host: the pick is staged. Otherwise it is suggested. */
    stages: Boolean,
    members: List<RoomMember>,
    /** Who in the room has watched a title; null when the server can't say. */
    memberState: (suspend (String) -> ItemMemberState?)?,
    onPick: (WatchPartyItem) -> Unit,
    onOpenSeries: (contentId: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: WatchPartyPickerViewModel = koinViewModel(key = "tv-watch-party-picker"),
) {
    val state by viewModel.state.collectAsState()
    var searchOpen by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<TvPickerChoice?>(null) }
    var choiceShelf by remember { mutableStateOf<String?>(null) }
    var searchFocused by remember { mutableStateOf(false) }
    var pickerHasFocus by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val firstCardFocus = remember { FocusRequester() }
    val shelfFocus = remember { mutableStateMapOf<String, Boolean>() }
    val shelfRequesters = remember { mutableMapOf<String, FocusRequester>() }

    LaunchedEffect(viewModel) { viewModel.loadRows() }

    val searching = state.query.isNotBlank()
    val resultsTitle = stringResource(CommonR.string.watchParty_picker_resultsFor, state.query.trim())
    val togetherTitle = stringResource(CommonR.string.watchParty_picker_continueTogether)
    val watchlistTitle = stringResource(CommonR.string.watchParty_picker_watchlists)
    val shelves: List<TvPickerShelf> = if (searching) {
        listOf(TvPickerShelf("results", resultsTitle, state.results.map { it to null }))
    } else {
        listOf(
            TvPickerShelf(
                "together",
                togetherTitle,
                state.rows?.continueTogether.orEmpty().map { it.item to it },
                showProgress = true,
            ),
            TvPickerShelf("watchlist", watchlistTitle, state.rows?.watchlistUnion.orEmpty().map { it.item to it }),
        )
    }.filter { it.entries.isNotEmpty() }
    val shelfKeys = shelves.joinToString { it.key }

    val open: (TvPickerShelf, String) -> Unit = { shelf, contentId ->
        val (item, entry) = shelf.entries.first { it.first.contentId == contentId }
        val nextUp = entry?.nextUp
        when {
            item.type == "series" && nextUp != null -> {
                choiceShelf = shelf.key
                choice = TvPickerChoice(
                    // Sent to the server as the suggestion's title and subtitle: stays English.
                    item = WatchPartyItem(
                        contentId = nextUp.contentId,
                        contentType = "episode",
                        title = nextUp.title.ifBlank { "Episode ${nextUp.episodeNumber}" },
                        subtitle = "${item.title} · S${nextUp.seasonNumber}E${nextUp.episodeNumber}",
                        posterUrl = item.posterUrl,
                        // Series artwork; the episode's own watch state isn't known here.
                        spoilers = WatchPartySpoilers(unwatchedEpisode = true, posterIsEpisodeStill = false, backdropIsEpisodeStill = false),
                    ),
                    title = item.title,
                    season = nextUp.seasonNumber,
                    episode = nextUp.episodeNumber,
                    episodeTitle = nextUp.title.takeIf { it.isNotBlank() },
                    posterUrl = item.posterUrl,
                    posterThumbhash = item.posterThumbhash,
                    backdropUrl = item.backdropUrl,
                    backdropThumbhash = item.backdropThumbhash,
                    overview = null,
                )
            }
            item.type == "series" -> onOpenSeries(item.contentId)
            else -> {
                choiceShelf = shelf.key
                choice = TvPickerChoice(
                    item = WatchPartyItem(
                        contentId = item.contentId,
                        contentType = item.type,
                        title = item.title,
                        subtitle = item.year.takeIf { it > 0 }?.toString(),
                        posterUrl = item.posterUrl,
                        spoilers = WatchPartySpoilers.of(item),
                    ),
                    title = item.title,
                    posterUrl = item.posterUrl,
                    posterThumbhash = item.posterThumbhash,
                    backdropUrl = item.backdropUrl,
                    backdropThumbhash = item.backdropThumbhash,
                    overview = item.overview?.takeIf { it.isNotBlank() },
                    year = item.year.takeIf { it > 0 }?.toString(),
                    runtimeMinutes = item.runtime?.takeIf { it > 0 },
                    otherFacts = listOfNotNull(
                        item.contentRating?.takeIf { it.isNotBlank() },
                        item.genres.firstOrNull(),
                    ),
                )
            }
        }
    }

    // A card that vanished under focus (a newer search, reloaded rows) would
    // otherwise leave the picker with no focus owner.
    LaunchedEffect(pickerHasFocus, searchOpen, choice != null) {
        if (pickerHasFocus || searchOpen || choice != null) return@LaunchedEffect
        delay(PICKER_FOCUS_RESCUE_DELAY_MS)
        requestFocusUntilObserved(
            maxAttempts = 20,
            awaitAttempt = { delay(60) },
            requestFocus = searchFocus::requestFocus,
            isFocused = { pickerHasFocus },
        )
    }
    // Shelves arrive after the page opens: the first card claims focus from
    // Search, as Home's first row does, unless the viewer already moved on.
    LaunchedEffect(shelfKeys) {
        if (shelves.isEmpty() || !searchFocused || searching) return@LaunchedEffect
        requestFocusUntilObserved(
            maxAttempts = 10,
            awaitAttempt = { delay(60) },
            requestFocus = firstCardFocus::requestFocus,
            isFocused = { shelfFocus[shelves.first().key] == true },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .onFocusChanged { pickerHasFocus = it.hasFocus }
                .tvModalFocusBoundary()
                .then(rememberTvDialogInitialFocus(if (shelves.isEmpty()) searchFocus else firstCardFocus)),
        ) {
            // ---- Strip: page name, search, close ----------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = TvSkyline.safeAreaX, end = TvSkyline.safeAreaX, top = TvSkyline.barTopInset)
                    .height(TvSkyline.barHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TvSkyline.barTrailingSpacing),
            ) {
                Text(
                    text = stringResource(if (stages) CommonR.string.watchParty_picker_select else CommonR.string.watchParty_picker_suggest),
                    fontSize = TvSkyline.tabLabelSize,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (searching) {
                    TvPartyButton(
                        label = stringResource(CommonR.string.watchParty_picker_clearSearch),
                        kind = TvPartyButtonKind.Secondary,
                        onClick = { viewModel.onQuery("") },
                        height = TvSkyline.barIconSize,
                        fontSize = 14.sp,
                    )
                }
                ChromeIcon(
                    icon = Icons.Filled.Search,
                    label = stringResource(CommonR.string.watchParty_picker_search),
                    onClick = { searchOpen = true },
                    modifier = Modifier
                        .focusRequester(searchFocus)
                        .onFocusChanged { searchFocused = it.isFocused },
                )
                ChromeIcon(icon = Icons.Filled.Close, label = stringResource(CommonR.string.watchParty_picker_close), onClick = onDismiss)
            }

            // ---- Shelves -----------------------------------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp, bottom = 24.dp),
            ) {
                val status = when {
                    searching && state.error != null && shelves.isEmpty() -> state.error?.text()
                    searching && state.searching && shelves.isEmpty() -> stringResource(CommonR.string.search_searching)
                    searching && shelves.isEmpty() -> stringResource(CommonR.string.watchParty_picker_noMatch, state.query.trim())
                    !searching && state.rowsLoading && shelves.isEmpty() -> stringResource(CommonR.string.status_loading)
                    else -> null
                }
                if (status != null) {
                    PickerStatus(status)
                } else if (!searching && shelves.isEmpty()) {
                    Column(
                        modifier = Modifier.padding(horizontal = TvSkyline.safeAreaX, vertical = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(text = stringResource(CommonR.string.watchParty_picker_emptyTitle), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = SiloOnSurface)
                        Text(
                            text = stringResource(CommonR.string.watchParty_picker_emptyBody),
                            fontSize = TvPartyMetrics.body,
                            color = SiloSecondaryText,
                        )
                        state.error?.let { message ->
                            TvPartyBanner(message = message.text(), warning = true)
                            TvPartyButton(
                                label = stringResource(CommonR.string.common_tryAgain),
                                kind = TvPartyButtonKind.Secondary,
                                onClick = viewModel::loadRows,
                            )
                        }
                    }
                }
                shelves.forEachIndexed { index, shelf ->
                    TvMediaRow(
                        title = shelf.title,
                        items = shelf.items,
                        onItemClick = { contentId -> open(shelf, contentId) },
                        showProgress = shelf.showProgress,
                        unwatchedById = shelf.unwatchedById,
                        startPadding = TvSkyline.safeAreaX,
                        endPadding = TvSkyline.safeAreaX,
                        rowTopPadding = 10.dp,
                        rowBottomPadding = 16.dp,
                        firstItemFocusRequester = if (index == 0) firstCardFocus else null,
                        rowContainerFocusRequester = shelfRequesters.getOrPut(shelf.key) { FocusRequester() },
                        onRowFocusChanged = { shelfFocus[shelf.key] = it },
                    )
                }
            }
        }
    }

    if (searchOpen) {
        TvTextInputDialog(
            title = stringResource(CommonR.string.watchParty_picker_searchTitle),
            label = stringResource(CommonR.string.watchParty_picker_searchLabel),
            confirmLabel = stringResource(CommonR.string.watchParty_picker_search),
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

    choice?.let { current ->
        TvPickerConfirmation(
            choice = current,
            stages = stages,
            members = members,
            memberState = memberState,
            onConfirm = {
                choice = null
                onPick(current.item)
            },
            onDismiss = { choice = null },
        )
    }
    // Back from the confirmation returns to the shelf it came from; the row's
    // focus restorer puts focus back on the card.
    TvRestoreFocusOnModalDismiss(
        visible = choice != null,
        opener = choiceShelf?.let { shelfRequesters[it] },
        isOpenerFocused = { choiceShelf?.let { shelfFocus[it] } == true },
    )
}

/**
 * The confirmation page: the title's artwork and facts, what everyone in the
 * room has seen, and one action. Back returns to the shelves.
 */
@Composable
private fun TvPickerConfirmation(
    choice: TvPickerChoice,
    stages: Boolean,
    members: List<RoomMember>,
    memberState: (suspend (String) -> ItemMemberState?)?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirmFocus = remember { FocusRequester() }
    val spoilerPrefs = LocalEpisodeSpoilerPrefs.current
    val contentId = choice.item.contentId
    val seen by produceState<MemberStateLoad>(initialValue = MemberStateLoad.Loading, contentId) {
        value = MemberStateLoad.Loading
        value = memberState?.invoke(contentId)?.let(MemberStateLoad::Loaded) ?: MemberStateLoad.Unavailable
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
        Box(modifier = Modifier.fillMaxSize()) {
            TvPartyBackdrop(
                url = choice.backdropUrl ?: choice.posterUrl,
                thumbhash = if (choice.backdropUrl != null) choice.backdropThumbhash else choice.posterThumbhash,
                isPoster = choice.backdropUrl == null,
                hidden = choice.spoilers.hidesBackdrop(spoilerPrefs, choice.backdropUrl),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = TvPartyMetrics.pageInsetX, vertical = 44.dp)
                    .tvModalFocusBoundary()
                    .then(rememberTvDialogInitialFocus(confirmFocus)),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvPoster(
                        imageUrl = choice.posterUrl,
                        contentDescription = null,
                        modifier = Modifier.size(width = 110.dp, height = 165.dp),
                        defaultArtwork = DefaultArtworkKind.forItemType(choice.item.contentType),
                        hidden = choice.spoilers.hidesPoster(spoilerPrefs),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TvPartyEyebrow(
                            stringResource(if (stages) CommonR.string.watchParty_picker_watchTogether else CommonR.string.watchParty_picker_suggestToParty),
                        )
                        Text(
                            text = choice.title,
                            fontSize = 44.sp,
                            lineHeight = 48.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            color = SiloOnSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val code = choice.season?.let { season -> choice.episode?.let { watchPartyEpisodeLine(season, it) } }
                        val rtl = isRightToLeftText()
                        val episodeTitle = choice.episodeTitle?.let { if (code != null) bidiIsolated(it, rtl) else it }
                        val subtitle = listOfNotNull(code, episodeTitle).joinToString(" · ").ifBlank { null }
                        val facts = listOfNotNull(subtitle, choice.year?.toIntOrNull()?.let { LocaleFormat.integer(it, currentNumberLocale()) }, choice.runtimeMinutes?.let { watchPartyRuntimeText(it) }) +
                            choice.otherFacts
                        if (facts.isNotEmpty()) {
                            Text(text = facts.joinToString(" · "), fontSize = TvPartyMetrics.body, color = SiloSecondaryText)
                        }
                    }
                }
                choice.overview?.takeUnless { choice.spoilers.hidesOverview(spoilerPrefs) }?.let { overview ->
                    Text(
                        text = overview,
                        fontSize = TvPartyMetrics.body,
                        lineHeight = 21.sp,
                        color = SiloSecondaryText,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 560.dp),
                    )
                }
                TvPartyButton(
                    label = stringResource(if (stages) CommonR.string.watchParty_confirm_choose else CommonR.string.watchParty_confirm_addSuggestion),
                    icon = if (stages) Icons.Filled.Check else Icons.Filled.Add,
                    onClick = onConfirm,
                    modifier = Modifier.focusRequester(confirmFocus),
                )
                when (val load = seen) {
                    MemberStateLoad.Unavailable -> Unit
                    MemberStateLoad.Loading -> if (memberState != null) {
                        WhoSeenSection {
                            Text(text = stringResource(CommonR.string.watchParty_whoSeen_checking), fontSize = TvPartyMetrics.caption, color = SiloSecondaryText)
                        }
                    }
                    is MemberStateLoad.Loaded -> WhoSeenSection {
                        members.forEach { member ->
                            val watch = load.state.members.firstOrNull {
                                it.userId == member.userId && it.profileId == member.profileId
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = watchPartyDisplayName(member.displayName.trim()),
                                    fontSize = TvPartyMetrics.body,
                                    color = SiloOnSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(text = tvWatchPartyHistoryLabel(watch), fontSize = TvPartyMetrics.body, color = SiloSecondaryText)
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface MemberStateLoad {
    data object Loading : MemberStateLoad
    data object Unavailable : MemberStateLoad
    data class Loaded(val state: ItemMemberState) : MemberStateLoad
}

@Composable
private fun WhoSeenSection(content: @Composable () -> Unit) {
    Column(modifier = Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TvPartyEyebrow(stringResource(CommonR.string.watchParty_whoSeen_title))
        content()
    }
}

/** "Watched", "In progress · Watchlist", "No viewing history". */
@Composable
internal fun tvWatchPartyHistoryLabel(state: MemberWatchState?): String = watchPartyHistoryLabel(
    watched = state?.let { it.state in setOf("watched", "completed", "played") },
    inProgress = state != null &&
        (state.state in setOf("in_progress", "inprogress") || (state.positionSeconds ?: 0.0) > 0.0),
    onWatchlist = state?.onWatchlist == true,
)

/** Capsule icon in the strip, the same geometry as the app's top bar. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChromeIcon(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(100.dp)
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = FocusedContainer,
            pressedContainerColor = FocusedContainer,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        modifier = modifier.size(TvSkyline.barIconSize),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isFocused) FocusedContent else Color.White.copy(alpha = 0.62f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun PickerStatus(text: String) {
    Text(
        text = text,
        fontSize = TvPartyMetrics.body,
        color = SiloSecondaryText,
        modifier = Modifier.padding(horizontal = TvSkyline.safeAreaX, vertical = 24.dp),
    )
}

private fun BrowseItem.toSectionItem(entry: PickerEntry?): SectionItem = SectionItem(
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
    posterIsEpisodeStill = posterIsEpisodeStill,
    backdropIsEpisodeStill = backdropIsEpisodeStill,
    userState = userState,
    overlaySummary = overlaySummary,
    positionSeconds = entry?.members?.mapNotNull { it.positionSeconds }?.maxOrNull(),
    durationSeconds = entry?.members?.mapNotNull { it.durationSeconds }?.maxOrNull(),
)

private const val PICKER_FOCUS_RESCUE_DELAY_MS = 150L
