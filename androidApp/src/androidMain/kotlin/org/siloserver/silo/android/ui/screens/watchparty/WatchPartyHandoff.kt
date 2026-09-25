package org.siloserver.silo.android.ui.screens.watchparty

import java.util.UUID
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.watchtogether.WatchPartyInvite

/**
 * Process-memory handoffs into the Watch Party hub, keyed by opaque ids that
 * routes may carry:
 *  - an invitation link, whose join token is a secret and must never reach a
 *    route string, saved state, or logs;
 *  - the item a detail page asked to host with.
 *
 * Each slot holds one entry; a newer offer replaces the older one, and a take
 * consumes it, so recreation or a restored route never replays it. After
 * process death the slots are empty and the hub simply opens.
 *
 * It also remembers title previews for staged items so the lobby can lay out
 * a staged title before any catalog read.
 */
class WatchPartyHandoff {
    private val lock = Any()
    private var invite: Pair<String, WatchPartyInvite.Link>? = null
    private var host: Pair<String, WatchPartyItem>? = null
    private val previews = LinkedHashMap<String, WatchPartyItem>()

    fun offerInvite(link: WatchPartyInvite.Link): String = synchronized(lock) {
        val id = watchPartyInviteHandoffId(link)
        invite = id to link
        id
    }

    fun takeInvite(id: String?): WatchPartyInvite.Link? = synchronized(lock) {
        val pending = invite?.takeIf { id != null && it.first == id } ?: return@synchronized null
        invite = null
        pending.second
    }

    fun offerHost(item: WatchPartyItem): String = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        host = id to item
        rememberPreviewLocked(item)
        id
    }

    fun takeHost(id: String?): WatchPartyItem? = synchronized(lock) {
        val pending = host?.takeIf { id != null && it.first == id } ?: return@synchronized null
        host = null
        pending.second
    }

    fun rememberPreview(item: WatchPartyItem) = synchronized(lock) { rememberPreviewLocked(item) }

    fun preview(contentId: String?): WatchPartyItem? = synchronized(lock) {
        contentId?.let { previews[it] }
    }

    private fun rememberPreviewLocked(item: WatchPartyItem) {
        previews.remove(item.contentId)
        previews[item.contentId] = item
        while (previews.size > MAX_PREVIEWS) previews.remove(previews.keys.first())
    }

    private companion object {
        const val MAX_PREVIEWS = 24
    }
}

/** The hub route that joins [link] once handed over. It carries only an opaque id, never the token. */
internal fun watchPartyInviteRoute(link: WatchPartyInvite.Link): String =
    org.siloserver.silo.android.ui.navigation.Route.WatchPartyHub(invite = watchPartyInviteHandoffId(link)).route
