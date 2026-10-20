package org.siloserver.silo.tv.cast

import java.security.SecureRandom

/**
 * Pure decisions for a receiver that serves several controllers at once,
 * ported from silo-apple's `SiloControlReceiverPolicy` so both TVs agree.
 * [TvSiloCastReceiver] applies them.
 */
internal object TvSiloCastReceiverPolicy {
    /** Most controllers one TV keeps connected. */
    const val MAX_CONTROLLERS = 8

    /** What the admission rules need to know about a connected controller. */
    data class ControllerSummary(
        val id: String,
        /** Order of acceptance; lower is older. */
        val sequence: Long,
        val didReceiveHello: Boolean,
        val passive: Boolean,
        val isAuthorized: Boolean = true,
        val isHandingOff: Boolean = false,
    )

    sealed interface Admission {
        data object Admit : Admission

        /** Admit after closing this controller to make room. */
        data class AdmitEvicting(val id: String) : Admission

        data object Refuse : Admission
    }

    /**
     * Whether a new connection fits. At the cap, a connection that never said
     * hello goes first (likely half-open or a port scan), then a cross-server
     * controller that isn't handing off (it can do nothing else, and nothing
     * else times it out), then the oldest passive controller. A controller
     * mid-handoff and an active one are never evicted by a stranger; the
     * newcomer is refused instead.
     */
    fun admission(existing: List<ControllerSummary>): Admission {
        if (existing.size < MAX_CONTROLLERS) return Admission.Admit
        val byAge = existing.sortedBy { it.sequence }
        byAge.firstOrNull { !it.didReceiveHello }?.let { return Admission.AdmitEvicting(it.id) }
        byAge.firstOrNull { !it.isAuthorized && !it.isHandingOff }?.let { return Admission.AdmitEvicting(it.id) }
        byAge.firstOrNull { it.passive && !it.isHandingOff }?.let { return Admission.AdmitEvicting(it.id) }
        return Admission.Refuse
    }

    /**
     * Other connections from the device that just said hello on [connection].
     * They are older sockets the device has abandoned (a Wi-Fi change, a
     * return from background) whose heartbeat hasn't noticed yet.
     */
    fun connectionsReplaced(
        deviceId: String,
        connection: String,
        existing: List<Pair<String, String?>>,
    ): List<String> {
        if (deviceId.isEmpty()) return emptyList()
        return existing.filter { (id, existingDeviceId) -> id != connection && existingDeviceId == deviceId }
            .map { it.first }
    }

    /**
     * A launch plays under the borrowed identity only for the controller whose
     * handoff produced the generation now active. Another controller's later
     * handoff replaces the identity and with it every earlier readiness.
     */
    fun canLaunchBorrowed(readyGeneration: String?, activeGeneration: String?): Boolean =
        readyGeneration != null && activeGeneration != null && readyGeneration == activeGeneration

    /**
     * Whether an offer that matches the active identity (same server, profile
     * and device ID) may reuse it without a new approval. Device IDs are
     * self-reported, so the match alone proves nothing: reuse needs the
     * connection that completed the handoff, or the resume token issued to it.
     */
    fun mayReuseIdentity(
        offerMatchesIdentity: Boolean,
        identityConnection: String?,
        connection: String,
        issuedResumeToken: String?,
        offeredResumeToken: String?,
    ): Boolean {
        if (!offerMatchesIdentity) return false
        if (identityConnection == connection) return true
        if (issuedResumeToken == null || offeredResumeToken == null) return false
        return constantTimeEquals(issuedResumeToken, offeredResumeToken)
    }

    /**
     * Whether a handoff must stop and ask before replacing what the TV shows.
     * Only a title playing under another profile needs consent. Controllers
     * without `replace_confirmation` can't ask, so they keep newest-launch-wins.
     */
    fun needsReplaceConfirmation(
        titleOnScreen: Boolean,
        playingUnderOfferedProfile: Boolean,
        replaceConfirmed: Boolean,
        controllerCanConfirm: Boolean,
    ): Boolean = titleOnScreen && !playingUnderOfferedProfile && !replaceConfirmed && controllerCanConfirm

    /** A confirmation covers only the title the user agreed to replace; if another started, the TV asks again. */
    fun confirmationCovers(onScreenContentId: String?, replaceConfirmed: Boolean, replacingContentId: String?): Boolean =
        replaceConfirmed && replacingContentId == onScreenContentId

    /** What a controller's authorization becomes after the TV's identity changed. */
    data class Reauthorization(
        val isAuthorized: Boolean,
        /** The controller's readiness is gone (another identity is active, or none). */
        val clearsReadiness: Boolean,
        /** Tell it "this TV now plays from a different server". */
        val notifyServerMismatch: Boolean,
    )

    /**
     * Re-judges one controller against the identity now active. A controller
     * stays authorized while its server matches the TV's effective one, or
     * while the borrowed identity it prepared is active. One that loses it is
     * told so, except when its own borrowed identity simply ended.
     */
    fun reauthorize(
        wasAuthorized: Boolean,
        matchesEffectiveServer: Boolean,
        readyGeneration: String?,
        activeGeneration: String?,
    ): Reauthorization {
        val stillReady = readyGeneration != null && readyGeneration == activeGeneration
        val ownIdentityEnded = readyGeneration != null && !stillReady
        val authorized = matchesEffectiveServer || stillReady
        return Reauthorization(
            isAuthorized = authorized,
            clearsReadiness = readyGeneration != null && !stillReady,
            notifyServerMismatch = wasAuthorized && !authorized && !ownIdentityEnded,
        )
    }

    /** Who the standby screen names: the newest waiting controller, and how many others wait. */
    data class StandbyPick(val controllerId: String, val otherControllerCount: Int)

    /** The standby screen shows for authorized, non-passive controllers only. */
    fun standbyPick(waiting: List<ControllerSummary>): StandbyPick? {
        val eligible = waiting.filter { it.isAuthorized && !it.passive }
        val newest = eligible.maxByOrNull { it.sequence } ?: return null
        return StandbyPick(newest.id, eligible.size - 1)
    }

    fun makeResumeToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(lhs: String, rhs: String): Boolean {
        val a = lhs.encodeToByteArray()
        val b = rhs.encodeToByteArray()
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /**
     * Per-controller command budget: a token bucket, so a stuck integration
     * loop can't wedge the player while bursts of hardware volume steps still
     * go through.
     */
    class CommandBudget {
        private var tokens = BURST
        private var refilledAtMs: Long? = null

        fun allow(nowMs: Long): Boolean {
            refilledAtMs?.let { last ->
                val elapsedSeconds = (nowMs - last).coerceAtLeast(0) / 1_000.0
                tokens = (tokens + elapsedSeconds * PER_SECOND).coerceAtMost(BURST)
            }
            refilledAtMs = nowMs
            if (tokens < 1) return false
            tokens -= 1
            return true
        }

        companion object {
            const val PER_SECOND = 20.0
            const val BURST = 40.0
        }
    }
}
