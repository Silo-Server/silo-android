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
}

/**
 * The catalog plurals the shared module names. [UiText.Plural] fills the
 * message's `{count}`, then its other placeholders from its args in order.
 */
enum class SharedPlural(val key: String) {
    PasswordRequirement("signIn.passwordChange.requirement"),
    PasswordTooShort("signIn.passwordChange.error.tooShort"),
}
