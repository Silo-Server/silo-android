package org.siloserver.silo.common.player

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.View
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
import org.siloserver.silo.libass.LibassBridge
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
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
class SubtitleManager(
    private val libassBridge: LibassBridge? = null,
) {

    private val videoRectSyncs = WeakHashMap<PlayerView, SubtitleVideoRectSync>()

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
            if (!isMedia3TextSidecarMimeType(mimeType)) {
                return@mapNotNull null
            }

            MediaItem.SubtitleConfiguration.Builder(Uri.parse(absoluteUrl))
                .setMimeType(mimeType)
                .setLanguage(subtitle.language)
                .setLabel(subtitle.label ?: subtitle.language ?: "Track ${subtitle.index}")
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
            ?: resolveSubtitleSelection(player.currentTracks, subtitleIndex)
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
        val selection = resolveSubtitleSelection(player.currentTracks, subtitle)
            ?: resolveSubtitleSelection(player.currentTracks, subtitleIndex)
            ?: return null
        return selection.mediaTrackGroup.getFormat(selection.trackIndex).id
    }

    /**
     * Applies the user's [SubtitleAppearance] to the [PlayerView]'s subtitle layer.
     *
     * Maps onto Media3 via [CaptionStyleCompat] (colors + edge style + typeface),
     * [androidx.media3.ui.SubtitleView.setFractionalTextSize] (relative-to-view-height
     * font scale), and [androidx.media3.ui.SubtitleView.setBottomPaddingFraction]
     * (vertical position within the surface).
     *
     * Media3-rendered text uses the user's appearance. ASS/SSA is rendered by
     * libass and deliberately preserves the script's authored typesetting,
     * animation, positioning, and embedded fonts, matching the Apple player.
     */
    fun applyAppearance(playerView: PlayerView, appearance: SubtitleAppearance) {
        val subtitleView = playerView.subtitleView ?: return
        libassBridge?.attachTo(subtitleView)
        val safe = appearance.sanitized()

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
        subtitleView.setFractionalTextSize(
            fractionalSizeFor(safe.fontSize),
            /* fractionalRelativeToTextSize = */ false,
        )
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
            SubtitleVideoRectSync(playerView).also { videoRectSyncs[playerView] = it }
        } else {
            existing
        }
        sync.update()
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
            appearance.textOutline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            appearance.backgroundStyle == SubtitleBackgroundStylePreset.Outline ->
                CaptionStyleCompat.EDGE_TYPE_OUTLINE
            appearance.backgroundStyle == SubtitleBackgroundStylePreset.Shadow ->
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
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

    private fun fractionalSizeFor(preset: SubtitleFontSizePreset): Float {
        return when (preset) {
            SubtitleFontSizePreset.Small -> 0.032f
            SubtitleFontSizePreset.Medium -> 0.040f
            SubtitleFontSizePreset.Large -> 0.050f
            SubtitleFontSizePreset.XLarge -> 0.060f
            SubtitleFontSizePreset.XXLarge -> 0.072f
        }
    }

    private fun bottomPaddingFor(position: SubtitlePositionPreset): Float {
        return when (position) {
            SubtitlePositionPreset.Bottom -> 0.06f
            SubtitlePositionPreset.LowerThird -> 0.18f
            SubtitlePositionPreset.Top -> 0.74f
        }
    }

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

    private fun isMedia3TextSidecarMimeType(mimeType: String): Boolean =
        when (mimeType) {
            MimeTypes.TEXT_VTT,
            MimeTypes.TEXT_SSA,
            MimeTypes.APPLICATION_SUBRIP,
            MimeTypes.APPLICATION_TTML,
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

@UnstableApi
private class SubtitleVideoRectSync(playerView: PlayerView) :
    View.OnLayoutChangeListener,
    View.OnAttachStateChangeListener,
    Player.Listener {

    private val playerViewRef = WeakReference(playerView)
    private val contentFrameRef = WeakReference(
        playerView.findViewById<AspectRatioFrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame
        )
    )
    private var observedPlayer: Player? = null

    var isDisposed: Boolean = false
        private set

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
        val subtitleView = playerView.subtitleView ?: return
        subtitleView.setCues(neutralizeFullWidthCueSizes(cueGroup).cues)
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
        val videoSize = playerView.player?.videoSize ?: VideoSize.UNKNOWN
        val rect = playerView.contentFrameSubtitleRect()
            ?: displayedSubtitleVideoRect(
                viewWidth = playerView.width,
                viewHeight = playerView.height,
                videoWidth = videoSize.width,
                videoHeight = videoSize.height,
                videoPixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
                resizeMode = playerView.resizeMode,
            )
        val current = subtitleView.layoutParams as? FrameLayout.LayoutParams
        val params = current ?: FrameLayout.LayoutParams(rect.width, rect.height)
        val gravity = Gravity.TOP or Gravity.START
        if (
            current == null ||
            params.width != rect.width ||
            params.height != rect.height ||
            params.leftMargin != rect.left ||
            params.topMargin != rect.top ||
            params.gravity != gravity
        ) {
            params.width = rect.width
            params.height = rect.height
            params.leftMargin = rect.left
            params.topMargin = rect.top
            params.gravity = gravity
            subtitleView.layoutParams = params
            subtitleView.requestLayout()
        }
    }

    private fun dispose(view: View?) {
        if (isDisposed) return
        observedPlayer?.removeListener(this)
        observedPlayer = null
        val playerView = (view as? PlayerView) ?: playerViewRef.get()
        playerView?.removeOnLayoutChangeListener(this)
        playerView?.removeOnAttachStateChangeListener(this)
        contentFrameRef.get()?.removeOnLayoutChangeListener(this)
        isDisposed = true
    }
}

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
    val label = subtitle.label?.trim()?.takeIf { it.isNotBlank() }
    val language = subtitle.language?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    val family = subtitleCodecFamily(subtitle.codec ?: subtitle.url)
    if (label == null && language == null && family == null) return null

    val candidates = textTrackCandidates(tracks)
    if (label != null) {
        candidates.firstOrNull { it.label?.trim() == label }?.let { return it.selection }
    }
    if (label != null) {
        val normalizedLabel = label.lowercase()
        candidates.firstOrNull { candidate ->
            candidate.label?.trim()?.lowercase() == normalizedLabel
        }?.let { return it.selection }
    }
    if (language != null) {
        val languageMatches = candidates.filter {
            it.language?.trim()?.lowercase() == language
        }
        family?.let { targetFamily ->
            languageMatches.firstOrNull { it.codecFamily == targetFamily }
                ?.let { return it.selection }
        }
        val targetIsBitmap = isBitmapSubtitleCodecOrMime(subtitle.codec)
        languageMatches.firstOrNull { it.isBitmap == targetIsBitmap }
            ?.let { return it.selection }
        languageMatches.firstOrNull()?.let { return it.selection }
    }
    family?.let { targetFamily ->
        candidates.firstOrNull { it.codecFamily == targetFamily }
            ?.let { return it.selection }
    }
    return null
}

private data class TextTrackCandidate(
    val selection: SubtitleSelection,
    val label: String?,
    val language: String?,
    val isBitmap: Boolean,
    val codecFamily: String?,
)

private fun textTrackCandidates(tracks: Tracks): List<TextTrackCandidate> {
    val candidates = mutableListOf<TextTrackCandidate>()
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_TEXT) continue
        for (trackIndex in 0 until group.length) {
            val format = group.getTrackFormat(trackIndex)
            candidates += TextTrackCandidate(
                selection = SubtitleSelection(group.mediaTrackGroup, trackIndex),
                label = format.label,
                language = format.language,
                isBitmap = isBitmapSubtitleCodecOrMime(format.subtitleCodecOrMime()),
                codecFamily = subtitleCodecFamily(format.subtitleCodecOrMime()),
            )
        }
    }
    return candidates
}

/**
 * Bitmap (image-based) subtitle detection over codec names and mimes.
 * Normalization strips ALL non-alphanumerics so ffprobe names
 * ("dvb_subtitle", "hdmv_pgs_subtitle", "dvd_subtitle"), short names
 * ("dvbsub"/"dvbsubs"/"vobsub") and the Media3 mimes
 * (`MimeTypes.APPLICATION_PGS` / `APPLICATION_DVBSUBS`) all classify
 * identically — Apple parity with `ApplePlaybackRoutePlanner`'s token set.
 */
fun isBitmapSubtitleCodecOrMime(codecOrMime: String?): Boolean {
    val normalized = codecOrMime
        ?.filter { it.isLetterOrDigit() }
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
        ?: return false
    return normalized.contains("pgs") ||
        normalized.contains("dvd") ||
        normalized.contains("dvbsub") ||
        normalized.contains("vobsub")
}

private fun subtitleCodecFamily(codecOrMime: String?): String? {
    val normalized = codecOrMime
        ?.filter { it.isLetterOrDigit() }
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return when {
        normalized.contains("pgs") -> "pgs"
        normalized.contains("vobsub") || normalized.contains("dvdsubtitle") -> "vobsub"
        normalized.contains("dvbsub") -> "dvbsub"
        normalized.contains("subrip") || normalized.endsWith("srt") -> "subrip"
        normalized.contains("webvtt") || normalized.endsWith("vtt") -> "webvtt"
        normalized.contains("tx3g") || normalized.contains("movtext") -> "tx3g"
        normalized.contains("ssa") || normalized.contains("ass") -> "ssa"
        normalized.contains("ttml") -> "ttml"
        normalized.contains("cea608") || normalized.contains("eia608") -> "cea608"
        normalized.contains("cea708") -> "cea708"
        else -> null
    }
}

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
