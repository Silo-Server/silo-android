package org.siloserver.silo.network.localroute

import org.siloserver.silo.network.HttpOrigin
import org.siloserver.silo.network.httpOrigin

/**
 * The active server's direct route on the local network (silo-server
 * `docs/architecture/server-identity.md`, "Local network TLS"). While one is
 * active, requests addressed to the saved server address travel to the
 * server's LAN address instead, over TLS pinned to the deployment's key.
 * Nothing that names the server changes: the saved address stays the
 * credential owner, the base of every stored URL, and the origin responses
 * report. Only the transport moves. Mirrors silo-apple `LocalRoute.swift`.
 */
data class LocalRoute(
    /** `ServerEntry.id` of the server the route belongs to. */
    val serverId: String,
    /** Origin of the saved address, which requests are built against. */
    val savedOrigin: HttpOrigin,
    /** `https://<private IPv4>:<port>` of the server on the local network. */
    val localOrigin: HttpOrigin,
    /** Advances every time a route is activated, so a failure drops only the route it was sent on. */
    val generation: Long,
) {
    /** Whether [scheme], [host] and [port] name the saved origin. */
    fun coversSaved(scheme: String, host: String, port: Int): Boolean = savedOrigin.isAt(scheme, host, port)

    /** Whether [scheme], [host] and [port] name the local origin. */
    fun coversLocal(scheme: String, host: String, port: Int): Boolean = localOrigin.isAt(scheme, host, port)

    companion object {
        /** Shown under the active server while its requests use the local route. */
        const val STATUS_TEXT = "Connected on your local network"

        /**
         * The local-route origin for a server address on the local network:
         * `https` on the same host and port, since the API port answers TLS
         * whatever scheme the address names. Only a private IPv4 literal
         * qualifies: no public certificate authority issues for one, so the
         * pin is the only trust that applies. A missing port is the scheme's
         * default.
         */
        fun localOriginForAddress(address: String): HttpOrigin? {
            val origin = httpOrigin(address.trim()) ?: return null
            if (!isPrivateIPv4(origin.host)) return null
            return HttpOrigin(scheme = "https", host = origin.host, port = origin.port)
        }

        /** RFC 1918 addresses: what a home LAN numbers its hosts from. */
        fun isPrivateIPv4(literal: String): Boolean {
            val parts = literal.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { part ->
                if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return false
                if (part.length > 1 && part[0] == '0') return false
                part.toInt().takeIf { it <= 255 } ?: return false
            }
            return octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }

        /** `scheme://host[:port]`, the default port omitted. */
        fun format(origin: HttpOrigin): String {
            val defaultPort = if (origin.scheme == "https") 443 else 80
            return if (origin.port == defaultPort) "${origin.scheme}://${origin.host}"
            else "${origin.scheme}://${origin.host}:${origin.port}"
        }
    }
}

private fun HttpOrigin.isAt(scheme: String, host: String, port: Int): Boolean =
    this.scheme.equals(scheme, ignoreCase = true) && this.host.equals(host, ignoreCase = true) && this.port == port
