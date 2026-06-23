package com.continuum.app.common.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.continuum.app.pairing.PairingProtocol
import com.continuum.app.pairing.PairingReceiverState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Advertises `_silopair._tcp` on the LAN via [NsdManager] and hands each
 * accepted connection (one at a time) to a [PairingReceiver] over a TLS-PSK
 * transport. Mirrors the silo-apple `TVPairingAdvertiser` + `PairingSession`
 * wiring.
 *
 * TXT record carries: v (protocol version), name (device name), id (stable
 * device id), sid (fresh nonce per [start]), st (receiver state).
 *
 * UI (a later step) observes [PairingReceiver.status]. This class owns only the
 * networking lifecycle.
 */
class TvPairingAdvertiser(
    private val context: Context,
    private val receiver: PairingReceiver,
    private val receiverStateProvider: () -> PairingReceiverState,
) {
    private companion object {
        private const val TAG = "TvPairingAdvertiser"
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    /** One connection at a time; later peers are closed as busy. */
    private val busy = AtomicBoolean(false)

    /** True between [start] and [stop]; gates restoring Advertising after a connection. */
    private val running = AtomicBoolean(false)

    /** Start advertising and accepting pairing connections. Idempotent restart. */
    @Synchronized
    fun start() {
        stop()
        running.set(true)
        val identity = currentIdentity()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { this.scope = it }

        scope.launch {
            val socket = ServerSocket(0).also { serverSocket = it }
            val port = socket.localPort
            registerService(port, identity)
            Log.i(TAG, "started pairing listener on port $port")
            receiver.setAdvertising()
            acceptLoop(socket)
        }
    }

    /** Stop advertising, close the listening socket, and release any session. */
    @Synchronized
    fun stop() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
        running.set(false)
        busy.set(false)
        receiver.setIdle()
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (true) {
            val client: Socket = try {
                withContext(Dispatchers.IO) { socket.accept() }
            } catch (_: Throwable) {
                Log.i(TAG, "pairing listener stopped")
                return // socket closed (stop()) — exit the loop.
            }
            if (!busy.compareAndSet(false, true)) {
                Log.i(TAG, "rejected pairing connection while busy")
                runCatching { client.close() } // already serving someone — busy.
                continue
            }
            Log.i(TAG, "accepted pairing connection")
            val connScope = scope ?: run {
                runCatching { client.close() }
                return
            }
            connScope.launch {
                try {
                    val transport = withContext(Dispatchers.IO) {
                        TlsPskPairingTransport.accept(client)
                    }
                    Log.i(TAG, "pairing TLS connected")
                    receiver.run(transport)
                    Log.i(TAG, "pairing session ended")
                } catch (t: Throwable) {
                    Log.w(TAG, "pairing connection failed", t)
                    runCatching { client.close() }
                } finally {
                    // Release for the next peer and resume advertising state —
                    // but only if we're still running. After stop()/cancellation
                    // the NSD + socket are down, so leave the receiver Idle rather
                    // than falsely claiming Advertising. Completed is a terminal
                    // UI dwell state; the setup screen advances after showing it.
                    busy.set(false)
                    if (receiver.status.value is PairingReceiverStatus.Completed) {
                        running.set(false)
                        runCatching { socket.close() }
                        if (serverSocket === socket) {
                            serverSocket = null
                        }
                        registrationListener?.let { listener ->
                            runCatching { nsdManager.unregisterService(listener) }
                        }
                        registrationListener = null
                    } else if (running.get()) {
                        receiver.setAdvertising()
                    }
                }
            }
        }
    }

    private fun registerService(port: Int, identity: PairingDeviceIdentity) {
        val sid = UUID.randomUUID().toString()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = identity.name
            serviceType = PairingProtocol.SERVICE_TYPE
            this.port = port
            setAttribute("v", PairingProtocol.VERSION.toString())
            setAttribute("name", identity.name)
            setAttribute("id", identity.deviceId)
            setAttribute("sid", sid)
            setAttribute("st", receiverStateProvider().wire)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "pairing service registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "pairing service unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "pairing service unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun currentIdentity(): PairingDeviceIdentity {
        val name = Build.MODEL?.trim()?.ifBlank { null } ?: "Android TV"
        return PairingDeviceIdentity(name = name, deviceId = PairingDeviceId.stable(context))
    }
}
