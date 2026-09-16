package org.siloserver.silo.network.apiv2

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The v2 package must not know a v1 path: no `/api/v1` literal anywhere under network/apiv2/. */
class ApiV2NoV1TransportSourceTest {
    @Test
    fun noV1PathLiteralUnderApiV2Package() {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "shared/src/commonMain/kotlin/org/siloserver/silo/network/apiv2") }
            .firstOrNull { it.isDirectory }
            ?: File("src/commonMain/kotlin/org/siloserver/silo/network/apiv2")
        assertTrue(dir.isDirectory, "missing ${dir.absolutePath}")
        val sources = dir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "no Kotlin sources under $dir")
        val offenders = sources.filter { "/api/v1" in it.readText() }.map { it.name }
        assertTrue(offenders.isEmpty(), "v1 path literal in v2 package: $offenders")
    }
}
