package com.continuum.app.common.player.video

data class VideoPlaybackStartRequest(
    val contentId: String,
    val preferredFileId: Int?,
    val roomId: String?,
    val resumePositionOverride: Double?,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    /**
     * Suppresses skip-back-on-resume for starts that are NOT a resume — Start
     * Over, retry, or any commanded position that should land exactly. (Watch
     * Together is detected separately via [roomId].) Default false = a normal
     * resume, which gets the rewind.
     */
    val suppressResumeRewind: Boolean = false,
)
