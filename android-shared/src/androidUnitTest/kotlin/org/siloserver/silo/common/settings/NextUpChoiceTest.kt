package org.siloserver.silo.common.settings

import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NextUpChoiceTest {
    @Test
    fun offIsOfferedWhereTheServerStoresItAndWhileASavedOffAwaitsTheManifest() {
        val times = listOf(0, 10, 30, 60, 120)
        assertEquals(listOf<Int?>(null) + times, NextUpChoice.options(offSupported = true, enabled = true))
        assertEquals(times, NextUpChoice.options(offSupported = false, enabled = true))
        // A saved Off before the manifest is read stays visible and selected.
        assertEquals(listOf<Int?>(null) + times, NextUpChoice.options(offSupported = false, enabled = false))
        assertEquals(null, NextUpChoice.selected(enabled = false, seconds = 30))
    }

    @Test
    fun aTimeIsAChoiceOfBothKeys() = runTest {
        // On already (inherited from the profile, or not yet known from the
        // manifest): the time still writes On, so this device's choice survives
        // the profile turning Next Up off later.
        assertEquals(listOf("enabled=true", "seconds=10"), writes(10))
        assertEquals(listOf("enabled=true", "seconds=0"), writes(0))
        assertEquals(listOf("enabled=false"), writes(null))
    }

    private suspend fun writes(choice: Int?): List<String> {
        val calls = mutableListOf<String>()
        val unused = Proxy.newProxyInstance(
            PlayerSettingsStore::class.java.classLoader,
            arrayOf(PlayerSettingsStore::class.java),
        ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as PlayerSettingsStore
        val store = object : PlayerSettingsStore by unused {
            override suspend fun setNextUpEnabled(value: Boolean) { calls += "enabled=$value" }
            override suspend fun setNextUpPromptSeconds(value: Int) { calls += "seconds=$value" }
        }
        NextUpChoice.apply(store, choice)
        return calls
    }
}
