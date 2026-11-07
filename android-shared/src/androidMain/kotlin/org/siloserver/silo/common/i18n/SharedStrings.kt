package org.siloserver.silo.common.i18n

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import org.siloserver.silo.common.R

// The one mapping from the shared module's catalog names to generated
// resources. Static R references keep the strings through release resource
// shrinking; SharedTextTest checks each row against the entry's key.

/** The generated string resource for this key. */
@get:StringRes
internal val SharedString.resId: Int
    get() = when (this) {
        SharedString.NetworkError -> R.string.catalog_error_network
        SharedString.NetworkErrorPleaseCheck -> R.string.catalog_error_networkPleaseCheck
        SharedString.NetworkErrorDetail -> R.string.catalog_error_networkDetail
        SharedString.NetworkErrorUnknown -> R.string.catalog_error_networkUnknown
        SharedString.RequestTimeout -> R.string.catalog_error_timeout
        SharedString.ServerUpdateRequired -> R.string.update_body
        SharedString.PasswordChangeRequired -> R.string.shell_error_temporaryPassword
        SharedString.PasswordChangeLead -> R.string.signIn_passwordChange_lead
        SharedString.PasswordChangeLeadUnnamed -> R.string.signIn_passwordChange_leadUnnamed
        SharedString.PasswordNewRequired -> R.string.signIn_passwordChange_error_newRequired
        SharedString.PasswordTemporaryRequired -> R.string.signIn_passwordChange_error_temporaryRequired
        SharedString.PasswordMismatch -> R.string.signIn_passwordChange_error_mismatch
        SharedString.PasswordTooLong -> R.string.signIn_passwordChange_error_tooLong
        SharedString.PasswordTemporaryIncorrect -> R.string.signIn_passwordChange_error_temporaryIncorrect
        SharedString.PasswordRejected -> R.string.signIn_passwordChange_error_rejected
        SharedString.PasswordRateLimited -> R.string.tvSignIn_error_rateLimited
        SharedString.PasswordNoSiloPassword -> R.string.signIn_passwordChange_error_noSiloPassword
        SharedString.PasswordSessionCannotChange -> R.string.signIn_passwordChange_error_sessionCannotChange
        SharedString.PasswordChangeFailed -> R.string.signIn_passwordChange_error_failed
        SharedString.PasswordNotChanged -> R.string.signIn_passwordChange_error_notChanged
        SharedString.ProfileChooseLibrary -> R.string.profiles_access_error_chooseLibrary
        SharedString.ProfileAnyRating -> R.string.profiles_access_anyRating
        SharedString.ProfileCeilingAny -> R.string.profiles_access_ceilingAny
        SharedString.ProfileCeiling -> R.string.profiles_access_ceiling
        SharedString.ProfileLibrariesFailed -> R.string.newProfile_librariesFailed
        SharedString.ProfileLibrariesFailedReason -> R.string.profiles_access_librariesFailedReason
        SharedString.ProfileVerificationRequired -> R.string.profiles_error_verificationRequired
        SharedString.PinWrong -> R.string.pin_wrong
        SharedString.PinIncorrect -> R.string.pin_error_incorrect
        SharedString.PinNetworkError -> R.string.pin_error_network
        SharedString.HomeLoadFailed -> R.string.home_error_loadFailed
        SharedString.HomeSignedOut -> R.string.home_error_signedOut
        SharedString.CalendarLoadFailed -> R.string.calendar_error_loadFailed
        SharedString.CalendarSignedOut -> R.string.calendar_error_signedOut
        SharedString.BoxSetLoadFailed -> R.string.boxSets_error_loadFailed
        SharedString.CollectionsLoadFailed -> R.string.collections_error_loadCollections
        SharedString.RecommendationsLoadFailed -> R.string.home_forYou_loadFailed
        SharedString.RecommendationsSignedOut -> R.string.home_forYou_signedOut
        SharedString.RowLoadFailed -> R.string.rowPage_loadFailed
        SharedString.RowSignedOut -> R.string.rowPage_signedOut
        SharedString.PersonalListLoadFailed -> R.string.personal_error_loadFailed
        SharedString.HistoryLoadFailed -> R.string.personal_history_error_loadFailed
        SharedString.HistoryExpired -> R.string.personal_history_error_expired
        SharedString.HistoryInvalidPagination -> R.string.personal_history_error_invalidPagination
        SharedString.NotificationsRefreshFailed -> R.string.notifications_error_refresh
        SharedString.NotificationsCatchUpIncomplete -> R.string.notifications_error_catchUpIncomplete
        SharedString.NotificationsCheckpointFailed -> R.string.notifications_error_checkpoint
        SharedString.NotificationsOlderFailed -> R.string.notifications_error_olderFailed
        SharedString.NotificationsReadUnconfirmed -> R.string.notifications_error_readUnconfirmed
        SharedString.NotificationsRefreshFirst -> R.string.notifications_error_refreshFirst
        SharedString.NotificationsMarkAllUnconfirmed -> R.string.notifications_error_markAllUnconfirmed

        // DX5c
        SharedString.QualityAuto -> R.string.settingsManifest_playback_preferred_quality_option_auto
        SharedString.Quality4K -> R.string.options_quality4k
        SharedString.Quality1080p -> R.string.settingsManifest_playback_preferred_quality_option_1080p
        SharedString.Quality720p -> R.string.settingsManifest_playback_preferred_quality_option_720p
        SharedString.Quality480p -> R.string.settingsManifest_playback_preferred_quality_option_480p
        SharedString.QualityPresetHigh -> R.string.settings_quality_presetHigh
        SharedString.QualityPresetLow -> R.string.settings_quality_presetLow
        SharedString.QualityNoLimit -> R.string.settings_noLimit
        SharedString.QualityFooterOriginal -> R.string.settings_quality_footerOriginal
        SharedString.QualityFooterUpTo -> R.string.settings_quality_footerUpTo
        SharedString.QualityFooterBitrate -> R.string.settings_quality_footerBitrate
        SharedString.QualityFooterJoined -> R.string.settings_quality_footerJoined
        SharedString.QualitySummaryUpTo -> R.string.settings_quality_summaryUpTo
        SharedString.QualitySummaryNoLimit -> R.string.settings_quality_summaryNoLimit
        SharedString.QualityCellularFooter -> R.string.settings_quality_cellularFooter
        SharedString.RateKbps -> R.string.settings_rate_kbps
        SharedString.RateMbps -> R.string.settings_rate_mbps
        SharedString.PosterSizeCompact -> R.string.settings_cards_size_compact
        SharedString.PosterSizeStandard -> R.string.settings_cards_size_standard
        SharedString.PosterSizeLarge -> R.string.settings_cards_size_large
        SharedString.LanguageUnset -> R.string.settings_languageUnset
        SharedString.AudioLanguageUnset -> R.string.settingsManifest_playback_audio_language_unsetLabel
        SharedString.SubtitleLanguageUnset -> R.string.settingsManifest_playback_subtitle_language_unsetLabel
        SharedString.MetadataLanguageUnset -> R.string.settingsManifest_catalog_metadata_language_unsetLabel
        SharedString.AppLanguageUnset -> R.string.settingsManifest_ui_app_language_unsetLabel
        SharedString.FilterDecade -> R.string.filters_decade
        SharedString.FilterUnwatched -> R.string.filters_value_unwatched
        SharedString.FilterInProgress -> R.string.filters_value_inProgress
        SharedString.FilterWatched -> R.string.filters_value_watched
        SharedString.FilterFavorited -> R.string.filters_value_favorited
        SharedString.FilterWatchlist -> R.string.filters_value_watchlist
        SharedString.FilterHdr -> R.string.filters_value_hdr
        SharedString.FilterDolbyVision -> R.string.filters_value_dolby_vision
        SharedString.PersonBorn -> R.string.person_born
        SharedString.PersonDied -> R.string.person_died
        SharedString.PersonDiedAge -> R.string.person_diedAge
        SharedString.BoxSetYears -> R.string.boxSets_years
        SharedString.BoxSetContinue -> R.string.boxSets_continue
        SharedString.BoxSetPlay -> R.string.boxSets_play
        SharedString.CollectionsUngrouped -> R.string.collections_ungrouped
        SharedString.EpisodeTag -> R.string.catalog_episodeTag
        SharedString.EpisodeCardTitle -> R.string.detail_episodeCard_title
        SharedString.EpisodeCardMetadata -> R.string.detail_episodeCard_metadata
        SharedString.RuntimeHoursMinutes -> R.string.catalog_runtimeHoursMinutes
        SharedString.RuntimeMinutesShort -> R.string.catalog_runtimeMinutesShort
        SharedString.ExtraTrailer -> R.string.catalog_extra_trailer
        SharedString.ExtraTeaser -> R.string.catalog_extra_teaser
        SharedString.ExtraFeaturette -> R.string.catalog_extra_featurette
        SharedString.ExtraBehindTheScenes -> R.string.catalog_extra_behind_the_scenes
        SharedString.ExtraDeletedScene -> R.string.catalog_extra_deleted_scene
        SharedString.ExtraInterview -> R.string.catalog_extra_interview
        SharedString.ExtraOther -> R.string.catalog_extra_otherExtra
        SharedString.CalendarSeriesPremiere -> R.string.calendar_badge_series_premiere
        SharedString.CalendarSeasonPremiere -> R.string.calendar_badge_season_premiere
        SharedString.CalendarFinale -> R.string.calendar_badge_finale
        SharedString.CalendarDigital -> R.string.calendar_badge_digital
        SharedString.CalendarPhysical -> R.string.calendar_badge_physical
        SharedString.AudiobookChapter -> R.string.detail_audiobook_chapter
        SharedString.AudiobookChapterOf -> R.string.detail_audiobook_chapterOf
        SharedString.BookFormatComicCbz -> R.string.reader_format_comicCbz
        SharedString.BookFormatComicCbr -> R.string.reader_format_comicCbr
        SharedString.BookFormatKindleAzw -> R.string.reader_format_kindleAzw
        SharedString.BookFormatKindleAzw3 -> R.string.reader_format_kindleAzw3
        SharedString.BookFormatPlainText -> R.string.reader_format_plainText
        SharedString.BookFormatUnknown -> R.string.reader_format_unknown
        SharedString.OverlayMultiAudio -> R.string.overlays_multiAudio
        SharedString.OverlaySubtitles -> R.string.overlays_subtitles
        SharedString.OverlayRuntimeHoursMinutes -> R.string.overlays_runtimeHoursMinutes
        SharedString.OverlayRuntimeMinutes -> R.string.overlays_runtimeMinutes
        SharedString.OverlayReturning -> R.string.overlays_returning
        SharedString.OverlayEnded -> R.string.overlays_ended
        SharedString.OverlayCancelled -> R.string.overlays_cancelled
        SharedString.OverlayUpcoming -> R.string.overlays_upcoming
        SharedString.OverlayCertifiedFresh -> R.string.overlays_certifiedFresh
    }

/** The generated plurals resource for this key. */
@get:PluralsRes
internal val SharedPlural.resId: Int
    get() = when (this) {
        SharedPlural.PasswordRequirement -> R.plurals.signIn_passwordChange_requirement
        SharedPlural.PasswordTooShort -> R.plurals.signIn_passwordChange_error_tooShort

        // DX5c
        SharedPlural.SeekIntervalSeconds -> R.plurals.player_seekInterval_choice
        SharedPlural.BoxSetFilms -> R.plurals.boxSets_film
        SharedPlural.BoxSetWatchedCard -> R.plurals.boxSets_watchedCard
        SharedPlural.BoxSetWatchedFact -> R.plurals.boxSets_watchedFact
        SharedPlural.PersonYearsOld -> R.plurals.person_yearsOld
        SharedPlural.PersonTitles -> R.plurals.person_count
        SharedPlural.PersonTitlesMore -> R.plurals.person_countMore
        SharedPlural.PersonTitlesOf -> R.plurals.person_countOf
    }
