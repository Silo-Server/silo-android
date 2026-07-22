package org.siloserver.silo.common.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import org.siloserver.silo.model.playback.HdrCapabilities

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

    /** Returns the default display's HDR support, restricted to standards we model. */
    fun probe(context: Context): HdrCapabilities {
        val display = defaultDisplay(context) ?: return HdrCapabilities()

        // HdrCapabilities is available from API 24+. On older API levels the
        // display effectively has no HDR — return the empty capability.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return HdrCapabilities()

        // Display.HdrCapabilities.supportedHdrTypes is deprecated in API 34 in
        // favor of Display.Mode.getSupportedHdrTypes(); the per-display getter
        // is still the right source pre-34 and is still functional, so we
        // suppress the warning rather than branching on API level.
        @Suppress("DEPRECATION")
        val types = runCatching { display.hdrCapabilities?.supportedHdrTypes }
            .getOrNull()
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

    /** Current and supported display modes, formatted `WIDTHxHEIGHT@RATE`. */
    data class DisplayModeInfo(
        val currentMode: String?,
        val supportedModes: List<String>,
        val wideColorGamut: Boolean,
    )

    /**
     * Diagnostics accessor: exact mode list for `device.json`. Separate from
     * [probe] because playback only needs HDR types, while a diagnostics
     * bundle needs the full mode inventory to reason about HDMI mode switches.
     */
    fun probeModes(context: Context): DisplayModeInfo {
        val display = defaultDisplay(context)
            ?: return DisplayModeInfo(currentMode = null, supportedModes = emptyList(), wideColorGamut = false)
        val current = runCatching { display.mode }.getOrNull()?.let(::formatMode)
        val supported = runCatching { display.supportedModes }.getOrNull()
            ?.map(::formatMode)
            ?.distinct()
            .orEmpty()
        val wideGamut = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { display.isWideColorGamut }.getOrDefault(false)
        return DisplayModeInfo(currentMode = current, supportedModes = supported, wideColorGamut = wideGamut)
    }

    private fun formatMode(mode: Display.Mode): String {
        val rate = mode.refreshRate
        val rateText = if (rate % 1f == 0f) rate.toInt().toString() else String.format(java.util.Locale.US, "%.2f", rate)
        return "${mode.physicalWidth}x${mode.physicalHeight}@$rateText"
    }

    private fun defaultDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        return dm.getDisplay(Display.DEFAULT_DISPLAY)
    }
}
