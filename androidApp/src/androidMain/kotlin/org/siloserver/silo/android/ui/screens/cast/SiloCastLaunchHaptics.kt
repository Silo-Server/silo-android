package org.siloserver.silo.android.ui.screens.cast

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import org.siloserver.silo.android.cast.SiloCastController
import org.siloserver.silo.android.cast.SiloCastLaunchFeedback

/**
 * Haptics for a Play sent to a TV, wherever the user is in the app: a light
 * tick when it goes out, success once the TV reports the title, and an error
 * if it fails. Mirrors silo-apple's remote.
 */
@Composable
fun SiloCastLaunchHaptics(controller: SiloCastController) {
    val view = LocalView.current
    LaunchedEffect(controller, view) {
        controller.launchFeedback.collect { feedback ->
            view.performHapticFeedback(
                when (feedback) {
                    SiloCastLaunchFeedback.Sent -> HapticFeedbackConstants.CLOCK_TICK
                    SiloCastLaunchFeedback.Started ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            HapticFeedbackConstants.CONFIRM
                        } else {
                            HapticFeedbackConstants.LONG_PRESS
                        }
                    SiloCastLaunchFeedback.Failed ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            HapticFeedbackConstants.REJECT
                        } else {
                            HapticFeedbackConstants.LONG_PRESS
                        }
                },
            )
        }
    }
}
