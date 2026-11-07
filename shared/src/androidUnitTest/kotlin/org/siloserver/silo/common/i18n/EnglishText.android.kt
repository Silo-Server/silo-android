package org.siloserver.silo.common.i18n

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

actual fun UiText.english(): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> error("UiText.Res names an app resource id; tests of the shared module don't build one")
    is UiText.Key -> {
        val template = EnglishCatalog.strings.getValue(key.key.replace('.', '_'))
        if (args.isEmpty()) template else String.format(Locale.US, template, *args.map(::englishArg).toTypedArray())
    }
    is UiText.Plural -> {
        val forms = EnglishCatalog.plurals.getValue(key.key.replace('.', '_'))
        val form = forms[if (count == 1) "one" else "other"] ?: forms.getValue("other")
        String.format(Locale.US, form, count, *args.map(::englishArg).toTypedArray())
    }
}

private fun englishArg(arg: Any): Any = if (arg is UiText) arg.english() else arg

/** The generated `values/strings_catalog.xml`, read once, with Android's resource escapes undone. */
private object EnglishCatalog {
    private val document by lazy {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "android-shared/src/androidMain/res/values/strings_catalog.xml") }
            .first { it.isFile }
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    val strings: Map<String, String> by lazy {
        document.elements("string").associate { it.getAttribute("name") to unescape(it.textContent) }
    }

    val plurals: Map<String, Map<String, String>> by lazy {
        document.elements("plurals").associate { plural ->
            plural.getAttribute("name") to plural.elements("item").associate { it.getAttribute("quantity") to unescape(it.textContent) }
        }
    }

    private fun Element.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 == value.length) {
                append(c)
                i++
                continue
            }
            when (val next = value[i + 1]) {
                'n' -> append('\n')
                't' -> append('\t')
                'u' -> {
                    append(value.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 4
                }
                else -> append(next)
            }
            i += 2
        }
    }
}
