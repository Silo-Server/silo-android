package org.siloserver.silo.common.player

import org.siloserver.silo.model.settings.SeekDirection
import java.util.concurrent.atomic.AtomicReference

/**
 * Hands relative seeks on an audiobook item from the media session (lock
 * screen, notification, headset, Bluetooth) to the audiobook player that owns
 * playback, so they move through the whole book like the in-app skip buttons.
 *
 * A multi-part book plays one file at a time; a file-local seek stops at the
 * part boundary, while the audiobook view model's `seekBy` loads the part that
 * contains the target. [SeekIntervalForwardingPlayer] consults this router
 * first and keeps the file-local seek when no handler is registered.
 *
 * Handlers run on the session player's application thread (main).
 */
class AudiobookSeekRouter {
    /** Returns true when it handled the seek. */
    fun interface Handler {
        fun seek(direction: SeekDirection): Boolean
    }

    private val handler = AtomicReference<Handler?>(null)

    /** Installs [owner]; a later registration replaces an earlier one. */
    fun register(owner: Handler) {
        handler.set(owner)
    }

    /** Removes [owner] only if it is still the registered handler. */
    fun unregister(owner: Handler) {
        handler.compareAndSet(owner, null)
    }

    fun seek(direction: SeekDirection): Boolean = handler.get()?.seek(direction) ?: false
}
