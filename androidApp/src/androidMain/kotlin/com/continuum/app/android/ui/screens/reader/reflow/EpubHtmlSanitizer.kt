package com.continuum.app.android.ui.screens.reader.reflow

internal fun sanitizeEpubChapterHtml(html: String): String =
    html
        .replace(BLOCKED_ELEMENT_WITH_BODY_REGEX, "")
        .replace(BLOCKED_ELEMENT_REGEX, "")
        .replace(EVENT_HANDLER_ATTR_REGEX, "")
        .replace(STYLE_ATTR_REGEX, "")
        .replace(SRCDOC_ATTR_REGEX, "")
        .replace(SRCSET_ATTR_REGEX, "")
        .replace(RESOURCE_ATTR_REGEX) { match ->
            val name = match.groupValues[1]
            val quote = match.groupValues[2]
            val value = match.groupValues[3]
            if (isUnsafeEpubResourceUrl(value)) "" else " $name=$quote$value$quote"
        }
        .replace(UNQUOTED_RESOURCE_ATTR_REGEX) { match ->
            val name = match.groupValues[1]
            val value = match.groupValues[2]
            if (isUnsafeEpubResourceUrl(value)) "" else " $name=$value"
        }

private fun isUnsafeEpubResourceUrl(value: String): Boolean {
    val normalized = value.trim().lowercase()
    return normalized.startsWith("javascript:") ||
        normalized.startsWith("vbscript:") ||
        normalized.startsWith("data:") ||
        normalized.startsWith("http://") ||
        normalized.startsWith("https://") ||
        normalized.startsWith("file://") ||
        normalized.startsWith("//")
}

private val BLOCKED_ELEMENT_WITH_BODY_REGEX = Regex(
    """<\s*(script|iframe|object|embed|form|textarea|select|button)\b[^>]*>.*?<\s*/\s*\1\s*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val BLOCKED_ELEMENT_REGEX = Regex(
    """<\s*/?\s*(script|iframe|object|embed|form|input|textarea|select|option|button|meta|link|base)\b[^>]*>""",
    RegexOption.IGNORE_CASE,
)
private val EVENT_HANDLER_ATTR_REGEX = Regex(
    """\s+on[a-zA-Z0-9_-]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""",
    RegexOption.IGNORE_CASE,
)
private val STYLE_ATTR_REGEX = Regex(
    """\s+style\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""",
    RegexOption.IGNORE_CASE,
)
private val SRCDOC_ATTR_REGEX = Regex(
    """\s+srcdoc\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""",
    RegexOption.IGNORE_CASE,
)
private val SRCSET_ATTR_REGEX = Regex(
    """\s+srcset\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""",
    RegexOption.IGNORE_CASE,
)
private val RESOURCE_ATTR_REGEX = Regex(
    """\s+(href|src|xlink:href)\s*=\s*(["'])(.*?)\2""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val UNQUOTED_RESOURCE_ATTR_REGEX = Regex(
    """\s+(href|src|xlink:href)\s*=\s*([^\s>]+)""",
    RegexOption.IGNORE_CASE,
)
