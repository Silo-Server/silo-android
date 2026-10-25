package org.siloserver.silo.cast

object SiloCastProtocol {
    const val version: Int = 2
    val supportedVersions: List<Int> = listOf(version)
    const val serviceType: String = "_silocast._tcp"

    /**
     * The Roku receiver's own DNS-SD type (silo-roku `docs/silocontrol.md`
     * section 5.1). Roku sockets have no TLS, so a Roku is dialed over plain
     * TCP or `sealed`, never TLS-PSK; the separate type keeps phones that
     * only speak TLS from listing a TV they cannot reach.
     */
    const val rokuServiceType: String = "_silocast-roku._tcp"

    fun negotiatedVersion(peerVersions: List<Int>): Int? =
        supportedVersions.filter(peerVersions::contains).maxOrNull()
}

/**
 * `hello.features` names, matching silo-apple's `SiloControlFeature`. A peer
 * sends a new field, payload, reason or message type only when the other side
 * listed the matching feature, so nothing reaches a build that would misread
 * it. Peers ignore names they don't know.
 */
object SiloCastFeature {
    /** The receiver keeps other controllers connected; a `close` from it concerns this controller alone. */
    const val MultiController = "multi_controller"

    /** `close` may carry a reason ([SiloCastCloseReason]). */
    const val CloseReason = "close_reason"

    /** The receiver issues `resumeToken` in `handoff_ready`; the controller sends it back to reuse that identity. */
    const val ResumeToken = "resume_token"

    /**
     * The receiver refuses a handoff that would replace another profile's title
     * with reason `replace_confirmation_required`, and the controller resends
     * with `replaceConfirmed` after asking.
     */
    const val ReplaceConfirmation = "replace_confirmation"

    /** The receiver accepts `presence`, so a controller can change its `passive` flag without reconnecting. */
    const val Presence = "presence"

    /**
     * The receiver can only mute, not set a level (Roku). The remote shows a
     * mute toggle instead of a slider and leaves hardware volume keys alone.
     * Android TV never lists it.
     */
    const val VolumeMuteOnly = "volume_mute_only"

    /** The receiver cannot change playback speed (Roku); the remote hides the speed control. */
    const val NoPlaybackSpeed = "no_playback_speed"

    val receiverFeatures: List<String> = listOf(MultiController, CloseReason, ResumeToken, ReplaceConfirmation, Presence)
    val phoneFeatures: List<String> = listOf(CloseReason, ResumeToken, ReplaceConfirmation)
}

/** Why a peer closed the session. Sent only to peers that list [SiloCastFeature.CloseReason]. */
object SiloCastCloseReason {
    /** A newer connection from the same device took this one's place. */
    const val Replaced = "replaced"

    /** Someone disconnected remote control on the TV. */
    const val TvDisconnected = "tv_disconnected"

    /** The receiver is at its controller limit. */
    const val TooManyControllers = "too_many_controllers"

    /** The peer never sent hello, or stopped answering pings. */
    const val Timeout = "timeout"

    /** The peer can't be authorized (version or server mismatch). */
    const val Unauthorized = "unauthorized"

    /** A borrowed profile was never used and has been handed back. */
    const val LaunchTimeout = "launch_timeout"
}

/** `handoff_cancel.reason` values both platforms send. */
object SiloCastHandoffCancelReason {
    /** A newer handoff from another controller replaced this one. */
    const val Superseded = "superseded"

    /** The handoff would replace a title another profile started; resend with `replaceConfirmed` once the user agrees. */
    const val ReplaceConfirmationRequired = "replace_confirmation_required"
    const val WatchPartyActive = "watch_party_active"
    const val VersionUnsupported = "version_unsupported"
    const val LaunchPending = "launch_pending"
    const val HandoffFailed = "handoff_failed"
    const val ControllerCancelled = "controller_cancelled"
}

/** Values for `SiloCastHello.clientKind`. Absent means a Silo app. */
object SiloCastClientKind {
    /** A home-automation or other third-party integration. */
    const val Integration = "integration"
}
