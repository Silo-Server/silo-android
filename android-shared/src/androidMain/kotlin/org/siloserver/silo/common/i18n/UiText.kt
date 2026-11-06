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
 * Text a view model hands its screen without holding a Context: the server's
 * own words, or a catalog string the screen resolves in the interface
 * language, so the text follows a language change.
 */
sealed interface UiText {
    /** Shown as is: the server's message, or text built in `shared`. */
    data class Verbatim(val text: String) : UiText

    /** A catalog string and its format arguments. */
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Verbatim -> text
    is UiText.Resource -> stringResource(id, *args.toTypedArray())
}

/** [asString] outside composition, such as for a toast. */
fun UiText.asString(context: Context): String = when (this) {
    is UiText.Verbatim -> text
    is UiText.Resource -> context.getString(id, *args.toTypedArray())
}

/**
 * The catalog form of `errorMessage(fallback)`: the server's message,
 * [fallback] when it sent none, and the timeout or network-error text for a
 * request the server didn't answer.
 */
fun ApiResult<*>.errorText(@StringRes fallback: Int): UiText = when (this) {
    is ApiResult.Success -> UiText.Resource(fallback)
    is ApiResult.Error -> if (message.isBlank()) UiText.Resource(fallback) else UiText.Verbatim(message)
    is ApiResult.NetworkError ->
        UiText.Resource(if (isTimeout) R.string.catalog_error_timeout else R.string.catalog_error_network)
}

/** "Network error: <cause>", for loads that show the system's reason. */
fun ApiResult.NetworkError.detailedErrorText(): UiText =
    exception.message?.let { UiText.Resource(R.string.catalog_error_networkDetail, listOf(it)) }
        ?: UiText.Resource(R.string.catalog_error_networkUnknown)

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
