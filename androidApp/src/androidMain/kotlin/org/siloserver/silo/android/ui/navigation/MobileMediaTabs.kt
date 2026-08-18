package org.siloserver.silo.android.ui.navigation

import org.siloserver.silo.model.navigation.MediaModeCapabilities
import org.siloserver.silo.model.settings.PrimaryMenu
import org.siloserver.silo.model.settings.PrimaryMenuBuiltin
import org.siloserver.silo.model.settings.PrimaryMenuItem

val Tab.isUtilityTab: Boolean
    get() = this == Tab.Downloads

/** Aggregate layouts shown by the mobile settings surface. */
enum class MobileNavigationPreset(val label: String) {
    STANDARD("Standard"),
    MEDIA_FIRST("Media first"),
    MINIMAL("Minimal"),
    CUSTOM("Custom"),
}

/** Downloads is dynamic utility chrome and is deliberately absent here. */
val configurableMobileTabs: List<Tab> = listOf(
    Tab.Home,
    Tab.Libraries,
    Tab.ForYou,
    Tab.Calendar,
)

private val standardMobileTabs = configurableMobileTabs
private val mediaFirstMobileTabs = listOf(
    Tab.Libraries,
    Tab.Home,
    Tab.ForYou,
    Tab.Calendar,
)
private val minimalMobileTabs = listOf(Tab.Home, Tab.ForYou)

/**
 * Projects the richer cross-client primary-menu document into mobile's four
 * aggregate destinations. Media builtins and direct library/section/collection
 * pins all lead to the Libraries hub; their first wire position determines the
 * aggregate tab's position, and later entries are deduplicated visually.
 */
fun projectedMobileTabs(primaryMenu: PrimaryMenu?): List<Tab> {
    if (primaryMenu == null) return standardMobileTabs
    val projected = buildList {
        primaryMenu.items.forEach { item ->
            val tab = item.mobileTab()
            if (tab !in this) add(tab)
        }
    }
    // Cached or forward-version documents can be constructed outside the
    // strict revision-5 codec. Keep the shell navigable even if none of their
    // entries project to a destination this client can render.
    return projected.ifEmpty { listOf(Tab.Home) }
}

fun mobileNavigationPreset(primaryMenu: PrimaryMenu?): MobileNavigationPreset =
    when (projectedMobileTabs(primaryMenu)) {
        standardMobileTabs -> MobileNavigationPreset.STANDARD
        mediaFirstMobileTabs -> MobileNavigationPreset.MEDIA_FIRST
        minimalMobileTabs -> MobileNavigationPreset.MINIMAL
        else -> MobileNavigationPreset.CUSTOM
    }

/**
 * Apple-aligned mobile shell. The server-authored order/visibility controls
 * the four content destinations; Downloads remains an automatic trailing
 * utility whenever local or server download state exists.
 */
fun visibleMobileTabs(
    @Suppress("UNUSED_PARAMETER") capabilities: MediaModeCapabilities,
    showDownloads: Boolean,
    primaryMenu: PrimaryMenu? = null,
): List<Tab> = buildList {
    addAll(projectedMobileTabs(primaryMenu))
    if (showDownloads) add(Tab.Downloads)
}

/** Raw mobile default used as the edit base once an inherited menu is changed. */
fun defaultMobilePrimaryMenu(): PrimaryMenu = PrimaryMenu(
    listOf(
        PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
        // The mobile Libraries destination aggregates every media family.
        // Keeping all four builtins in the wire document preserves semantic
        // intent for another mobile client while Android renders one tab.
        PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.MOVIES),
        PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.SERIES),
        PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.MUSIC),
        PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.AUDIOBOOKS),
        PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.FOR_YOU),
        PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.CALENDAR),
    ),
)

/**
 * Moves one aggregate destination while retaining every underlying wire item.
 * Library, section, and collection entries move together in their original
 * relative order, so editing For You or Calendar never destroys pins Android
 * does not render as separate bottom tabs.
 */
fun moveMobileTab(menu: PrimaryMenu, tab: Tab, offset: Int): PrimaryMenu {
    if (tab.isUtilityTab || offset == 0) return menu
    val order = projectedMobileTabs(menu).toMutableList()
    val from = order.indexOf(tab)
    if (from < 0) return menu
    val to = (from + offset).coerceIn(0, order.lastIndex)
    if (from == to) return menu
    order.add(to, order.removeAt(from))
    return rebuildMobileMenu(menu, order)
}

/**
 * Whether the aggregate destination can actually be hidden from this document.
 *
 * Home is contract-required and Downloads is automatic utility chrome. The
 * Libraries hub additionally refuses to hide while the synced document pins a
 * library, section, or collection: those pins are authored on iPhone/web,
 * Android mobile has no editor for them, and they always project into the
 * Libraries tab. Hiding would therefore be impossible to honour — the tab
 * would stay on screen while the media builtins were stripped, and
 * [showMobileTab] could never bring them back because the tab never
 * disappeared. A `null` menu means "inherit the native default", which has no
 * pins.
 */
fun canHideMobileTab(menu: PrimaryMenu?, tab: Tab): Boolean {
    if (tab == Tab.Home || tab.isUtilityTab) return false
    if (tab != Tab.Libraries) return true
    return menu == null || menu.items.none { it !is PrimaryMenuItem.Builtin }
}

/**
 * Hides one aggregate destination by removing only the builtin entries that
 * project to it.
 *
 * Library, section, and collection pins are never removed: Android mobile does
 * not author them, they are invisible as roots here (they are folded into the
 * Libraries tab), and dropping them would permanently destroy destinations for
 * every other client sharing the same `profile_client` document.
 */
fun hideMobileTab(menu: PrimaryMenu, tab: Tab): PrimaryMenu {
    if (!canHideMobileTab(menu, tab)) return menu
    return PrimaryMenu(
        menu.items.filterNot { it is PrimaryMenuItem.Builtin && it.mobileTab() == tab },
    )
}

/** Adds a missing aggregate destination at the end of the authored menu. */
fun showMobileTab(menu: PrimaryMenu, tab: Tab): PrimaryMenu {
    if (tab.isUtilityTab || tab in projectedMobileTabs(menu)) return menu
    val additions = when (tab) {
        Tab.Home -> listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME))
        Tab.Libraries -> defaultMobilePrimaryMenu().items.filter { it.mobileTab() == Tab.Libraries }
        Tab.ForYou -> listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.FOR_YOU))
        Tab.Calendar -> listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.CALENDAR))
        Tab.Downloads -> emptyList()
    }
    return PrimaryMenu(menu.items + additions)
}

fun primaryMenuForMobilePreset(
    preset: MobileNavigationPreset,
    currentMenu: PrimaryMenu? = null,
): PrimaryMenu? {
    if (preset == MobileNavigationPreset.CUSTOM) return null
    if (preset == MobileNavigationPreset.STANDARD && currentMenu == null) return null

    val order = when (preset) {
        MobileNavigationPreset.STANDARD -> standardMobileTabs
        MobileNavigationPreset.MEDIA_FIRST -> mediaFirstMobileTabs
        MobileNavigationPreset.MINIMAL -> minimalMobileTabs
        MobileNavigationPreset.CUSTOM -> return null
    }
    return rebuildMobileMenuForPreset(
        menu = currentMenu ?: defaultMobilePrimaryMenu(),
        order = order,
    )
}

fun fallbackMobileTab(
    visibleTabs: List<Tab>,
    defaultTab: Tab,
): Tab? {
    if (defaultTab in visibleTabs) return defaultTab
    return visibleTabs.firstOrNull { !it.isUtilityTab } ?: visibleTabs.firstOrNull()
}

private fun PrimaryMenuItem.mobileTab(): Tab = when (this) {
    is PrimaryMenuItem.Builtin -> when (destination) {
        PrimaryMenuBuiltin.HOME -> Tab.Home
        PrimaryMenuBuiltin.FOR_YOU -> Tab.ForYou
        PrimaryMenuBuiltin.CALENDAR -> Tab.Calendar
        PrimaryMenuBuiltin.MOVIES,
        PrimaryMenuBuiltin.SERIES,
        PrimaryMenuBuiltin.MUSIC,
        PrimaryMenuBuiltin.AUDIOBOOKS -> Tab.Libraries
    }
    is PrimaryMenuItem.Library,
    is PrimaryMenuItem.Section,
    is PrimaryMenuItem.Collection -> Tab.Libraries
}

private fun rebuildMobileMenu(menu: PrimaryMenu, order: List<Tab>): PrimaryMenu {
    val buckets = menu.items.groupBy { it.mobileTab() }
    return PrimaryMenu(order.flatMap { buckets[it].orEmpty() })
}

/**
 * Reorders the current aggregate buckets without replacing their rich wire
 * items. A bucket missing from a custom/minimal menu is restored from the
 * native default so selecting a preset still produces that preset's complete
 * aggregate layout.
 *
 * A preset whose layout omits a bucket (Minimal has no Libraries) is held to
 * the same invariant [canHideMobileTab] enforces on the hide path: an omitted
 * bucket that still holds a library/section/collection pin is preserved whole,
 * builtins included, appended in its original relative order. Stripping only
 * its builtins would be unrecoverable — the pins keep the aggregate tab in
 * [projectedMobileTabs], so [showMobileTab] short-circuits, [canHideMobileTab]
 * refuses, and re-selecting a preset that includes the bucket never reaches the
 * default restore below. An omitted bucket holding only builtins is dropped as
 * the preset intends: the tab genuinely leaves the projection and Add Menu Item
 * can put it back.
 */
private fun rebuildMobileMenuForPreset(menu: PrimaryMenu, order: List<Tab>): PrimaryMenu {
    val currentBuckets = menu.items.groupBy { it.mobileTab() }
    val defaultBuckets = defaultMobilePrimaryMenu().items.groupBy { it.mobileTab() }
    val laid = order.flatMap { tab ->
        currentBuckets[tab].orEmpty().ifEmpty { defaultBuckets[tab].orEmpty() }
    }
    val preservedTabs = currentBuckets.keys.filter { tab ->
        tab !in order && currentBuckets.getValue(tab).any { it !is PrimaryMenuItem.Builtin }
    }.toSet()
    val preserved = menu.items.filter { it.mobileTab() in preservedTabs }
    return PrimaryMenu(laid + preserved)
}
