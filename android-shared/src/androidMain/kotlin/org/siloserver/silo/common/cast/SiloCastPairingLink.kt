package org.siloserver.silo.common.cast

import java.net.URLDecoder
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.common.lan.SiloCastSealed

/**
 * A Roku's pairing link, from the QR code or address on its Connect a Phone
 * screen (silo-roku `docs/silocontrol.md` section 5.3), for networks where
 * DNS-SD cannot find it:
 *
 * `silo://cast?v=2&host=<ip>&port=<port>&id=<deviceId>&name=<name>&server=<serverId>&security=plain|sealed[&key=<base32>]`
 *
 * The phone remembers the Roku by [target]'s `deviceId` and [pairingKey], finds
 * it over DNS-SD like any other TV, and dials `host:port` when DNS-SD finds
 * nothing. Unknown parameters are ignored.
 */
class SiloCastPairingLink(val target: SiloCastTarget, val pairingKey: ByteArray?) {
    companion object {
        /** The Roku receiver's first-choice port, for a typed address without one. */
        const val DEFAULT_ROKU_PORT = 52817

        fun parse(text: String): SiloCastPairingLink? {
            val trimmed = text.trim()
            val prefix = "silo://cast?"
            if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
            val query = trimmed.substring(prefix.length).substringBefore('#')
                .split('&')
                .filter { '=' in it }
                .associate { part ->
                    decode(part.substringBefore('=')) to decode(part.substringAfter('='))
                }
            fun string(key: String) = query[key]?.trim()?.takeIf { it.isNotEmpty() }
            if (string("v")?.toIntOrNull() != SiloCastProtocol.version) return null
            val host = string("host")?.takeIf(::isIpv4Literal) ?: return null
            val port = string("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val deviceId = string("id") ?: return null
            val (transport, key) = when (string("security")?.lowercase()) {
                null, "plain" -> SiloCastTransport.Plain to null
                "sealed" -> SiloCastTransport.Sealed to (string("key")?.let(SiloCastSealed::parseKey) ?: return null)
                else -> return null
            }
            val name = string("name") ?: "Roku"
            return SiloCastPairingLink(
                target = SiloCastTarget(
                    deviceId = deviceId,
                    name = name,
                    host = host,
                    port = port,
                    version = SiloCastProtocol.version,
                    serviceName = name,
                    serverId = string("server"),
                    transport = transport,
                    keyId = key?.let(SiloCastSealed::keyId),
                ),
                pairingKey = key,
            )
        }

        /**
         * A typed `host` or `host:port` (IPv4 only, as the Roku listens): the
         * address to probe for a Roku at level `plain`. Null when it isn't one.
         */
        fun parseAddress(text: String): Pair<String, Int>? {
            val trimmed = text.trim()
            val host = trimmed.substringBefore(':')
            if (!isIpv4Literal(host)) return null
            val port = if (':' in trimmed) {
                trimmed.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            } else {
                DEFAULT_ROKU_PORT
            }
            return host to port
        }

        private fun isIpv4Literal(host: String): Boolean {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() <= 255
            }
        }

        private fun decode(text: String): String = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
    }
}
