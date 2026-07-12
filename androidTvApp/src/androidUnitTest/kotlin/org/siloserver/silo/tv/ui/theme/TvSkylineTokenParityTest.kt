package org.siloserver.silo.tv.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSkylineTokenParityTest {
    private val spacing = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/theme/Spacing.kt",
    ).readText()

    private val cascadeSelector = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvCascadeSelector.kt",
    ).readText()

    private val panelChrome = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylinePanelChrome.kt",
    ).readText()

    private val mediaCard = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvMediaCard.kt",
    ).readText()

    private val episodeCard = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvEpisodeCard.kt",
    ).readText()

    private val mediaRow = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvMediaRow.kt",
    ).readText()

    private val focusMarquee = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarquee.kt",
    ).readText()

    private val homeScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/home/TvHomeScreen.kt",
    ).readText()

    private val skylineSectionFeed = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineSectionFeed.kt",
    ).readText()

    private val browseScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/browse/TvBrowseScreen.kt",
    ).readText()

    private val libraryDetailScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/library/TvLibraryDetailScreen.kt",
    ).readText()

    private val playerTransportCluster = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerTransportCluster.kt",
    ).readText()

    private val playerScrubber = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScrubber.kt",
    ).readText()

    private val playerHud = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerHud.kt",
    ).readText()

    private val settingsScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/settings/TvSettingsScreen.kt",
    ).readText()

    private val fullScreenPicker = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFullScreenPicker.kt",
    ).readText()

    private val cardOverlaySettingsScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/settings/TvCardOverlaySettingsScreen.kt",
    ).readText()

    private val loginScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/auth/TvLoginScreen.kt",
    ).readText()

    private val detailEpisodeRail = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailEpisodeRail.kt",
    ).readText()

    private val detailSectionHeader = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailSectionHeader.kt",
    ).readText()

    private val detailHero = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt",
    ).readText()

    private val castCrewSection = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvCastCrewSection.kt",
    ).readText()

    private val anchoredSelectorMenu = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvAnchoredSelectorMenu.kt",
    ).readText()

    private val detailFactsTable = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailFactsTable.kt",
    ).readText()

    private val shell = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt",
    ).readText()

    @Test
    fun topBarUsesHalfScaleSkylineGeometry() {
        assertToken("val safeAreaX = 44.dp")
        assertToken("val barTopInset = 28.dp")
        assertToken("val barHeight = 32.dp")
        assertToken("val tabPillHeight = 30.dp")
        assertToken("val dropdownTopInset = 66.dp")
        assertToken("val tabSpacing = 4.dp")
        assertToken("val tabLabelSize = 15.sp")
        assertToken("val tabPaddingHorizontal = 14.5.dp")
        assertToken("val tabPaddingVertical = 6.dp")
        assertToken("val barIconSize = 28.dp")
        assertToken("val barTrailingSpacing = 11.dp")
        assertToken("val wordmarkSize = 15.sp")
    }

    @Test
    fun profileDropdownUsesHalfScaleSkylineGeometry() {
        assertToken("val profileMenuWidth = 240.dp")
        assertToken("val profileMenuPanelVerticalPadding = 6.dp")
        assertToken("val profileMenuItemSpacing = 2.dp")
        assertToken("val profileMenuHeaderHorizontalPadding = 16.dp")
        assertToken("val profileMenuHeaderVerticalPadding = 6.dp")
        assertToken("val profileMenuHeaderGap = 10.dp")
        assertToken("val profileMenuAvatarSize = 32.dp")
        assertToken("val profileMenuHeaderTitleSize = 15.sp")
        assertToken("val profileMenuHeaderSubtitleSize = 13.sp")
        assertToken("val profileMenuDividerHorizontalPadding = 12.dp")
        assertToken("val profileMenuDividerVerticalPadding = 4.dp")
        assertToken("val profileMenuRowOuterHorizontalPadding = 8.dp")
        assertToken("val profileMenuRowContentHorizontalPadding = 12.dp")
        assertToken("val profileMenuRowContentVerticalPadding = 8.dp")
        assertToken("val profileMenuRowGap = 10.dp")
        assertToken("val profileMenuRowIconSize = 16.dp")
        assertToken("val profileMenuRowCornerRadius = 8.dp")
        assertToken("val profileMenuRowTextSize = 15.sp")

        assertTrue(shell.contains(".width(TvSkyline.profileMenuWidth)"))
        assertTrue(shell.contains(".padding(vertical = TvSkyline.profileMenuPanelVerticalPadding)"))
        assertTrue(shell.contains("Arrangement.spacedBy(TvSkyline.profileMenuItemSpacing)"))
        assertTrue(shell.contains("TvSkyline.profileMenuRowContentHorizontalPadding"))
        assertTrue(shell.contains("TvSkyline.profileMenuRowContentVerticalPadding"))
        assertTrue(shell.contains("TvSkyline.profileMenuRowIconSize"))
        assertTrue(shell.contains("TvSkyline.profileMenuRowTextSize"))
        assertFalse(shell.contains(".width(320.dp)"))
        assertFalse(shell.contains(".padding(horizontal = 16.dp, vertical = 13.dp)"))
        assertFalse(shell.contains("modifier = Modifier.size(22.dp)"))
    }

    @Test
    fun cascadeSelectorUsesHalfScalePanelGeometry() {
        assertTrue(cascadeSelector.contains("val CascadeLibraryColumnWidth = 230.dp"))
        assertTrue(cascadeSelector.contains("val CascadeFlyoutWidth = 150.dp"))
        assertTrue(cascadeSelector.contains("val CascadeFlyoutGap = 9.dp"))
        assertTrue(cascadeSelector.contains("val CascadePanelPadding = 7.dp"))
        assertTrue(cascadeSelector.contains("val CascadeFlyoutPadding = 5.dp"))
        assertTrue(cascadeSelector.contains("val CascadeFlyoutCornerRadius = 9.dp"))
        assertTrue(cascadeSelector.contains("val CascadeMaxListHeight = 180.dp"))
        assertTrue(cascadeSelector.contains("val TvCascadeSelectorMaxPanelWidth = 389.dp"))
    }

    @Test
    fun cascadeSelectorRowsUseHalfScaleSkylineContentTokens() {
        assertTrue(cascadeSelector.contains("val CascadeRowTextSize = 13.sp"))
        assertTrue(cascadeSelector.contains("val CascadeRowIconSize = 15.dp"))
        assertTrue(cascadeSelector.contains("val CascadeRowPaddingHorizontal = 9.dp"))
        assertTrue(cascadeSelector.contains("val CascadeRowPaddingVertical = 8.dp"))
        assertTrue(cascadeSelector.contains("val CascadeRowCornerRadius = 7.dp"))
        assertTrue(cascadeSelector.contains("val CascadeFlyoutRowTextSize = 13.sp"))
        assertTrue(cascadeSelector.contains("val CascadeFlyoutRowIconSize = 9.dp"))
        assertTrue(cascadeSelector.contains("val CascadeFlyoutRowPaddingHorizontal = 8.dp"))
        assertTrue(cascadeSelector.contains("val CascadeFlyoutRowPaddingVertical = 6.5.dp"))
        assertTrue(cascadeSelector.contains("val CascadeFlyoutRowCornerRadius = 6.dp"))
    }

    @Test
    fun posterCardsUseSharedTvOsMappedPosterGeometry() {
        assertTrue(mediaCard.contains("val TvCardWidth: Dp = RowDimens.PosterWidth"))
        assertFalse(mediaCard.contains("val TvCardWidth: Dp = 200.dp"))
    }

    @Test
    fun posterWatchedBadgeScalesWithCardWidth() {
        assertToken("const val WatchedBadgeSizeFraction = 0.24f")
        assertToken("const val WatchedBadgeIconSizeFraction = 0.55f")
        assertToken("const val WatchedBadgePaddingFraction = 0.06f")
        assertToken("val WatchedBadgeMinSize = 20.dp")
        assertToken("val WatchedBadgeMaxSize = 32.dp")
        assertToken("val WatchedBadgeMinPadding = 4.dp")
        assertToken("val WatchedBadgeMaxPadding = 8.dp")

        assertTrue(mediaCard.contains("val watchedBadgeSize = (width * RowDimens.WatchedBadgeSizeFraction)"))
        assertTrue(mediaCard.contains("val watchedBadgeIconSize = watchedBadgeSize * RowDimens.WatchedBadgeIconSizeFraction"))
        assertTrue(mediaCard.contains("val watchedBadgePadding = (width * RowDimens.WatchedBadgePaddingFraction)"))
        assertTrue(mediaCard.contains(".padding(watchedBadgePadding)"))
        assertTrue(mediaCard.contains(".size(watchedBadgeSize)"))
        assertTrue(mediaCard.contains("modifier = Modifier.size(watchedBadgeIconSize)"))
        assertFalse(mediaCard.contains(".padding(12.dp)"))
        assertFalse(mediaCard.contains(".size(40.dp)"))
        assertFalse(mediaCard.contains("modifier = Modifier.size(22.dp)"))
    }

    @Test
    fun backdropCardsUseSharedTvOsMappedBackdropGeometry() {
        assertTrue(episodeCard.contains("val TvEpisodeCardWidth: Dp = RowDimens.BackdropWidth"))
        assertFalse(episodeCard.contains("val TvEpisodeCardWidth: Dp = 180.dp"))
    }

    @Test
    fun backdropCardsMirrorTvOsEpisodeBadgeAndTwoLineCaptionTreatment() {
        assertTrue(episodeCard.contains("val episodeBadge = formatEpisodeTag(seasonNumber, episodeNumber)"))
        assertTrue(episodeCard.contains(".align(Alignment.BottomStart)"))
        assertTrue(episodeCard.contains("text = seriesTitle ?: title,"))
        assertTrue(episodeCard.contains("val secondaryLine = if (seriesTitle != null) title else year?.toString()"))
        assertTrue(episodeCard.contains("verticalArrangement = Arrangement.spacedBy(4.dp)"))
        assertFalse(episodeCard.contains("m left"))
        assertTrue(episodeCard.contains("fontSize = 13.sp"))
        assertFalse(episodeCard.contains("Icons.Default.PlayArrow"))
        assertFalse(episodeCard.contains("val subtitle = if (tag != null) \"\$tag · \$title\" else title"))
    }

    @Test
    fun homeFeedUsesDenseSkylineRowGeometry() {
        assertToken("val DensePosterWidth = 88.dp")
        assertToken("val DensePosterHeight = 132.dp")
        assertTrue(mediaRow.contains("posterWidth: androidx.compose.ui.unit.Dp? = null"))
        assertTrue(mediaRow.contains("width = posterWidth ?: TvCardWidth"))
        assertTrue(skylineSectionFeed.contains("posterWidth = RowDimens.DensePosterWidth"))
        assertTrue(skylineSectionFeed.contains("val TvSkylineItemSpacing = 20.dp"))
        assertTrue(skylineSectionFeed.contains("val TvSkylineRowPreviewSpacing = 14.dp"))
        assertTrue(skylineSectionFeed.contains("val TvSkylineRowCardVerticalPadding = 7.dp"))
        assertTrue(skylineSectionFeed.contains("val TvSkylineRowBandBottomInset = 10.dp"))
        assertTrue(skylineSectionFeed.contains("verticalArrangement = Arrangement.spacedBy(TvSkylineRowPreviewSpacing)"))
        assertTrue(skylineSectionFeed.contains("bottom = trailingPreviewPadding"))
    }

    @Test
    fun homeMarqueeUsesHalfScaleTvOsLogoBounds() {
        assertTrue(focusMarquee.contains("private val MarqueeLogoMaxWidth = 440.dp"))
        assertTrue(focusMarquee.contains("private val MarqueeLogoMaxHeight = 95.dp"))
        assertTrue(focusMarquee.contains(".height(MarqueeLogoMaxHeight)"))
        assertFalse(focusMarquee.contains("private val MarqueeLogoMaxWidth = 320.dp"))
        assertFalse(focusMarquee.contains(".heightIn(max = MarqueeLogoMaxHeight)"))
    }

    @Test
    fun homeSeedsPassiveMarqueeBeforeAnyRowFocus() {
        assertTrue(skylineSectionFeed.contains("val initialMarqueeSeed = remember(rows)"))
        assertTrue(skylineSectionFeed.contains("marquee.seedInitialPreview(seed.item, seed.rowTitle)"))
        assertTrue(skylineSectionFeed.contains("data class TvSkylineMarqueeSeed"))
        assertTrue(skylineSectionFeed.contains("if (marquee.content == null)"))
    }

    @Test
    fun detailEpisodeRailUsesHalfScaleTvOsCardGeometry() {
        assertTrue(detailEpisodeRail.contains("val cardWidth = 230.dp"))
        assertTrue(detailEpisodeRail.contains("val stillHeight = 130.dp"))
        assertTrue(detailEpisodeRail.contains("fontSize = 13.sp"))
        assertTrue(detailEpisodeRail.contains("fontSize = 14.sp"))
        assertFalse(detailEpisodeRail.contains("val cardWidth = 460.dp"))
        assertFalse(detailEpisodeRail.contains("val stillHeight = 260.dp"))
        assertFalse(detailEpisodeRail.contains("fontSize = 16.sp"))
    }

    @Test
    fun detailHeroUsesHalfScaleTvOsTypographyAndOverlayGeometry() {
        assertTrue(detailHero.contains("val contentMaxWidth = 600.dp"))
        assertTrue(detailHero.contains(".padding(start = Spacing.safeArea, end = Spacing.safeArea, bottom = TvDetailHeroBottomInset)"))
        // Starring credit: trailing overlay, vertically centered then lifted
        // by 0.45×heroHeight (tvOS `starringOverlay`), capped at 280dp.
        assertTrue(detailHero.contains(".align(Alignment.CenterEnd)"))
        assertTrue(detailHero.contains(".padding(end = Spacing.safeArea, bottom = heroHeight * 0.45f)"))
        assertTrue(detailHero.contains(".widthIn(max = 280.dp)"))
        assertTrue(detailHero.contains("fontSize = 13.sp"))
        // Display title: condensed Inter companion, tvOS 92pt ÷ 2 trimmed to
        // 42sp per design review (2026-07-11).
        assertTrue(detailHero.contains("private val heroDisplayHero = TextStyle"))
        assertTrue(detailHero.contains("R.font.inter_tight_black"))
        assertTrue(detailHero.contains("fontSize = 42.sp"))
        assertTrue(detailHero.contains("lineHeight = 46.sp"))
        assertTrue(detailHero.contains("letterSpacing = 0.sp"))
        assertTrue(detailHero.contains(".height(110.dp)"))
        assertTrue(detailHero.contains(".widthIn(max = 310.dp)"))
        assertTrue(detailHero.contains("fontSize = 23.sp"))
        assertTrue(detailHero.contains("fontSize = 14.sp"))
        assertTrue(detailHero.contains("fontSize = 16.sp"))
        assertFalse(detailHero.contains("fontSize = 56.sp"))
        assertFalse(detailHero.contains("letterSpacing = (-1).sp"))
        assertFalse(detailHero.contains(".align(Alignment.TopEnd)"))
    }

    @Test
    fun castRailUsesHalfScaleTvOsPortraitGeometry() {
        assertTrue(castCrewSection.contains("val photoSize = 100.dp"))
        assertTrue(castCrewSection.contains("fontSize = 13.sp"))
        assertFalse(castCrewSection.contains("val photoSize = 200.dp"))
        assertFalse(castCrewSection.contains("fontSize = 16.sp"))
    }

    @Test
    fun detailSectionHeadersUseHalfScaleTvOsTypography() {
        // tvOS TVSectionHeader ÷ 2: eyebrow 20pt bold tracking 3.0 → 10sp /
        // 1.5sp; title 42pt semibold → 21sp.
        assertTrue(detailSectionHeader.contains("fontSize = 10.sp"))
        assertTrue(detailSectionHeader.contains("lineHeight = 12.sp"))
        assertTrue(detailSectionHeader.contains("letterSpacing = 1.5.sp"))
        assertTrue(detailSectionHeader.contains("fontSize = 21.sp"))
        assertFalse(detailSectionHeader.contains("fontSize = 16.sp"))
    }

    @Test
    fun browseAndLibraryHeadersAvoidOversizedLegacyTitleTreatment() {
        assertTrue(browseScreen.contains("fontSize = 21.sp"))
        assertTrue(browseScreen.contains("lineHeight = 23.sp"))
        assertTrue(browseScreen.contains("letterSpacing = 0.sp"))
        assertFalse(browseScreen.contains("fontSize = 32.sp"))
        assertFalse(libraryDetailScreen.contains("fontSize = 32.sp"))
        assertFalse(browseScreen.contains("letterSpacing = (-0.8).sp"))
        assertFalse(libraryDetailScreen.contains("letterSpacing = (-0.8).sp"))
    }

    @Test
    fun playerTransportUsesHalfScaleTvOsControls() {
        assertTrue(playerTransportCluster.contains("val buttonSize = 33.dp"))
        assertTrue(playerTransportCluster.contains("val symbolSize = if (isPrimary) 15.dp else 12.5.dp"))
        assertTrue(playerTransportCluster.contains("isPrimary: Boolean = false"))
        assertTrue(playerTransportCluster.contains("isPrimary = true"))
        assertTrue(playerTransportCluster.contains("Spacer(modifier = Modifier.size(width = 5.dp, height = 1.dp))"))
        assertTrue(playerTransportCluster.contains("width = 0.5.dp"))
        assertFalse(playerTransportCluster.contains("val buttonSize = 66.dp"))
        assertFalse(playerTransportCluster.contains("val symbolSize = 28.dp"))
    }

    @Test
    fun playerScrubberUsesHalfScaleTvOsGeometry() {
        assertTrue(playerScrubber.contains("if (isTimelineScrubbing) 6.dp else 3.5.dp"))
        assertTrue(playerScrubber.contains("isTimelineScrubbing -> 21.dp"))
        assertTrue(playerScrubber.contains("isFocused -> 21.dp"))
        assertTrue(playerScrubber.contains("else -> 15.dp"))
        assertTrue(playerScrubber.contains("modifier = modifier.height(41.dp)"))
        assertTrue(playerScrubber.contains("verticalArrangement = Arrangement.spacedBy(5.dp)"))
        assertTrue(playerScrubber.contains(".height(28.dp)"))
        assertFalse(playerScrubber.contains("modifier = modifier.height(82.dp)"))
        assertFalse(playerScrubber.contains("isTimelineScrubbing -> 42.dp"))
    }

    @Test
    fun playerHudUsesAdaptiveAndroidTvShellAndPickerGeometry() {
        assertTrue(playerHud.contains("private val HudMaxWidth = 680.dp"))
        assertTrue(playerHud.contains("private val HudMinHeight = 290.dp"))
        assertTrue(playerHud.contains("private val HudMaxHeight = 360.dp"))
        assertTrue(playerHud.contains(".widthIn(max = HudMaxWidth)"))
        assertTrue(playerHud.contains(".fillMaxWidth(0.72f)"))
        assertTrue(playerHud.contains(".heightIn(min = HudMinHeight, max = HudMaxHeight)"))
        assertTrue(playerHud.contains(".clip(RoundedCornerShape(HudPanelCorner))"))
        assertTrue(playerHud.contains("width = 0.5.dp"))
        assertTrue(playerHud.contains(".padding(HudPanelPadding)"))
        assertTrue(playerHud.contains(".height(HudTabHeight)"))
        assertTrue(playerHud.contains(".padding(horizontal = 16.dp)"))
        assertTrue(playerHud.contains("fontSize = HudTabTextSize"))
        assertTrue(playerHud.contains(".width(360.dp)"))
        assertTrue(playerHud.contains(".heightIn(max = 220.dp)"))
        assertTrue(playerHud.contains(".padding(horizontal = 18.dp, vertical = 14.dp)"))
        assertFalse(playerHud.contains(".widthIn(max = 550.dp)"))
        assertFalse(playerHud.contains(".height(190.dp)"))
        assertFalse(playerHud.contains("private val HudMaxWidth = 600.dp"))
        assertFalse(playerHud.contains(".fillMaxWidth(0.66f)"))
        assertFalse(playerHud.contains(".widthIn(max = 1100.dp)"))
        assertFalse(playerHud.contains(".height(380.dp)"))
        assertFalse(playerHud.contains(".width(620.dp)"))
    }

    @Test
    fun playerHudPaneRowsUseReadableAndroidTvTypography() {
        assertTrue(playerHud.contains("private val HudPaneColumnGap = 32.dp"))
        assertTrue(playerHud.contains("horizontalArrangement = Arrangement.spacedBy(HudPaneColumnGap)"))
        assertTrue(playerHud.contains("verticalArrangement = Arrangement.spacedBy(8.dp)"))
        assertTrue(playerHud.contains("fontSize = HudTitleTextSize"))
        assertTrue(playerHud.contains("fontSize = HudBodyTextSize"))
        assertTrue(playerHud.contains(".padding(horizontal = 7.dp, vertical = 3.dp)"))
        assertTrue(playerHud.contains(".clip(RoundedCornerShape(8.dp))"))
        assertTrue(playerHud.contains(".padding(horizontal = 10.dp, vertical = 8.dp)"))
        assertTrue(playerHud.contains("modifier = Modifier.size(11.dp)"))
        assertFalse(playerHud.contains("horizontalArrangement = Arrangement.spacedBy(24.dp)"))
        assertFalse(playerHud.contains("horizontalArrangement = Arrangement.spacedBy(48.dp)"))
        assertFalse(playerHud.contains("fontSize = 13.sp"))
        assertFalse(playerHud.contains("fontSize = 7.sp"))
        assertFalse(playerHud.contains("fontSize = 10.sp"))
        assertFalse(playerHud.contains(".padding(horizontal = 24.dp, vertical = 12.dp)"))
        assertFalse(playerHud.contains("modifier = Modifier.size(18.dp)"))
    }

    @Test
    fun settingsPickerSheetUsesHalfScaleTvOsRows() {
        assertTrue(settingsScreen.contains("style = MaterialTheme.typography.displaySmall.copy(fontSize = 19.sp, lineHeight = 23.sp)"))
        assertTrue(settingsScreen.contains(".width(420.dp)"))
        assertTrue(settingsScreen.contains("contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)"))
        assertTrue(settingsScreen.contains("verticalArrangement = Arrangement.spacedBy(5.dp)"))
        assertTrue(settingsScreen.contains("val shape = RoundedCornerShape(7.dp)"))
        assertTrue(settingsScreen.contains(".padding(horizontal = 14.dp, vertical = 9.dp)"))
        assertTrue(settingsScreen.contains("fontSize = 15.sp"))
        assertTrue(settingsScreen.contains("lineHeight = 18.sp"))
        assertTrue(settingsScreen.contains(".size(15.dp)"))
        assertFalse(settingsScreen.contains(".width(680.dp)"))
        assertFalse(settingsScreen.contains(".padding(horizontal = 26.dp, vertical = 18.dp)"))
    }

    @Test
    fun genericFullScreenPickerUsesHalfScaleTvOsRows() {
        assertTrue(fullScreenPicker.contains("style = MaterialTheme.typography.displaySmall.copy(fontSize = 18.sp, lineHeight = 21.sp)"))
        assertTrue(fullScreenPicker.contains(".width(340.dp)"))
        assertTrue(fullScreenPicker.contains("contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)"))
        assertTrue(fullScreenPicker.contains("verticalArrangement = Arrangement.spacedBy(5.dp)"))
        assertTrue(fullScreenPicker.contains("val shape = RoundedCornerShape(7.dp)"))
        assertTrue(fullScreenPicker.contains(".padding(horizontal = 13.dp, vertical = 8.dp)"))
        assertTrue(fullScreenPicker.contains("fontSize = 17.sp"))
        assertTrue(fullScreenPicker.contains("lineHeight = 20.sp"))
        assertTrue(fullScreenPicker.contains("modifier = Modifier.size(14.dp)"))
        assertFalse(fullScreenPicker.contains(".width(680.dp)"))
        assertFalse(fullScreenPicker.contains(".padding(horizontal = 26.dp, vertical = 18.dp)"))
    }

    @Test
    fun cardOverlaySettingsUseHalfScaleTvOsPreviewAndRows() {
        assertTrue(cardOverlaySettingsScreen.contains("horizontalArrangement = Arrangement.spacedBy(30.dp)"))
        assertTrue(cardOverlaySettingsScreen.contains("modifier = Modifier.width(260.dp)"))
        assertTrue(cardOverlaySettingsScreen.contains("modifier = Modifier.width(210.dp)"))
        assertTrue(cardOverlaySettingsScreen.contains(".clip(RoundedCornerShape(11.dp))"))
        assertTrue(cardOverlaySettingsScreen.contains("private fun Modifier.heightInChips(): Modifier = this.height(150.dp)"))
        assertTrue(cardOverlaySettingsScreen.contains("verticalArrangement = Arrangement.spacedBy(14.dp)"))
        assertTrue(cardOverlaySettingsScreen.contains("contentPadding = PaddingValues(bottom = 20.dp)"))
        assertTrue(cardOverlaySettingsScreen.contains("modifier = Modifier.width(160.dp)"))
        assertTrue(cardOverlaySettingsScreen.contains("width = 110.dp"))
        assertTrue(cardOverlaySettingsScreen.contains("private const val OverlayRowMaxWidthValue = 480"))
        assertFalse(cardOverlaySettingsScreen.contains("modifier = Modifier.width(440.dp)"))
        assertFalse(cardOverlaySettingsScreen.contains("modifier = Modifier.width(360.dp)"))
        assertFalse(cardOverlaySettingsScreen.contains("private const val OverlayRowMaxWidthValue = 960"))
    }

    @Test
    fun loginScreenUsesHalfScaleTvOsPhoneFirstLayout() {
        assertFalse(loginScreen.contains("credentialKeyboardVisible"))
        assertTrue(loginScreen.contains(".imePadding()"))
        assertTrue(loginScreen.contains("top = if (showPasswordForm) 20.dp else 32.dp"))
        assertTrue(loginScreen.contains("bottom = 32.dp"))
        assertTrue(loginScreen.contains("start = 54.dp"))
        assertTrue(loginScreen.contains("end = 54.dp"))
        assertTrue(loginScreen.contains("horizontalArrangement = Arrangement.spacedBy(44.dp)"))
        assertTrue(loginScreen.contains(".widthIn(max = 840.dp)"))
        assertTrue(loginScreen.contains("modifier = Modifier.width(430.dp)"))
        assertTrue(loginScreen.contains("modifier = Modifier.width(300.dp)"))
        assertTrue(loginScreen.contains("modifier = Modifier.width(400.dp)"))
        assertTrue(loginScreen.contains(".padding(24.dp)"))
        assertTrue(loginScreen.contains(".auroraGlass(15.dp)"))
        assertTrue(loginScreen.contains("letterSpacing = 0.sp"))
        assertFalse(loginScreen.contains("letterSpacing = (-0.5).sp"))
    }

    @Test
    fun anchoredSelectorTriggerUsesHalfScaleTvOsMetrics() {
        assertTrue(anchoredSelectorMenu.contains("contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)"))
        assertTrue(anchoredSelectorMenu.contains("modifier = Modifier.size(13.dp)"))
        assertTrue(anchoredSelectorMenu.contains("Spacer(Modifier.width(7.dp))"))
        // tvOS TVSelectorButton ÷ 2 +1 per design review: label 10sp, value 12sp.
        assertTrue(anchoredSelectorMenu.contains("fontSize = 10.sp"))
        assertTrue(anchoredSelectorMenu.contains("fontSize = 12.sp"))
        assertTrue(anchoredSelectorMenu.contains("modifier = Modifier.size(9.5.dp)"))
        assertFalse(anchoredSelectorMenu.contains("contentPadding = PaddingValues(horizontal = 40.dp, vertical = 22.dp)"))
    }

    @Test
    fun detailFactsTableUsesHalfScaleTvOsGridMetrics() {
        assertTrue(detailFactsTable.contains("modifier = modifier.widthIn(max = 700.dp)"))
        assertTrue(detailFactsTable.contains(".padding(vertical = 11.dp)"))
        assertTrue(detailFactsTable.contains("horizontalArrangement = Arrangement.spacedBy(32.dp)"))
        assertTrue(detailFactsTable.contains("fontSize = 13.sp"))
        assertTrue(detailFactsTable.contains("modifier = Modifier.width(130.dp)"))
        assertTrue(detailFactsTable.contains("fontSize = 13.sp"))
        assertFalse(detailFactsTable.contains("widthIn(max = 1400.dp)"))
        assertFalse(detailFactsTable.contains("Modifier.width(260.dp)"))
    }

    @Test
    fun cascadeSelectorIncludesTvOsHeaderFooterAndDefaultFlyoutBehavior() {
        assertTrue(cascadeSelector.contains("val CascadePanelHeaderSize = 11.sp"))
        assertTrue(cascadeSelector.contains("val CascadeFlyoutHeaderSize = 13.sp"))
        assertTrue(cascadeSelector.contains("CascadePanelHeader(type.librariesHeader)"))
        assertTrue(cascadeSelector.contains("CascadePanelFooter(isSingleLibrary = false)"))
        assertTrue(cascadeSelector.contains("CascadeFlyoutHeader(anchorLibrary.name)"))
        assertTrue(cascadeSelector.contains("var flyoutVisible by remember { mutableStateOf(true) }"))
        assertFalse(cascadeSelector.contains("flyoutVisible = false"))
    }

    @Test
    fun anchoredPanelChromeUsesHalfScaleCornersAndLayeredTvOsShadows() {
        assertTrue(panelChrome.contains("fun Modifier.tvSkylinePanelChrome(corner: Dp = 11.dp)"))
        assertTrue(panelChrome.contains("elevation = 4.dp"))
        assertTrue(panelChrome.contains("Color.Black.copy(alpha = 0.35f)"))
        assertTrue(panelChrome.contains("elevation = 20.dp"))
        assertTrue(panelChrome.contains("Color.Black.copy(alpha = 0.55f)"))
        assertTrue(panelChrome.contains(".border(\n            width = 0.5.dp"))
        assertFalse(panelChrome.contains("color = Color.White.copy(alpha = 0.10f)"))
        assertFalse(panelChrome.contains("width = 1.dp"))
    }

    @Test
    fun cascadeSelectorOwnsSeparateColumnChromeLikeTvOs() {
        assertTrue(cascadeSelector.split(".tvSkylinePanelChrome()").size >= 3)
        assertFalse(shell.contains(".let { if (active) it.tvSkylinePanelChrome() else it }"))
    }

    @Test
    fun cascadePanelCentersLevelOneColumnUnderTheTabLikeTvOs() {
        assertTrue(shell.contains("level1WidthPx = with(density) { CascadeLibraryColumnWidth.toPx() }"))
        assertTrue(shell.contains("totalPanelWidthPx = with(density) { TvCascadeSelectorMaxPanelWidth.toPx() }"))
        assertTrue(shell.contains("val anchorCenterX = anchor.positionInRoot().x + anchor.size.width / 2f"))
        assertTrue(shell.contains("val centeredX = anchorCenterX - level1WidthPx / 2f"))
        assertFalse(shell.contains("val anchorLeftX = anchor.positionInRoot().x"))
        assertFalse(shell.contains("val clampedX = anchorLeftX.coerceIn"))
    }

    private fun assertToken(token: String) {
        assertTrue(spacing.contains(token), "Expected Spacing.kt to contain `$token`")
    }
}
