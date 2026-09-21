package org.siloserver.silo.common.player.video

/**
 * Owns one in-place Next Up handoff until the successor's exact media mount
 * renders a frame. A callback from the outgoing item, or from a superseded
 * replacement mount, can never complete the transition.
 */
class NextUpTransitionGate {
    private data class Transition(
        val contentId: String,
        val expectedMountToken: Long? = null,
    )

    private var transition: Transition? = null

    val isActive: Boolean
        get() = synchronized(this) { transition != null }

    @Synchronized
    fun begin(contentId: String): Boolean {
        if (contentId.isBlank() || transition != null) return false
        transition = Transition(contentId = contentId)
        return true
    }

    @Synchronized
    fun expectMount(contentId: String, mountToken: Long): Boolean {
        val current = transition ?: return false
        if (current.contentId != contentId) return false
        transition = current.copy(expectedMountToken = mountToken)
        return true
    }

    @Synchronized
    fun completeOnFirstFrame(mountToken: Long?): Boolean {
        val current = transition ?: return false
        if (mountToken == null || current.expectedMountToken != mountToken) return false
        transition = null
        return true
    }

    @Synchronized
    fun cancel() {
        transition = null
    }
}
