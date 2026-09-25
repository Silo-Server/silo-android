package org.siloserver.silo.model.feature

import kotlinx.coroutines.flow.StateFlow

/**
 * Client-side exposure switches for implemented surfaces that are not ready to
 * appear in normal user navigation yet. Routes, repositories, and deep-link
 * plumbing can remain compiled while menus/actions stay hidden.
 */
const val CLIENT_WATCH_TOGETHER_SURFACE_ENABLED: Boolean = false

/**
 * Whether Watch Party entry points may appear. The platform injects it from
 * the device-local Settings → Experimental toggle, which defaults on in debug
 * builds and off in release builds. The server's capabilities still decide
 * whether the feature works once shown.
 */
interface WatchPartyExposure {
    val enabled: StateFlow<Boolean>
}
