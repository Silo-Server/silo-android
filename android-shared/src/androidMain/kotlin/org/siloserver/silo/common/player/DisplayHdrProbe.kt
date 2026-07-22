package org.siloserver.silo.common.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import org.siloserver.silo.model.playback.HdrCapabilities

data class DisplayDiagnosticsSnapshot(
    val widthPx: Int,
    val heightPx: Int,
    val refreshRateHz: Double,
    val currentMode: String,
    val supportedModes: List<String>,
    val wideColorGamut: Boolean?,
    val hdr: HdrCapabilities,
)

/**
 * Reports the default display's HDR support. Used to narrow the codec-level
 * HDR claim so we don't advertise HDR direct-play on a panel that would
 * tone-map it back to SDR anyway.
 *
 * Callers surface `codecHdr AND displayHdr` to the server. Decoder support
 * alone is insufficient when the active HDMI/display path cannot carry the
 * same transfer function.
 */
object DisplayHdrProbe {

    /** Immutable, read-only evidence for diagnostics; never changes display state. */
    fun diagnosticsSnapshot(context: Context): DisplayDiagnosticsSnapshot? {
        val display = defaultDisplay(context) ?: return null
        val mode = display.mode
        return DisplayDiagnosticsSnapshot(
            widthPx = mode.physicalWidth,
            heightPx = mode.physicalHeight,
            refreshRateHz = mode.refreshRate.toDouble(),
            currentMode = mode.diagnosticsLabel(),
            supportedModes = display.supportedModes
                .map(Display.Mode::diagnosticsLabel)
                .distinct()
                .sorted(),
            wideColorGamut = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                display.isWideColorGamut
            } else {
                null
            },
            hdr = hdrCapabilities(display),
        )
    }

    /** Returns the default display's HDR support, restricted to standards we model. */
    fun probe(context: Context): HdrCapabilities {
        val display = defaultDisplay(context) ?: return HdrCapabilities()

        return runCatching { hdrCapabilities(display) }.getOrDefault(HdrCapabilities())
    }

    private fun hdrCapabilities(display: Display): HdrCapabilities {

        // HdrCapabilities is available from API 24+. On older API levels the
        // display effectively has no HDR — return the empty capability.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return HdrCapabilities()

        // Display.HdrCapabilities.supportedHdrTypes is deprecated in API 34 in
        // favor of Display.Mode.getSupportedHdrTypes(); the per-display getter
        // is still the right source pre-34 and is still functional, so we
        // suppress the warning rather than branching on API level.
        @Suppress("DEPRECATION")
        val types = display.hdrCapabilities?.supportedHdrTypes
            ?.toSet()
            .orEmpty()

        val hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in types
        val hdr10p = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types
        val hlg = Display.HdrCapabilities.HDR_TYPE_HLG in types
        val dv = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types

        // The panel-side probe doesn't differentiate DV profiles — Android
        // only reports whether the link/panel carries DV at all. The codec
        // probe (MediaCodecCapabilitiesProbe) enumerates actual profile
        // support (and already strips P7 without multi-instance HEVC), so
        // this layer must list every profile we model or the intersection
        // silently drops legitimate decoder claims — listing only [5, 8]
        // here is what previously made native P7 support undetectable even
        // on dual-layer-capable hardware.
        return HdrCapabilities(
            hdr10 = hdr10,
            hdr10Plus = hdr10p,
            hlg = hlg,
            dolbyVisionProfiles = if (dv) listOf(5, 7, 8) else emptyList(),
        )
    }

    /**
     * Combines codec-reported HDR profiles with display-reported HDR types.
     * A profile is advertised to the server only when *both* the decoder and
     * the panel can handle it.
     */
    fun intersect(codec: HdrCapabilities, display: HdrCapabilities): HdrCapabilities {
        val dvIntersection = codec.dolbyVisionProfiles
            .filter { it in display.dolbyVisionProfiles }
        return HdrCapabilities(
            hdr10 = codec.hdr10 && display.hdr10,
            hdr10Plus = codec.hdr10Plus && display.hdr10Plus,
            hlg = codec.hlg && display.hlg,
            dolbyVisionProfiles = dvIntersection,
        )
    }

    private fun defaultDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        return dm.getDisplay(Display.DEFAULT_DISPLAY)
    }
}

private fun Display.Mode.diagnosticsLabel(): String =
    "${physicalWidth}x${physicalHeight}@${"%.2f".format(java.util.Locale.ROOT, refreshRate)}"
