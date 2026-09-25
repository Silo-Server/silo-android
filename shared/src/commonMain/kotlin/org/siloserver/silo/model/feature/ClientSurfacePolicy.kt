package org.siloserver.silo.model.feature

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether Watch Party entry points may appear. The platform injects it from
 * the device-local Settings → Experimental toggle, which defaults on in debug
 * builds and off in release builds. The server's capabilities still decide
 * whether the feature works once shown.
 */
interface WatchPartyExposure {
    val enabled: StateFlow<Boolean>
}
