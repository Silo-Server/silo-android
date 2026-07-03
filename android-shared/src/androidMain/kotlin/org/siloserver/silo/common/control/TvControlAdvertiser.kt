package org.siloserver.silo.common.control

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.siloserver.silo.common.pairing.PairingDeviceId
import org.siloserver.silo.control.SiloControlProtocol
import java.net.ServerSocket
import java.net.Socket

/**
 * Advertises `_silocast._tcp` on the LAN via [NsdManager] and hands each
 * accepted connection to the session callback over a TLS-PSK transport.
 * Mirrors the silo-apple tvOS `TVControlReceiver` listener + the pairing
 * [org.siloserver.silo.common.pairing.TvPairingAdvertiser] wiring.
 *
 * TXT record carries: v (protocol version), name (device name), id (stable
 * device id), server (active server id), serverName (active server display
 * name), playing ("1" while a player is registered, else "0").
 *
 * Unlike pairing (one busy connection at a time), every accepted socket is
 * handed to [onSession] — the receiver implements newest-controller-wins by
 * closing its previous session.
 */
class TvControlAdvertiser(private val context: Context) {
    private companion object {
        private const val TAG = "TvControlAdvertiser"
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serverId: String? = null
    private var serverName: String? = null
    private var playing = false

    /** Start advertising and accepting control connections. Idempotent restart. */
    @Synchronized
    fun start(
        serverId: String,
        serverName: String,
        onSession: (TlsPskControlTransport) -> Unit,
    ) {
        stop()
        this.serverId = serverId
        this.serverName = serverName
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { this.scope = it }

        scope.launch {
            val socket = ServerSocket(0).also { serverSocket = it }
            val port = socket.localPort
            synchronized(this@TvControlAdvertiser) { registerService(port) }
            Log.i(TAG, "started control listener on port $port")
            acceptLoop(socket, onSession)
        }
    }

    /**
     * Stop advertising and close the listening socket. Accepted sessions are
     * owned by the receiver once handed off, so they survive this — the
     * listening socket and the session sockets are independent.
     */
    @Synchronized
    fun stop() {
        unregisterLocked()
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
        serverId = null
        serverName = null
        playing = false
    }

    /**
     * Flip the TXT `playing` flag. [NsdManager] cannot mutate a TXT record in
     * place, so this unregisters and re-registers the service — the
     * [ServerSocket] (and any accepted session) stays alive across the
     * re-registration, exactly like the Apple receiver recreates only its
     * NWListener.
     */
    @Synchronized
    fun setPlaying(playing: Boolean) {
        if (this.playing == playing) return
        this.playing = playing
        val port = serverSocket?.localPort ?: return
        if (serverId == null) return
        unregisterLocked()
        registerService(port)
    }

    private fun unregisterLocked() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
    }

    private suspend fun acceptLoop(socket: ServerSocket, onSession: (TlsPskControlTransport) -> Unit) {
        while (true) {
            val client: Socket = try {
                withContext(Dispatchers.IO) { socket.accept() }
            } catch (_: Throwable) {
                Log.i(TAG, "control listener stopped")
                return // socket closed (stop()) — exit the loop.
            }
            Log.i(TAG, "accepted control connection")
            val connScope = scope ?: run {
                runCatching { client.close() }
                return
            }
            connScope.launch {
                try {
                    val transport = withContext(Dispatchers.IO) {
                        TlsPskControlTransport.accept(client)
                    }
                    Log.i(TAG, "control TLS connected")
                    onSession(transport)
                } catch (t: Throwable) {
                    Log.w(TAG, "control connection failed", t)
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun registerService(port: Int) {
        val name = deviceName()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SiloControlProtocol.SERVICE_TYPE
            this.port = port
            setAttribute("v", SiloControlProtocol.VERSION.toString())
            setAttribute("name", name)
            setAttribute("id", PairingDeviceId.stable(context))
            setAttribute("server", serverId.orEmpty())
            setAttribute("serverName", serverName.orEmpty())
            setAttribute("playing", if (playing) "1" else "0")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // NSD may rename on conflict; the final name is the one peers see.
                Log.i(TAG, "control service registered as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "control service registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "control service unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "control service unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun deviceName(): String =
        Build.MODEL?.trim()?.ifBlank { null } ?: "Android TV"
}
