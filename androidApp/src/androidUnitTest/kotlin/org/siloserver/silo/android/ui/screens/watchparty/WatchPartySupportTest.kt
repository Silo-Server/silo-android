package org.siloserver.silo.android.ui.screens.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.watchtogether.WatchPartyInvite

class WatchPartySupportTest {

    // ---- Invitation links ---------------------------------------------------

    @Test
    fun `a silo watch-party link parses into its server and token`() {
        val link = watchPartyAppLinkOrNull(
            "silo://watch-party?server=https%3A%2F%2Fmedia.example.com%2Fsilo&token=secret-token",
        )
        assertEquals(WatchPartyInvite.Link("https://media.example.com/silo", "secret-token"), link)
    }

    @Test
    fun `account invitations, web links, codes, and malformed links are not party app links`() {
        assertNull(watchPartyAppLinkOrNull("silo://invite?server=https%3A%2F%2Fmedia.example.com&token=t"))
        assertNull(watchPartyAppLinkOrNull("https://media.example.com/rooms/join?token=t"))
        assertNull(watchPartyAppLinkOrNull("ABCD2345"))
        assertNull(watchPartyAppLinkOrNull("silo://watch-party?server=https%3A%2F%2Fmedia.example.com"))
        assertNull(watchPartyAppLinkOrNull("silo://watch-party?server=https%3A%2F%2Fmedia.example.com&token=a&token=b"))
        assertNull(watchPartyAppLinkOrNull(null))
        assertNull(watchPartyAppLinkOrNull(""))
    }

    @Test
    fun `the handoff id is stable per link and never contains the token`() {
        val link = WatchPartyInvite.Link("https://media.example.com", "secret-token")
        val id = watchPartyInviteHandoffId(link)
        assertEquals(id, watchPartyInviteHandoffId(link.copy()))
        assertFalse(id.contains("secret"))
        assertNotEquals(id, watchPartyInviteHandoffId(link.copy(token = "other-token")))
        assertNotEquals(id, watchPartyInviteHandoffId(link.copy(serverUrl = "https://other.example.com")))
    }

    @Test
    fun `an invitation is taken once, and a newer one replaces an older one`() {
        val handoff = WatchPartyHandoff()
        val first = WatchPartyInvite.Link("https://media.example.com", "first")
        val second = WatchPartyInvite.Link("https://media.example.com", "second")
        val firstId = handoff.offerInvite(first)
        val secondId = handoff.offerInvite(second)

        assertNull(handoff.takeInvite(firstId))
        assertEquals(second, handoff.takeInvite(secondId))
        assertNull(handoff.takeInvite(secondId))
        assertNull(handoff.takeInvite(null))
    }

    @Test
    fun `a host item is taken once and leaves a title preview behind`() {
        val handoff = WatchPartyHandoff()
        val item = WatchPartyItem(contentId = "movie:1", contentType = "movie", title = "Heat")
        val id = handoff.offerHost(item)

        assertEquals(item, handoff.takeHost(id))
        assertNull(handoff.takeHost(id))
        assertEquals(item, handoff.preview("movie:1"))
    }

    // ---- In-playback status -------------------------------------------------

    @Test
    fun `members the room went on without are named once it plays again`() {
        val previous = room(
            RoomPlaybackState.Waiting,
            member("sam", "Sam", syncing = true),
            member("kim", "Kim", syncing = true),
            member("me", "Me", syncing = true, self = true),
        )
        val next = room(
            RoomPlaybackState.Playing,
            member("sam", "Sam", ready = false),
            member("kim", "Kim", ready = true),
            member("me", "Me", ready = false, self = true),
        )
        assertEquals(listOf("Sam"), watchPartyLeftBehindNames(previous, next))
    }

    @Test
    fun `nobody is left behind across a new selection, a pause, or a departure`() {
        val waiting = room(RoomPlaybackState.Waiting, member("sam", "Sam", syncing = true))
        val playingNewRevision = room(RoomPlaybackState.Playing, member("sam", "Sam"), revision = 2)
        val paused = room(RoomPlaybackState.Paused, member("sam", "Sam"))
        val samLeft = room(RoomPlaybackState.Playing)
        val stillWaiting = room(RoomPlaybackState.Waiting, member("sam", "Sam", syncing = true))

        assertEquals(emptyList(), watchPartyLeftBehindNames(waiting, playingNewRevision))
        assertEquals(emptyList(), watchPartyLeftBehindNames(waiting, paused))
        assertEquals(emptyList(), watchPartyLeftBehindNames(waiting, samLeft))
        assertEquals(emptyList(), watchPartyLeftBehindNames(waiting, stillWaiting))
        assertEquals(emptyList(), watchPartyLeftBehindNames(null, waiting))
    }

    @Test
    fun `the waiting status names the other members still syncing`() {
        val waiting = room(
            RoomPlaybackState.Waiting,
            member("sam", "Sam", syncing = true),
            member("kim", "", syncing = true),
            member("lee", "Lee", ready = true),
            member("me", "Me", syncing = true, self = true),
        )
        assertEquals(listOf("Sam", "Someone"), watchPartyWaitingNames(waiting))
        assertEquals(emptyList(), watchPartyWaitingNames(room(RoomPlaybackState.Playing, member("sam", "Sam", syncing = true))))
    }

    @Test
    fun `names read naturally`() {
        assertEquals("", formatWatchPartyNames(emptyList()))
        assertEquals("Sam", formatWatchPartyNames(listOf("Sam")))
        assertEquals("Sam and Kim", formatWatchPartyNames(listOf("Sam", "Kim")))
        assertEquals("Sam, Kim, and Lee", formatWatchPartyNames(listOf("Sam", "Kim", "Lee")))
        assertEquals("Sam, Kim, and 3 others", formatWatchPartyNames(listOf("Sam", "Kim", "Lee", "Ann", "Bo")))
    }

    private fun room(
        state: RoomPlaybackState,
        vararg members: RoomMember,
        revision: Long = 1L,
    ) = RoomSnapshot(
        roomId = "room-1",
        phase = RoomPhase.Playing,
        playbackState = state,
        selectionRevision = revision,
        members = members.toList(),
    )

    private fun member(
        id: String,
        name: String,
        syncing: Boolean = false,
        ready: Boolean = false,
        self: Boolean = false,
    ) = RoomMember(
        userId = "user-$id",
        profileId = "profile-$id",
        displayName = name,
        isSelf = self,
        connected = true,
        isReady = ready,
        isSyncing = syncing,
    )
}
