package org.siloserver.silo.common.i18n

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.isTimeout

/**
 * Text a view model hands its screen, resolved in composition so it follows
 * an in-place language change: a catalog string with its arguments, or text
 * that arrives already worded (the server's message, a shared-module notice).
 */
sealed interface UiText {
    /**
     * A catalog string. Each of [args] is a [String] (a name, address or
     * server text, isolated in a right-to-left interface), another [UiText]
     * (resolved first), or a number, in the order its placeholders are declared.
     */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** Text that arrives already worded. */
    data class Raw(val text: String) : UiText

    companion object {
        /** The server's [message] when it sent one, otherwise [fallback]. */
        fun serverOr(message: String, @StringRes fallback: Int): UiText =
            if (message.isBlank()) Res(fallback) else Raw(message)
    }
}

/** [UiText] for a catalog string. */
fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

@Composable
fun UiText.text(): String = when (this) {
    is UiText.Res -> when {
        args.isEmpty() -> stringResource(id)
        args.all { it is String || it is UiText } ->
            stringResourceWithNames(id, *args.map { if (it is UiText) it.text() else it as String }.toTypedArray())
        else -> stringResource(id, *args.map { if (it is UiText) it.text() else it }.toTypedArray())
    }
    is UiText.Raw -> text
}

/** [text] outside composition, such as for a toast. */
fun UiText.text(context: Context): String = when (this) {
    is UiText.Res -> when {
        args.isEmpty() -> context.getString(id)
        args.all { it is String || it is UiText } ->
            context.getStringWithNames(id, *args.map { if (it is UiText) it.text(context) else it as String }.toTypedArray())
        else -> context.getString(id, *args.map { if (it is UiText) it.text(context) else it }.toTypedArray())
    }
    is UiText.Raw -> text
}

/**
 * The catalog form of `errorMessage(fallback)`: the server's message,
 * [fallback] when it sent none, and the timeout or network-error text for a
 * request the server didn't answer.
 */
fun ApiResult<*>.errorText(@StringRes fallback: Int): UiText = when (this) {
    is ApiResult.Success -> UiText.Res(fallback)
    is ApiResult.Error -> UiText.serverOr(message, fallback)
    is ApiResult.NetworkError ->
        UiText.Res(if (isTimeout) R.string.catalog_error_timeout else R.string.catalog_error_network)
}

/** "Network error: <cause>", for loads that show the system's reason. */
fun ApiResult.NetworkError.detailedErrorText(): UiText =
    exception.message?.let { UiText.Res(R.string.catalog_error_networkDetail, listOf(it)) }
        ?: UiText.Res(R.string.catalog_error_networkUnknown)

/**
 * The app's resources, for catalog text built outside `stringResource` (helpers
 * that unit tests call with a plain `Resources`). Reading [LocalConfiguration]
 * recomposes the caller when the language changes in place, as `stringResource`
 * does. `LocalResources`, which lint suggests, isn't in this Compose version.
 */
@SuppressLint("LocalContextResourcesRead")
@Composable
@ReadOnlyComposable
fun localizedResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

/**
 * Uppercases interface text with the interface language's rules (Turkish
 * dotted I, for example), not the root locale's.
 */
@Composable
@ReadOnlyComposable
fun String.uppercaseForUi(): String = uppercase(LocalConfiguration.current.locales[0])

/** Lowercases interface text with the interface language's rules. */
@Composable
@ReadOnlyComposable
fun String.lowercaseForUi(): String = lowercase(LocalConfiguration.current.locales[0])
