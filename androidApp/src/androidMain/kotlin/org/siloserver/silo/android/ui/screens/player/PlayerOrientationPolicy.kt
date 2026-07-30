package org.siloserver.silo.android.ui.screens.player

private const val ANDROID_16_API_LEVEL = 36
private const val LARGE_SCREEN_SMALLEST_WIDTH_DP = 600

/**
 * Android 16 ignores requested orientation on displays whose smallest width is
 * at least 600dp for apps targeting API 36. Keep the lock available everywhere
 * the platform can honor it, and let large screens remain adaptive.
 */
internal fun supportsPlayerOrientationLock(
    sdkInt: Int,
    smallestScreenWidthDp: Int,
): Boolean =
    sdkInt < ANDROID_16_API_LEVEL || smallestScreenWidthDp < LARGE_SCREEN_SMALLEST_WIDTH_DP
