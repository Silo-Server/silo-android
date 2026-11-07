package org.siloserver.silo.common.i18n

/**
 * The catalog strings the shared module names, by catalog [key]. android-shared
 * maps each entry to its generated resource once (`SharedStrings.kt`), and a
 * unit test checks that every entry's resource is the one its [key] generates.
 *
 * To add one: add the key to the catalog (or reuse one, widening its
 * `x-platforms`), regenerate, add the entry here and its row in the mapping.
 * Arguments go in the order the key declares its placeholders.
 */
enum class SharedString(val key: String) {
    // Requests the server didn't answer.
    NetworkError("catalog.error.network"),
    NetworkErrorPleaseCheck("catalog.error.networkPleaseCheck"),
    NetworkErrorDetail("catalog.error.networkDetail"),
    NetworkErrorUnknown("catalog.error.networkUnknown"),
    RequestTimeout("catalog.error.timeout"),
    ServerUpdateRequired("update.body"),

    // Choose a new password (temporary passwords).
    PasswordChangeRequired("shell.error.temporaryPassword"),
    PasswordChangeLead("signIn.passwordChange.lead"),
    PasswordChangeLeadUnnamed("signIn.passwordChange.leadUnnamed"),
    PasswordNewRequired("signIn.passwordChange.error.newRequired"),
    PasswordTemporaryRequired("signIn.passwordChange.error.temporaryRequired"),
    PasswordMismatch("signIn.passwordChange.error.mismatch"),
    PasswordTooLong("signIn.passwordChange.error.tooLong"),
    PasswordTemporaryIncorrect("signIn.passwordChange.error.temporaryIncorrect"),
    PasswordRejected("signIn.passwordChange.error.rejected"),
    PasswordRateLimited("tvSignIn.error.rateLimited"),
    PasswordNoSiloPassword("signIn.passwordChange.error.noSiloPassword"),
    PasswordSessionCannotChange("signIn.passwordChange.error.sessionCannotChange"),
    PasswordChangeFailed("signIn.passwordChange.error.failed"),
    PasswordNotChanged("signIn.passwordChange.error.notChanged"),

    // Profile forms and the household PIN.
    ProfileChooseLibrary("profiles.access.error.chooseLibrary"),
    ProfileAnyRating("profiles.access.anyRating"),
    ProfileCeilingAny("profiles.access.ceilingAny"),
    ProfileCeiling("profiles.access.ceiling"),
    ProfileLibrariesFailed("newProfile.librariesFailed"),
    ProfileLibrariesFailedReason("profiles.access.librariesFailedReason"),
    ProfileVerificationRequired("profiles.error.verificationRequired"),
    PinWrong("pin.wrong"),
    PinIncorrect("pin.error.incorrect"),
    PinNetworkError("pin.error.network"),

    // Load errors.
    HomeLoadFailed("home.error.loadFailed"),
    HomeSignedOut("home.error.signedOut"),
    CalendarLoadFailed("calendar.error.loadFailed"),
    CalendarSignedOut("calendar.error.signedOut"),
    BoxSetLoadFailed("boxSets.error.loadFailed"),
    CollectionsLoadFailed("collections.error.loadCollections"),
    RecommendationsLoadFailed("home.forYou.loadFailed"),
    RecommendationsSignedOut("home.forYou.signedOut"),
    RowLoadFailed("rowPage.loadFailed"),
    RowSignedOut("rowPage.signedOut"),
    PersonalListLoadFailed("personal.error.loadFailed"),
    HistoryLoadFailed("personal.history.error.loadFailed"),
    HistoryExpired("personal.history.error.expired"),
    HistoryInvalidPagination("personal.history.error.invalidPagination"),

    // Notifications inbox notices.
    NotificationsRefreshFailed("notifications.error.refresh"),
    NotificationsCatchUpIncomplete("notifications.error.catchUpIncomplete"),
    NotificationsCheckpointFailed("notifications.error.checkpoint"),
    NotificationsOlderFailed("notifications.error.olderFailed"),
    NotificationsReadUnconfirmed("notifications.error.readUnconfirmed"),
    NotificationsRefreshFirst("notifications.error.refreshFirst"),
    NotificationsMarkAllUnconfirmed("notifications.error.markAllUnconfirmed"),

    // DX5c
    // Settings: quality, skip intervals, cards, languages.
    QualityAuto("settingsManifest.playback.preferred_quality.option.auto"),
    Quality4K("options.quality4k"),
    Quality1080p("settingsManifest.playback.preferred_quality.option.1080p"),
    Quality720p("settingsManifest.playback.preferred_quality.option.720p"),
    Quality480p("settingsManifest.playback.preferred_quality.option.480p"),
    QualityPresetHigh("settings.quality.presetHigh"),
    QualityPresetLow("settings.quality.presetLow"),
    QualityNoLimit("settings.noLimit"),
    QualityFooterOriginal("settings.quality.footerOriginal"),
    QualityFooterUpTo("settings.quality.footerUpTo"),
    QualityFooterBitrate("settings.quality.footerBitrate"),
    QualityFooterJoined("settings.quality.footerJoined"),
    QualitySummaryUpTo("settings.quality.summaryUpTo"),
    QualitySummaryNoLimit("settings.quality.summaryNoLimit"),
    QualityCellularFooter("settings.quality.cellularFooter"),
    RateKbps("settings.rate.kbps"),
    RateMbps("settings.rate.mbps"),
    PosterSizeCompact("settings.cards.size.compact"),
    PosterSizeStandard("settings.cards.size.standard"),
    PosterSizeLarge("settings.cards.size.large"),
    LanguageUnset("settings.languageUnset"),
    AudioLanguageUnset("settingsManifest.playback.audio_language.unsetLabel"),
    SubtitleLanguageUnset("settingsManifest.playback.subtitle_language.unsetLabel"),
    MetadataLanguageUnset("settingsManifest.catalog.metadata_language.unsetLabel"),
    AppLanguageUnset("settingsManifest.ui.app_language.unsetLabel"),

    // Library filters.
    FilterDecade("filters.decade"),
    FilterUnwatched("filters.value.unwatched"),
    FilterInProgress("filters.value.inProgress"),
    FilterWatched("filters.value.watched"),
    FilterFavorited("filters.value.favorited"),
    FilterWatchlist("filters.value.watchlist"),
    FilterHdr("filters.value.hdr"),
    FilterDolbyVision("filters.value.dolby_vision"),

    // Catalog presentation.
    PersonBorn("person.born"),
    PersonDied("person.died"),
    PersonDiedAge("person.diedAge"),
    BoxSetYears("boxSets.years"),
    BoxSetContinue("boxSets.continue"),
    BoxSetPlay("boxSets.play"),
    CollectionsUngrouped("collections.ungrouped"),
    EpisodeTag("catalog.episodeTag"),
    EpisodeCardTitle("detail.episodeCard.title"),
    EpisodeCardMetadata("detail.episodeCard.metadata"),
    RuntimeHoursMinutes("catalog.runtimeHoursMinutes"),
    RuntimeMinutesShort("catalog.runtimeMinutesShort"),
    ExtraTrailer("catalog.extra.trailer"),
    ExtraTeaser("catalog.extra.teaser"),
    ExtraFeaturette("catalog.extra.featurette"),
    ExtraBehindTheScenes("catalog.extra.behind_the_scenes"),
    ExtraDeletedScene("catalog.extra.deleted_scene"),
    ExtraInterview("catalog.extra.interview"),
    ExtraOther("catalog.extra.otherExtra"),
    CalendarSeriesPremiere("calendar.badge.series_premiere"),
    CalendarSeasonPremiere("calendar.badge.season_premiere"),
    CalendarFinale("calendar.badge.finale"),
    CalendarDigital("calendar.badge.digital"),
    CalendarPhysical("calendar.badge.physical"),
    AudiobookChapter("detail.audiobook.chapter"),
    AudiobookChapterOf("detail.audiobook.chapterOf"),
    BookFormatComicCbz("reader.format.comicCbz"),
    BookFormatComicCbr("reader.format.comicCbr"),
    BookFormatKindleAzw("reader.format.kindleAzw"),
    BookFormatKindleAzw3("reader.format.kindleAzw3"),
    BookFormatPlainText("reader.format.plainText"),
    BookFormatUnknown("reader.format.unknown"),

    // Card overlay badges.
    OverlayMultiAudio("overlays.multiAudio"),
    OverlaySubtitles("overlays.subtitles"),
    OverlayRuntimeHoursMinutes("overlays.runtimeHoursMinutes"),
    OverlayRuntimeMinutes("overlays.runtimeMinutes"),
    OverlayReturning("overlays.returning"),
    OverlayEnded("overlays.ended"),
    OverlayCancelled("overlays.cancelled"),
    OverlayUpcoming("overlays.upcoming"),
    OverlayCertifiedFresh("overlays.certifiedFresh"),
}

/**
 * The catalog plurals the shared module names. [UiText.Plural] fills the
 * message's `{count}`, then its other placeholders from its args in order.
 * [countIndex] is `{count}`'s position among the placeholders when the key
 * declares another one before it (`{played} / # watched`).
 */
enum class SharedPlural(val key: String, val countIndex: Int = 0) {
    PasswordRequirement("signIn.passwordChange.requirement"),
    PasswordTooShort("signIn.passwordChange.error.tooShort"),

    // DX5c
    SeekIntervalSeconds("player.seekInterval.choice"),
    BoxSetFilms("boxSets.film"),
    BoxSetWatchedCard("boxSets.watchedCard", countIndex = 1),
    BoxSetWatchedFact("boxSets.watchedFact", countIndex = 1),
    PersonYearsOld("person.yearsOld"),
    PersonTitles("person.count"),
    PersonTitlesMore("person.countMore"),
    PersonTitlesOf("person.countOf"),
}
