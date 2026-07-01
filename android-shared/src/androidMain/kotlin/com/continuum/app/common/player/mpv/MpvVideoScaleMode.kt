package com.continuum.app.common.player.mpv

enum class MpvVideoScaleMode {
    Fit,
    Zoom,
    Stretch,
}

interface MpvVideoScaleController {
    fun setVideoScaleMode(mode: MpvVideoScaleMode)
}
