package org.siloserver.silo.tv.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * tvOS-faithful spacing scale. Values mirror SiloTheme.swift for the tvOS
 * branch (~2x of the iPhone scale): 24 / 48 / 60 / 80 dp.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 18.dp
    val xl = 24.dp
    val xxl = 30.dp
    val xxxl = 40.dp

    /** Horizontal content inset — matches tvOS overscan safe-area padding. */
    val safeArea = 40.dp

    /** Top safe area for hero surfaces. */
    val heroTopSafe = 60.dp

    /** Vertical gap between rows on Home and detail screens (tvOS rowSpacing = 60pt). */
    val sectionSpacing = 30.dp

    /** Inset applied around the Infuse-style player HUD panel (A.3). */
    val hudPanelInset = 24.dp

    /** Nominal height reserved for the floating top menu bar (A.1). */
    val topMenuBarHeight = 32.dp

    /** Vertical distance over which the home-hero backdrop fades into the rows (A.2). */
    val heroBackdropFade = 120.dp
}

/**
 * Skyline top-bar chrome metrics, mirrored from `SiloTheme.Skyline` in
 * `iosApp/iosApp/Theme/SiloTheme.swift`. tvOS values are points at
 * 1920×1080; Android TV chrome renders in a ~960×540dp layout, so positional
 * and media geometry tokens map at ~0.5x to preserve the same on-screen
 * proportions. Text keeps the TV font scale supplied by [SiloTvTheme].
 * Names match the Swift tokens 1:1 so audits against the iOS source stay
 * mechanical.
 */
object TvSkyline {
    /** Root horizontal inset for chrome and content — tvOS `safeAreaX` (88pt). */
    val safeAreaX = 44.dp

    /** Top bar offset from the screen's top edge — tvOS `barTopInset` (56pt). */
    val barTopInset = 28.dp

    /** Top bar row height — tvOS `barHeight` (64pt). */
    val barHeight = 32.dp

    /** Active tab capsule height, aligned to the trailing search/profile chrome. */
    val tabPillHeight = 30.dp

    /** Gap between tab capsules in the bar's center cluster — tvOS `tabSpacing` (8pt). */
    val tabSpacing = 4.dp

    /** Tab label size — exact 0.5x mapping of the 26pt tvOS token. */
    val tabLabelSize = 15.sp

    /** Tab capsule horizontal padding — tvOS `tabPaddingHorizontal` (29pt). */
    val tabPaddingHorizontal = 14.5.dp

    /** Tab capsule vertical padding — tvOS `tabPaddingVertical` (12pt). */
    val tabPaddingVertical = 6.dp

    /** Square hit target of the search button and the avatar — tuned 8% larger for TV readability. */
    val barIconSize = 28.dp

    /** Panel top offset — tvOS `dropdownTopInset` (132pt). */
    val dropdownTopInset = 66.dp

    /** Gap between the search button and the avatar — tvOS `barTrailingSpacing` (22pt). */
    val barTrailingSpacing = 11.dp

    /** Wordmark size, visually balanced with the top navigation labels. */
    val wordmarkSize = 15.sp

    /** Wordmark letter tracking — tvOS `wordmarkTracking` (+0.34 em). */
    val wordmarkTracking = 0.34.em

    /** Bar opacity while focus is down in the content zone — tvOS `barDimmedOpacity`. */
    const val barDimmedOpacity = 0.70f

    /** Profile dropdown width, mapped from a 480pt tvOS panel. */
    val profileMenuWidth = 240.dp

    /** Profile dropdown body inset, mapped from 12pt tvOS vertical padding. */
    val profileMenuPanelVerticalPadding = 6.dp

    /** Gap between rows in the profile dropdown, mapped from 4pt tvOS spacing. */
    val profileMenuItemSpacing = 2.dp

    /** Profile dropdown header inset, mapped from 32pt x 12pt tvOS padding. */
    val profileMenuHeaderHorizontalPadding = 16.dp
    val profileMenuHeaderVerticalPadding = 6.dp

    /** Gap between avatar and account text, mapped from a 20pt tvOS gap. */
    val profileMenuHeaderGap = 10.dp

    /** Profile dropdown avatar size, mapped from a 64pt tvOS avatar. */
    val profileMenuAvatarSize = 32.dp

    /** Profile header typography, mapped from 28pt / 34pt tvOS title text with a readability floor. */
    val profileMenuHeaderTitleSize = 15.sp
    val profileMenuHeaderTitleLineHeight = 18.sp

    /** Profile subtitle typography, mapped from 20pt / 24pt tvOS metadata text with a readability floor. */
    val profileMenuHeaderSubtitleSize = 13.sp
    val profileMenuHeaderSubtitleLineHeight = 16.sp

    /** Divider inset, mapped from 24pt x 8pt tvOS padding. */
    val profileMenuDividerHorizontalPadding = 12.dp
    val profileMenuDividerVerticalPadding = 4.dp

    /** Outer row inset inside the panel, mapped from 16pt tvOS padding. */
    val profileMenuRowOuterHorizontalPadding = 8.dp

    /** Row content inset, mapped from 24pt x 16pt tvOS padding. */
    val profileMenuRowContentHorizontalPadding = 12.dp
    val profileMenuRowContentVerticalPadding = 8.dp

    /** Gap between row icon and text, mapped from a 20pt tvOS gap. */
    val profileMenuRowGap = 10.dp

    /** Profile row icon size, mapped from a 32pt tvOS icon. */
    val profileMenuRowIconSize = 16.dp

    /** Profile row capsule radius, mapped from a 16pt tvOS radius. */
    val profileMenuRowCornerRadius = 8.dp

    /** Profile row typography, mapped from 28pt / 34pt tvOS row text with a readability floor. */
    val profileMenuRowTextSize = 15.sp
    val profileMenuRowLineHeight = 18.sp
}

object HeroDimens {
    /** tvOS detail hero is tall enough to hold logo, metadata, overview, facts, and actions. */
    val Height = 600.dp

    /** Home hero matches the Browse-row reveal — shorter than detail. */
    val HomeHeight = 300.dp
}

object RowDimens {
    /** 2:3 poster card. tvOS uses 260×390pt; Android TV maps to 130×195dp. */
    val PosterHeight = 195.dp
    val PosterWidth = 130.dp

    /** Dense Skyline Home/Browse poster — tvOS `densePosterCardWidth` 176pt → 88dp. */
    val DensePosterHeight = 132.dp
    val DensePosterWidth = 88.dp

    /** 16:9 episode/backdrop card — tvOS 360×200pt → 180×100dp. */
    val BackdropHeight = 100.dp
    val BackdropWidth = 180.dp

    /** Watched badge geometry scales with the active poster width. */
    const val WatchedBadgeSizeFraction = 0.24f
    const val WatchedBadgeIconSizeFraction = 0.55f
    const val WatchedBadgePaddingFraction = 0.06f
    val WatchedBadgeMinSize = 20.dp
    val WatchedBadgeMaxSize = 32.dp
    val WatchedBadgeMinPadding = 4.dp
    val WatchedBadgeMaxPadding = 8.dp
}
