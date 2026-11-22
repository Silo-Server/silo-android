package org.siloserver.silo.android.push

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.NotificationsRepository

class AndroidPushRegistrationStarter(
    private val registrar: AndroidPushRegistrar,
    private val notificationsRepository: NotificationsRepository,
    private val registry: ServerRegistry? = null,
) : DefaultLifecycleObserver {
    private var scope: CoroutineScope? = null

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (scope != null) return
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = nextScope
        // A removed or signed-out server keeps pushing until its retirement lands.
        // It needs no login, so it goes out on app start and every foreground.
        nextScope.launch { runCatching { registrar.retryPendingRetirements() } }
        nextScope.registerOnce()
        nextScope.launch {
            notificationsRepository.resetSignals.collect {
                registerOnce()
            }
        }
        // A server's installation belongs to its current account, so another
        // account becoming active there (a tile switch, or a new account signing
        // in) hands it over, at its next generation. An account that restores
        // its saved profile picks none, so the reset above never fires for it.
        registry?.let { registry ->
            nextScope.launch {
                combine(registry.activeServerId, registry.activeAccountId) { server, account -> server to account }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { registerOnce() }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        scope?.cancel()
        scope = null
    }

    private fun CoroutineScope.registerOnce() {
        launch {
            runCatching { registrar.registerIfAvailable() }
            // Saved servers a token rotation did not reach in time.
            runCatching { registrar.registerOtherServersIfBehind() }
        }
    }
}
