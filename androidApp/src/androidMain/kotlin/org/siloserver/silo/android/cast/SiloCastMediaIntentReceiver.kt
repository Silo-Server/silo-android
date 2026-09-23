package org.siloserver.silo.android.cast

import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import org.koin.core.context.GlobalContext
import org.siloserver.silo.common.settings.SeekIntervalStore
import org.siloserver.silo.model.settings.SeekDirection

/**
 * Receives the Google Cast notification and lock-screen rewind/forward actions
 * (registered through [SiloCastOptionsProvider]) and seeks by the profile's
 * video intervals instead of the SDK's fixed step.
 *
 * On a server without the revision-9 keys, or before discovery answers, the
 * seek stays at [GOOGLE_CAST_LEGACY_SEEK_INTERVALS], the pre-setting 30 s.
 * The seek goes through [SiloCastSessionManager.skipBy] so it maps transcoded
 * positions like the in-app cast controls. With no cast client to seek, the
 * SDK's own handling runs.
 */
class SiloCastMediaIntentReceiver : MediaIntentReceiver() {

    override fun onReceiveActionForward(currentSession: Session, forwardStepMs: Long) {
        if (!skip(SeekDirection.Forward)) super.onReceiveActionForward(currentSession, forwardStepMs)
    }

    override fun onReceiveActionRewind(currentSession: Session, rewindStepMs: Long) {
        if (!skip(SeekDirection.Back)) super.onReceiveActionRewind(currentSession, rewindStepMs)
    }

    private fun skip(direction: SeekDirection): Boolean {
        val koin = GlobalContext.getOrNull() ?: return false
        val store = koin.getOrNull<SeekIntervalStore>() ?: return false
        val manager = koin.getOrNull<SiloCastSessionManager>() ?: return false
        val seconds = store.state.value.video(GOOGLE_CAST_LEGACY_SEEK_INTERVALS).seconds(direction)
        val delta = if (direction == SeekDirection.Back) -seconds else seconds
        return manager.skipFromSystemControl(delta.toDouble())
    }
}
