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
        SharedString.PasswordChangeRequired -> R.string.signIn_passwordChange_error_required
        SharedString.PasswordChangeLead -> R.string.web_auth_choosePassword_description
        SharedString.PasswordChangeLeadAccount -> R.string.signIn_passwordChange_leadAccount
        SharedString.PasswordEmptyNew -> R.string.signIn_passwordChange_error_emptyNew
        SharedString.PasswordEmptyTemporary -> R.string.signIn_passwordChange_error_emptyTemporary
        SharedString.PasswordMismatch -> R.string.signIn_passwordChange_error_mismatch
        SharedString.PasswordTooLong -> R.string.signIn_passwordChange_error_tooLong
        SharedString.PasswordWrongTemporary -> R.string.signIn_passwordChange_error_wrongTemporary
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
    }

/** The generated plurals resource for this key. */
@get:PluralsRes
internal val SharedPlural.resId: Int
    get() = when (this) {
        SharedPlural.PasswordRequirement -> R.plurals.signIn_passwordChange_requirement
        SharedPlural.PasswordTooShort -> R.plurals.signIn_passwordChange_error_tooShort
    }
