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
     * once its extraction lands; a new `Text("…")`, `text = "…"` or
     * `contentDescription = "…"` literal in it then fails this test.
     * `// i18n-ignore` on the line opts out (brand names, symbols).
     */
    private val convertedDirectories = listOf(
        "android-shared/src/androidMain/kotlin/org/siloserver/silo/common/calendar",
        "android-shared/src/androidMain/kotlin/org/siloserver/silo/common/cards",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/home",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/boxset",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/browse",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/calendar",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/collections",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/libraries",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/pairing",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/people",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/personal",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/recommendations",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/search",
        "androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/shuffle",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/home",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/boxset",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/browse",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/calendar",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/collections",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/home",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/libraries",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/library",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/people",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/personal",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/search",
        "androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/watchnext",
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
            Icon(Icons.Default.Close, contentDescription = "Close")
            Icon(Icons.Default.Close, contentDescription = null)
        """.trimIndent()
        assertEquals(listOf(1, 2, 5, 10), hardcodedText(source))
    }

    private fun names(xml: String): Set<String> =
        Regex("""<(?:string|plurals) name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()

    /** 1-based lines that pass a literal with a letter to `Text(`, `text =` or `contentDescription =`. */
    private fun hardcodedText(source: String): List<Int> =
        LITERAL.findAll(source)
            .map { match -> source.substring(0, match.range.first).count { it == '\n' } + 1 }
            .filter { line -> "// i18n-ignore" !in source.lines()[line - 1] }
            .distinct()
            .toList()

    private companion object {
        val LITERAL = Regex("""(?:\bText\(\s*|\b(?:text|contentDescription)\s*=\s*)"(?=[^"\n]*\p{L})""")
    }
}
