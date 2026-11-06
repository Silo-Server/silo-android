package org.siloserver.silo.android.cast

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import org.siloserver.silo.common.i18n.EnglishText

/**
 * Text the remote shows: a catalog message resolved when it is drawn, so a
 * language change applies to an error already on screen, or text a TV or the
 * server sent, shown as it arrived.
 */
sealed interface SiloCastText {
    data class Res(@StringRes val id: Int, val args: List<Any>) : SiloCastText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    data class Verbatim(val text: String) : SiloCastText

    fun resolve(resources: Resources): String = when (this) {
        is Res -> resources.getString(id, *args.toTypedArray())
        is Verbatim -> text
    }

    /** The text in English, for logs and protocol messages a TV reads (null in unit tests). */
    fun english(): String? = when (this) {
        is Res -> EnglishText.string(id, *args.toTypedArray())
        is Verbatim -> text
    }
}

@Composable
fun SiloCastText.resolve(): String {
    return resolve(LocalResources.current)
}

/**
 * A Remote Control failure the user sees. The exception message is its text in
 * English ([english], when the shown text is a translation of a server's
 * message), which logs and the handoff cancel sent to the TV carry.
 */
open class SiloCastFailure(val text: SiloCastText, cause: Throwable? = null, english: String? = text.english()) :
    IllegalStateException(english, cause) {
    constructor(@StringRes id: Int) : this(SiloCastText.Res(id))
}

/** What to show for this failure: its [SiloCastFailure.text], else its message. */
internal fun Throwable.castTextOrNull(): SiloCastText? =
    (this as? SiloCastFailure)?.text ?: message?.let(SiloCastText::Verbatim)

internal fun Throwable.castText(@StringRes fallback: Int): SiloCastText =
    castTextOrNull() ?: SiloCastText.Res(fallback)
