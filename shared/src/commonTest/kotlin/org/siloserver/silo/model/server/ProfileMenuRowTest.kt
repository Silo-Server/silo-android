package org.siloserver.silo.model.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileMenuRowTest {

    @Test
    fun `the profile row follows the profile count and the account's limit`() {
        data class Case(
            val profiles: Int,
            val limit: Int?,
            val handoff: Boolean = false,
            val expected: ProfileMenuRow,
        )
        listOf(
            // Several profiles: there is someone to switch to, at the limit or not.
            Case(profiles = 2, limit = 5, expected = ProfileMenuRow.SwitchProfile),
            Case(profiles = 2, limit = 2, expected = ProfileMenuRow.SwitchProfile),
            Case(profiles = 3, limit = null, expected = ProfileMenuRow.SwitchProfile),
            // One profile with room for another, or a limit the server did not send.
            Case(profiles = 1, limit = 5, expected = ProfileMenuRow.AddProfile),
            Case(profiles = 1, limit = null, expected = ProfileMenuRow.AddProfile),
            // One profile and no room: nothing to switch to and nothing to add.
            Case(profiles = 1, limit = 1, expected = ProfileMenuRow.None),
            // No list fetched yet keeps Switch Profile.
            Case(profiles = 0, limit = null, expected = ProfileMenuRow.SwitchProfile),
            Case(profiles = 0, limit = 1, expected = ProfileMenuRow.SwitchProfile),
            // A remote-playback handoff session can neither switch nor add.
            Case(profiles = 1, limit = null, handoff = true, expected = ProfileMenuRow.None),
            Case(profiles = 2, limit = 5, handoff = true, expected = ProfileMenuRow.None),
        ).forEach { case ->
            assertEquals(
                case.expected,
                profileMenuRow(case.profiles, case.limit, case.handoff),
                case.toString(),
            )
        }
    }

    @Test
    fun `the row reads the open account's cached list and limit`() {
        val one = CachedProfile(id = "p1", name = "Jordan", isPrimary = true)
        val account = AccountEntry(localAccountId = "a", serverId = "s", cachedProfiles = listOf(one))
        assertEquals(ProfileMenuRow.AddProfile, account.profileMenuRow())
        assertEquals(ProfileMenuRow.None, account.copy(maxProfiles = 1).profileMenuRow())
        val two = account.copy(cachedProfiles = listOf(one, CachedProfile(id = "p2", name = "Sam")), maxProfiles = 2)
        assertEquals(ProfileMenuRow.SwitchProfile, two.profileMenuRow())
        // No account open: today's row.
        assertEquals(ProfileMenuRow.SwitchProfile, (null as AccountEntry?).profileMenuRow())
    }
}
