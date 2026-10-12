package org.siloserver.silo.network.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Request administration (approve, decline, retry, limits, auto-approve) is
 * web-admin only: the 1.0 Requests criteria say native clients never expose it.
 * No Android source may call the admin request endpoints or bring back the
 * moderation surface that used to sit on the Requests screens.
 */
class NoNativeRequestModerationSourceTest {
    private val sourceRoots = listOf(
        "shared/src/commonMain/kotlin",
        "android-shared/src/androidMain/kotlin",
        "androidApp/src/androidMain/kotlin",
        "androidTvApp/src/androidMain/kotlin",
    )

    private val forbidden = Regex(
        """/admin/requests|\bAdminRequest(Action|Capabilities|ActionBody)\b|\bcanModerate\b|\bRequestApprovals\w*|requests/approvals|\bmoderat(e|ion)\w*\(""",
    )

    // The phone app keeps a redirect-only alias for the removed Approvals route
    // so a back stack saved by an older build restores onto Requests instead of
    // crashing. Only these exact lines in AppNavigation.kt may name the route.
    private val legacyRouteAliasFile =
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/navigation/AppNavigation.kt"
    private val legacyRouteAliasLines = setOf(
        """composable("requests/approvals") {""",
        """popUpTo("requests/approvals") { inclusive = true }""",
    )

    @Test
    fun noAndroidSourceExposesRequestModeration() {
        val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "shared").isDirectory }
        requireNotNull(repoRoot) { "repository root not found from ${File("").absolutePath}" }
        val roots = sourceRoots.map { File(repoRoot, it) }
        roots.forEach { assertTrue(it.isDirectory, "missing ${it.absolutePath}") }

        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val path = file.relativeTo(repoRoot).invariantSeparatorsPath
                    file.readLines().mapIndexedNotNull { index, line ->
                        val allowedAlias = path == legacyRouteAliasFile && line.trim() in legacyRouteAliasLines
                        if (forbidden.containsMatchIn(line) && !allowedAlias) {
                            "$path:${index + 1}: ${line.trim()}"
                        } else {
                            null
                        }
                    }
                }
                .toList()
        }

        assertTrue(
            offenders.isEmpty(),
            "Request moderation is web-admin only; remove it from the Android clients:\n${offenders.joinToString("\n")}",
        )
    }
}
