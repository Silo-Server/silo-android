package org.siloserver.silo.common.player.video

import org.siloserver.silo.model.playback.videoQualityPreference

object VideoPlayerRouteArgs {
    const val RESUME_POSITION = "resumePosition"
    const val QUALITY = "quality"

    fun parseResumePosition(value: String?): Double? {
        val parsed = value?.toDoubleOrNull() ?: return null
        return parsed.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun encodeResumePosition(value: Double?): String? {
        val valid = value?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        return valid.toString()
    }

    /** A route's quality, with a legacy "original" link read as Auto. */
    fun normalizeQuality(value: String?): String? = videoQualityPreference(value)
        ?.takeIf { it in PLAYBACK_QUALITIES }

    private val PLAYBACK_QUALITIES = setOf("auto", "2160p", "1080p", "720p", "480p")
}
