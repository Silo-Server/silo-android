package org.siloserver.silo.model.server

/** The profile row of the profile menu, on phone and TV. */
enum class ProfileMenuRow {
    /** "Switch Profile": opens the open account's picker. */
    SwitchProfile,

    /**
     * "Add Profile": the account has one profile and room for another, so
     * there is nothing to switch to.
     */
    AddProfile,

    /**
     * No row: one profile and no room for another, or a session that can
     * neither switch nor add.
     */
    None,
}

/**
 * Which profile row the menu shows for the open account.
 *
 * [profileCount] is the size of the account's cached profile list; zero means
 * no list has been fetched yet, which keeps Switch Profile. [maxProfiles] is
 * the account's profile limit, null when its server did not say; an unknown
 * limit keeps offering a new profile, and the server's create stays the
 * check. A remote-playback handoff session ([isHandoffSession]) lists only
 * its own profile and cannot create one.
 */
fun profileMenuRow(profileCount: Int, maxProfiles: Int?, isHandoffSession: Boolean = false): ProfileMenuRow = when {
    isHandoffSession -> ProfileMenuRow.None
    profileCount != 1 -> ProfileMenuRow.SwitchProfile
    maxProfiles == null || maxProfiles > 1 -> ProfileMenuRow.AddProfile
    else -> ProfileMenuRow.None
}

/** [profileMenuRow] for this account; with no account open, Switch Profile. */
fun AccountEntry?.profileMenuRow(isHandoffSession: Boolean = false): ProfileMenuRow =
    profileMenuRow(this?.cachedProfiles?.size ?: 0, this?.maxProfiles, isHandoffSession)
