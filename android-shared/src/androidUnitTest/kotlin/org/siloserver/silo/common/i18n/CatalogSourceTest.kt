package org.siloserver.silo.common.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source guards for the generated translation catalog (docs/i18n.md).
 * Unit tests run from the module directory, so the repository root is "..".
 */
class CatalogSourceTest {
    private val root = File("..")

    /**
     * App and hand-written library resources never reuse a generated name:
     * an app resource silently shadows the library's, so the catalog's text
     * (and every translation of it) would never show.
     */
    @Test
    fun noHandWrittenResourceReusesAGeneratedName() {
        val catalog = File(root, "android-shared/src/androidMain/res")
        val generated = names(File(catalog, "values/strings_catalog.xml").readText())
        assertTrue(generated.size > 300, "generated catalog not found or empty")
        val clashes = listOf("androidApp", "androidTvApp", "android-shared").flatMap { module ->
            File(root, "$module/src").walkTopDown()
                .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }
                // Only android-shared's generated files may declare catalog names.
                .filterNot { it.name == "strings_catalog.xml" && it.parentFile.parentFile.canonicalFile == catalog.canonicalFile }
                .flatMap { file -> names(file.readText()).filter { it in generated }.map { "${file.relativeTo(root)}: $it" } }
                .toList()
        }
        assertEquals(emptyList(), clashes)
    }

    /**
     * Directories whose UI text all comes from the catalog, including
     * constants and messages, not only `Text` calls. Add a directory here
     * once its extraction lands; a new `Text("…")` or `text = "…"`
     * literal in it then fails this test. `// i18n-ignore` on the line opts
     * out (brand names, symbols).
     */
    private val convertedDirectories = listOf(
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/pairing",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/servers",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/settings",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/servers",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/settings",
        "android-shared/src/androidMain/kotlin/org/siloserver/silo/common/diagnostics",
        "android-shared/src/androidMain/kotlin/org/siloserver/silo/common/settings",
        // Playback and media consumption.
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/audiobook",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/book",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/reader",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/cast",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/downloads",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/downloads",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/audiobook",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/cast",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/cast",
        "android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player",
        "android-shared/src/androidMain/kotlin/org/siloserver/silo/common/pip",
        "android-shared/src/androidMain/kotlin/org/siloserver/silo/common/audiobook",
        "android-shared/src/androidMain/kotlin/org/siloserver/silo/common/ebook",
        "android-shared/src/androidMain/kotlin/org/siloserver/silo/common/cast",
        "android-shared/src/androidMain/kotlin/org/siloserver/silo/common/downloads",
        "android-shared/src/androidMain/kotlin/org/siloserver/silo/common/overlays",
        "android-shared/src/androidMain/kotlin/org/siloserver/silo/common/thememusic",
    )

    @Test
    fun convertedDirectoriesHaveNoHardcodedText() {
        val found = convertedDirectories.flatMap { dir ->
            val files = File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            assertTrue(files.isNotEmpty(), "$dir has no Kotlin files")
            files.flatMap { file -> hardcodedText(file.readText()).map { "${file.relativeTo(root)}:$it" } }
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun scannerFindsEachForm() {
        val source = """
            Text("Sign in")
            Text(
                "Two lines"
            )
            Button(onClick = {}) { Text(text = "Retry") }
            Text(stringResource(R.string.common_cancel))
            Text("•")
            Text("Silo") // i18n-ignore
            Text(text = title)
            SiloLogAttribute.Text("route")
        """.trimIndent()
        assertEquals(listOf(1, 2, 5), hardcodedText(source))
    }

    private fun names(xml: String): Set<String> =
        Regex("""<(?:string|plurals) name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()

    /** 1-based lines that pass a literal with a letter to `Text(` or `text =`. */
    private fun hardcodedText(source: String): List<Int> =
        LITERAL.findAll(source)
            .map { match -> source.substring(0, match.range.first).count { it == '\n' } + 1 }
            .filter { line -> "// i18n-ignore" !in source.lines()[line - 1] }
            .distinct()
            .toList()

    private companion object {
        // `(?<![.\w])` skips qualified calls such as `SiloLogAttribute.Text("…")`, which are log fields.
        val LITERAL = Regex("""(?:(?<![.\w])Text\(\s*|\btext\s*=\s*)"(?=[^"\n]*\p{L})""")
    }
}
