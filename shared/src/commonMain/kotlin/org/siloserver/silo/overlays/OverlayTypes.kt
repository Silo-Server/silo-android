package org.siloserver.silo.overlays

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
    @SerialName("resolution_hdr_audio") ResolutionHdrAudio("resolution_hdr_audio"),
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

/**
 * Where a badge sits on a poster: three corners and a rail centred on each
 * side edge. Mirrors web's `OVERLAY_POSITIONS`. The top-right corner belongs
 * to the watch-status indicator ([CardWatchStatus]), so no badge is placed
 * there; a stored `top-right` parses as [RightEdge] (see [fromStored]).
 */
@Serializable
enum class OverlayPosition(val raw: String) {
    @SerialName("top-left") TopLeft("top-left"),
    @SerialName("bottom-left") BottomLeft("bottom-left"),
    @SerialName("bottom-right") BottomRight("bottom-right"),
    @SerialName("left-edge") LeftEdge("left-edge"),
    @SerialName("right-edge") RightEdge("right-edge"),
    ;

    val displayName: String
        get() = when (this) {
            TopLeft -> "Top Left"
            BottomLeft -> "Bottom Left"
            BottomRight -> "Bottom Right"
            LeftEdge -> "Left Edge"
            RightEdge -> "Right Edge"
        }

    companion object {
        /** The legacy corner the contract still accepts; documents before settings revision 25 use it. */
        const val LEGACY_TOP_RIGHT = "top-right"

        /** Exactly one of the five positions; `top-right` is not one. */
        fun fromRaw(value: String?): OverlayPosition? =
            value?.let { v -> entries.firstOrNull { it.raw == v } }

        /**
         * A position as stored in a document, web's `parsePosition`: a badge
         * stored in the `top-right` corner moves to the [RightEdge] rail, the
         * same side of the card. `null` for anything else.
         */
        fun fromStored(value: String?): OverlayPosition? =
            if (value == LEGACY_TOP_RIGHT) RightEdge else fromRaw(value)
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
    @SerialName("plate") Plate("plate"),
    @SerialName("band") Band("band"),
    ;

    /** English settings copy, from web's `web.settings.overlays.preset.*` strings. */
    val label: String
        get() = when (this) {
            Minimal -> "Minimal"
            Classic -> "Classic"
            Vibrant -> "Vibrant"
            Pill -> "Pill"
            Square -> "Square"
            Plate -> "Plate"
            Band -> "Edge band"
        }

    val description: String
        get() = when (this) {
            Minimal -> "Near-invisible. Tiny text, no background."
            Classic -> "Semi-transparent dark pill with a white border. The default."
            Vibrant -> "Opaque, accent-colored badges. High contrast."
            Pill -> "Larger pill with more padding. Works well with icons."
            Square -> "Blocky, high-density. Plex-inspired."
            Plate -> "Large dark plates with heavy type. Ratings lead with a coloured source chip."
            Band -> "Each edge's badges in one strip of marks over a soft scrim."
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
    /**
     * Uploaded image ref ([OverlayImage.ref]) drawn in the icon's place beside
     * the label. On a rating badge it stands for the source, so the label
     * shows only the score ([OverlayDef.getScore]).
     */
    val image: String? = null,
    /**
     * Value key ([OverlayDef.getValueKey]) → uploaded image ref that replaces
     * the whole badge while the badge shows that value. `null` when none; never
     * an empty map.
     */
    val valueImages: Map<String, String>? = null,
    /**
     * Fields of the stored entry this client does not know, kept verbatim so a
     * save does not erase another client's choice. Empty for a document built
     * here.
     */
    val extras: JsonObject = EmptyJson,
)

enum class OverlayShape(val raw: String) {
    Pill("pill"),
    Rounded("rounded"),
    Square("square"),
    ;

    companion object {
        fun fromRaw(value: String?): OverlayShape? = entries.firstOrNull { it.raw == value }
    }
}

/** Badge text weight. The steps are 200 apart so each reads differently at badge sizes. */
enum class OverlayFontWeight(val raw: String, val weight: Int) {
    Regular("regular", 400),
    Semibold("semibold", 600),
    Extrabold("extrabold", 800),
    ;

    companion object {
        fun fromRaw(value: String?): OverlayFontWeight? = entries.firstOrNull { it.raw == value }
    }
}

/**
 * Overrides layered on the preset (web `OverlayStyle`, applied by
 * [applyOverlayStyle]). Each `null` field keeps the preset's own value.
 * Colours are stored as the contract writes them: [background] and
 * [borderColor] `#RRGGBBAA` (alpha last), [textColor] `#RRGGBB`.
 */
data class OverlayStyle(
    /** 0.75–2; scales text, padding, icons and gaps. */
    val size: Double? = null,
    val shape: OverlayShape? = null,
    val background: String? = null,
    val textColor: String? = null,
    val borderColor: String? = null,
    /** 0–3 at the 185-unit reference card width. */
    val borderWidth: Int? = null,
    val fontWeight: OverlayFontWeight? = null,
    val uppercase: Boolean? = null,
    /** Drop shadow under the badge. */
    val shadow: Boolean? = null,
    /** Stored style fields this client does not know, kept verbatim. */
    val extras: JsonObject = EmptyJson,
) {
    /** No known field set and nothing to preserve: the same as no style. */
    val isEmpty: Boolean
        get() = size == null && shape == null && background == null && textColor == null &&
            borderColor == null && borderWidth == null && fontWeight == null &&
            uppercase == null && shadow == null && extras.isEmpty()
}

/**
 * Versioned root document stored under the user setting key
 * `ui.card_overlays`. Parse it with [OverlaySchema.parse] and write it with
 * [OverlaySchema.serialize] (with an [OverlayServerSupport] for a server
 * write). Shared across web, iOS, tvOS, and Android.
 *
 * The document keeps what Android does not render, as the architecture doc
 * requires of every client that parses it: [passthroughItems] holds the
 * entries of ids outside [OverlayRegistry] (for example `advisory_age`,
 * `request_status`, or an id a later revision adds), [order] keeps their
 * places, and [extras] / [OverlayItemConfig.extras] / [OverlayStyle.extras]
 * hold unknown fields. All of them are plain values, so `==` and `copy`
 * compare and copy whole documents.
 */
data class CardOverlayPrefs(
    val version: Int,
    val preset: PresetId,
    /**
     * Render order of badges that share a position, as wire ids (an
     * [OverlayId.raw] or a [passthroughItems] key). Empty means registry
     * order. Raw strings so ids this client does not know keep their place;
     * use [orderedIds] for the known ones.
     */
    val order: List<String>,
    /** One entry per registry overlay. */
    val items: Map<OverlayId, OverlayItemConfig>,
    /** Look overrides on top of [preset]; `null` when none. */
    val style: OverlayStyle? = null,
    /**
     * Item entries whose id is not an [OverlayId], verbatim except that a
     * `top-right` position reads `right-edge`. Never rendered on Android.
     */
    val passthroughItems: Map<String, JsonObject> = emptyMap(),
    /** Unknown root fields, kept verbatim. */
    val extras: JsonObject = EmptyJson,
) {
    /** [order] restricted to the ids this client knows, in order. */
    val orderedIds: List<OverlayId>
        get() = order.mapNotNull(OverlayId::fromRaw)

    companion object {
        const val CURRENT_VERSION = 2
    }
}

/**
 * An image an administrator uploaded for badges, as
 * `GET /api/v2/settings/overlay-config` lists it in `images` (OpenAPI
 * `OverlayImage`). [ref] is what documents store in
 * [OverlayItemConfig.image] / [OverlayItemConfig.valueImages]; [url] is where
 * the bytes are (`/api/v2/overlay-images/{ref}` on the wire, made absolute
 * against the answering server when the config is decoded), which never
 * changes meaning and can be cached forever.
 */
@Serializable
data class OverlayImage(
    val ref: String,
    val name: String,
    val url: String,
    val width: Int,
    val height: Int,
)

internal val EmptyJson = JsonObject(emptyMap())

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
