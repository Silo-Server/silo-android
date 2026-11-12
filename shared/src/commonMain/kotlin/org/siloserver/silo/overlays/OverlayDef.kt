package org.siloserver.silo.overlays

import org.siloserver.silo.common.i18n.UiText

/**
 * One overlay type, registered statically. Mirrors web's `OverlayDef`.
 * [getValue] extracts the badge's text from [OverlayData]; returning `null`
 * hides the badge for that item even if the user enabled the overlay
 * (matching Apple's `getValue == nil`).
 * [label], [description] and [availabilityNote] are the settings copy web and
 * Apple show; Android has no overlay settings screen, so they stay English.
 *
 * No Compose/UI here — this is the foundation only.
 */
data class OverlayDef(
    val id: OverlayId,
    val category: OverlayCategory,
    val label: String,
    val description: String,
    val defaultPosition: OverlayPosition,
    val defaultEnabled: Boolean,
    /**
     * Static icon (e.g. "monitor" for resolution). When [getIcon] is set,
     * the dynamic icon overrides this.
     */
    val iconId: OverlayIconId? = null,
    val defaultAccent: String? = null,
    /** Whether the settings UI should expose the "show icon" toggle. */
    val iconCapable: Boolean,
    /** When true, render the icon without the text label. Android-only; no registry entry sets it. */
    val iconOnly: Boolean = false,
    /**
     * Shown beneath the row in the settings UI when the data source
     * isn't wired up yet (e.g. IMDb Top 250).
     */
    val availabilityNote: String? = null,
    /**
     * Settings manifest revision whose card-overlays schema first accepts this
     * id; `null` means every revision does. See
     * [OverlaySchema.isOverlaySupportedBy].
     */
    val introducedInManifest: Int? = null,
    /**
     * The rating source a rating badge shows, for a rating an administrator
     * can hide (`rt_critic`, `rt_audience`). Settings offer the badge only
     * while the server's ratings capability lists it
     * ([OverlayRegistry.isOffered]).
     */
    val ratingSource: String? = null,
    val getValue: (OverlayData) -> UiText?,
    /**
     * A rating's source mark ("IMDb") for presets that draw it as a chip
     * ([OverlayPreset.sourceChips]), set together with [getScore].
     */
    val sourceMark: UiText? = null,
    /** A rating's bare score ("9.2", "89%"), drawn after a source chip or an uploaded image. */
    val getScore: ((OverlayData) -> UiText?)? = null,
    /**
     * The key an [OverlayItemConfig.valueImages] entry matches: the value as
     * the API returns it ("2160p", "DV HDR10", "Returning"), not the formatted
     * label, so every client matches the same keys. `null` means the badge
     * takes no per-value images.
     */
    val getValueKey: ((OverlayData) -> String?)? = null,
    /** Value keys a settings UI suggests when assigning per-value images. */
    val valueKeySuggestions: List<String> = emptyList(),
    val getIcon: ((OverlayData) -> OverlayIconId?)? = null,
)
