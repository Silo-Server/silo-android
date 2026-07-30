package org.siloserver.silo.overlays

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable identifiers for every overlay the system knows about. The wire
 * format (server `defaults.card_overlays`, profile `ui.card_overlays`) uses
 * these as keys, so they MUST stay in sync with web's `OverlayId`,
 * iOS `OverlayId`, and tvOS. Adding a new overlay requires updating the
 * registry; renaming an existing one is a breaking change for stored
 * prefs.
 *
 * The [raw] value is the wire string; serialization for the prefs
 * document is performed manually in [OverlaySchema] so JSON stays
 * byte-compatible across platforms.
 */
@Serializable
enum class OverlayId(val raw: String) {
    // tech
    @SerialName("resolution") Resolution("resolution"),
    @SerialName("hdr") Hdr("hdr"),
    @SerialName("resolution_hdr") ResolutionHdr("resolution_hdr"),
    @SerialName("audio") Audio("audio"),
    @SerialName("audio_channels") AudioChannels("audio_channels"),
    @SerialName("video_codec") VideoCodec("video_codec"),
    @SerialName("container") Container("container"),
    @SerialName("aspect_ratio") AspectRatio("aspect_ratio"),
    @SerialName("release_type") ReleaseType("release_type"),
    @SerialName("edition") Edition("edition"),
    @SerialName("multi_audio") MultiAudio("multi_audio"),
    @SerialName("multi_sub") MultiSub("multi_sub"),

    // ratings
    @SerialName("rating_imdb") RatingImdb("rating_imdb"),
    @SerialName("rating_tmdb") RatingTmdb("rating_tmdb"),
    @SerialName("rating_rt") RatingRt("rating_rt"),
    @SerialName("rating_rt_audience") RatingRtAudience("rating_rt_audience"),
    @SerialName("content_rating") ContentRating("content_rating"),

    // metadata
    @SerialName("year") Year("year"),
    @SerialName("runtime") Runtime("runtime"),
    @SerialName("original_language") OriginalLanguage("original_language"),
    @SerialName("studio") Studio("studio"),
    @SerialName("network") Network("network"),

    // ribbons
    @SerialName("show_status") ShowStatus("show_status"),
    @SerialName("imdb_top_250") ImdbTop250("imdb_top_250"),
    @SerialName("rt_certified_fresh") RtCertifiedFresh("rt_certified_fresh"),
    ;

    companion object {
        fun fromRaw(value: String?): OverlayId? =
            value?.let { v -> entries.firstOrNull { it.raw == v } }
    }
}

@Serializable
enum class OverlayPosition(val raw: String) {
    @SerialName("top-left") TopLeft("top-left"),
    @SerialName("top-right") TopRight("top-right"),
    @SerialName("bottom-left") BottomLeft("bottom-left"),
    @SerialName("bottom-right") BottomRight("bottom-right"),
    ;

    val displayName: String
        get() = when (this) {
            TopLeft -> "Top Left"
            TopRight -> "Top Right"
            BottomLeft -> "Bottom Left"
            BottomRight -> "Bottom Right"
        }

    companion object {
        fun fromRaw(value: String?): OverlayPosition? =
            value?.let { v -> entries.firstOrNull { it.raw == v } }
    }
}

@Serializable
enum class OverlayCategory(val raw: String) {
    @SerialName("tech") Tech("tech"),
    @SerialName("ratings") Ratings("ratings"),
    @SerialName("metadata") Metadata("metadata"),
    @SerialName("ribbons") Ribbons("ribbons"),
    ;

    val displayName: String
        get() = when (this) {
            Tech -> "Media Info"
            Ratings -> "Ratings"
            Metadata -> "Metadata"
            Ribbons -> "Ribbons"
        }

    val description: String
        get() = when (this) {
            Tech -> "Quality and codec details from the file itself."
            Ratings -> "External scores and age ratings."
            Metadata -> "Year, runtime, studio, network, language."
            Ribbons -> "Status badges and award ribbons (data sources pending)."
        }
}

@Serializable
enum class PresetId(val raw: String) {
    @SerialName("minimal") Minimal("minimal"),
    @SerialName("classic") Classic("classic"),
    @SerialName("vibrant") Vibrant("vibrant"),
    @SerialName("pill") Pill("pill"),
    @SerialName("square") Square("square"),
    ;

    val label: String
        get() = when (this) {
            Minimal -> "Minimal"
            Classic -> "Classic"
            Vibrant -> "Vibrant"
            Pill -> "Pill"
            Square -> "Square"
        }

    val description: String
        get() = when (this) {
            Minimal -> "Near-invisible. Tiny text, no background."
            Classic -> "Semi-transparent dark pill with a thin border. The default."
            Vibrant -> "Opaque, accent-colored badges. High contrast."
            Pill -> "Larger pill with more padding. Works well with icons."
            Square -> "Blocky, high-density. Plex-inspired."
        }

    companion object {
        fun fromRaw(value: String?): PresetId? =
            value?.let { v -> entries.firstOrNull { it.raw == v } }
    }
}

/**
 * Per-overlay user configuration. [accentColor] and [showIcon] are
 * optional overrides — `null` means "use the registry default" /
 * "use the preset's icon preference".
 */
data class OverlayItemConfig(
    val enabled: Boolean,
    val position: OverlayPosition,
    /** Hex string (`"#f5c518"`); `null` falls back to `OverlayDef.defaultAccent`. */
    val accentColor: String? = null,
    /** When non-null, overrides the preset's icon preference for this badge. */
    val showIcon: Boolean? = null,
)

/**
 * Versioned root document stored under the user setting key
 * `ui.card_overlays`. Serialized as a typed JSON object and PUT to
 * `/api/v1/settings/values/ui.card_overlays?scope=profile`. Shared across
 * web, iOS, tvOS, and Android.
 */
data class CardOverlayPrefs(
    val version: Int,
    val preset: PresetId,
    /**
     * User-chosen render order within each corner. Empty list means
     * "use registry order".
     */
    val order: List<OverlayId>,
    val items: Map<OverlayId, OverlayItemConfig>,
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

/**
 * Typed icon identifiers. Lucide icons map to platform icons in the
 * renderer; brand marks (HDR10, DV, Atmos, AV1, Tomato) render from
 * inline shape views.
 */
enum class OverlayIconId(val raw: String) {
    // generic
    Star("star"),
    Clock("clock"),
    Tv("tv"),
    Film("film"),
    Award("award"),
    Ribbon("ribbon"),
    Subtitles("subtitles"),
    Languages("languages"),
    Building("building"),
    Shield("shield"),
    Layout("layout"),
    Monitor("monitor"),
    Volume("volume"),
    Calendar("calendar"),
    Globe("globe"),

    // brand marks
    Hdr10("hdr10"),
    Hdr("hdr"),
    DolbyVision("dolby-vision"),
    Atmos("atmos"),
    Av1("av1"),
    Tomato("tomato"),
}

/**
 * How the preset paints the accent color. Mirrors web's `AccentStrategy`
 * so the visual outcome matches per-platform.
 */
enum class AccentStrategy(val raw: String) {
    Background("background"),
    Border("border"),
    Text("text"),
    Dot("dot"),
}

/**
 * Curated accent palette shown in the settings UI. Mirrors the web
 * `ACCENT_PALETTE` so a color picked on one platform looks the same on
 * the other.
 */
object OverlayAccentPalette {
    data class Entry(val label: String, val hex: String)

    val entries: List<Entry> = listOf(
        Entry("Gold", "#f5c518"),
        Entry("Tomato", "#fa320a"),
        Entry("Orange", "#f97316"),
        Entry("Amber", "#f59e0b"),
        Entry("Emerald", "#10b981"),
        Entry("Cyan", "#06b6d4"),
        Entry("Blue", "#3b82f6"),
        Entry("Indigo", "#6366f1"),
        Entry("Violet", "#8b5cf6"),
        Entry("Pink", "#ec4899"),
        Entry("Slate", "#64748b"),
        Entry("White", "#ffffff"),
    )
}
