// Not UiText.kt: android-shared has a UiText.kt in this package, and both would compile to UiTextKt.

package org.siloserver.silo.common.i18n

import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.isTimeout

/**
 * Text a view model hands its screen, resolved by the screen so it follows an
 * in-place language change: a catalog string with its arguments, or text that
 * arrives already worded (the server's message, user input).
 *
 * The shared module names catalog strings with [Key] and [Plural]; app code can
 * also use [Res] with a generated resource id. android-shared resolves all of
 * them (`text()` in composition, `text(context)` or `text(resources)` outside).
 * See "Text from the shared module" in `docs/i18n.md`.
 *
 * This type lives in `shared` so shared view models and app view models hand
 * screens the same type; its package matches the android-shared resolvers.
 */
sealed interface UiText {
    /**
     * A generated Android string resource ([id] is an `R.string` id), named by
     * app code. Each of [args] is a [String] (a name, address or server text),
     * another [UiText] (resolved first), or a number, in the order its
     * placeholders are declared. When every argument is a [String] or a
     * [UiText], each is isolated in a right-to-left interface.
     */
    data class Res(val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A catalog string named from the shared module; [args] as for [Res]. */
    data class Key(val key: SharedString, val args: List<Any> = emptyList()) : UiText

    /**
     * A catalog plural. [count] picks the form and fills the message's first
     * placeholder, `{count}`; [args] fill the ones after it, as for [Res]. A
     * message with a `{count_text}` slot (a count that can pass 999) takes
     * `count.toString()` as its first arg.
     */
    data class Plural(val key: SharedPlural, val count: Int, val args: List<Any> = emptyList()) : UiText

    /** Text that arrives already worded: the server's own message, user input. */
    data class Raw(val text: String) : UiText

    /**
     * A number for a `String` placeholder, or shown alone: in the interface
     * language's digits, without grouping (D55), so English keeps "1234".
     * At most [maxFractionDigits] decimals, and at least [minIntegerDigits]
     * whole digits ("02" for an episode code).
     */
    data class Number(
        val value: Double,
        val maxFractionDigits: Int = 0,
        val minIntegerDigits: Int = 1,
    ) : UiText {
        constructor(value: Int, minIntegerDigits: Int = 1) : this(value.toDouble(), 0, minIntegerDigits)
    }

    /**
     * A calendar date with the month as a word, in the interface language's
     * order and month names: "Jan 5, 1980" in US English. [month] is 1–12.
     */
    data class CalendarDate(val year: Int, val month: Int, val day: Int) : UiText

    /**
     * A language's name in the interface language, for a BCP 47 [tag]; the
     * uppercased tag when the platform has no name for it.
     */
    data class LanguageName(val tag: String) : UiText
}

/**
 * What a screen shows for a failed [ApiResult]: the text a client-built error
 * names ([ApiResult.Error.text]), else the server's message (a v2 problem's
 * `detail`, translated per D33), else [fallback]; the timeout or network-error
 * text for a request the server didn't answer. Total over the sealed type
 * ([fallback] for Success), so it works on a merged `is Error, is NetworkError`
 * branch. [ApiResult.Error.message] itself stays for logs.
 */
fun ApiResult<*>.errorText(fallback: UiText): UiText = errorTextOrNull() ?: fallback

/** [errorText] without a fallback: null for Success and for an error with nothing to show. */
fun ApiResult<*>.errorTextOrNull(): UiText? = when (this) {
    is ApiResult.Success -> null
    is ApiResult.Error -> text ?: message.takeIf { it.isNotBlank() }?.let(UiText::Raw)
    is ApiResult.NetworkError -> UiText.Key(if (isTimeout) SharedString.RequestTimeout else SharedString.NetworkError)
}

/** [errorText] with a shared catalog string as the fallback. */
fun ApiResult<*>.errorText(fallback: SharedString): UiText = errorText(UiText.Key(fallback))

/** "Network error: <cause>", for loads that show the system's reason. */
fun ApiResult.NetworkError.detailedErrorText(): UiText =
    exception.message?.let { UiText.Key(SharedString.NetworkErrorDetail, listOf(it)) }
        ?: UiText.Key(SharedString.NetworkErrorUnknown)
