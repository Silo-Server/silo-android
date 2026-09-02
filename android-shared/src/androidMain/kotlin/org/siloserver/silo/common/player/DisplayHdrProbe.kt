package org.siloserver.silo.common.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.HdrConversionMode
import android.os.Build
import android.view.Display
import org.siloserver.silo.model.playback.HdrCapabilities
import org.siloserver.silo.model.playback.OUTPUT_HDR_EVIDENCE_EXACT
import org.siloserver.silo.model.playback.OUTPUT_HDR_EVIDENCE_UNKNOWN
import org.siloserver.silo.model.playback.PlaybackOutputDisplay

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
 * What the active display reported, with the evidence tier attached.
 *
 * [Exact] is a successful probe, including a confirmed SDR panel (empty
 * [Exact.hdr]). [Unknown] means the platform gave no answer: no display, a
 * pre-API-24 device, a null capability object, or a probe exception. The two
 * must stay distinct because the server treats "confirmed SDR" as a fact and
 * "unknown" as a reason to fail closed on native HDR output.
 */
sealed class DisplayHdrProbeResult {
    abstract val hdr: HdrCapabilities
    abstract val displayId: Int?

    data class Exact(override val hdr: HdrCapabilities, override val displayId: Int?) : DisplayHdrProbeResult()

    data class Unknown(override val displayId: Int?, val reason: String) : DisplayHdrProbeResult() {
        override val hdr: HdrCapabilities get() = HdrCapabilities()
    }

    val isExact: Boolean get() = this is Exact

    fun toOutputDisplay(): PlaybackOutputDisplay = PlaybackOutputDisplay(
        hdrEvidence = if (isExact) OUTPUT_HDR_EVIDENCE_EXACT else OUTPUT_HDR_EVIDENCE_UNKNOWN,
        hdrTypes = hdr,
        displayId = displayId?.toString(),
    )
}

/**
 * Reports the HDR support of the display that owns the playback surface.
 *
 * The result narrows the codec-level HDR claim so we don't advertise native
 * HDR direct-play on a panel that would tone-map it back to SDR. Callers
 * surface `codecHdr AND displayHdr` to the server as the native-output
 * capability; the raw display facts travel separately with their evidence
 * tier so the server can tell a confirmed SDR panel from a failed probe.
 */
object DisplayHdrProbe {

    /**
     * Every Dolby Vision profile the codec probe can report. The panel flag is
     * generic, so it must not exclude a profile the decoder supports.
     */
    internal val PANEL_DOLBY_VISION_PROFILES: List<Int> = (0..10).toList()

    /** Immutable, read-only evidence for diagnostics; never changes display state. */
    fun diagnosticsSnapshot(context: Context, displayId: Int? = null): DisplayDiagnosticsSnapshot? {
        val display = resolveDisplay(context, displayId) ?: return null
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
            hdr = probeDetailed(context, displayId).hdr,
        )
    }

    /**
     * The active display's HDR support restricted to standards we model. An
     * unknown probe collapses to the empty capability so native-output gates
     * fail closed; use [probeDetailed] when the evidence tier matters.
     */
    fun probe(context: Context, displayId: Int? = null): HdrCapabilities = probeDetailed(context, displayId).hdr

    /**
     * The active display's HDR support with its evidence tier.
     *
     * @param displayId the display that owns the playback surface, when the
     * caller knows it. Without it an Activity context resolves its own
     * display and any other context resolves the default display.
     */
    fun probeDetailed(context: Context, displayId: Int? = null): DisplayHdrProbeResult {
        val display = resolveDisplay(context, displayId)
            ?: return DisplayHdrProbeResult.Unknown(displayId = displayId, reason = "no_display")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return DisplayHdrProbeResult.Unknown(displayId = display.displayId, reason = "api_below_24")
        }
        return runCatching { hdrCapabilities(context, display) }
            .getOrElse { DisplayHdrProbeResult.Unknown(displayId = display.displayId, reason = "probe_failed") }
    }

    private fun hdrCapabilities(context: Context, display: Display): DisplayHdrProbeResult {
        val displayId = display.displayId
        // Android 14 lets the user force the system HDR conversion to SDR. The
        // legacy per-display capability object still lists the panel's HDR
        // types then, so a plan promising native HDR would be tone-mapped by
        // the compositor. Treat forced-SDR as a confirmed SDR output.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && systemForcesSdr(context)) {
            return DisplayHdrProbeResult.Exact(HdrCapabilities(), displayId)
        }

        val types: Set<Int>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Per-mode HDR types are the current API; a panel can carry HDR10
            // at 4K60 but not at 4K120. Report the *active* mode only: the
            // refresh-rate matcher switches modes by resolution and rate, and
            // the display listener re-probes on every change, so a switch
            // onto a mode without HDR rotates the output context and replans
            // instead of leaving a stale union in place.
            val activeMode = runCatching { display.mode }.getOrNull()
            val anyModeDeclares = display.supportedModes.any { it.supportedHdrTypes.isNotEmpty() }
            if (activeMode != null && anyModeDeclares) {
                activeMode.supportedHdrTypes.toSet()
            } else {
                @Suppress("DEPRECATION")
                display.hdrCapabilities?.supportedHdrTypes?.toSet()
            }
        } else {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes?.toSet()
        }
        // The platform is documented to return an empty (not null) capability
        // for an SDR panel; a null object means the display could not answer.
        if (types == null) {
            return DisplayHdrProbeResult.Unknown(displayId, reason = "null_capabilities")
        }

        val hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in types
        val hdr10p = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types
        val hlg = Display.HdrCapabilities.HDR_TYPE_HLG in types
        val dv = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types

        // The panel-side probe doesn't differentiate DV profiles — Android
        // only reports whether the link/panel carries DV at all. The codec
        // probe (MediaCodecCapabilitiesProbe) enumerates actual profile
        // support (and already strips P7 without multi-instance HEVC), so
        // this layer lists every profile the codec probe can emit or the
        // intersection silently drops legitimate decoder claims — listing
        // only [5, 8] here is what previously made native P7 support
        // undetectable even on dual-layer-capable hardware.
        return DisplayHdrProbeResult.Exact(
            HdrCapabilities(
                hdr10 = hdr10,
                hdr10Plus = hdr10p,
                hlg = hlg,
                dolbyVisionProfiles = if (dv) PANEL_DOLBY_VISION_PROFILES else emptyList(),
            ),
            displayId,
        )
    }

    private fun systemForcesSdr(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
        val mode = runCatching { dm.hdrConversionMode }.getOrNull() ?: return false
        return mode.conversionMode == HdrConversionMode.HDR_CONVERSION_FORCE &&
            mode.preferredHdrOutputType == Display.HdrCapabilities.HDR_TYPE_INVALID
    }

    /**
     * Combines codec-reported HDR profiles with display-reported HDR types.
     * A profile is advertised to the server only when *both* the decoder and
     * the panel can handle it. Decoder-side profile bounds survive for the
     * profiles that remain.
     */
    fun intersect(codec: HdrCapabilities, display: HdrCapabilities): HdrCapabilities {
        val dvIntersection = codec.dolbyVisionProfiles
            .filter { it in display.dolbyVisionProfiles }
        return HdrCapabilities(
            hdr10 = codec.hdr10 && display.hdr10,
            hdr10Plus = codec.hdr10Plus && display.hdr10Plus,
            hlg = codec.hlg && display.hlg,
            dolbyVisionProfiles = dvIntersection,
            dolbyVisionProfileLevels = codec.dolbyVisionProfileLevels
                .filter { it.profile in dvIntersection },
        )
    }

    /**
     * The display that owns [context]'s window when [context] is (or wraps) an
     * Activity, as Kodi does; otherwise the default display. Media3's own
     * Dolby Vision display check reads the default display, so a playback
     * Activity on a secondary display is reported here but should be treated
     * as a device-class quirk rather than assumed to match Media3.
     */
    private fun resolveDisplay(context: Context, displayId: Int?): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        if (displayId != null) {
            return dm.getDisplay(displayId) ?: dm.getDisplay(Display.DEFAULT_DISPLAY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.findActivity()?.let { activity ->
                runCatching { activity.display }.getOrNull()?.let { return it }
            }
        }
        return dm.getDisplay(Display.DEFAULT_DISPLAY)
    }
}

private fun Display.Mode.diagnosticsLabel(): String =
    "${physicalWidth}x${physicalHeight}@${"%.2f".format(java.util.Locale.ROOT, refreshRate)}"
