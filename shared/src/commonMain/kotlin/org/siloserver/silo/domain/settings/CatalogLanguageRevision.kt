package org.siloserver.silo.domain.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bumps when the active profile's metadata language changes on this device.
 *
 * The server localizes titles and descriptions per profile, so every catalog
 * list on screen holds text in the old language until it is read again. Home,
 * For You and library surfaces collect this and re-read quietly; a profile
 * switch needs no signal because it rebuilds those screens.
 */
class CatalogLanguageRevision {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun changed() {
        _revision.value += 1
    }
}
