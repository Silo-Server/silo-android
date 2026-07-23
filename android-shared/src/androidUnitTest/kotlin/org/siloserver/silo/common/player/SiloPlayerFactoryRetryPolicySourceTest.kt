package org.siloserver.silo.common.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SiloPlayerFactoryRetryPolicySourceTest {
    private val source = File("src/androidMain/kotlin/org/siloserver/silo/common/player/SiloPlayerFactory.kt")
        .readText()

    @Test
    fun mediaSourceFactoryUsesServerRestartRetryPolicy() {
        assertTrue(
            source.contains("val mediaLoadErrorHandlingPolicy = SiloMediaLoadErrorHandlingPolicy(") &&
                source.split(".setLoadErrorHandlingPolicy(mediaLoadErrorHandlingPolicy)").size - 1 >= 2,
            "DefaultMediaSourceFactory must keep retrying transient manifest/segment failures during server restarts.",
        )
    }
}
