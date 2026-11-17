package org.siloserver.silo.android.ui.screens.libraries

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.SiloDropdownMenu
import org.siloserver.silo.android.ui.components.SiloDropdownMenuItem
import org.siloserver.silo.android.ui.theme.SiloNavPillBorder
import org.siloserver.silo.android.ui.theme.SiloNavPillSelected
import org.siloserver.silo.android.ui.theme.SiloNavPillSelectedContent
import org.siloserver.silo.android.ui.theme.SiloNavPillSurface
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.currentNumberLocale
import org.siloserver.silo.model.personal.UserLibrary

/** Phone windows narrower than this get the dock; wider ones keep the chip row (iPad parity). */
internal val LibraryDockMaxWindowWidth = 600.dp

/** Room between the dock and the nav pill below it; the pill adds its own top margin. */
internal val LibraryDockBottomGap = 8.dp

/** Room between the phone header (or its Movies/Series switch) and the first row. */
internal val LibraryDockHeaderContentGap = 12.dp

private val DockHeight = 48.dp
private val DockHorizontalMargin = 20.dp
private const val SelectionMillis = 220
private const val LabelMinSp = 10f
private val SegmentLabelPadding = 8.dp

/**
 * Where the dock is drawn (iOS `PageDockStyle`). Floating in the page it draws
 * its own capsule; in the tab bar's accessory slot the bar draws the one
 * capsule and moves it, and the dock draws only its contents.
 */
internal enum class LibraryDockStyle(
    val leadingButtonWidth: Dp,
    val inset: Dp,
    val labelMaxSp: Float,
    val iconSize: Dp,
    val segmentPadding: Dp,
) {
    /** Floating just above the tab bar, while a now-playing bar holds its slot. */
    Floating(52.dp, 5.dp, 15f, 20.dp, SegmentLabelPadding),

    /** In the slot above the expanded tab bar. */
    Accessory(52.dp, 5.dp, 15f, 20.dp, SegmentLabelPadding),

    /** In the slot beside the minimized tab control: smaller and tighter, the
     *  segments sharing what is left of the row. */
    Inline(40.dp, 6.dp, 13f, 18.dp, 6.dp),
}

/** Height the dock takes above the nav pill, for the pages' bottom room. */
internal val LibraryDockFootprint: Dp = DockHeight + LibraryDockBottomGap

/** The library's name, with its size (or kind) underneath: the phone Libraries header's title. */
@Composable
internal fun LibraryHeaderTitle(
    library: UserLibrary?,
    count: Int?,
    mediaScope: String?,
    kindLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { heading() },
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = library?.name ?: stringResource(CommonR.string.library_libraries),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (library != null && count != null) libraryCountLabel(count, library, mediaScope) else kindLabel,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "1,284 movies": a library's size, named for what it holds (iOS `libraryCountLabel`). */
@Composable
private fun libraryCountLabel(count: Int, library: UserLibrary, mediaScope: String?): String {
    val type = library.type.trim().lowercase()
    val plural = when {
        type in AudiobookTypes -> CommonR.plurals.library_collectionCard_books
        mediaScope == "movie" -> CommonR.plurals.library_collectionCard_movies
        mediaScope == "series" -> CommonR.plurals.library_collectionCard_shows
        type in SeriesTypes -> CommonR.plurals.library_collectionCard_shows
        type in MovieTypes -> CommonR.plurals.library_collectionCard_movies
        else -> CommonR.plurals.library_titleCount
    }
    return pluralStringResource(plural, count, count, LocaleFormat.groupedCount(count, currentNumberLocale()))
}

/** A mixed library's Movies / Series switch: one full-width segmented control under the header. */
@Composable
internal fun LibraryScopeSwitch(
    mediaScope: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        "movie" to stringResource(CommonR.string.filters_value_movie),
        "series" to stringResource(CommonR.string.filters_value_series),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(CircleShape)
            .background(SiloNavPillSurface)
            .border(1.dp, SiloNavPillBorder, CircleShape)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        options.forEach { (scope, label) ->
            val selected = scope == mediaScope
            val fill by animateColorAsState(
                targetValue = if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent,
                animationSpec = tween(SelectionMillis),
                label = "scopeFill",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(fill)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(scope) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The phone Libraries dock floating in the page just above the tab bar (iOS
 * #1137), drawing its own capsule. It floats only while a now-playing bar
 * holds the tab bar's accessory slot; otherwise the slot shows
 * [LibraryDockContent] and the tab bar draws the capsule.
 */
@Composable
internal fun LibraryDock(
    library: UserLibrary?,
    libraries: List<UserLibrary>,
    tabs: List<LibrariesSubtab>,
    selectedTab: LibrariesSubtab,
    onSelect: (LibrariesSubtab) -> Unit,
    onReselect: (LibrariesSubtab) -> Unit,
    onPickLibrary: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DockHorizontalMargin)
            .height(DockHeight)
            .shadow(elevation = 16.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(SiloNavPillSurface)
            .border(1.dp, SiloNavPillBorder, CircleShape),
    ) {
        LibraryDockContent(
            style = LibraryDockStyle.Floating,
            library = library,
            libraries = libraries,
            tabs = tabs,
            selectedTab = selectedTab,
            onSelect = onSelect,
            onReselect = onReselect,
            onPickLibrary = onPickLibrary,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The dock's contents without a capsule: a library button that opens a menu
 * of the profile's libraries, then a switcher between the library's pages.
 * Tapping the page on screen asks for [onReselect] (scroll to top); a drag
 * across the switcher selects the page it ends over. Above the expanded tab
 * bar it is [DockHeight] tall; inline it fills the row the bar gives it.
 */
@Composable
internal fun LibraryDockContent(
    style: LibraryDockStyle,
    library: UserLibrary?,
    libraries: List<UserLibrary>,
    tabs: List<LibrariesSubtab>,
    selectedTab: LibrariesSubtab,
    onSelect: (LibrariesSubtab) -> Unit,
    onReselect: (LibrariesSubtab) -> Unit,
    onPickLibrary: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .then(
                when (style) {
                    LibraryDockStyle.Accessory -> Modifier.fillMaxWidth().height(DockHeight)
                    else -> Modifier
                },
            )
            .padding(style.inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryDockButton(
            library = library,
            libraries = libraries,
            onPickLibrary = onPickLibrary,
            width = style.leadingButtonWidth,
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .width(1.dp)
                .height(20.dp)
                .background(Color.White.copy(alpha = 0.18f)),
        )
        LibraryDockSegments(
            style = style,
            tabs = tabs,
            selectedTab = selectedTab,
            onSelect = onSelect,
            onReselect = onReselect,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun LibraryDockButton(
    library: UserLibrary?,
    libraries: List<UserLibrary>,
    onPickLibrary: (Int) -> Unit,
    width: Dp,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val canSwitch = libraries.size > 1 && library != null
    val switchHint = stringResource(CommonR.string.library_page_a11ySwitchLibrary)
    Box {
        Box(
            modifier = Modifier
                .width(width)
                .fillMaxHeight()
                .clip(CircleShape)
                .clickable(enabled = canSwitch, onClickLabel = switchHint) { menuOpen = true }
                .semantics { contentDescription = library?.name.orEmpty() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = library?.let { dockLibraryIcon(it.type) } ?: Icons.Filled.Layers,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        SiloDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            val groups = LibraryMenuGroup.grouped(libraries)
            groups.forEach { (group, members) ->
                if (groups.size > 1) {
                    Text(
                        text = stringResource(group.titleRes),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
                            .semantics { heading() },
                    )
                }
                members.forEach { member ->
                    val selected = member.id == library?.id
                    SiloDropdownMenuItem(
                        text = {
                            Text(
                                text = member.name,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        leadingIcon = {
                            Icon(dockLibraryIcon(member.type), contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        onClick = {
                            menuOpen = false
                            onPickLibrary(member.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryDockSegments(
    style: LibraryDockStyle,
    tabs: List<LibrariesSubtab>,
    selectedTab: LibrariesSubtab,
    onSelect: (LibrariesSubtab) -> Unit,
    onReselect: (LibrariesSubtab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // Where a finger dragging across the switcher is; null otherwise.
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    val currentTabs by rememberUpdatedState(tabs)
    val currentSelected by rememberUpdatedState(selectedTab)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val sectionsLabel = stringResource(CommonR.string.library_page_a11ySections)

    val labels = tabs.map { stringResource(it.dockLabelRes) }
    val measurer = rememberTextMeasurer()
    val baseStyle = LocalTextStyle.current.merge(TextStyle(fontWeight = FontWeight.SemiBold))
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)

    BoxWithConstraints(
        modifier = modifier.semantics { contentDescription = sectionsLabel },
    ) {
        val density = LocalDensity.current
        val totalPx = constraints.maxWidth.toFloat()
        val padPx = with(density) { (style.segmentPadding * 2).toPx() }
        val iconPx = with(density) { style.iconSize.toPx() }
        val layout = remember(labels, totalPx, baseStyle, density, style, selectedIndex) {
            dockSegmentLayout(
                labelWidths = { size ->
                    labels.map {
                        measurer.measure(it, baseStyle.copy(fontSize = size.sp), maxLines = 1).size.width.toFloat()
                    }
                },
                selectedIndex = selectedIndex,
                totalPx = totalPx,
                iconPx = iconPx,
                padPx = padPx,
                maxSp = style.labelMaxSp,
                minSp = LabelMinSp,
            )
        }
        val segmentPx = layout.widths
        val starts = segmentPx.runningFold(0f) { acc, w -> acc + w }
        // Pointer x runs left to right, while a right-to-left Row lays the
        // first segment out on the right; measure from the start edge.
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        fun indexAt(x: Float): Int {
            val fromStart = if (isRtl) totalPx - x else x
            return starts.indexOfLast { it <= fromStart }.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        }

        val shownIndex = dragIndex ?: selectedIndex
        val fillOffset by animateDpAsState(
            targetValue = with(density) { (starts.getOrElse(shownIndex) { 0f }).toDp() },
            animationSpec = tween(SelectionMillis),
            label = "dockFillOffset",
        )
        val fillWidth by animateDpAsState(
            targetValue = with(density) { (segmentPx.getOrElse(shownIndex) { 0f }).toDp() },
            animationSpec = tween(SelectionMillis),
            label = "dockFillWidth",
        )
        // The selected page's fill slides between segments.
        Box(
            modifier = Modifier
                .offset(x = fillOffset)
                .width(fillWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(SiloNavPillSelected),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .selectableGroup()
                .pointerInput(starts, isRtl) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragIndex = indexAt(offset.x) },
                        onHorizontalDrag = { change, _ ->
                            val next = indexAt(change.position.x)
                            if (next != dragIndex) {
                                dragIndex = next
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        },
                        onDragEnd = {
                            val tab = dragIndex?.let { currentTabs.getOrNull(it) }
                            dragIndex = null
                            // Ending where it started changes nothing.
                            if (tab != null && tab != currentSelected) currentOnSelect(tab)
                        },
                        onDragCancel = { dragIndex = null },
                    )
                },
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == shownIndex
                val tint by animateColorAsState(
                    targetValue = if (selected) SiloNavPillSelectedContent else Color.White,
                    animationSpec = tween(SelectionMillis),
                    label = "dockLabel",
                )
                Box(
                    modifier = Modifier
                        .width(with(density) { segmentPx.getOrElse(index) { 0f }.toDp() })
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = tab == selectedTab,
                            role = Role.Tab,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                if (tab == selectedTab) onReselect(tab) else onSelect(tab)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (layout.showsLabel.getOrElse(index) { true }) {
                        Text(
                            text = labels[index],
                            style = baseStyle.copy(color = tint, fontSize = layout.labelSp.sp),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        // Too narrow for every label: the page's icon, named
                        // for TalkBack as its label would be.
                        Icon(
                            imageVector = tab.dockIcon,
                            contentDescription = labels[index],
                            tint = tint,
                            modifier = Modifier.size(style.iconSize),
                        )
                    }
                }
            }
        }
    }
}

/** How the switcher shares its width: one label size, which segments show a label, and each segment's width. */
internal data class DockSegmentLayout(
    val labelSp: Float,
    val showsLabel: List<Boolean>,
    val widths: List<Float>,
)

/**
 * Lays the page switcher out in [totalPx] (iOS `PageDockSegments`): every
 * label, at the largest size from [maxSp] down to [minSp] that fits them all;
 * failing that, the selected page's label with the others as icons; failing
 * that, icons only. Each segment gets its content's width plus an equal share
 * of what is left, so the segments fill the switcher; if even icons overflow,
 * the width is split in proportion so every segment stays tappable.
 */
internal fun dockSegmentLayout(
    labelWidths: (sizeSp: Float) -> List<Float>,
    selectedIndex: Int,
    totalPx: Float,
    iconPx: Float,
    padPx: Float,
    maxSp: Float,
    minSp: Float,
): DockSegmentLayout {
    fun fits(contents: List<Float>) = contents.sum() + padPx * contents.size <= totalPx
    fun share(contents: List<Float>): List<Float> {
        val used = contents.sum()
        return if (used <= totalPx) {
            val extra = (totalPx - used) / contents.size.coerceAtLeast(1)
            contents.map { it + extra }
        } else {
            contents.map { it / used * totalPx }
        }
    }

    var size = maxSp
    var measured = labelWidths(size)
    while (size > minSp && !fits(measured)) {
        size -= 0.5f
        measured = labelWidths(size)
    }
    if (fits(measured)) return DockSegmentLayout(size, measured.map { true }, share(measured))

    val mixed = measured.mapIndexed { index, width -> if (index == selectedIndex) width else iconPx }
    if (fits(mixed)) {
        return DockSegmentLayout(size, measured.indices.map { it == selectedIndex }, share(mixed))
    }
    val icons = measured.map { iconPx }
    return DockSegmentLayout(size, measured.map { false }, share(icons))
}

/** The page's glyph when its label cannot fit (iOS `dockIcon`). */
private val LibrariesSubtab.dockIcon: ImageVector
    get() = when (this) {
        LibrariesSubtab.Recommended -> Icons.Filled.AutoAwesome
        LibrariesSubtab.Browse -> Icons.Filled.Apps
        LibrariesSubtab.Collections -> Icons.Filled.AutoAwesomeMotion
    }

/** The dock's name for a page; the Library page reads as Browse there (iOS parity). */
private val LibrariesSubtab.dockLabelRes: Int
    get() = when (this) {
        LibrariesSubtab.Recommended -> CommonR.string.library_recommended
        LibrariesSubtab.Browse -> CommonR.string.library_browse
        LibrariesSubtab.Collections -> CommonR.string.library_collections
    }

private val AudiobookTypes = setOf("audiobook", "audiobooks")
private val SeriesTypes = setOf("series", "tv", "shows")
private val MovieTypes = setOf("movies", "movie")

/** The library's glyph in the dock and its menu; a mixed library shows a stack (iOS parity). */
private fun dockLibraryIcon(type: String): ImageVector =
    if (type.trim().lowercase() == "mixed") Icons.Filled.Layers else libraryIcon(type.trim())

/** What you do with a library: the library menu's groups, in this order. */
internal enum class LibraryMenuGroup(val titleRes: Int) {
    Watch(CommonR.string.library_switcher_watch),
    Listen(CommonR.string.library_switcher_listen),
    Read(CommonR.string.library_switcher_read),
    ;

    companion object {
        /** Libraries of a kind the app does not know fall under Watch, so none is dropped. */
        fun of(library: UserLibrary): LibraryMenuGroup = when (library.type.trim().lowercase()) {
            "audiobook", "audiobooks", "music" -> Listen
            "book", "books", "ebook", "ebooks", "comic", "comics", "manga" -> Read
            else -> Watch
        }

        /** Groups in Watch, Listen, Read order, skipping empty ones; libraries keep their order. */
        fun grouped(libraries: List<UserLibrary>): List<Pair<LibraryMenuGroup, List<UserLibrary>>> {
            val byGroup = libraries.groupBy(::of)
            return entries.mapNotNull { group -> byGroup[group]?.let { group to it } }
        }
    }
}
