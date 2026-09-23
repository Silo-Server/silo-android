package org.siloserver.silo.android.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions
import org.siloserver.silo.android.MainActivity
import org.siloserver.silo.model.settings.SeekIntervalPair

/**
 * Google Cast seek pair before the revision-9 profile intervals: the
 * notification, mini bar and cast overlay all skipped 30 s both ways.
 */
internal val GOOGLE_CAST_LEGACY_SEEK_INTERVALS = SeekIntervalPair(backSeconds = 30, forwardSeconds = 30)

/**
 * Step the Cast SDK is configured with. The SDK draws "10" or "30" icons and
 * titles only for exactly 10 000 or 30 000 ms; any other value gets the plain
 * rewind/forward icon. The actual distance comes from the profile setting in
 * [SiloCastMediaIntentReceiver], so a numbered icon could be wrong.
 */
private const val CAST_NOTIFICATION_SKIP_STEP_MS = 15_000L

/**
 * Google Cast (Chromecast) configuration. Uses the Default Media Receiver so no
 * custom receiver app registration is needed.
 *
 * Referenced by name from AndroidManifest.xml
 * (`com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME`). This is
 * DISTINCT from the NSD/mDNS SiloCast device-remote — that casts to Silo's own
 * TV app and does not use the Cast SDK.
 */
class SiloCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            // Without media-notification options the Cast SDK tears the session
            // down ~10s after the app leaves the foreground (observed on-device:
            // background → CastService "Disposing ConnectedClient" → receiver
            // stops). The notification keeps the session alive app-wide and
            // gives lock-screen/notification transport controls for free.
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    // Rewind/forward from the notification and lock screen
                    // land in this receiver, which applies the profile intervals.
                    .setMediaIntentReceiverClassName(SiloCastMediaIntentReceiver::class.java.name)
                    .setNotificationOptions(
                        NotificationOptions.Builder()
                            .setTargetActivityClassName(MainActivity::class.java.name)
                            // Rewind/forward alongside play/pause and stop; the
                            // compact (lock-screen) view keeps the three
                            // transport actions. SiloCastMediaIntentReceiver
                            // handles the seeks.
                            .setActions(
                                listOf(
                                    MediaIntentReceiver.ACTION_REWIND,
                                    MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
                                    MediaIntentReceiver.ACTION_FORWARD,
                                    MediaIntentReceiver.ACTION_STOP_CASTING,
                                ),
                                intArrayOf(0, 1, 2),
                            )
                            .setSkipStepMs(CAST_NOTIFICATION_SKIP_STEP_MS)
                            .build(),
                    )
                    .build(),
            )
            .setEnableReconnectionService(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
