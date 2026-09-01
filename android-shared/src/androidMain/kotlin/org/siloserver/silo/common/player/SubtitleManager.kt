package org.siloserver.silo.common.player

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import org.siloserver.silo.libass.LibassBridge
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.siloserver.silo.playback.downloadedSubtitleArtifactTrackId
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Manages subtitle track configuration for the ExoPlayer instance.
 *
 * External subtitles come from the server as URLs that need authentication.
 * This manager builds subtitle configurations and applies track selection.
 */
@UnstableApi
enum class AndroidSubtitlePresentation {
    Phone,
    Television,
}

@UnstableApi
class SubtitleManager(
    private val libassBridge: LibassBridge? = null,
    private val presentation: AndroidSubtitlePresentation =
        AndroidSubtitlePresentation.Television,
) {

    private val videoRectSyncs = WeakHashMap<PlayerView, SubtitleVideoRectSync>()
    /** Test-only execution observer; null in production. */
    internal var postLayoutReconciliationObserver: (() -> Unit)? = null

    var letterbox: LetterboxInsets = LetterboxInsets.NONE
        set(value) {
            if (field == value) return
            field = value
            videoRectSyncs.values.forEach { it.letterbox = value }
        }

    var titleSafeFraction: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            videoRectSyncs.values.forEach { it.titleSafeFraction = value }
        }

    /**
     * The appearance last handed to [applyAppearance], kept so the cue
     * forwarding can remap BITMAP cues — Media3's `SubtitlePainter` positions
     * and sizes those from the cue's own fields alone, so `setStyle` /
     * `setFixedTextSize` / `setBottomPaddingFraction` never reach them.
     */
    private var appearance: SubtitleAppearance = SubtitleAppearance.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            videoRectSyncs.values.forEach { it.appearance = value }
        }

    /**
     * Builds MediaItem.SubtitleConfiguration entries for external subtitle tracks.
     *
     * @param subtitles The subtitle info list from the playback session
     * @param serverUrl The base server URL for resolving relative subtitle URLs
     * @return List of subtitle configurations to add to the MediaItem
     */
    fun buildSubtitleConfigurations(
        subtitles: List<PlayerSubtitleInfo>,
        serverUrl: String,
    ): List<MediaItem.SubtitleConfiguration> {
        return subtitles.mapNotNull { subtitle ->
            // V3 embedded-bitmap rows intentionally carry a blank URL: they
            // are selection metadata for a track already in the primary
            // media, not a merging sidecar source. Do not key only on
            // `source=embedded`, because legacy/remux routes can still expose
            // a real extracted text artifact from an embedded source.
            if (subtitle.url.isBlank()) {
                return@mapNotNull null
            }
            val absoluteUrl = resolveSubtitleUrl(serverUrl, subtitle.url)
            val mimeType = subtitleMimeType(subtitle.codec, absoluteUrl)
            if (!isMountableSidecarMimeType(mimeType)) {
                return@mapNotNull null
            }

            val builder = MediaItem.SubtitleConfiguration.Builder(Uri.parse(absoluteUrl))
            val stableTrackId = if (subtitle.isDownloadedSubtitleArtifact()) {
                subtitle.downloadId?.let(::downloadedSubtitleArtifactTrackId)
            } else {
                subtitleArtifactTrackId(subtitle.index)
            }
            if (stableTrackId != null) {
                builder.setId(stableTrackId)
            }
            builder
                .setMimeType(mimeType)
                .setLanguage(subtitle.language)
                // Preserve the catalog label when a server-side artifact has a
                // generic runtime label. The mounted label also carries SDH/CC
                // semantics used by exact identity reconciliation.
                .setLabel(
                    subtitle.catalogLabel
                        ?: subtitle.label
                        ?: subtitle.language
                        ?: "Track ${subtitle.index}",
                )
                .setSelectionFlags(
                    if (subtitle.forced == true) C.SELECTION_FLAG_FORCED else 0
                )
                .build()
        }
    }

    /**
     * Selects or disables subtitles on the player.
     *
     * Widened from `ExoPlayer` to `Player` so callers holding a `MediaController`
     * can invoke it — `currentTracks` and `trackSelectionParameters` are both
     * on `Player` and that's all this method touches.
     *
     * @param player The player instance (ExoPlayer or MediaController)
     * @param subtitleIndex The subtitle track index to select, or -1 to disable subtitles
     */
    fun selectSubtitle(player: Player, subtitleIndex: Int): Boolean {
        if (subtitleIndex < 0) {
            disableSubtitles(player)
            return true
        } else {
            val selection = resolveSubtitleSelection(player.currentTracks, subtitleIndex)
            if (selection == null) {
                Log.w(
                    TAG,
                    "selectSubtitle failed: index=$subtitleIndex not found " +
                        "tracks=${player.currentTracks.describeTextTracks()}",
                )
                return false
            }
            applySubtitleSelection(player, selection)
            return true
        }
    }

    /**
     * Selects a subtitle from the app/server subtitle list. Mobile renders
     * [PlayerSubtitleInfo] rows, while Media3 can expose embedded text tracks
     * before sidecar tracks; selecting by raw ordinal would then choose the
     * wrong language. Prefer metadata matching and fall back to the old flat
     * ordinal for callers whose tracks do not expose labels yet.
     */
    fun selectSubtitle(
        player: Player,
        subtitles: List<PlayerSubtitleInfo>,
        subtitleIndex: Int,
    ): Boolean {
        if (subtitleIndex < 0) {
            disableSubtitles(player)
            return true
        }

        val subtitle = subtitles.getOrNull(subtitleIndex)
        if (subtitle == null) {
            Log.w(TAG, "selectSubtitle failed: app index=$subtitleIndex outside subtitles=${subtitles.size}")
            return false
        }

        val selection = resolveSubtitleSelection(player.currentTracks, subtitle)
        if (selection == null) {
            Log.w(
                TAG,
                "selectSubtitle failed: app index=$subtitleIndex metadata=${subtitle.label ?: subtitle.language} " +
                    "tracks=${player.currentTracks.describeTextTracks()}",
            )
            return false
        }

        applySubtitleSelection(player, selection)
        return true
    }

    /**
     * Selects a track already present in the Media3 snapshot by its complete
     * domain identity. This path never converts the identity back to an app
     * list ordinal, so exact artifact and Format ids survive list reordering.
     */
    fun selectSubtitle(player: Player, identity: SubtitleIdentity): Boolean {
        if (identity == SubtitleIdentity.Off || identity is SubtitleIdentity.ServerBurnIn) {
            disableSubtitles(player)
            return true
        }

        val selection = resolveSubtitleSelection(player.currentTracks, identity)
        if (selection == null) {
            Log.w(
                TAG,
                "selectSubtitle failed: identity=$identity " +
                    "tracks=${player.currentTracks.describeTextTracks()}",
            )
            return false
        }

        applySubtitleSelection(player, selection)
        return true
    }

    /**
     * Resolves the backend track id (Format.id) for the [subtitleIndex]-th
     * subtitle track (`secondary-sid`) without going through Media3's
     * single-text-override selection parameters.
     */
    fun resolveSubtitleTrackId(
        player: Player,
        subtitles: List<PlayerSubtitleInfo>,
        subtitleIndex: Int,
    ): String? {
        val subtitle = subtitles.getOrNull(subtitleIndex) ?: return null
        val selection = resolveSubtitleSelection(player.currentTracks, subtitle) ?: return null
        return selection.mediaTrackGroup.getFormat(selection.trackIndex).id
    }

    /**
     * Applies the user's [SubtitleAppearance] to the [PlayerView]'s subtitle layer.
     *
     * Maps onto Media3 via [CaptionStyleCompat] (colors + edge style + typeface),
     * [androidx.media3.ui.SubtitleView.setFractionalTextSize] for phone
     * relative-to-view-height sizing, [androidx.media3.ui.SubtitleView.setFixedTextSize]
     * for television SP sizing, and
     * [androidx.media3.ui.SubtitleView.setBottomPaddingFraction] (vertical position
     * within the surface).
     *
     * Media3-rendered text uses the user's appearance. ASS/SSA is rendered by
     * libass and deliberately preserves the script's authored typesetting,
     * animation, positioning, and embedded fonts, matching the Apple player.
     *
     * Bitmap cues (PGS/DVB) take Position and Size only, and not through this
     * view-level API at all — see [remapBitmapCue].
     */
    fun applyAppearance(playerView: PlayerView, appearance: SubtitleAppearance) {
        val subtitleView = playerView.subtitleView ?: return
        libassBridge?.attachTo(subtitleView)
        val safe = appearance.sanitized()
        this.appearance = safe

        val captionStyle = try {
            buildCaptionStyle(safe)
        } catch (_: NumberFormatException) {
            // Defense-in-depth: fall back to default white-on-transparent.
            CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_NONE,
                Color.BLACK,
                Typeface.SANS_SERIF,
            )
        }

        subtitleView.setApplyEmbeddedStyles(false)
        subtitleView.setApplyEmbeddedFontSizes(false)
        subtitleView.setStyle(captionStyle)
        applyAndroidSubtitleTextSize(
            subtitleView,
            androidSubtitleTextSize(presentation, safe.fontSize),
        )
        // The picture-relative fraction, which is the answer whenever the canvas
        // is the picture and the starting point before there is any geometry to
        // measure. The sync overwrites it from the placed canvas — the
        // screen-anchored Bottom preset depends on how far that canvas extends
        // past the picture, which only the sync knows.
        subtitleView.setBottomPaddingFraction(bottomPaddingFor(safe.position))
        syncSubtitleVideoBounds(playerView)
    }

    /**
     * Recomputes the subtitle layer's displayed-video bounds. Callers invoke
     * this after PlayerView/player/resize-mode changes; the installed sync also
     * reacts to later layout and video-size callbacks.
     */
    fun syncSubtitleVideoBounds(playerView: PlayerView) {
        playerView.subtitleView?.let { libassBridge?.attachTo(it) }
        val existing = videoRectSyncs[playerView]
        val sync = if (existing?.isDisposed == true || existing == null) {
            SubtitleVideoRectSync(
                playerView = playerView,
                presentation = presentation,
                libassBridge = libassBridge,
                onPostLayoutReconciled = { postLayoutReconciliationObserver?.invoke() },
            ).also {
                it.letterbox = letterbox
                it.titleSafeFraction = titleSafeFraction
                it.appearance = appearance
                videoRectSyncs[playerView] = it
            }
        } else {
            existing
        }
        sync.updateAndReconcileAfterLayout()
    }

    private fun buildCaptionStyle(appearance: SubtitleAppearance): CaptionStyleCompat {
        val foreground = parseHexColor(appearance.fontColor)
        val backgroundAlpha = if (appearance.backgroundStyle == SubtitleBackgroundStylePreset.Box) {
            (appearance.backgroundOpacity.coerceIn(0, 100) * 255 / 100)
        } else {
            0
        }
        val background = parseHexColor(appearance.backgroundColor, backgroundAlpha)
        val edgeType = when {
            appearance.backgroundStyle == SubtitleBackgroundStylePreset.Shadow ->
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            appearance.backgroundStyle == SubtitleBackgroundStylePreset.Outline ->
                CaptionStyleCompat.EDGE_TYPE_OUTLINE
            appearance.textOutline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            else -> CaptionStyleCompat.EDGE_TYPE_NONE
        }
        val edgeColor = parseHexColor(appearance.textOutlineColor)
        val typeface = typefaceFor(appearance.fontFamily)
        // Box renders through windowColor: Media3's SubtitlePainter draws the
        // window as one block behind the whole cue with INNER_PADDING_RATIO
        // (12.5% of the text size) of horizontal breathing room, whereas
        // backgroundColor is a per-line BackgroundColorSpan that hugs the
        // glyphs (QA 2026-07-08: "SRT subtitles with box should have more
        // floor instead.
        return CaptionStyleCompat(
            foreground,
            Color.TRANSPARENT,
            background,
            edgeType,
            edgeColor,
            typeface,
        )
    }

    private fun typefaceFor(family: String): Typeface {
        return when (family) {
            SubtitleAppearance.SANS_SERIF -> Typeface.SANS_SERIF
            SubtitleAppearance.SERIF -> Typeface.SERIF
            SubtitleAppearance.MONOSPACE -> Typeface.MONOSPACE
            else -> Typeface.create(family, Typeface.NORMAL)
        }
    }

    private fun bottomPaddingFor(position: SubtitlePositionPreset): Float =
        subtitleBottomPaddingFraction(position, titleSafeFraction)

    private fun parseHexColor(hex: String, alpha: Int = 255): Int {
        val cleaned = if (hex.startsWith("#")) hex.drop(1) else hex
        val rgb = cleaned.toLong(16).toInt()
        return ((alpha and 0xFF) shl 24) or (rgb and 0x00FFFFFF)
    }

    private fun subtitleMimeType(codec: String?, url: String): String =
        mimeTypeFromUrl(url) ?: codecToMimeType(codec)

    private fun mimeTypeFromUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".vtt") || path.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".sup") -> MimeTypes.APPLICATION_PGS
            path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            path.endsWith(".ttml") -> MimeTypes.APPLICATION_TTML
            else -> null
        }
    }

    private fun codecToMimeType(codec: String?): String {
        return when (codec?.lowercase()) {
            "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "ttml" -> MimeTypes.APPLICATION_TTML
            "pgs", "hdmv_pgs_subtitle" -> MimeTypes.APPLICATION_PGS
            "dvd_subtitle", "dvdsub" -> MimeTypes.APPLICATION_DVBSUBS
            else -> MimeTypes.APPLICATION_SUBRIP // default to SRT
        }
    }

    /**
     * Sidecar formats Media3 can parse for us. Bitmap families are included:
     * the server raw-serves an embedded PGS track as `.sup`, and Media3's
     * DefaultSubtitleParserFactory decodes PGS, VobSub and DVB. Mounting those
     * is what lets a bitmap subtitle render client-side instead of forcing the
     * server to burn it into the picture, which costs a full transcode.
     */
    private fun isMountableSidecarMimeType(mimeType: String): Boolean =
        when (mimeType) {
            MimeTypes.TEXT_VTT,
            MimeTypes.TEXT_SSA,
            MimeTypes.APPLICATION_SUBRIP,
            MimeTypes.APPLICATION_TTML,
            MimeTypes.APPLICATION_PGS,
            MimeTypes.APPLICATION_VOBSUB,
            MimeTypes.APPLICATION_DVBSUBS,
            -> true
            else -> false
        }

    private fun disableSubtitles(player: Player) {
        // Disable all text tracks
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun applySubtitleSelection(
        player: Player,
        selection: SubtitleSelection,
    ) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(
                TrackSelectionOverride(
                    selection.mediaTrackGroup,
                    selection.trackIndex,
                )
            )
            .build()
    }

}

internal data class SubtitleVideoRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal fun displayedSubtitleVideoRect(
    viewWidth: Int,
    viewHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
    videoPixelWidthHeightRatio: Float = 1f,
    resizeMode: Int,
): SubtitleVideoRect {
    val full = SubtitleVideoRect(
        left = 0,
        top = 0,
        width = viewWidth.coerceAtLeast(0),
        height = viewHeight.coerceAtLeast(0),
    )
    if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
        return full
    }

    val pixelRatio = videoPixelWidthHeightRatio
        .takeIf { it.isFinite() && it > 0f }
        ?: 1f
    val aspect = (videoWidth.toFloat() * pixelRatio) / videoHeight.toFloat()
    val (targetWidth, targetHeight) = when (resizeMode) {
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> {
            val width = viewWidth
            width to (width / aspect).roundToInt()
        }
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> {
            val height = viewHeight
            (height * aspect).roundToInt() to height
        }
        AspectRatioFrameLayout.RESIZE_MODE_FIT -> {
            val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
            if (viewAspect > aspect) {
                val height = viewHeight
                (height * aspect).roundToInt() to height
            } else {
                val width = viewWidth
                width to (width / aspect).roundToInt()
            }
        }
        // Zoom intentionally crops outside the view, and Fill intentionally
        // distorts to the view. In both cases the visible video occupies the
        // full PlayerView bounds, so the subtitle layer should too.
        else -> return full
    }

    val safeWidth = targetWidth.coerceAtLeast(1).coerceAtMost(viewWidth)
    val safeHeight = targetHeight.coerceAtLeast(1).coerceAtMost(viewHeight)
    return SubtitleVideoRect(
        left = ((viewWidth - safeWidth) / 2f).roundToInt(),
        top = ((viewHeight - safeHeight) / 2f).roundToInt(),
        width = safeWidth,
        height = safeHeight,
    )
}

/**
 * Picks the rect the subtitle layer is laid out in.
 *
 * The one thing that governs this is a coordinate space. `SubtitleView` is a
 * child of `exo_content_frame` — NOT of the PlayerView — so whatever comes back
 * here is applied as margins inside the CONTENT FRAME.
 * [displayedSubtitleContentFrameRect] already speaks that space: it intersects
 * the frame with the view and returns the result relative to the frame's own
 * origin, which is both the visible-region clamp and the right anchor.
 *
 * [displayedVideoRect] does not speak it — it is measured from the PlayerView's
 * top-left. It survives only as the fallback for having no content frame at
 * all, where there is nothing better to say.
 *
 * This used to prefer [displayedVideoRect] for ZOOM and FILL whenever the
 * frame's visible size did not equal the view's, meaning to say "the zoomed
 * video covers the whole view, so the captions should too". The intent was
 * right and the arithmetic was in the wrong space: that mismatch happens
 * exactly when the frame is OFFSET inside the view (a resize mode changed and
 * the frame has not been laid out again yet), and applying a view-space rect at
 * frame-relative margins then shifts the captions by the offset — 127px off
 * centre on a 3120px display, for one layout pass, which is what "the
 * subtitles aren't centred" was. Anchoring to the frame is correct in that
 * moment too: the captions track whatever the video is actually rendered at
 * right now, mid-transition included.
 */
internal fun selectSubtitleCanvasRect(
    contentFrameRect: SubtitleVideoRect?,
    displayedVideoRect: SubtitleVideoRect,
): SubtitleVideoRect = contentFrameRect ?: displayedVideoRect

internal fun displayedSubtitleContentFrameRect(
    viewWidth: Int,
    viewHeight: Int,
    frameLeft: Int,
    frameTop: Int,
    frameWidth: Int,
    frameHeight: Int,
): SubtitleVideoRect? {
    if (viewWidth <= 0 || viewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
        return null
    }
    val visibleLeft = frameLeft.coerceAtLeast(0)
    val visibleTop = frameTop.coerceAtLeast(0)
    val visibleRight = (frameLeft + frameWidth).coerceAtMost(viewWidth)
    val visibleBottom = (frameTop + frameHeight).coerceAtMost(viewHeight)
    if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return null
    return SubtitleVideoRect(
        left = visibleLeft - frameLeft,
        top = visibleTop - frameTop,
        width = visibleRight - visibleLeft,
        height = visibleBottom - visibleTop,
    )
}

/**
 * Neutralizes the WebVTT default full-width cue size so the Box subtitle
 * background hugs the text (media3 SubtitlePainter expands the window rect to
 * the full cue-region width when size is set). NEEDS ON-DEVICE VERIFICATION
 * across SRT-as-VTT, native SRT, positioned WebVTT, and PGS.
 *
 * WebvttCueParser defaults every cue to `size == 1.0` (full width), and the
 * Silo server serves all sidecar text subs (including converted SRT) as .vtt —
 * so nearly every sidecar cue would otherwise trigger a full-width opaque band
 * behind the text under the Box style. Only the full-width default is stripped:
 *
 *  - Bitmap cues (PGS/DVBSUB) render their own pixels — never touched.
 *  - Text cues with an intentional narrower size (0 < size < 1, size != 1) use
 *    size for real positioning and are left alone.
 *  - Text cues already at `DIMEN_UNSET` (native in-container SubRip) are
 *    unchanged — they already produce the intended snug box.
 *
 * Stripping the full-width default is harmless for non-Box styles: the window
 * color is transparent there, and text still lays out across the full region.
 */
internal fun neutralizeFullWidthCueSize(cue: Cue): Cue {
    if (cue.bitmap != null || cue.text == null) return cue
    // 1.0 is WebvttCueParser's full-width default (the band trigger). Any other
    // value — including DIMEN_UNSET — is either an intentional narrower region
    // or an already-snug cue, so leave the layout untouched.
    if (cue.size == 1f) {
        return cue.buildUpon().setSize(Cue.DIMEN_UNSET).build()
    }
    return cue
}

/**
 * Where the TOP edge of a Top-preset caption sits, as a fraction of the caption
 * canvas.
 *
 * Top is anchored from the top rather than expressed as a bottom padding: a
 * bottom padding places the BOTTOM of the text block, so a two-line cue starts
 * lower than a one-line cue and neither lands where "top" means. The canvas is
 * already inset by the title-safe fraction on television, so 0.01 of it is
 * about 6% down the picture — the tvOS client's ~70px on 1080.
 */
internal const val SUBTITLE_TOP_LINE_FRACTION = 0.01f

/**
 * The smallest gap the bottom-anchored presets may leave below the caption, as
 * a fraction of the canvas. Mirrors [SUBTITLE_TOP_LINE_FRACTION] at the other
 * edge: enough that the text never touches the picture edge.
 */
internal const val MIN_SUBTITLE_BOTTOM_PADDING = 0.01f

/**
 * Where the Bottom preset puts the caption, as a fraction of the PLAYER VIEW's
 * height above the player view's bottom edge — the screen, not the picture.
 *
 * Bottom is the only screen-anchored preset, matching the Apple client: tvOS
 * enables libass `use_margins` for it so regular events render across the full
 * overlay frame at `primaryMarginV` 60 on a 1080 frame, and its own comment
 * says the preset "can sit in the letterbox bar below the picture when the
 * overlay extends past the video rect". Lower Third and Top stay anchored to
 * the picture, where the author's framing is what matters.
 *
 * 6% of 1080 is 65px, the tvOS ~60px reference, and on 16:9 content — where the
 * picture fills the screen — it is exactly where the old picture-anchored 6%
 * already landed. The two only diverge when the content letterboxes.
 */
internal const val SUBTITLE_BOTTOM_SCREEN_FRACTION = 0.06f

/**
 * Re-places a text cue that carries the parser's DEFAULT vertical placement, so
 * the user's Position preset decides where it lands.
 *
 * Bottom and Lower Third are applied as `SubtitleView.setBottomPaddingFraction`,
 * and Media3 1.11.0's `SubtitlePainter.setupTextLayout` only consults that
 * fraction when `cue.line == DIMEN_UNSET` — any explicit line wins outright.
 * Every cue from a streamed SRT/VTT sidecar carries WebVTT's default "auto"
 * placement, which the parser materializes as `line = -1` with
 * `LINE_TYPE_NUMBER` ("one line up from the bottom"), so all three presets were
 * drawn in the same place. Clearing the line hands those two back to the
 * padding; Top instead gets an explicit top-anchored line
 * ([SUBTITLE_TOP_LINE_FRACTION]).
 *
 * Only that exact default is re-placed. An authored placement — a fraction
 * line, any other line number — is the author positioning the caption around
 * the picture and is left alone, as are bitmap cues (see [remapBitmapCue]) and
 * ASS, which libass renders and never reaches this path.
 */
@UnstableApi
internal fun remapDefaultTextCuePlacement(
    cue: Cue,
    position: SubtitlePositionPreset,
): Cue {
    if (cue.bitmap != null || cue.text == null) return cue
    if (cue.lineType != Cue.LINE_TYPE_NUMBER || cue.line != -1f) return cue
    // The parser emits the default with no meaningful line anchor; a cue that
    // anchors its line elsewhere is expressing a real placement.
    if (cue.lineAnchor != Cue.TYPE_UNSET && cue.lineAnchor != Cue.ANCHOR_TYPE_START) return cue
    if (position == SubtitlePositionPreset.Top) {
        return cue.buildUpon()
            .setLine(SUBTITLE_TOP_LINE_FRACTION, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build()
    }
    return cue.buildUpon().setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).build()
}

@UnstableApi
internal fun remapDefaultTextCuePlacements(
    cueGroup: CueGroup,
    position: SubtitlePositionPreset,
): CueGroup {
    if (cueGroup.cues.isEmpty()) return cueGroup
    var changed = false
    val mapped = cueGroup.cues.map { original ->
        val next = remapDefaultTextCuePlacement(original, position)
        if (next !== original) changed = true
        next
    }
    return if (changed) CueGroup(mapped, cueGroup.presentationTimeUs) else cueGroup
}

/**
 * Vertical placement of the caption block, expressed the way Media3 wants it:
 * the fraction of the subtitle surface left free BELOW the caption.
 *
 * Shared by the text path ([androidx.media3.ui.SubtitleView.setBottomPaddingFraction])
 * and the bitmap path ([remapBitmapCue]) so both presets land in the same place.
 *
 * The bases are the broadcast/Apple references measured from the frame edge:
 * Bottom sits just inside the picture (SMPTE ST 2046-1 title-safe is 90% of the
 * frame, and the tvOS client places captions ~60px above the bottom on 1080),
 * Lower Third about a fifth up. Bottom's frame-relative answer holds while the
 * canvas IS the picture; once the canvas extends into the letterbox bar,
 * [subtitleBottomPaddingFractionForCanvas] restates it against the screen.
 * Top does not belong here at all — it is
 * top-anchored per [SUBTITLE_TOP_LINE_FRACTION] — and its value is inert: the
 * only text cues that read this fraction are the ones the Top branch of
 * [remapDefaultTextCuePlacement] has given an explicit line instead.
 */
internal fun subtitleBottomPaddingFraction(
    position: SubtitlePositionPreset,
    titleSafeFraction: Float,
): Float {
    // PHYSICAL fractions of the frame height, measured from the frame edge —
    // the same on every presentation. Bottom ~6% (tvOS client: ~60px on
    // 1080; SMPTE ST 2046-1 title-safe is 5%), Lower Third ~18%.
    val base = when (position) {
        SubtitlePositionPreset.Bottom -> SUBTITLE_BOTTOM_SCREEN_FRACTION
        SubtitlePositionPreset.LowerThird -> 0.18f
        SubtitlePositionPreset.Top -> 0.74f
    }
    // The title-safe inset moves the subtitle surface in by f on both
    // edges, leaving a height of (1 - 2f). Preserve the physical preset by
    // solving f + p(1 - 2f) = base for the padding p inside the canvas. On
    // the phone f is 0 and the base applies raw; on television (f = 0.05)
    // Bottom becomes ~1% of the canvas, which lands the text ~6% up the frame.
    val remainingScale = 1f - 2f * titleSafeFraction
    if (remainingScale <= 0f) return base
    return ((base - titleSafeFraction) / remainingScale).coerceAtLeast(MIN_SUBTITLE_BOTTOM_PADDING)
}

/**
 * The caption canvas for [position], given the picture-anchored canvas the
 * letterbox and title-safe insets produce.
 *
 * Everything here is in the SubtitleView's parent space (the content frame),
 * which is the one space the rect sync works in — the canvas stays a single
 * rect written as margins on the same parent, and only its bottom edge moves.
 *
 * Bottom extends that bottom edge down to the PLAYER VIEW's bottom, so the
 * caption drops into the letterbox bar when the picture does not reach the
 * screen edge. This covers both ways a bar can appear: an
 * `AspectRatioFrameLayout` frame shorter than the view (2.39:1 in a 16:9
 * PlayerView) and encoded bars inside a 16:9 frame, because
 * [insetByLetterbox]'s bottom inset is simply discarded by the extension.
 * The rect is never taken PAST the player view, so the canvas still lives
 * inside the PlayerView's own bounds and only the content frame has to stop
 * clipping it.
 *
 * Lower Third and Top are returned untouched: they are anchored to the picture
 * by definition, and Top does not read this edge at all.
 */
internal fun subtitleCanvasRectFor(
    position: SubtitlePositionPreset,
    pictureRect: SubtitleVideoRect,
    playerBottomInParentSpace: Int,
): SubtitleVideoRect {
    if (position != SubtitlePositionPreset.Bottom) return pictureRect
    if (pictureRect.height <= 0) return pictureRect
    val extendedHeight = playerBottomInParentSpace - pictureRect.top
    if (extendedHeight <= pictureRect.height) return pictureRect
    return pictureRect.copy(height = extendedHeight)
}

/**
 * The bottom padding fraction for a canvas that may extend past the picture.
 *
 * Media3 reads the fraction against the SubtitleView's own height, so the
 * screen-anchored Bottom preset has to be converted into the canvas' space:
 * put the caption's bottom edge [SUBTITLE_BOTTOM_SCREEN_FRACTION] of the player
 * height above the player's bottom, whatever the canvas happens to span. Stated
 * against the canvas bottom's real position rather than assuming the extension
 * succeeded, so a canvas that reaches past the player view (zoom overhang) or
 * falls short of it (degenerate geometry) still lands the text in the same
 * place on screen.
 *
 * Lower Third and Top keep the picture-relative
 * [subtitleBottomPaddingFraction], which is what "anchored to the picture"
 * means once the canvas is the picture.
 */
internal fun subtitleBottomPaddingFractionForCanvas(
    position: SubtitlePositionPreset,
    titleSafeFraction: Float,
    canvasHeight: Int,
    canvasBottomInPlayerSpace: Int,
    playerHeight: Int,
): Float {
    if (
        position != SubtitlePositionPreset.Bottom ||
        canvasHeight <= 0 ||
        playerHeight <= 0
    ) {
        return subtitleBottomPaddingFraction(position, titleSafeFraction)
    }
    val targetBottom = playerHeight * (1f - SUBTITLE_BOTTOM_SCREEN_FRACTION)
    return ((canvasBottomInPlayerSpace - targetBottom) / canvasHeight)
        .coerceIn(MIN_SUBTITLE_BOTTOM_PADDING, 1f)
}

/**
 * Size ladder for bitmap cues, as a multiplier on the AUTHORED cue size.
 *
 * Medium is 1.0 — the disc's own typesetting — and the rest follow the shape of
 * the television text ladder in `AndroidSubtitleTextSizePolicy` without its full
 * reach: a PGS cue is a fixed-resolution image, so every step above 1.0 is
 * upscaling real pixels and the text ladder's 1.8x top end would visibly smear.
 */
internal fun bitmapCueScaleFor(preset: SubtitleFontSizePreset): Float = when (preset) {
    SubtitleFontSizePreset.Small -> 0.85f
    SubtitleFontSizePreset.Medium -> 1f
    SubtitleFontSizePreset.Large -> 1.15f
    SubtitleFontSizePreset.XLarge -> 1.3f
    SubtitleFontSizePreset.XXLarge -> 1.5f
}

/**
 * Applies the user's Position and Size to a BITMAP cue by rewriting the cue's
 * own geometry.
 *
 * Media3 1.11.0's `SubtitlePainter.setupBitmapLayout()` derives the destination
 * rect purely from `position`/`positionAnchor`/`line`/`lineAnchor`/`size`/
 * `bitmapHeight` — the caption style, the fixed text size and the bottom-padding
 * fraction are all read only by the text branch. So for PGS/DVB the appearance
 * has to be baked into the cue before `SubtitleView.setCues`, or it has no
 * effect at all.
 *
 * `PgsParser` and `DvbParser` both emit `position` = left fraction with
 * `ANCHOR_TYPE_START`, `line` = top fraction (`LINE_TYPE_FRACTION`) with
 * `ANCHOR_TYPE_START`, `size` = width fraction and `bitmapHeight` = height
 * fraction (verified against the 1.11.0 sources). This preserves whatever
 * anchors the cue carries and re-expresses the same edges through them.
 *
 * Every bitmap cue is re-anchored to the preset, exactly as the text path
 * re-anchors every text cue: the user picked a position and disc subtitles are
 * authored at the bottom regardless.
 *
 * [bottomPaddingFraction] is the SAME fraction the text path writes to
 * `SubtitleView.setBottomPaddingFraction`, so both kinds of cue land on the
 * same line — including the screen-anchored Bottom preset, whose fraction only
 * the sync can work out because it depends on how far the canvas extends past
 * the picture. It defaults to the picture-relative value for callers with no
 * canvas in hand.
 *
 * A cue whose `size`/`bitmapHeight`/`position` are missing or out of range is
 * returned untouched — without a height fraction the painter falls back to the
 * bitmap's own aspect against the parent width, which is not knowable here.
 * Text cues are never touched, and neither is ASS (libass renders that itself).
 */
@UnstableApi
internal fun remapBitmapCue(
    cue: Cue,
    appearance: SubtitleAppearance,
    titleSafeFraction: Float,
    bottomPaddingFraction: Float =
        subtitleBottomPaddingFraction(appearance.position, titleSafeFraction),
): Cue {
    if (cue.bitmap == null) return cue
    val width = cue.size.takeIf(::isUsableCueFraction) ?: return cue
    val height = cue.bitmapHeight.takeIf(::isUsableCueFraction) ?: return cue
    val position = cue.position.takeIf { it.isFinite() && it >= 0f && it <= 1f } ?: return cue

    val left = when (cue.positionAnchor) {
        Cue.ANCHOR_TYPE_END -> position - width
        Cue.ANCHOR_TYPE_MIDDLE -> position - width / 2f
        // START, and TYPE_UNSET which the painter treats as START.
        else -> position
    }

    // Never let a scaled-up cue outgrow the surface it is drawn on.
    val requested = bitmapCueScaleFor(appearance.fontSize)
    val scale = requested.coerceAtMost(minOf(1f / width, 1f / height))
    val scaledWidth = (width * scale).coerceIn(0f, 1f)
    val scaledHeight = (height * scale).coerceIn(0f, 1f)

    // Scale about the cue's own horizontal centre, then clamp on screen. The
    // authored horizontal placement is preserved; only the preset moves it
    // vertically.
    val centerX = left + width / 2f
    val scaledLeft = (centerX - scaledWidth / 2f)
        .coerceIn(0f, (1f - scaledWidth).coerceAtLeast(0f))
    // Top is anchored from the top for the same reason the text path is: a
    // bottom padding places the bottom of a block whose height varies.
    val unclampedTop = if (appearance.position == SubtitlePositionPreset.Top) {
        SUBTITLE_TOP_LINE_FRACTION
    } else {
        1f - bottomPaddingFraction - scaledHeight
    }
    val scaledTop = unclampedTop.coerceIn(0f, (1f - scaledHeight).coerceAtLeast(0f))

    val newPosition = when (cue.positionAnchor) {
        Cue.ANCHOR_TYPE_END -> scaledLeft + scaledWidth
        Cue.ANCHOR_TYPE_MIDDLE -> scaledLeft + scaledWidth / 2f
        else -> scaledLeft
    }
    val newLine = when (cue.lineAnchor) {
        Cue.ANCHOR_TYPE_END -> scaledTop + scaledHeight
        Cue.ANCHOR_TYPE_MIDDLE -> scaledTop + scaledHeight / 2f
        else -> scaledTop
    }

    if (
        newPosition == cue.position &&
        newLine == cue.line &&
        cue.lineType == Cue.LINE_TYPE_FRACTION &&
        scaledWidth == cue.size &&
        scaledHeight == cue.bitmapHeight
    ) {
        // Identical geometry — hand back the same instance so SubtitlePainter
        // keeps its cached layout.
        return cue
    }

    return cue.buildUpon()
        .setPosition(newPosition)
        .setLine(newLine, Cue.LINE_TYPE_FRACTION)
        .setSize(scaledWidth)
        .setBitmapHeight(scaledHeight)
        .build()
}

private fun isUsableCueFraction(value: Float): Boolean =
    value.isFinite() && value > 0f && value <= 1f

@UnstableApi
internal fun remapBitmapCues(
    cueGroup: CueGroup,
    appearance: SubtitleAppearance,
    titleSafeFraction: Float,
    bottomPaddingFraction: Float =
        subtitleBottomPaddingFraction(appearance.position, titleSafeFraction),
): CueGroup {
    if (cueGroup.cues.isEmpty()) return cueGroup
    var changed = false
    val mapped = cueGroup.cues.map { original ->
        val next = remapBitmapCue(original, appearance, titleSafeFraction, bottomPaddingFraction)
        if (next !== original) changed = true
        next
    }
    return if (changed) CueGroup(mapped, cueGroup.presentationTimeUs) else cueGroup
}

@UnstableApi
internal fun neutralizeFullWidthCueSizes(cueGroup: CueGroup): CueGroup {
    if (cueGroup.cues.isEmpty()) return cueGroup
    var changed = false
    val mapped = cueGroup.cues.map { original ->
        val next = neutralizeFullWidthCueSize(original)
        if (next !== original) changed = true
        next
    }
    return if (changed) CueGroup(mapped, cueGroup.presentationTimeUs) else cueGroup
}

/**
 * How many times the sync may re-ask for a layout it has already written to the
 * SubtitleView's params before accepting the answer. Three covers a dropped
 * in-pass `requestLayout()` and the traversal that follows it; beyond that the
 * parent is declining the geometry and retrying would only spin.
 */
private const val MAX_SUBTITLE_RELAYOUT_ATTEMPTS = 3

@UnstableApi
private class SubtitleVideoRectSync(
    playerView: PlayerView,
    private val presentation: AndroidSubtitlePresentation,
    private val libassBridge: LibassBridge?,
    private val onPostLayoutReconciled: () -> Unit,
) :
    View.OnLayoutChangeListener,
    View.OnAttachStateChangeListener,
    Player.Listener {

    /** The canvas geometry last written to the SubtitleView's layout params. */
    private data class RequestedCanvas(
        val width: Int,
        val height: Int,
        val leftMargin: Int,
        val topMargin: Int,
        val gravity: Int,
    )

    private data class LayoutSnapshot(
        val resizeMode: Int,
        val playerWidth: Int,
        val playerHeight: Int,
        val frameLeft: Int,
        val frameTop: Int,
        val frameWidth: Int,
        val frameHeight: Int,
    )

    private val playerViewRef = WeakReference(playerView)
    private val contentFrameRef = WeakReference(
        playerView.findViewById<AspectRatioFrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame
        )
    )
    private var observedPlayer: Player? = null
    private var reconciliationGeneration = 0L
    private var pendingVerification: Runnable? = null
    private var appliedPasses = 0
    private var requestedCanvas: RequestedCanvas? = null
    private var relayoutAttempts = 0
    private var pendingLayoutRequest = false
    private var pendingCanvasPlacement = false
    /** Mirrors the content frame's `clipChildren`, which starts out enabled. */
    private var contentFrameClipped = true

    var letterbox: LetterboxInsets = LetterboxInsets.NONE
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    var titleSafeFraction: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            update()
            reforwardLastCues()
        }

    /**
     * Drives [remapBitmapCue] and, through [SubtitlePositionPreset], the canvas
     * itself: Bottom is screen-anchored and spans past the picture, the other
     * two presets stop at it. Changing it re-places the canvas and re-forwards
     * the last cue group, so a Position change lands on the caption currently
     * on screen without waiting for the next cue or for a parent layout pass.
     */
    var appearance: SubtitleAppearance = SubtitleAppearance.DEFAULT
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            if (previous.position != value.position) update()
            reforwardLastCues()
        }

    /**
     * The bottom padding fraction [applyRect] resolved for the current canvas,
     * shared by the text path (`SubtitleView.setBottomPaddingFraction`) and the
     * bitmap path. Null until the canvas has been placed once; cues arriving
     * before then fall back to the picture-relative fraction.
     */
    private var canvasBottomPaddingFraction: Float? = null
        set(value) {
            if (field == value) return
            field = value
            reforwardLastCues()
        }

    /** The last group received from the player, BEFORE any transformation. */
    private var lastCueGroup: CueGroup? = null

    var isDisposed: Boolean = false
        private set

    private var pendingPreDrawObserver: ViewTreeObserver? = null
    private var pendingPreDrawGeneration = 0L
    private val postLayoutUpdate = ViewTreeObserver.OnPreDrawListener {
        val generation = pendingPreDrawGeneration
        clearPendingPostLayoutUpdate()
        if (!isDisposed && generation == reconciliationGeneration) {
            update()
            val currentPlayerView = playerViewRef.get()
            val appliedSnapshot = currentPlayerView?.let(::currentSnapshot)
            appliedPasses++
            onPostLayoutReconciled()
            if (currentPlayerView != null && appliedSnapshot != null && appliedPasses < 2) {
                postSnapshotVerification(
                    playerView = currentPlayerView,
                    generation = generation,
                    appliedSnapshot = appliedSnapshot,
                )
            }
        }
        true
    }

    init {
        playerView.addOnLayoutChangeListener(this)
        playerView.addOnAttachStateChangeListener(this)
        // PlayerView itself remains full-screen when FIT changes the measured
        // content frame (for example, 4:3 video on a 16:9 TV). Observe that
        // child as well so an early full-screen subtitle measurement cannot
        // survive after the video frame narrows and shift authored ASS cues.
        contentFrameRef.get()?.addOnLayoutChangeListener(this)
    }

    fun update() {
        val playerView = playerViewRef.get() ?: return dispose(null)
        if (playerView.width <= 0 || playerView.height <= 0) return
        val currentPlayer = playerView.player
        if (observedPlayer !== currentPlayer) {
            observedPlayer?.removeListener(this)
            observedPlayer = currentPlayer
            currentPlayer?.addListener(this)
            // PlayerView pulls the raw getCurrentCues() at setPlayer time (before
            // this listener exists), so re-push the neutralized cues once on bind
            // to override that initial full-width write for an already-playing
            // player (e.g. an engine swap mid-cue).
            currentPlayer?.let { forwardNeutralizedCues(playerView, it.currentCues) }
        }
        applyRect(playerView)
    }

    fun updateAndReconcileAfterLayout() {
        update()
        val playerView = playerViewRef.get() ?: return
        if (isDisposed) return
        reconciliationGeneration++
        appliedPasses = 0
        clearPendingVerification(playerView)
        schedulePreDrawFor(reconciliationGeneration)
    }

    private fun schedulePreDrawFor(generation: Long) {
        val playerView = playerViewRef.get() ?: return dispose(null)
        if (isDisposed || generation != reconciliationGeneration) return
        pendingPreDrawObserver?.let { observer ->
            if (observer.isAlive) {
                pendingPreDrawGeneration = generation
                return
            }
            pendingPreDrawObserver = null
        }
        val observer = playerView.viewTreeObserver
        if (!observer.isAlive) return
        pendingPreDrawGeneration = generation
        pendingPreDrawObserver = observer
        observer.addOnPreDrawListener(postLayoutUpdate)
    }

    private fun postSnapshotVerification(
        playerView: PlayerView,
        generation: Long,
        appliedSnapshot: LayoutSnapshot,
    ) {
        lateinit var verification: Runnable
        verification = Runnable {
            if (pendingVerification === verification) {
                pendingVerification = null
            }
            val currentPlayerView = playerViewRef.get()
            if (
                !isDisposed &&
                generation == reconciliationGeneration &&
                appliedPasses < 2 &&
                currentPlayerView != null &&
                currentSnapshot(currentPlayerView) != appliedSnapshot
            ) {
                schedulePreDrawFor(generation)
            }
        }
        pendingVerification = verification
        playerView.post(verification)
    }

    private fun currentSnapshot(playerView: PlayerView): LayoutSnapshot {
        val contentFrame = contentFrameRef.get()
        return LayoutSnapshot(
            resizeMode = playerView.resizeMode,
            playerWidth = playerView.width,
            playerHeight = playerView.height,
            frameLeft = contentFrame?.left ?: 0,
            frameTop = contentFrame?.top ?: 0,
            frameWidth = contentFrame?.width ?: 0,
            frameHeight = contentFrame?.height ?: 0,
        )
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        update()
    }

    /**
     * Both screens re-install this sync via `syncSubtitleVideoBounds` right after
     * `view.player = …`, so this listener is registered after PlayerView's own
     * cue listener and its neutralized `setCues` runs last, winning the frame.
     * See [neutralizeFullWidthCueSize] for why the size is stripped.
     */
    override fun onCues(cueGroup: CueGroup) {
        val playerView = playerViewRef.get() ?: return
        forwardNeutralizedCues(playerView, cueGroup)
    }

    private fun forwardNeutralizedCues(playerView: PlayerView, cueGroup: CueGroup) {
        lastCueGroup = cueGroup
        val subtitleView = playerView.subtitleView ?: return
        val cues = remapBitmapCues(
            cueGroup = remapDefaultTextCuePlacements(
                cueGroup = neutralizeFullWidthCueSizes(cueGroup),
                position = appearance.position,
            ),
            appearance = appearance,
            titleSafeFraction = titleSafeFraction,
            bottomPaddingFraction = canvasBottomPaddingFraction
                ?: subtitleBottomPaddingFraction(appearance.position, titleSafeFraction),
        ).cues
        logSubtitleCueGeometry(cues)
        subtitleView.setCues(cues)
    }

    private fun reforwardLastCues() {
        if (isDisposed) return
        val playerView = playerViewRef.get() ?: return
        val cueGroup = lastCueGroup ?: return
        forwardNeutralizedCues(playerView, cueGroup)
    }

    override fun onLayoutChange(
        v: View,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int,
    ) {
        update()
    }

    override fun onViewAttachedToWindow(v: View) {
        update()
    }

    override fun onViewDetachedFromWindow(v: View) {
        dispose(v)
    }

    private fun applyRect(playerView: PlayerView) {
        val subtitleView = playerView.subtitleView ?: return
        val resizeMode = playerView.resizeMode
        val gravity = Gravity.TOP or Gravity.START
        val position = appearance.position
        // The SubtitleView's parent is the content frame, so the player view's
        // bottom edge — the anchor the Bottom preset needs — is that many
        // pixels down in the space every rect here is written in.
        val contentFrame = contentFrameRef.get()
        val parentTop = contentFrame?.top ?: 0
        val parentHeight = contentFrame?.height ?: playerView.height
        val playerBottomInParentSpace = playerView.height - parentTop
        if (
            presentation == AndroidSubtitlePresentation.Phone &&
            (
                resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT ||
                    resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL
            ) &&
            !letterbox.isDetected &&
            titleSafeFraction <= 0f &&
            // MATCH_PARENT is the content frame, so it can only stand in for the
            // screen-anchored canvas while the frame already reaches the
            // player's bottom. A letterboxed phone frame falls through to the
            // explicit rect, which is what puts Bottom in the bar.
            !(
                position == SubtitlePositionPreset.Bottom &&
                    playerBottomInParentSpace > parentHeight
                )
        ) {
            applyLayoutParams(
                subtitleView = subtitleView,
                width = FrameLayout.LayoutParams.MATCH_PARENT,
                height = FrameLayout.LayoutParams.MATCH_PARENT,
                leftMargin = 0,
                topMargin = 0,
                gravity = gravity,
            )
            setContentFrameClipping(clipped = true)
            libassBridge?.constrainOverlayHeight(0)
            applyBottomPadding(
                subtitleView = subtitleView,
                position = position,
                canvasHeight = parentHeight,
                canvasBottomInPlayerSpace = parentTop + parentHeight,
                playerHeight = playerView.height,
            )
            logSubtitleCanvasGeometry(
                playerView = playerView,
                subtitleView = subtitleView,
                appliedLabel = "MATCH_PARENT",
                resizeMode = resizeMode,
                bottomPaddingFraction = canvasBottomPaddingFraction,
                playerBottomInParentSpace = playerBottomInParentSpace,
            )
            return
        }

        val videoSize = playerView.player?.videoSize ?: VideoSize.UNKNOWN
        val displayedVideoRect = displayedSubtitleVideoRect(
            viewWidth = playerView.width,
            viewHeight = playerView.height,
            videoWidth = videoSize.width,
            videoHeight = videoSize.height,
            videoPixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
            resizeMode = resizeMode,
        )
        val pictureRect = selectSubtitleCanvasRect(
            contentFrameRect = playerView.contentFrameSubtitleRect(),
            displayedVideoRect = displayedVideoRect,
        ).insetByLetterbox(letterbox).insetByTitleSafe(titleSafeFraction)
        val rect = subtitleCanvasRectFor(
            position = position,
            pictureRect = pictureRect,
            playerBottomInParentSpace = playerBottomInParentSpace,
        )
        // Only a canvas that actually reaches past the frame needs the frame to
        // stop clipping, and only while it does: extending back over the
        // title-safe bottom inset stays inside the frame and changes nothing.
        // The canvas never leaves the PlayerView, so the frame is the one
        // ancestor in the way.
        setContentFrameClipping(clipped = rect.top + rect.height <= parentHeight)
        // libass scales the script to the frame it is given, so the ASS overlay
        // must NOT follow the canvas into the bar — authored typesetting keeps
        // the picture on every preset.
        libassBridge?.constrainOverlayHeight(
            if (rect.height > pictureRect.height) pictureRect.height else 0,
        )
        applyLayoutParams(
            subtitleView = subtitleView,
            width = rect.width,
            height = rect.height,
            leftMargin = rect.left,
            topMargin = rect.top,
            gravity = gravity,
        )
        applyBottomPadding(
            subtitleView = subtitleView,
            position = position,
            canvasHeight = rect.height,
            canvasBottomInPlayerSpace = parentTop + rect.top + rect.height,
            playerHeight = playerView.height,
        )
        logSubtitleCanvasGeometry(
            playerView = playerView,
            subtitleView = subtitleView,
            appliedLabel = "${rect.width}x${rect.height}@${rect.left},${rect.top}",
            resizeMode = resizeMode,
            bottomPaddingFraction = canvasBottomPaddingFraction,
            playerBottomInParentSpace = playerBottomInParentSpace,
        )
    }

    /**
     * Writes the resolved bottom padding to both cue paths at once. Media3
     * reads the text one straight off the view; the bitmap one is baked into
     * the cues, so a change has to re-forward whatever is on screen.
     */
    private fun applyBottomPadding(
        subtitleView: SubtitleView,
        position: SubtitlePositionPreset,
        canvasHeight: Int,
        canvasBottomInPlayerSpace: Int,
        playerHeight: Int,
    ) {
        val fraction = subtitleBottomPaddingFractionForCanvas(
            position = position,
            titleSafeFraction = titleSafeFraction,
            canvasHeight = canvasHeight,
            canvasBottomInPlayerSpace = canvasBottomInPlayerSpace,
            playerHeight = playerHeight,
        )
        subtitleView.setBottomPaddingFraction(fraction)
        canvasBottomPaddingFraction = fraction
    }

    /**
     * The content frame clips its children, which is exactly right for the
     * video surface and exactly wrong for a caption canvas that is meant to
     * reach into the letterbox bar below the picture. Toggled rather than
     * disabled once, so a preset or aspect change puts the clip back, and
     * restored on [dispose] because the PlayerView outlives this sync.
     */
    private fun setContentFrameClipping(clipped: Boolean) {
        if (clipped == contentFrameClipped) return
        val frame = contentFrameRef.get() ?: return
        contentFrameClipped = clipped
        frame.clipChildren = clipped
        frame.clipToPadding = clipped
        // A parent's clipChildren clips each CHILD'S drawing to that child's own
        // bounds — so with the frame un-clipped, the PlayerView would still cut
        // the frame's overflow (our canvas in the bar) at the picture's edge.
        // Verified on a Shield: captions stopped exactly at the frame bottom
        // until this was released too. The PlayerView's own bounds are never
        // exceeded (the canvas ends at the PlayerView bottom), and its parent
        // keeps clipping to it, so nothing can escape the player surface.
        (frame.parent as? ViewGroup)?.let { host ->
            host.clipChildren = clipped
            host.clipToPadding = clipped
        }
    }

    /**
     * Writes the caption canvas geometry, and keeps writing until the VIEW —
     * not just its params object — has actually adopted it.
     *
     * The invariant this defends: `LayoutParams` are a request, `left/top/
     * width/height` are the answer, and the two can disagree indefinitely.
     * Writing the params and calling `requestLayout()` does not settle it. When
     * the video size arrives and `exo_content_frame` narrows to the letterboxed
     * aspect, the frame measures its children FIRST and dispatches
     * `onLayoutChange` after, so the canvas is measured at the outgoing aspect
     * and the corrected params land a beat too late. Nothing re-measures the
     * frame afterwards — see [requestSubtitleLayout] for why the follow-up
     * request never gets serviced — and a params-only diff sees no change on
     * every later pass and never asks again. Measured on a Shield: a 2.39:1
     * title after a 16:9 measurement left a 1728x972 canvas hanging 361px below
     * an 803px frame, bottom-anchored cues drawn off screen, for the whole
     * session.
     *
     * So: diff the params to decide what to WRITE, and diff the laid-out bounds
     * to decide whether the canvas still needs PLACING — by request first, and
     * by [placeSubtitleCanvas] when the request goes unanswered.
     */
    private fun applyLayoutParams(
        subtitleView: View,
        width: Int,
        height: Int,
        leftMargin: Int,
        topMargin: Int,
        gravity: Int,
    ) {
        val requested = RequestedCanvas(width, height, leftMargin, topMargin, gravity)
        if (requested != requestedCanvas) {
            requestedCanvas = requested
            relayoutAttempts = 0
        }
        val current = subtitleView.layoutParams as? FrameLayout.LayoutParams
        val params = current ?: FrameLayout.LayoutParams(width, height)
        if (
            current == null ||
            params.width != width ||
            params.height != height ||
            params.leftMargin != leftMargin ||
            params.topMargin != topMargin ||
            params.gravity != gravity
        ) {
            params.width = width
            params.height = height
            params.leftMargin = leftMargin
            params.topMargin = topMargin
            params.gravity = gravity
            subtitleView.layoutParams = params
            relayoutAttempts = 0
            requestSubtitleLayout(subtitleView)
            return
        }
        // Params already say the right thing, which is not the same as the view
        // having been laid out that way. `isLayoutRequested` is deliberately NOT
        // consulted: the stuck state IS a set flag that no ancestor acts on, so
        // reading it as "a layout is coming" is what makes the wedge permanent.
        // The laid-out bounds are the only honest signal.
        if (subtitleLayoutMatches(subtitleView, width, height, leftMargin, topMargin)) return
        requestSubtitleLayout(subtitleView)
        placeSubtitleCanvas(subtitleView, width, height, leftMargin, topMargin)
    }

    /**
     * Asks the framework for a layout, bounded by [MAX_SUBTITLE_RELAYOUT_ATTEMPTS].
     *
     * This is the polite path and it is not sufficient on its own, which is why
     * [placeSubtitleCanvas] follows it. Measured on a Shield: the request does
     * reach `exo_content_frame` (its `isLayoutRequested` flips to true), and the
     * frame is then never laid out again — the PlayerView is hosted in a Compose
     * `AndroidView`, whose holder answers a child's `requestLayout()` by
     * invalidating its own Compose layout node rather than scheduling a View
     * traversal, and with the node's constraints unchanged nothing re-measures
     * the interop subtree. The frame keeps a pending request forever and the
     * canvas keeps the geometry of whichever aspect ratio was measured first.
     *
     * A request issued while the tree is in layout, or while the parent has its
     * own pending one, is posted instead: `View.requestLayout` is dropped
     * outright by `ViewRootImpl` in the first case and stops walking up in the
     * second.
     */
    private fun requestSubtitleLayout(subtitleView: View) {
        val parent = subtitleView.parent as? View
        if (subtitleView.isInLayout || parent?.isLayoutRequested == true) {
            if (pendingLayoutRequest) return
            pendingLayoutRequest = true
            subtitleView.post {
                pendingLayoutRequest = false
                if (!isDisposed && subtitleView.isAttachedToWindow) {
                    issueLayoutRequest(subtitleView)
                }
            }
            return
        }
        issueLayoutRequest(subtitleView)
    }

    private fun issueLayoutRequest(subtitleView: View) {
        if (relayoutAttempts >= MAX_SUBTITLE_RELAYOUT_ATTEMPTS) return
        relayoutAttempts++
        subtitleView.requestLayout()
    }

    /**
     * Measures and lays the caption canvas out directly, at the geometry this
     * sync just computed.
     *
     * Doing a child's layout by hand is unusual and deliberate: the whole point
     * of this class is that the subtitle canvas's bounds are ours to decide —
     * they are derived from the content frame, not negotiated with it — and the
     * hosting arrangement (see [requestSubtitleLayout]) provides no reliable way
     * to have the parent do it. The measurement is EXACTLY the requested size,
     * the same spec `FrameLayout` would produce from these params, so this is
     * the layout the parent would have run, run at the only moment anyone is
     * willing to run it. `SubtitleView.onLayout` still positions its own
     * children from here, so the ASS overlay keeps matching.
     *
     * Only ever reached when the bounds already disagree, so it cannot fight a
     * parent that is doing its job. Deferred out of an in-progress layout pass,
     * where measuring another subtree is not safe.
     */
    private fun placeSubtitleCanvas(
        subtitleView: View,
        width: Int,
        height: Int,
        leftMargin: Int,
        topMargin: Int,
    ) {
        if (subtitleView.isInLayout) {
            if (pendingCanvasPlacement) return
            pendingCanvasPlacement = true
            subtitleView.post {
                pendingCanvasPlacement = false
                if (
                    !isDisposed &&
                    subtitleView.isAttachedToWindow &&
                    !subtitleLayoutMatches(subtitleView, width, height, leftMargin, topMargin)
                ) {
                    measureAndLayoutSubtitleCanvas(
                        subtitleView,
                        width,
                        height,
                        leftMargin,
                        topMargin,
                    )
                }
            }
            return
        }
        measureAndLayoutSubtitleCanvas(subtitleView, width, height, leftMargin, topMargin)
    }

    private fun measureAndLayoutSubtitleCanvas(
        subtitleView: View,
        width: Int,
        height: Int,
        leftMargin: Int,
        topMargin: Int,
    ) {
        val parent = subtitleView.parent as? View
        val resolvedWidth = if (width == FrameLayout.LayoutParams.MATCH_PARENT) {
            parent?.width ?: return
        } else {
            width
        }
        val resolvedHeight = if (height == FrameLayout.LayoutParams.MATCH_PARENT) {
            parent?.height ?: return
        } else {
            height
        }
        if (resolvedWidth <= 0 || resolvedHeight <= 0) return
        subtitleView.measure(
            View.MeasureSpec.makeMeasureSpec(resolvedWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(resolvedHeight, View.MeasureSpec.EXACTLY),
        )
        subtitleView.layout(
            leftMargin,
            topMargin,
            leftMargin + resolvedWidth,
            topMargin + resolvedHeight,
        )
    }

    /**
     * Whether the view's laid-out bounds already are the requested canvas.
     *
     * A child with exact params is measured EXACTLY, and the sync always lays
     * out TOP|START, so the margins are the expected origin inside the content
     * frame. A view that has never been laid out counts as matching: its first
     * layout is already on the way.
     */
    private fun subtitleLayoutMatches(
        subtitleView: View,
        width: Int,
        height: Int,
        leftMargin: Int,
        topMargin: Int,
    ): Boolean {
        if (subtitleView.width <= 0 && subtitleView.height <= 0) return true
        val parent = subtitleView.parent as? View
        val expectedWidth = if (width == FrameLayout.LayoutParams.MATCH_PARENT) {
            parent?.width ?: return true
        } else {
            width
        }
        val expectedHeight = if (height == FrameLayout.LayoutParams.MATCH_PARENT) {
            parent?.height ?: return true
        } else {
            height
        }
        return subtitleView.width == expectedWidth &&
            subtitleView.height == expectedHeight &&
            subtitleView.left == leftMargin &&
            subtitleView.top == topMargin
    }

    private fun dispose(view: View?) {
        if (isDisposed) return
        val playerView = (view as? PlayerView) ?: playerViewRef.get()
        setContentFrameClipping(clipped = true)
        isDisposed = true
        reconciliationGeneration++
        clearPendingPostLayoutUpdate()
        clearPendingVerification(playerView)
        observedPlayer?.removeListener(this)
        observedPlayer = null
        // Cues hold decoded bitmaps; do not outlive the view they were for.
        lastCueGroup = null
        playerView?.removeOnLayoutChangeListener(this)
        playerView?.removeOnAttachStateChangeListener(this)
        contentFrameRef.get()?.removeOnLayoutChangeListener(this)
    }

    private fun clearPendingPostLayoutUpdate() {
        pendingPreDrawObserver?.let { observer ->
            if (observer.isAlive) {
                observer.removeOnPreDrawListener(postLayoutUpdate)
            }
        }
        pendingPreDrawObserver = null
    }

    private fun clearPendingVerification(playerView: PlayerView?) {
        pendingVerification?.let { verification ->
            playerView?.removeCallbacks(verification)
        }
        pendingVerification = null
    }
}

@UnstableApi
private fun PlayerView.contentFrameSubtitleRect(): SubtitleVideoRect? {
    val frame = findViewById<AspectRatioFrameLayout>(
        androidx.media3.ui.R.id.exo_content_frame
    ) ?: return null
    return displayedSubtitleContentFrameRect(
        viewWidth = width,
        viewHeight = height,
        frameLeft = frame.left,
        frameTop = frame.top,
        frameWidth = frame.width,
        frameHeight = frame.height,
    )
}

internal fun resolveSubtitleUrl(serverUrl: String, url: String): String =
    resolvePlaybackStreamUrl(serverUrl, url)

internal data class SubtitleSelection(
    val mediaTrackGroup: TrackGroup,
    val trackIndex: Int,
)

internal fun resolveSubtitleSelection(
    tracks: Tracks,
    subtitleIndex: Int,
): SubtitleSelection? {
    var flatIndex = 0
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_TEXT) continue
        for (trackIndex in 0 until group.length) {
            if (flatIndex == subtitleIndex) {
                return SubtitleSelection(group.mediaTrackGroup, trackIndex)
            }
            flatIndex++
        }
    }
    return null
}

internal fun resolveSubtitleSelection(
    tracks: Tracks,
    subtitle: PlayerSubtitleInfo,
): SubtitleSelection? {
    val candidates = textTrackCandidates(tracks)
    val mounted = candidates.map(TextTrackCandidate::track)
    val match = resolveMountedSubtitle(subtitle, mounted) ?: return null
    return candidates.firstOrNull { it.track.index == match.track.index }?.selection
}

internal fun resolveSubtitleSelection(
    tracks: Tracks,
    identity: SubtitleIdentity,
): SubtitleSelection? {
    val candidates = textTrackCandidates(tracks)
    val match = resolveMountedSubtitle(identity, candidates.map(TextTrackCandidate::track)) ?: return null
    return candidates.firstOrNull { it.track.index == match.track.index }?.selection
}

private data class TextTrackCandidate(
    val selection: SubtitleSelection,
    val track: MountedSubtitleTrack,
)

private fun textTrackCandidates(tracks: Tracks): List<TextTrackCandidate> {
    val candidates = mutableListOf<TextTrackCandidate>()
    var flatIndex = 0
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_TEXT) continue
        for (trackIndex in 0 until group.length) {
            val format = group.getTrackFormat(trackIndex)
            candidates += TextTrackCandidate(
                selection = SubtitleSelection(group.mediaTrackGroup, trackIndex),
                track = MountedSubtitleTrack(
                    index = flatIndex,
                    trackId = format.id,
                    label = format.label,
                    language = format.language,
                    codec = format.subtitleCodecOrMime(),
                    forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                    hearingImpaired = format.isHearingImpairedSubtitle(),
                ),
            )
            flatIndex++
        }
    }
    return candidates
}

private fun Format.isHearingImpairedSubtitle(): Boolean =
    roleFlags and (C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 ||
        subtitleLabelIndicatesHearingImpaired(label)

/**
 * Bitmap (image-based) subtitle detection over codec names and mimes.
 * Normalization strips ALL non-alphanumerics so ffprobe names
 * ("dvb_subtitle", "hdmv_pgs_subtitle", "dvd_subtitle"), short names
 * ("dvbsub"/"dvbsubs"/"vobsub") and the Media3 mimes
 * (`MimeTypes.APPLICATION_PGS` / `APPLICATION_DVBSUBS`) all classify
 * identically — Apple parity with `ApplePlaybackRoutePlanner`'s token set.
 */
private fun Format.subtitleCodecOrMime(): String? =
    if (sampleMimeType == MEDIA3_CUES_MIME_TYPE) {
        codecs ?: sampleMimeType
    } else {
        sampleMimeType ?: codecs
    }

private fun Tracks.describeTextTracks(): String {
    val parts = mutableListOf<String>()
    var flatIndex = 0
    for (group in groups) {
        if (group.type != C.TRACK_TYPE_TEXT) continue
        for (trackIndex in 0 until group.length) {
            val format = group.getTrackFormat(trackIndex)
            parts += "$flatIndex:${format.label ?: format.language ?: "?"}" +
                "[selected=${group.isTrackSelected(trackIndex)} supported=${group.isTrackSupported(trackIndex)}]"
            flatIndex++
        }
    }
    return parts.joinToString(prefix = "[", postfix = "]")
}

private const val TAG = "SiloSubtitles"
private const val MEDIA3_CUES_MIME_TYPE = "application/x-media3-cues"

/**
 * Diagnostic tag for subtitle placement. Silent unless explicitly enabled:
 *
 *     adb shell setprop log.tag.SiloSubtitleGeom DEBUG
 *
 * Placement here spans three coordinate spaces — window, PlayerView, and the
 * content frame the SubtitleView is actually a child of — and then the cue's
 * own anchoring on top. A caption that lands in the wrong place looks identical
 * whichever of those is at fault, and static reading has twice now produced a
 * confident answer that the device disagreed with. These print the real numbers
 * so the space at fault can be read off rather than deduced.
 */
private const val SUBTITLE_GEOM_TAG = "SiloSubtitleGeom"

/**
 * Where the caption canvas ended up, in every space at once. Compare
 * `subtitleView` (on screen, after layout) against `player` and `frame`: if the
 * canvas is centred on screen and the text still is not, the cue is positioning
 * itself and [logSubtitleCueGeometry] has the answer instead.
 */
@UnstableApi
private fun logSubtitleCanvasGeometry(
    playerView: PlayerView,
    subtitleView: View,
    appliedLabel: String,
    resizeMode: Int,
    bottomPaddingFraction: Float? = null,
    playerBottomInParentSpace: Int? = null,
) {
    if (!Log.isLoggable(SUBTITLE_GEOM_TAG, Log.DEBUG)) return
    val frame = playerView.findViewById<AspectRatioFrameLayout>(
        androidx.media3.ui.R.id.exo_content_frame,
    )
    val playerLoc = IntArray(2).also(playerView::getLocationOnScreen)
    val subtitleLoc = IntArray(2).also(subtitleView::getLocationOnScreen)
    Log.d(
        SUBTITLE_GEOM_TAG,
        "resize=" + resizeMode +
            " player=" + playerView.width + "x" + playerView.height +
            "@" + playerLoc[0] + "," + playerLoc[1] +
            " frame=" + frame?.width + "x" + frame?.height +
            "@" + frame?.left + "," + frame?.top +
            " frameClip=" + frame?.clipChildren +
            " applied=" + appliedLabel +
            " playerBottomInParent=" + playerBottomInParentSpace +
            " bottomPad=" + bottomPaddingFraction +
            " subtitleView=" + subtitleView.width + "x" + subtitleView.height +
            "@" + subtitleLoc[0] + "," + subtitleLoc[1] +
            " subtitleBottomOnScreen=" + (subtitleLoc[1] + subtitleView.height) +
            " subtitleParent=" + (subtitleView.parent as? View)?.javaClass?.simpleName,
    )
}

/** The cue's own anchoring, which positions text independently of the canvas. */
private fun logSubtitleCueGeometry(cues: List<Cue>) {
    if (!Log.isLoggable(SUBTITLE_GEOM_TAG, Log.DEBUG)) return
    val cue = cues.firstOrNull() ?: return
    Log.d(
        SUBTITLE_GEOM_TAG,
        "cue bitmap=" + (cue.bitmap != null) +
            " position=" + cue.position +
            " positionAnchor=" + cue.positionAnchor +
            " size=" + cue.size +
            " line=" + cue.line +
            " lineType=" + cue.lineType +
            " lineAnchor=" + cue.lineAnchor +
            " textAlignment=" + cue.textAlignment +
            " text=" + cue.text?.toString()?.take(28),
    )
}
