package org.siloserver.silo.tv.ui.focus

import androidx.compose.ui.focus.FocusRequester
import org.siloserver.silo.common.diagnostics.DiagnosticsFocusLogger

/**
 * A focus claim for callers that have no suspend point to retry in.
 *
 * [requestFocusUntilObserved] is the right answer wherever a coroutine is
 * available, because arrival can be observed and a claim that never lands can
 * be retried. Some callers cannot use it at all:
 *
 * - a `BackHandler` or `onPreviewKeyEvent` branch has to return synchronously
 *   whether it consumed the key;
 * - `DisposableEffect { onDispose { … } }` runs during teardown, where there is
 *   no scope left to launch into.
 *
 * Those sites were the argument for leaving some `runCatching { requestFocus() }`
 * in place forever. That argument was wrong. Retrying is not the only thing the
 * policy provides — the other half is that a failure stops being invisible, and
 * that half is available here too.
 *
 * So this does the one attempt those callers are limited to, and reports when
 * it does not land instead of swallowing it. The caller gets the boolean it
 * needs; the diagnostic exists whether or not anyone acts on it. `requestFocus`
 * throws rather than returning false when its node has not attached, which is
 * exactly the case worth knowing about, so the throw is caught and reported
 * rather than propagated into a key handler.
 *
 * @param target short, stable name of what focus was aimed at — it lands in
 *   diagnostics, so it must not carry titles, ids, or anything else derived
 *   from the viewer's library.
 * @return whether the request was accepted. Accepted is not arrival; nothing
 *   here can tell the difference. A caller that needs arrival needs a
 *   coroutine and [requestFocusUntilObserved].
 */
internal fun FocusRequester.claimFocusOrReport(target: String, action: String): Boolean {
    val accepted = runCatching { requestFocus() }.getOrElse { throwable ->
        DiagnosticsFocusLogger.transition(target, "$action:threw:${throwable::class.simpleName}")
        return false
    }
    if (!accepted) {
        DiagnosticsFocusLogger.transition(target, "$action:rejected")
    }
    return accepted
}
