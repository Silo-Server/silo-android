package org.siloserver.silo.android.cast

import org.siloserver.silo.cast.SiloCastCloseReason
import org.siloserver.silo.cast.SiloCastLaunchRequest
import org.siloserver.silo.cast.SiloCastNowPlayingSummary
import org.siloserver.silo.common.cast.SiloCastTarget

/** What the phone does when the TV closes its session (silo-apple `SiloControlCloseResponse`). */
sealed interface SiloCastCloseResponse {
    /** Connect again quietly, as after a dropped link. */
    data object Reconnect : SiloCastCloseResponse

    /** End the session. With [forgetTarget], no later foreground probe silently reattaches to that TV. */
    data class LetGo(val forgetTarget: Boolean) : SiloCastCloseResponse
}

object SiloCastClosePolicy {
    /**
     * A TV without `multi_controller` closes only to hand the slot to another
     * phone or because someone pressed Disconnect, so the phone lets go and
     * forgets it, as it always has. A TV that keeps several controllers says
     * why: a deliberate disconnect (or no reason) still forgets the TV, a
     * timeout reconnects, and the rest end the session but let a later
     * foreground find the TV again.
     */
    fun response(reason: String?, tvKeepsOtherControllers: Boolean): SiloCastCloseResponse {
        if (!tvKeepsOtherControllers) return SiloCastCloseResponse.LetGo(forgetTarget = true)
        return when (reason) {
            SiloCastCloseReason.Timeout -> SiloCastCloseResponse.Reconnect
            null, SiloCastCloseReason.TvDisconnected -> SiloCastCloseResponse.LetGo(forgetTarget = true)
            else -> SiloCastCloseResponse.LetGo(forgetTarget = false)
        }
    }
}

/**
 * The user's agreement to replace one particular title on the TV, carried from
 * the prompt to the offer's `replacingContentId`.
 */
data class SiloCastReplaceConsent(
    /** The title the user saw when agreeing; null when the TV didn't say. */
    val contentId: String?,
)

/**
 * A launch waiting for the user to agree to replace what the TV plays. Asked
 * either by the phone before sending a different title to an engaged TV, or by
 * the TV when another profile's title is on screen.
 */
data class SiloCastPendingReplace(
    val target: SiloCastTarget,
    val request: SiloCastLaunchRequest,
    val message: String,
    val confirmLabel: String,
    /** Consent sent with the launch once the user agrees. */
    val consent: SiloCastReplaceConsent,
)

object SiloCastReplacePrompt {
    /** The TV refused the handoff because another profile's title is on screen. */
    fun fromTv(
        target: SiloCastTarget,
        request: SiloCastLaunchRequest,
        nowPlaying: SiloCastNowPlayingSummary?,
        fallbackMessage: String?,
    ): SiloCastPendingReplace = SiloCastPendingReplace(
        target = target,
        request = request,
        message = tvMessage(nowPlaying, target.name, fallbackMessage),
        confirmLabel = "Replace",
        consent = SiloCastReplaceConsent(contentId = nowPlaying?.contentId),
    )

    /** The phone is about to send a different title to a TV that is playing one. */
    fun beforeSending(
        target: SiloCastTarget,
        request: SiloCastLaunchRequest,
        currentContentId: String,
        currentTitle: String,
    ): SiloCastPendingReplace = SiloCastPendingReplace(
        target = target,
        request = request,
        message = "${target.name} is playing $currentTitle. Playing this will stop it.",
        confirmLabel = "Play on ${target.name}",
        consent = SiloCastReplaceConsent(contentId = currentContentId),
    )

    /** Whether sending [contentId] would replace a different title the TV is playing. */
    fun replacesPlayingTitle(playingContentId: String?, contentId: String): Boolean =
        !playingContentId.isNullOrEmpty() && playingContentId != contentId

    /** The same sentence silo-apple builds from the TV's summary. */
    fun tvMessage(nowPlaying: SiloCastNowPlayingSummary?, tvName: String, fallbackMessage: String?): String {
        if (nowPlaying == null) return fallbackMessage ?: "Someone is watching something on $tvName. Play yours instead?"
        val who = nowPlaying.profileName ?: "Someone"
        val what = listOfNotNull(nowPlaying.subtitle, nowPlaying.title)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        return "$who is watching $what on $tvName. Play yours instead?"
    }
}

/**
 * Resume tokens the TVs issued this phone, held in memory only. A token lets a
 * later connection to the same TV reuse the profile it already holds for this
 * phone without a new approval, as after a Wi-Fi change.
 */
class SiloCastResumeTokens(
    private val serverIdsMatch: (String?, String?) -> Boolean,
) {
    private data class Entry(val serverId: String, val profileId: String, val token: String)

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun token(tvId: String, serverId: String, profileId: String): String? {
        val entry = entries[tvId] ?: return null
        if (!serverIdsMatch(entry.serverId, serverId) || entry.profileId != profileId) return null
        return entry.token
    }

    @Synchronized
    fun store(token: String?, tvId: String, serverId: String, profileId: String) {
        if (token.isNullOrEmpty()) return
        entries[tvId] = Entry(serverId, profileId, token)
    }
}
