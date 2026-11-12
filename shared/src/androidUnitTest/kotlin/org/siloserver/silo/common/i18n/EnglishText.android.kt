package org.siloserver.silo.common.i18n

import java.io.File
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.math.roundToLong

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
        val values = args.map(::englishArg).toMutableList<Any>().apply { add(key.countIndex, count) }
        String.format(Locale.US, form, *values.toTypedArray())
    }
    // As android-shared's resolver (LocaleFormat) formats them in US English.
    is UiText.Number ->
        if (maxFractionDigits == 0) {
            String.format(Locale.US, "%0${minIntegerDigits.coerceAtLeast(1)}d", value.roundToLong())
        } else {
            NumberFormat.getNumberInstance(Locale.US).apply {
                isGroupingUsed = false
                maximumFractionDigits = this@english.maxFractionDigits
                roundingMode = RoundingMode.HALF_UP
            }.format(value)
        }
    is UiText.CalendarDate -> LocalDate.of(year, month, day).format(DateTimeFormatter.ofPattern("MMM d, y", Locale.US))
    is UiText.LanguageName -> {
        val name = Locale.forLanguageTag(tag.replace('_', '-')).getDisplayName(Locale.US)
        when {
            name.isBlank() -> if (uppercaseIfUnnamed) tag.uppercase(Locale.ROOT) else tag
            uppercaseIfUnnamed && name.equals(tag, ignoreCase = true) -> tag.uppercase(Locale.ROOT)
            else -> name
        }
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
