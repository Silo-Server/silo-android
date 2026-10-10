package org.siloserver.silo.tv.watchnext

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchNextSeederTest {
    @Test
    fun startupKeepsTilesAndOnlyNewlyEnabledProtectionInvalidatesThem() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("silo_watch_next_protection", Context.MODE_PRIVATE).edit().clear().commit()
        if (runCatching { WorkManager.getInstance(context) }.isFailure) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
        val repository = WatchNextRepository(context)
        val seeder = WatchNextSeeder(context, repository)
        val original = repository.writeGate.capture()
        seeder.updateImageProtection(false)
        assertEquals(original, repository.writeGate.capture())
        seeder.updateImageProtection(true)
        val protected = repository.writeGate.capture()
        assertNotEquals(original, protected)
        // The setting is recorded only once the wipe finishes; a restart before
        // then must wipe again.
        val prefs = context.getSharedPreferences("silo_watch_next_protection", Context.MODE_PRIVATE)
        val deadline = System.currentTimeMillis() + 5_000
        while (!prefs.getBoolean("hide_images", false) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(prefs.getBoolean("hide_images", false))
        // Recreating the seeder models a process restart with the same known setting.
        WatchNextSeeder(context, repository).updateImageProtection(true)
        assertEquals(protected, repository.writeGate.capture())
        seeder.updateImageProtection(false)
        assertEquals(protected, repository.writeGate.capture())
        WorkManager.getInstance(context).cancelAllWork()
    }
}
