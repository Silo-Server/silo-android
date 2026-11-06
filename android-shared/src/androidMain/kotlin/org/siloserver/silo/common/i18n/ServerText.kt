package org.siloserver.silo.common.i18n

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.apiv2.Problem
import org.siloserver.silo.network.apiv2.ProblemText
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Text the server sends that the shared translation catalog can replace:
 * section titles (`title_key`/`title_args`) and v2 problem details
 * (`error.<type>`, `error.field.<code>`). Keys arrive at run time, so they
 * are looked up by the name the catalog generator gives them on Android:
 * the key with `.` turned into `_`.
 */
object ServerText {
    private const val SECTION_KEYS = "home.section."
    private const val PLACEHOLDER = "%1\$s"

    /**
     * The title to show: the catalog's message for [key] with [args] filled
     * in, or [title] when the key is absent, unknown to this build, or its
     * arguments don't fit the message. Section messages take at most one
     * argument, and Android placeholders are positional, so a message with
     * more than one couldn't be filled from named arguments; none exists.
     * [template] returns the raw resource text for a catalog key, or null.
     * In a right-to-left interface the arguments (library, title and genre
     * names) are wrapped in Unicode isolates.
     */
    fun sectionTitle(
        title: String,
        key: String?,
        args: Map<String, String>?,
        rtl: Boolean,
        template: (String) -> String?,
    ): String {
        if (key == null || !key.startsWith(SECTION_KEYS)) return title
        val text = template(key) ?: return title
        val slots = PLACEHOLDER_SPEC.findAll(text).map { it.value }.toList()
        val values = args.orEmpty().values
        return when {
            slots.isEmpty() -> if (values.isEmpty()) text else title
            slots.all { it == PLACEHOLDER } && values.size == 1 -> {
                val value = values.single()
                text.replace(PLACEHOLDER, if (rtl) isolate(value) else value)
            }
            else -> title
        }
    }

    /**
     * The catalog's text for a problem in a non-English interface: the first
     * field error with a translation, then the problem type; null keeps the
     * server's `detail`. [translated] returns a key's text only when the
     * interface language has its own translation of it.
     */
    fun problem(problem: Problem, english: Boolean, translated: (String) -> String?): String? {
        if (english) return null
        return problem.errors.firstNotNullOfOrNull { translated("error.field.${it.code}") }
            ?: translated("error.${problem.code}")
    }

    /** Installs the problem-detail translation for every v2 call (see [ProblemText]). */
    fun install(context: Context) {
        val app = context.applicationContext
        ProblemText.localize = { problem ->
            val tag = AppLocales.appliedTag(app)
            problem(problem, english = Locale.forLanguageTag(tag).language == "en") { key -> translated(app, tag, key) }
        }
    }

    private fun isolate(value: String) = "\u2068$value\u2069"

    private val PLACEHOLDER_SPEC = Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]")
    private val ids = ConcurrentHashMap<String, Int>()
    private val localized = ConcurrentHashMap<String, Context>()

    /** The string resource id for a catalog key, or null when this build lacks it. */
    @SuppressLint("DiscouragedApi")
    internal fun id(context: Context, key: String): Int? = ids.getOrPut(key) {
        context.resources.getIdentifier(key.replace('.', '_'), "string", context.packageName)
    }.takeIf { it != 0 }

    /** [key]'s text in [tag] when that language has its own translation (Android falls back to English silently). */
    private fun translated(app: Context, tag: String, key: String): String? {
        val id = id(app, key) ?: return null
        val text = resourcesFor(app, tag).getString(id)
        return text.takeIf { it != resourcesFor(app, "en").getString(id) }
    }

    private fun resourcesFor(app: Context, tag: String) = localized.getOrPut(tag) {
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
    }.resources
}

/** [ResolvedSection.title], translated when the server says it is its own text. */
@Composable
fun sectionTitle(section: ResolvedSection): String {
    LocalConfiguration.current // recompose on a language change
    val context = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return context.sectionTitle(section, rtl)
}

/** [ResolvedSection.title] for code outside composition; see [sectionTitle]. */
fun Context.sectionTitle(
    section: ResolvedSection,
    rtl: Boolean = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
): String = ServerText.sectionTitle(section.title, section.titleKey, section.titleArgs, rtl) { key ->
    ServerText.id(this, key)?.let(resources::getString)
}
