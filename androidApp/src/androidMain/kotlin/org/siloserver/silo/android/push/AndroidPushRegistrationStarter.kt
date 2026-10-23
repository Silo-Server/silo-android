package org.siloserver.silo.android.push

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.siloserver.silo.repository.NotificationsRepository

class AndroidPushRegistrationStarter(
    private val registrar: AndroidPushRegistrar,
    private val notificationsRepository: NotificationsRepository,
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
