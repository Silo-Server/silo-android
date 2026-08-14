package org.siloserver.silo.tv.ui.focus

import android.util.Log
import org.siloserver.silo.tv.BuildConfig

/**
 * Debug-build tracing for TV focus and IME behavior — `adb logcat -s SiloTvFocus`.
 *
 * The failures this exists to tell apart look identical on screen ("keys do
 * nothing" / "focus disappeared"):
 *  - the WINDOW lost focus (launcher stole input — no app log lines at all
 *    except the window-focus loss from MainTvActivity),
 *  - a focus claim was skipped (touch mode) or exhausted its retries,
 *  - the IME opened and is swallowing the D-pad.
 */
internal object TvFocusLog {
    const val TAG = "SiloTvFocus"

    inline fun d(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }
}
