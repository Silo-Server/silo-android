package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.tv.ui.shell.TvLibraryPill
import org.siloserver.silo.tv.ui.shell.TvLibraryTabType
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.DarkBackground
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal val CascadeLibraryColumnWidth = 230.dp
internal val CascadeFlyoutWidth = 150.dp
internal val CascadeFlyoutGap = 9.dp
internal val CascadePanelPadding = 7.dp
internal val CascadeFlyoutPadding = 5.dp
internal val CascadeFlyoutCornerRadius = 9.dp
internal val CascadeMaxListHeight = 180.dp
internal val TvCascadeSelectorMaxPanelWidth = 389.dp
internal val CascadePanelHeaderSize = 14.sp
internal val CascadeFlyoutHeaderSize = 14.sp
internal val CascadeFooterTextSize = 14.sp
internal val CascadeRowTextSize = 14.sp
internal val CascadeRowIconSize = 15.dp
internal val CascadeRowPaddingHorizontal = 9.dp
internal val CascadeRowPaddingVertical = 8.dp
internal val CascadeRowCornerRadius = 7.dp

internal val CascadeFlyoutRowTextSize = 14.sp
internal val CascadeFlyoutRowIconSize = 9.dp
internal val CascadeFlyoutRowPaddingHorizontal = 8.dp
internal val CascadeFlyoutRowPaddingVertical = 6.5.dp

/** Resolves per-row state by stable identity while preserving the current display order. */
internal fun <K, V> stableIdentityValues(
    ids: List<K>,
    valuesById: MutableMap<K, V>,
    create: () -> V,
): Map<K, V> = buildMap {
    ids.forEach { id ->
        put(id, valuesById.getOrPut(id, create))
    }
}

internal val CascadeFlyoutRowCornerRadius = 6.dp

private val CascadeRowSpacing = 7.dp
private val CascadeRowTrailingIconSize = 9.5.dp
private val CascadeFlyoutRowSpacing = 6.dp
private val CascadePanelHeaderTracking = 1.5.sp
private val CascadeFlyoutHeaderTracking = 1.45.sp
private val CascadeFooterTracking = 0.45.sp
private val CascadeFooterLineHeight = 18.sp
private const val CascadeFlyoutFollowDelayMillis = 80L

/**
 * Personal-library selector using the same panel, rows, and focus behavior as
 * the library cascades. It can be shown as a dwell preview while focus remains
 * on the top bar; [entersPanel] only becomes true after Down explicitly hands
 * focus into the selector.
 */
@Composable
fun TvForYouSelector(
    entersPanel: Boolean,
    focusEntryToken: Int,
    onWatchlist: () -> Unit,
    onFavorites: () -> Unit,
    onRecommendations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val watchlistFocus = remember { FocusRequester() }
    val favoritesFocus = remember { FocusRequester() }
    val recommendationsFocus = remember { FocusRequester() }

    LaunchedEffect(entersPanel, focusEntryToken) {
        if (entersPanel && focusEntryToken > 0) {
            runCatching { recommendationsFocus.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            .width(CascadeLibraryColumnWidth)
            .tvSkylinePanelChrome()
            .padding(CascadePanelPadding)
            .focusGroup(),
    ) {
        CascadePanelHeader("FOR YOU")
        // Recommendations first: it is the tab's landing content, so entry
        // focus sits on what the viewer is already looking at.
        CascadeActionRow(
            title = "Recommendations",
            icon = Icons.Filled.AutoAwesome,
            entersPanel = entersPanel,
            focusRequester = recommendationsFocus,
            onSelect = onRecommendations,
        )
        CascadeActionRow(
            title = "Favorites",
            icon = Icons.Filled.Favorite,
            entersPanel = entersPanel,
            focusRequester = favoritesFocus,
            onSelect = onFavorites,
        )
        CascadeActionRow(
            title = "Watchlist",
            icon = Icons.Filled.Bookmark,
            entersPanel = entersPanel,
            focusRequester = watchlistFocus,
            onSelect = onWatchlist,
        )
        CascadePanelFooter(isSingleLibrary = true)
    }
}

/**
 * The Skyline cascading library selector — a faithful Compose-for-TV port of
 * tvOS `TVCascadeSelector` (§5.3). One component, two levels:
 *
 * - **Level 1 — libraries.** A row per real library of [type]. The current
 *   scope shows a `✓`; the others a `›` chevron. A single-library tab
 *   collapses this away and shows the sections directly.
 * - **Level 2 — sections flyout.** The pill set ([TvLibraryPill.set]) anchored
 *   to and vertically aligned with the focused/anchor library row. It follows
 *   focus up/down the list after a ~150 ms rest debounce.
 *
 * The component owns no scope state; every outcome is a callback so persistence
 * and the page swap stay with the host. Like tvOS, each level owns its own
 * glass panel chrome so the library column and flyout read as separate surfaces.
 *
 * Focus contract (matches tvOS §5.3/§7):
 * - On entry ([focusEntryToken] bump while [entersPanel]) focus lands on the
 *   current-scope library row (or the first). A single-library tab focuses the
 *   first section instead.
 * - **Right** on a library row enters the flyout; **Left** in the flyout
 *   returns to the anchored library row.
 * - **Select/Enter** on a library row commits that scope ([onCommitLibrary]);
 *   on a section row commits scope + section ([onCommitSection]).
 */
@Composable
fun TvCascadeSelector(
    type: TvLibraryTabType,
    libraries: List<UserLibrary>,
    currentScopeId: Int?,
    selectedPill: TvLibraryPill,
    entersPanel: Boolean,
    focusEntryToken: Int,
    onCommitLibrary: (UserLibrary) -> Unit,
    onCommitSection: (UserLibrary, TvLibraryPill) -> Unit,
    onPanelFocusChanged: (Boolean) -> Unit,
    /** Gates the Collections pill per anchored library (QA 2026-07-08). */
    libraryHasCollections: (Int) -> Boolean = { true },
    modifier: Modifier = Modifier,
) {
    val isSingleLibrary = libraries.size <= 1

    // One stable FocusRequester per library id and per pill, surviving recomposition.
    val libraryRequesters = remember { mutableStateMapOf<Int, FocusRequester>() }
    val pillRequesters = remember { mutableStateMapOf<TvLibraryPill, FocusRequester>() }
    val visibleLibraryRequesters = stableIdentityValues(
        ids = libraries.map(UserLibrary::id),
        valuesById = libraryRequesters,
        create = ::FocusRequester,
    )

    // Each library row's top edge in the level-1 column's coordinate space; the
    // flyout offsets down to the anchored row's value to align tops (§5.3).
    val rowTops = remember { mutableStateMapOf<Int, Float>() }

    // The library row whose flyout is currently shown.
    var anchorId by remember(libraries, currentScopeId) {
        mutableStateOf(currentScopeId ?: libraries.firstOrNull()?.id)
    }

    // Section pills for the anchored library; Collections is offered only
    // when that library actually has collections. Evaluated OUTSIDE remember
    // so the async probe's arrival (librariesWithCollections landing after
    // the cascade composed) recomputes the list — a stale remember kept the
    // pill visible (QA 2026-07-08).
    val anchorHasCollections = anchorId?.let(libraryHasCollections) ?: true
    val pills = remember(type, anchorHasCollections) {
        TvLibraryPill.set(type).filter { pill ->
            pill != TvLibraryPill.Collections || anchorHasCollections
        }
    }
    val visiblePillRequesters = stableIdentityValues(
        ids = pills,
        valuesById = pillRequesters,
        create = ::FocusRequester,
    )

    // Scroll state for the lazy (libraries.size > 6) level-1 list, so focus
    // entry can scroll the current-scope row into composition before focusing.
    val lazyListState = rememberLazyListState()

    // Which library row currently holds focus (drives the debounced follow).
    var focusedRowId by remember { mutableStateOf<Int?>(null) }
    // Whether any pill in the flyout currently holds focus.
    var focusedPill by remember { mutableStateOf<TvLibraryPill?>(null) }

    // The tvOS cascade keeps the level-2 flyout visible from open, anchored to
    // the current library. D-pad Right only moves focus into it.
    var flyoutVisible by remember { mutableStateOf(true) }
    // Bumped when the flyout is revealed via Right so the first pill is focused
    // AFTER it has been composed (and its FocusRequester attached) on the next
    // frame — a synchronous requestFocus in the key handler would target an
    // unattached requester and silently no-op.
    var focusFirstPillToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusFirstPillToken) {
        if (focusFirstPillToken > 0) {
            pills.firstOrNull()?.let { pill ->
                pillRequesters[pill]?.let { runCatching { it.requestFocus() } }
            }
        }
    }

    val anchorLibrary = libraries.firstOrNull { it.id == anchorId } ?: libraries.firstOrNull()

    // Drive onPanelFocusChanged from whether ANY row or pill is focused.
    val anyFocused = focusedRowId != null || focusedPill != null
    LaunchedEffect(anyFocused) { onPanelFocusChanged(anyFocused) }

    // Flyout follows the resting focused row after a short debounce.
    LaunchedEffect(focusedRowId) {
        val id = focusedRowId ?: return@LaunchedEffect
        delay(CascadeFlyoutFollowDelayMillis)
        if (focusedRowId == id) anchorId = id
    }

    // Focus entry: land on current-scope row (or first), or first pill when single.
    LaunchedEffect(focusEntryToken) {
        if (entersPanel && focusEntryToken > 0) {
            if (isSingleLibrary) {
                pills.firstOrNull()?.let { pill ->
                    pillRequesters[pill]?.let { runCatching { it.requestFocus() } }
                }
            } else {
                flyoutVisible = true
                val target = currentScopeId ?: libraries.firstOrNull()?.id
                target?.let { id ->
                    anchorId = id
                    // When the list is lazy (size > 6) the target row may be
                    // offscreen / un-composed, so its FocusRequester is not yet
                    // attached. Scroll it into view first, then request focus.
                    if (libraries.size > 6) {
                        val index = libraries.indexOfFirst { it.id == id }
                        lazyListState.scrollToItem(index.coerceAtLeast(0))
                        withFrameNanos { }
                    }
                    libraryRequesters[id]?.requestFocus()
                }
            }
        }
    }

    // Back/Escape is intentionally NOT handled here. It is centralized at the
    // shell-level Back handler in TvMainShell, which closes any open panel
    // first. Handling it here too would double-consume (close + nav-pop).
    Row(
        modifier = modifier
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(CascadeFlyoutGap),
    ) {
        if (isSingleLibrary && anchorLibrary != null) {
            Column(
                modifier = Modifier
                    .width(CascadeLibraryColumnWidth)
                    .tvSkylinePanelChrome()
                    .padding(CascadePanelPadding),
            ) {
                CascadePanelHeader(anchorLibrary.name.uppercase())
                pills.forEach { pill ->
                    val requester = visiblePillRequesters.getValue(pill)
                    CascadeSectionRow(
                        pill = pill,
                        entersPanel = entersPanel,
                        focusRequester = requester,
                        onFocusChanged = { focused ->
                            focusedPill = if (focused) pill else focusedPill.takeUnless { it == pill }
                        },
                        onMoveLeft = { false },
                        onSelect = {
                            onCommitSection(anchorLibrary, pill)
                            true
                        },
                    )
                }
                CascadePanelFooter(isSingleLibrary = true)
            }
            return@Row
        }

        // LEVEL 1 — library rows (skipped entirely for a single-library tab).
        if (!isSingleLibrary) {
            val rowsContent: @Composable () -> Unit = {
                libraries.forEach { library ->
                    key(library.id) {
                        val requester = visibleLibraryRequesters.getValue(library.id)
                        CascadeLibraryRow(
                            library = library,
                            type = type,
                            isCurrent = library.id == currentScopeId,
                            entersPanel = entersPanel,
                            focusRequester = requester,
                            onFocusChanged = { focused ->
                                focusedRowId = if (focused) {
                                    library.id
                                } else {
                                    focusedRowId.takeUnless { it == library.id }
                                }
                            },
                            onTopChanged = { top -> rowTops[library.id] = top },
                            onMoveRight = {
                                anchorId = library.id
                                val firstPill = pills.firstOrNull()
                                if (firstPill != null) {
                                    flyoutVisible = true
                                    focusFirstPillToken++
                                    true
                                } else {
                                    false
                                }
                            },
                            onSelect = {
                                onCommitLibrary(library)
                                true
                            },
                        )
                    }
                }
            }

            if (libraries.size > 6) {
                Column(
                    modifier = Modifier
                        .width(CascadeLibraryColumnWidth)
                        .tvSkylinePanelChrome()
                        .padding(CascadePanelPadding),
                ) {
                    CascadePanelHeader(type.librariesHeader)
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.heightIn(max = CascadeMaxListHeight),
                    ) {
                        items(libraries, key = { it.id }) { library ->
                            val requester = visibleLibraryRequesters.getValue(library.id)
                            CascadeLibraryRow(
                                library = library,
                                type = type,
                                isCurrent = library.id == currentScopeId,
                                entersPanel = entersPanel,
                                focusRequester = requester,
                                onFocusChanged = { focused ->
                                    focusedRowId = if (focused) {
                                        library.id
                                    } else {
                                        focusedRowId.takeUnless { it == library.id }
                                    }
                                },
                                onTopChanged = { top -> rowTops[library.id] = top },
                                onMoveRight = {
                                    anchorId = library.id
                                    val firstPill = pills.firstOrNull()
                                    if (firstPill != null) {
                                        flyoutVisible = true
                                        focusFirstPillToken++
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onSelect = {
                                    onCommitLibrary(library)
                                    true
                                },
                            )
                        }
                    }
                    CascadePanelFooter(isSingleLibrary = false)
                }
            } else {
                Column(
                    modifier = Modifier
                        .width(CascadeLibraryColumnWidth)
                        .tvSkylinePanelChrome()
                        .padding(CascadePanelPadding),
                ) {
                    CascadePanelHeader(type.librariesHeader)
                    rowsContent()
                    CascadePanelFooter(isSingleLibrary = false)
                }
            }
        }

        // LEVEL 2 — sections flyout. For a single-library tab it is the only
        // column; otherwise it is revealed (composed) only after Right, as a
        // second column beside the library list (top-aligned).
        if (anchorLibrary != null && flyoutVisible) {
            val flyoutTopOffset = if (isSingleLibrary) 0 else {
                rowTops[anchorId]?.roundToInt() ?: 0
            }
            Column(
                modifier = Modifier
                    .offset { IntOffset(0, flyoutTopOffset) }
                    .width(CascadeFlyoutWidth)
                    .tvSkylinePanelChrome(corner = CascadeFlyoutCornerRadius)
                    .padding(CascadeFlyoutPadding),
            ) {
                CascadeFlyoutHeader(anchorLibrary.name)
                pills.forEach { pill ->
                    val requester = visiblePillRequesters.getValue(pill)
                    CascadeSectionRow(
                        pill = pill,
                        entersPanel = entersPanel,
                        focusRequester = requester,
                        onFocusChanged = { focused ->
                            focusedPill = if (focused) pill else focusedPill.takeUnless { it == pill }
                        },
                        onMoveLeft = {
                            if (isSingleLibrary) {
                                false
                            } else {
                                val target = anchorId ?: libraries.firstOrNull()?.id
                                if (target != null) {
                                    libraryRequesters[target]?.let { runCatching { it.requestFocus() } }
                                    true
                                } else {
                                    false
                                }
                            }
                        },
                        onSelect = {
                            onCommitSection(anchorLibrary, pill)
                            true
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun CascadePanelHeader(text: String) {
    Text(
        text = text,
        color = SiloOnSurface.copy(alpha = 0.38f),
        fontSize = CascadePanelHeaderSize,
        fontWeight = FontWeight.Medium,
        letterSpacing = CascadePanelHeaderTracking,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(top = 4.dp, bottom = 5.dp),
    )
}

@Composable
private fun CascadeFlyoutHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = SiloOnSurface.copy(alpha = 0.38f),
        fontSize = CascadeFlyoutHeaderSize,
        fontWeight = FontWeight.Medium,
        letterSpacing = CascadeFlyoutHeaderTracking,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 7.dp)
            .padding(top = 3.dp, bottom = 4.dp),
    )
}

@Composable
private fun CascadePanelFooter(isSingleLibrary: Boolean) {
    CascadePanelFooter(
        caption = if (isSingleLibrary) {
            "Press opens the section · Menu closes"
        } else {
            "Press opens the library · → jumps to a section · Menu closes"
        },
    )
}

/** Hairline + hint caption closing a Skyline panel; shared with the anchored selector menu. */
@Composable
internal fun CascadePanelFooter(caption: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .padding(top = 3.dp)
                .height(0.5.dp)
                .background(Color.White.copy(alpha = 0.12f)),
        )
        Text(
            text = caption,
            color = SiloOnSurface.copy(alpha = 0.52f),
            fontSize = CascadeFooterTextSize,
            fontWeight = FontWeight.Medium,
            letterSpacing = CascadeFooterTracking,
            lineHeight = CascadeFooterLineHeight,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(top = 6.dp, bottom = 3.dp),
        )
    }
}

/**
 * Level-1 library row (§5.3): type icon — name — trailing `✓`/`›`. Inverts to
 * a solid [SiloOnSurface] capsule with [DarkBackground] content on focus,
 * matching the bar's focused tab; bare row at rest.
 */
@Composable
private fun CascadeLibraryRow(
    library: UserLibrary,
    type: TvLibraryTabType,
    isCurrent: Boolean,
    entersPanel: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onTopChanged: (Float) -> Unit,
    onMoveRight: () -> Boolean,
    onSelect: () -> Boolean,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(isFocused) { onFocusChanged(isFocused) }

    CascadeRowChrome(
        icon = type.icon,
        title = library.name,
        trailingIcon = if (isCurrent) Icons.Filled.Check else Icons.Filled.ChevronRight,
        isFocused = isFocused,
        interactionSource = interactionSource,
        focusRequester = focusRequester,
        focusable = entersPanel,
        onTopChanged = onTopChanged,
        textSize = CascadeRowTextSize,
        iconSize = CascadeRowIconSize,
        trailingIconSize = CascadeRowTrailingIconSize,
        rowSpacing = CascadeRowSpacing,
        horizontalPadding = CascadeRowPaddingHorizontal,
        verticalPadding = CascadeRowPaddingVertical,
        cornerRadius = CascadeRowCornerRadius,
        onKey = { event ->
            when (event.key) {
                // Commit on key-UP and consume BOTH phases of Center/Enter. The
                // key-DOWN is swallowed (returns true) so the trailing key-UP that
                // would otherwise reach the newly-focused content card after
                // commit has nothing to act on — fixing the "commit key bleed".
                Key.DirectionCenter, Key.Enter -> {
                    if (event.type == KeyEventType.KeyUp) onSelect() else true
                }
                Key.DirectionRight ->
                    if (event.type == KeyEventType.KeyDown) onMoveRight() else false
                else -> false
            }
        },
    )
}

/**
 * Flyout / single-level section row (§5.3): pill icon — title, inverting to a
 * solid [SiloOnSurface] capsule on focus.
 */
@Composable
private fun CascadeSectionRow(
    pill: TvLibraryPill,
    entersPanel: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onMoveLeft: () -> Boolean,
    onSelect: () -> Boolean,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(isFocused) { onFocusChanged(isFocused) }

    CascadeRowChrome(
        icon = pill.icon,
        title = pill.title,
        trailingIcon = null,
        isFocused = isFocused,
        interactionSource = interactionSource,
        focusRequester = focusRequester,
        focusable = entersPanel,
        onTopChanged = null,
        textSize = CascadeFlyoutRowTextSize,
        iconSize = CascadeFlyoutRowIconSize,
        trailingIconSize = CascadeFlyoutRowIconSize,
        rowSpacing = CascadeFlyoutRowSpacing,
        horizontalPadding = CascadeFlyoutRowPaddingHorizontal,
        verticalPadding = CascadeFlyoutRowPaddingVertical,
        cornerRadius = CascadeFlyoutRowCornerRadius,
        onKey = { event ->
            when (event.key) {
                // Same commit-on-UP + consume-both contract as the library row so
                // committing a section can't bleed the key-up into content.
                Key.DirectionCenter, Key.Enter -> {
                    if (event.type == KeyEventType.KeyUp) onSelect() else true
                }
                Key.DirectionLeft ->
                    if (event.type == KeyEventType.KeyDown) onMoveLeft() else false
                else -> false
            }
        },
    )
}

@Composable
private fun CascadeActionRow(
    title: String,
    icon: ImageVector,
    entersPanel: Boolean,
    focusRequester: FocusRequester,
    onSelect: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    CascadeRowChrome(
        icon = icon,
        title = title,
        trailingIcon = null,
        isFocused = isFocused,
        interactionSource = interactionSource,
        focusRequester = focusRequester,
        focusable = entersPanel,
        onTopChanged = null,
        textSize = CascadeRowTextSize,
        iconSize = CascadeRowIconSize,
        trailingIconSize = CascadeRowTrailingIconSize,
        rowSpacing = CascadeRowSpacing,
        horizontalPadding = CascadeRowPaddingHorizontal,
        verticalPadding = CascadeRowPaddingVertical,
        cornerRadius = CascadeRowCornerRadius,
        onKey = { event ->
            when (event.key) {
                Key.DirectionCenter, Key.Enter -> {
                    if (event.type == KeyEventType.KeyUp) {
                        onSelect()
                    }
                    true
                }
                else -> false
            }
        },
    )
}

/**
 * Shared inverted-capsule row chrome for both levels: solid white fill +
 * background-colored content on focus, bare at rest. The [androidx.compose.ui.input.key.Key]
 * contract is handled by the caller via [onKey]; this row is focusable only
 * while [focusable] (the host has handed focus into the panel).
 */
@Composable
private fun CascadeRowChrome(
    icon: ImageVector,
    title: String,
    trailingIcon: ImageVector?,
    isFocused: Boolean,
    interactionSource: MutableInteractionSource,
    focusRequester: FocusRequester,
    focusable: Boolean,
    onTopChanged: ((Float) -> Unit)?,
    textSize: androidx.compose.ui.unit.TextUnit,
    iconSize: androidx.compose.ui.unit.Dp,
    trailingIconSize: androidx.compose.ui.unit.Dp,
    rowSpacing: androidx.compose.ui.unit.Dp,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    verticalPadding: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val contentColor = if (isFocused) DarkBackground else SiloOnSurface.copy(alpha = 0.9f)

    var rowModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)
        .clip(shape)
        .background(if (isFocused) SiloOnSurface else Color.Transparent)

    if (onTopChanged != null) {
        rowModifier = rowModifier.onGloballyPositioned { onTopChanged(it.positionInParent().y) }
    }

    rowModifier = rowModifier.focusRequester(focusRequester)

    if (focusable) {
        rowModifier = rowModifier
            .androidxFocusableRow(interactionSource)
            .onPreviewKeyEvent(onKey)
    }

    Row(
        modifier = rowModifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
        Text(
            text = title,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = textSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailingIcon != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = contentColor.copy(alpha = if (isFocused) 1f else 0.5f),
                modifier = Modifier.size(trailingIconSize),
            )
        }
    }
}

/** Makes a row focusable and routes its focus state into [interactionSource]. */
private fun Modifier.androidxFocusableRow(
    interactionSource: MutableInteractionSource,
): Modifier = this.focusable(interactionSource = interactionSource)
