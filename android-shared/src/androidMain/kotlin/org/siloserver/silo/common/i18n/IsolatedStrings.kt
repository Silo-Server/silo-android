package org.siloserver.silo.common.i18n

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * [stringResource] for a message whose arguments are titles or names. Each
 * one is wrapped in Unicode isolates (FSI…PDI), so a right-to-left name in a
 * left-to-right sentence, or the reverse, keeps the sentence's punctuation and
 * word order. `BidiFormatter.unicodeWrap` uses embeddings and marks instead,
 * which leak the name's direction into the text around it.
 */
@Composable
fun stringResourceWithNames(@StringRes id: Int, vararg names: String): String =
    stringResource(id, *names.map { "\u2068$it\u2069" }.toTypedArray())
