package org.siloserver.silo.common.player.video

data class VideoPlaybackStartRequest(
    val contentId: String,
    val preferredFileId: Int?,
    val roomId: String?,
    val resumePositionOverride: Double?,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    /** Selects the preferred source file version and supplies the default output-quality ceiling. */
    val preferredQualityOverride: String? = null,
    /**
     * Explicit delivery-quality intent from the in-player quality control.
     * Null falls back to [preferredQualityOverride] or the saved profile/device
     * setting. A fixed rung authorizes video transcoding only when the selected
     * source exceeds that rung; it never requests an upscale.
     */
    val playbackQualityIntent: String? = null,
    /**
     * Suppresses skip-back-on-resume for starts that are NOT a resume — Start
     * Over, retry, or any commanded position that should land exactly. (Watch
     * Together is detected separately via [roomId].) Default false = a normal
     * resume, which gets the rewind.
     */
    val suppressResumeRewind: Boolean = false,
)
