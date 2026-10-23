package org.siloserver.silo.overlays

/**
 * One overlay type, registered statically. [getValue] extracts the label
 * from [OverlayData]; returning `null` hides the badge for that item even
 * if the user enabled the overlay (matching Apple's `getValue == nil`).
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
    /** When true, render the icon without the text label. */
    val iconOnly: Boolean = false,
    /**
     * Shown beneath the row in the settings UI when the data source
     * isn't wired up yet (e.g. IMDb Top 250).
     */
    val availabilityNote: String? = null,
    val getValue: (OverlayData) -> String?,
    val getIcon: ((OverlayData) -> OverlayIconId?)? = null,
    /** Hex accent for this item. A null result keeps [defaultAccent]. */
    val getAccent: ((OverlayData) -> String?)? = null,
)
