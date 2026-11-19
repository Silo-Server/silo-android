package org.siloserver.silo.common.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A plural whose English forms print no number can still have translations
 * that do: Croatian and Ukrainian `one` also covers 21, 31, ..., so those forms
 * carry the count. A call that passes only the quantity then shows a literal
 * `%1$d` in those languages. Every call to such a plural must pass its
 * arguments.
 */
class PluralPlaceholderTest {
    private val root = File("..")
    private val catalog = File(root, "android-shared/src/androidMain/res")
    private val modules = listOf("androidApp", "androidTvApp", "android-shared")

    @Test
    fun pluralsWhoseTranslationsAddPlaceholdersAreCalledWithArguments() {
        val english = placeholders(File(catalog, "values/strings_catalog.xml"))
        val translated = catalog.listFiles().orEmpty()
            .filter { it.name.startsWith("values-") }
            .map { File(it, "strings_catalog.xml") }
            .filter { it.isFile }
            .map(::placeholders)
        val needArguments = english.keys.filter { name ->
            translated.any { lang -> (lang[name].orEmpty() - english.getValue(name)).isNotEmpty() }
        }

        val sources = modules.flatMap { module ->
            File(root, "$module/src").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        val missing = needArguments.flatMap { name ->
            // `plurals.name, quantity)`: the call ends right after the quantity.
            val bare = Regex("""plurals\.$name\s*,\s*[^,()]+(\([^()]*\))?\s*\)""")
            sources.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (bare.containsMatchIn(line)) "${file.path}:${i + 1} ($name)" else null
                }
            }
        }
        assertTrue(missing.isEmpty(), "pass the count to these plurals, whose translations print it:\n" + missing.joinToString("\n"))
    }

    @Test
    fun theCallCheckRecognisesABareCall() {
        val bare = Regex("""plurals\.x\s*,\s*[^,()]+(\([^()]*\))?\s*\)""")
        assertTrue(bare.containsMatchIn("pluralStringResource(CommonR.plurals.x, ids.size)"))
        assertTrue(bare.containsMatchIn("getQuantityString(R.plurals.x, count())"))
        assertTrue(!bare.containsMatchIn("pluralStringResource(CommonR.plurals.x, ids.size, ids.size)"))
    }

    /** Positional placeholders (`%1$d`, `%2$s`) used anywhere in each plural's forms. */
    private fun placeholders(file: File): Map<String, Set<String>> {
        val plural = Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
        val placeholder = Regex("""%(\d+)\$[sd]""")
        return plural.findAll(file.readText()).associate { m ->
            m.groupValues[1] to placeholder.findAll(m.groupValues[2]).map { it.groupValues[1] }.toSet()
        }
    }
}
