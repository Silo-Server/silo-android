package org.siloserver.silo.common.player.video

data class VideoPlaybackStartRequest(
    val contentId: String,
    val preferredFileId: Int?,
    val roomId: String?,
    val resumePositionOverride: Double?,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    /** Selects the preferred source file version; it does not authorize transcoding. */
    val preferredQualityOverride: String? = null,
    /**
     * Explicit delivery-quality intent from the in-player quality control.
     * Null means original-quality playback: the server may adapt audio, but
     * must not transcode video merely because a source-version preference is
     * set.
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
