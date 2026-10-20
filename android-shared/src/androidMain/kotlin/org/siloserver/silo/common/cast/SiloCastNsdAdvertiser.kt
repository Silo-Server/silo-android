package org.siloserver.silo.common.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.common.pairing.PairingDeviceId

/**
 * Advertises the SiloCast receiver. TXT keys mirror silo-apple's
 * TVControlReceiver Bonjour record (`v`, `name`, `id`, `server`,
 * `serverName`, `serverIdentity`, `playing`) so an iPhone browsing `_silocast._tcp` can filter
 * an Android TV by server the same way it filters an Apple TV — and the
 * Android browser can read Apple receivers. Android's NsdManager can't
 * mutate a live TXT record, so [updatePlaying] re-registers.
 */
class SiloCastNsdAdvertiser(
    context: Context,
    private val nameProvider: () -> String = {
        Build.MODEL?.trim()?.ifBlank { null } ?: "Android TV"
    },
    private val deviceIdProvider: () -> String = {
        PairingDeviceId.stable(context)
    },
) {
    private val appContext = context.applicationContext
    private val nsdManager: NsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var lastRegistration: Registration? = null
    private var lastRecord: Map<String, String>? = null

    /** The service name NsdManager registered, which it may change to resolve a conflict; null until it confirms. */
    private var registeredName: String? = null

    /** The registered advertisement's name, port and TXT record, for the debug adb hook. */
    @Synchronized
    fun currentAdvertisement(): Triple<String, Int, Map<String, String>>? {
        val registration = lastRegistration ?: return null
        val record = lastRecord ?: return null
        val name = registeredName ?: return null
        return Triple(name, registration.port, record)
    }

    @Synchronized
    fun start(
        port: Int,
        serverId: String?,
        serverName: String?,
        playing: Boolean = false,
        serverIdentity: String? = null,
    ) {
        register(Registration(port, serverId, serverName, playing, serverIdentity))
    }

    /** Re-advertise with an updated `playing` flag (no-op when not started). */
    @Synchronized
    fun updatePlaying(playing: Boolean) {
        val current = lastRegistration ?: return
        if (current.playing == playing) return
        register(current.copy(playing = playing))
    }

    @Synchronized
    fun stop() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        lastRegistration = null
        lastRecord = null
        registeredName = null
    }

    private fun register(registration: Registration) {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        val name = nameProvider()
        val record = buildMap {
            put("v", SiloCastProtocol.version.toString())
            put("name", name)
            put("id", deviceIdProvider())
            registration.serverId?.let { put("server", it) }
            registration.serverName?.let { put("serverName", it) }
            // Lets a phone signed in at another address of the same deployment list this TV.
            registration.serverIdentity?.let { put("serverIdentity", it) }
            put("playing", if (registration.playing) "1" else "0")
        }
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SiloCastProtocol.serviceType
            port = registration.port
            record.forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "SiloCast registered on ${info.port}")
                synchronized(this@SiloCastNsdAdvertiser) {
                    if (registrationListener === this) registeredName = info.serviceName
                }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "SiloCast registration failed: $errorCode")
                synchronized(this@SiloCastNsdAdvertiser) {
                    if (registrationListener === this) registeredName = null
                }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "SiloCast unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "SiloCast unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        lastRegistration = registration
        lastRecord = record
        registeredName = null
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private data class Registration(
        val port: Int,
        val serverId: String?,
        val serverName: String?,
        val playing: Boolean,
        val serverIdentity: String?,
    )

    private companion object {
        const val TAG = "SiloCastNsdAdvertiser"
    }
}
