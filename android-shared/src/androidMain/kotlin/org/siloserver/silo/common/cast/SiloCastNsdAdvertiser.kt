package org.siloserver.silo.common.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.common.pairing.PairingDeviceId

/**
 * Advertises the SiloCast receiver. TXT keys mirror silo-apple's
 * TVControlReceiver Bonjour record (`v`, `name`, `id`, `server`,
 * `serverName`, `playing`) so an iPhone browsing `_silocast._tcp` can filter
 * an Android TV by server the same way it filters an Apple TV — and the
 * Android browser can read Apple receivers. Android's NsdManager can't
 * mutate a live TXT record, so [updatePlaying] re-registers.
 */
class SiloCastNsdAdvertiser(
    context: Context,
    private val nameProvider: () -> String = {
        SiloCastDeviceName.resolve(context, fallback = "Android TV")
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

    @Synchronized
    fun start(port: Int, serverId: String?, serverName: String?, playing: Boolean = false) {
        register(Registration(port, serverId, serverName, playing))
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
    }

    private fun register(registration: Registration) {
        val name = nameProvider()
        val record = buildMap {
            put("v", SiloCastProtocol.version.toString())
            put("name", name)
            put("id", deviceIdProvider())
            registration.serverId?.let { put("server", it) }
            registration.serverName?.let { put("serverName", it) }
            put("playing", if (registration.playing) "1" else "0")
        }
        // Re-registering an identical record only churns: NsdManager cancels
        // the old record's goodbye, so a stop() right after (sign-out) leaves
        // phones listing a TV that is gone.
        if (registrationListener != null && registration == lastRegistration && record == lastRecord) return
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SiloCastProtocol.serviceType
            port = registration.port
            record.forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "SiloCast registered on ${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "SiloCast registration failed: $errorCode")
                // Nothing is live: forget it, so the next refresh registers
                // again instead of skipping an "identical" record.
                synchronized(this@SiloCastNsdAdvertiser) {
                    if (registrationListener === this) {
                        registrationListener = null
                        lastRegistration = null
                        lastRecord = null
                    }
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
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private data class Registration(
        val port: Int,
        val serverId: String?,
        val serverName: String?,
        val playing: Boolean,
    )

    private companion object {
        const val TAG = "SiloCastNsdAdvertiser"
    }
}
