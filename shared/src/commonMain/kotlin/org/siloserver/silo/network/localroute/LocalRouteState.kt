package org.siloserver.silo.network.localroute

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide state the transport reads on every request: the route in use
 * and the keys pinned for local addresses. [LocalRouteController] decides what
 * goes here; the transport only reads it and reports failures, which take a
 * route out of use at once, before the controller hears about them.
 */
class LocalRouteState {
    private val _active = MutableStateFlow<LocalRoute?>(null)

    /** The route requests should use now, if any. */
    val active: StateFlow<LocalRoute?> = _active.asStateFlow()

    private val pins = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _events = MutableSharedFlow<LocalRouteEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Failures the transport saw, for the controller. */
    val events: SharedFlow<LocalRouteEvent> = _events.asSharedFlow()

    /** Set by the controller only. */
    fun publish(route: LocalRoute?) {
        _active.value = route
    }

    /**
     * A request on [route] failed to connect. Takes it out of use at once if it
     * is still the active one, so requests from now on go to the saved address.
     */
    fun reportFailure(route: LocalRoute) {
        _active.update { current -> if (current?.generation == route.generation) null else current }
        _events.tryEmit(LocalRouteEvent.Failed(route))
    }

    /** [host]:[port] presented a key other than its pin: rotated, or not the server. */
    fun reportKeyMismatch(host: String, port: Int) {
        _active.update { current ->
            if (current != null && current.localOrigin.host.equals(host, ignoreCase = true) &&
                current.localOrigin.port == port
            ) null else current
        }
        _events.tryEmit(LocalRouteEvent.KeyMismatch(host, port))
    }

    /** Trust [pin] at [host]:[port]. */
    fun trust(host: String, port: Int, pin: String) {
        pins.update { it + (key(host, port) to pin) }
    }

    /** The pin for [host]:[port], or null when it is not a local address. */
    fun pinFor(host: String, port: Int): String? = pins.value[key(host, port)]

    /** The pins of every pinned port of [host]. */
    fun pinsForHost(host: String): Set<String> {
        val prefix = "${host.lowercase()}:"
        return pins.value.filterKeys { it.startsWith(prefix) }.values.toSet()
    }

    /** Forget every pinned address, when the active server or its key changes. */
    fun removeAllPins() {
        pins.value = emptyMap()
    }

    private fun key(host: String, port: Int) = "${host.lowercase()}:$port"
}

sealed interface LocalRouteEvent {
    data class Failed(val route: LocalRoute) : LocalRouteEvent
    data class KeyMismatch(val host: String, val port: Int) : LocalRouteEvent
}

/** The app's one [LocalRouteState]; tests build their own. */
object LocalRoutes {
    val state = LocalRouteState()
}
