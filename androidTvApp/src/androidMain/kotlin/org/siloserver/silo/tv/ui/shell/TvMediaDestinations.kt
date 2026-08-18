package org.siloserver.silo.tv.ui.shell

import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.settings.PrimaryMenu
import org.siloserver.silo.model.settings.PrimaryMenuBuiltin
import org.siloserver.silo.model.settings.PrimaryMenuItem
import org.siloserver.silo.model.settings.UiCustomizationCodec
import org.siloserver.silo.model.settings.effectivePrimaryMenuForSupport
import org.siloserver.silo.tv.ui.navigation.TvMainRoute

/** Capability-aware audiobook visibility that can resolve authored library IDs after loading. */
data class TvAudiobookVisibilityPolicy(
    val primaryMenu: PrimaryMenu?,
    val uiCustomizationSupported: Boolean?,
    val legacyFallback: Boolean,
) {
    fun resolve(libraries: List<UserLibrary> = emptyList()): Boolean =
        effectivePrimaryMenuForSupport(primaryMenu, uiCustomizationSupported)
            ?.items
            ?.any { item ->
                item == PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.AUDIOBOOKS) ||
                    item is PrimaryMenuItem.Library && libraries.any { library ->
                        library.id == item.libraryId &&
                            tabTypeFor(library) == TvLibraryTabType.Audiobooks
                    }
            }
            ?: legacyFallback
}

/** One capability-aware source of truth for every TV audiobook surface. */
fun resolvedTvAudiobookVisibility(
    primaryMenu: PrimaryMenu?,
    uiCustomizationSupported: Boolean?,
    legacyFallback: Boolean,
    libraries: List<UserLibrary> = emptyList(),
): Boolean = TvAudiobookVisibilityPolicy(
    primaryMenu = primaryMenu,
    uiCustomizationSupported = uiCustomizationSupported,
    legacyFallback = legacyFallback,
).resolve(libraries)

/**
 * Skyline content-type-first shell (§3.1): a fixed root order of `Home`, then
 * one tab per [TvLibraryTabType] the profile can actually see (a library of
 * that type exists), then `Calendar`. Search and For You are no longer tabs —
 * Search is a trailing icon button and For You is reached as a Home row.
 *
 * Mirrors tvOS `TVMainTabView.visibleRoots`.
 */
fun visibleTvRoots(
    libraries: List<UserLibrary>,
    /** tvOS navPrefs.showAudiobooks parity: the Audiobooks tab is opt-in
     *  (hidden by default) even when an audiobook library exists. */
    showAudiobooks: Boolean = false,
    primaryMenu: PrimaryMenu? = null,
): List<TvRootDestination> = buildList {
    if (primaryMenu != null) {
        primaryMenu.items.forEach { item ->
            when (item) {
                is PrimaryMenuItem.Builtin -> builtinRoot(
                    destination = item.destination,
                    libraries = libraries,
                    // Once a family-authored menu exists, its own inclusion or
                    // omission is authoritative. The legacy device flag only
                    // shapes the null/native fallback menu.
                    showAudiobooks = true,
                )?.let(::add)
                is PrimaryMenuItem.Library -> {
                    val library = libraries.firstOrNull { it.id == item.libraryId }
                    val type = library?.let(::tabTypeFor)
                    if (library != null && type != null) {
                        add(TvRootDestination.LibraryType(type, library.id, item.label))
                    }
                }
                // Android TV can open these from their existing library and
                // collection surfaces, but has no stable root route for them.
                // Preserve them in the wire document; omit them from the bar.
                is PrimaryMenuItem.Section,
                is PrimaryMenuItem.Collection -> Unit
            }
        }
        // Strict revision-5 documents always contain Home. Still protect the
        // focus graph from manually-constructed, stale, or future documents
        // whose entries cannot be rendered by this version of Android TV.
        if (isEmpty()) add(TvRootDestination.Home)
        return@buildList
    }

    add(TvRootDestination.Home)
    TvLibraryTabType.entries
        .filter { type -> libraries.any { type.matches(it) } }
        .filter { type -> type != TvLibraryTabType.Audiobooks || showAudiobooks }
        .forEach { type -> add(TvRootDestination.LibraryType(type)) }
    // tvOS root order: libraries, then For You, then Calendar.
    add(TvRootDestination.ForYou)
    add(TvRootDestination.Calendar)
}

private fun builtinRoot(
    destination: PrimaryMenuBuiltin,
    libraries: List<UserLibrary>,
    showAudiobooks: Boolean,
): TvRootDestination? = when (destination) {
    PrimaryMenuBuiltin.HOME -> TvRootDestination.Home
    PrimaryMenuBuiltin.FOR_YOU -> TvRootDestination.ForYou
    PrimaryMenuBuiltin.CALENDAR -> TvRootDestination.Calendar
    PrimaryMenuBuiltin.MOVIES -> typeRoot(TvLibraryTabType.Movies, libraries, true)
    PrimaryMenuBuiltin.SERIES -> typeRoot(TvLibraryTabType.Series, libraries, true)
    PrimaryMenuBuiltin.MUSIC -> typeRoot(TvLibraryTabType.Music, libraries, true)
    PrimaryMenuBuiltin.AUDIOBOOKS ->
        typeRoot(TvLibraryTabType.Audiobooks, libraries, showAudiobooks)
}

private fun typeRoot(
    type: TvLibraryTabType,
    libraries: List<UserLibrary>,
    enabled: Boolean,
): TvRootDestination? =
    TvRootDestination.LibraryType(type).takeIf { enabled && libraries.any(type::matches) }

private fun tabTypeFor(library: UserLibrary): TvLibraryTabType? =
    TvLibraryTabType.entries.firstOrNull { it.matches(library) }

fun firstTvRoute(): String = TvMainRoute.Home.route

fun TvRootDestination.isVisibleIn(destinations: List<TvRootDestination>): Boolean =
    when (this) {
        is TvRootDestination.LibraryType -> destinations.any { destination ->
            destination is TvRootDestination.LibraryType && destination.type == type
        }
        else -> this in destinations
    }

/**
 * Resolves the exact bar entry to highlight for a route. Library pins and
 * built-in media roots intentionally share one content route, so [routeRoot]
 * alone is not enough to preserve D-pad focus identity.
 */
fun selectedTvRoot(
    routeRoot: TvRootDestination?,
    destinations: List<TvRootDestination>,
    selectedLibraryId: Int?,
    exactSelection: TvRootDestination.LibraryType?,
): TvRootDestination? {
    if (routeRoot !is TvRootDestination.LibraryType) return routeRoot
    val libraryRoots = destinations.filterIsInstance<TvRootDestination.LibraryType>()
    return exactSelection
        ?.takeIf { it.type == routeRoot.type && it in libraryRoots }
        ?: selectedLibraryId?.let { libraryId ->
            libraryRoots.firstOrNull {
                it.type == routeRoot.type && it.libraryId == libraryId
            }
        }
        // A customized menu can expose only direct-library entries for a
        // media type. Never return the synthetic route root in that case: it
        // is not part of the visible focus graph, so the bar would render no
        // selected entry and Up could not restore focus deterministically.
        ?: libraryRoots.firstOrNull { it.type == routeRoot.type }
        ?: routeRoot
}

/**
 * Saveable identity for the exact media tab selected by the viewer.
 *
 * Labels are presentation, not identity: resolving this value against the
 * current visible roots after recreation picks up a renamed pinned library
 * without accidentally changing a built-in Movies selection into that pin.
 */
internal fun TvRootDestination.LibraryType.saveableSelectionIdentity(): String =
    libraryId?.let { id ->
        UiCustomizationCodec.identity(PrimaryMenuItem.Library(id, title))
    } ?: UiCustomizationCodec.identity(
        PrimaryMenuItem.Builtin(
            when (type) {
                TvLibraryTabType.Movies -> PrimaryMenuBuiltin.MOVIES
                TvLibraryTabType.Series -> PrimaryMenuBuiltin.SERIES
                TvLibraryTabType.Music -> PrimaryMenuBuiltin.MUSIC
                TvLibraryTabType.Audiobooks -> PrimaryMenuBuiltin.AUDIOBOOKS
            },
        ),
    )

/** Resolves a restored semantic identity to today's root object and label. */
internal fun resolveSavedTvLibraryDestination(
    identity: String?,
    destinations: List<TvRootDestination>,
): TvRootDestination.LibraryType? {
    if (identity == null) return null
    return destinations
        .filterIsInstance<TvRootDestination.LibraryType>()
        .firstOrNull { it.saveableSelectionIdentity() == identity }
}

/**
 * Preserves the exact bar identity when committing a library scope. A direct
 * tab selection is authoritative over any same-type panel that happened to be
 * open from an earlier dwell preview; otherwise the panel that produced the
 * commit owns the identity. This keeps a stale built-in Movies preview from
 * replacing a just-selected pinned Movies destination merely because its
 * `libraryId == null` used to act as a wildcard.
 */
internal fun committedTvLibraryDestination(
    type: TvLibraryTabType,
    libraryId: Int,
    explicitDestination: TvRootDestination.LibraryType?,
    panelDestination: TvRootDestination.LibraryType?,
    destinations: List<TvRootDestination>,
): TvRootDestination.LibraryType? {
    val libraryRoots = destinations.filterIsInstance<TvRootDestination.LibraryType>()
    fun eligible(destination: TvRootDestination.LibraryType?): TvRootDestination.LibraryType? =
        destination?.takeIf {
            it in libraryRoots && it.type == type &&
                (it.libraryId == null || it.libraryId == libraryId)
        }

    return eligible(explicitDestination)
        ?: eligible(panelDestination)
        ?: libraryRoots.firstOrNull { it.type == type && it.libraryId == libraryId }
        ?: libraryRoots.firstOrNull { it.type == type && it.libraryId == null }
}

/**
 * Chooses the initial scope for a media-root cascade. A direct library pin is
 * its own scope even when another library of the same type is currently open;
 * built-in type roots continue from the active library for that type.
 */
internal fun cascadeCurrentScopeId(
    destination: TvRootDestination.LibraryType,
    activeLibraryId: Int?,
): Int? = destination.libraryId ?: activeLibraryId
