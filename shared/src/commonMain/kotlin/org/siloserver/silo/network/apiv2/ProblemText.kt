package org.siloserver.silo.network.apiv2

import kotlin.concurrent.Volatile

/**
 * The text a screen shows for a v2 problem. In English it is always the
 * server's `detail`. A platform installs [localize] at startup to return the
 * shared catalog's `error.field.<code>` or `error.<type>` message when the
 * interface language has one; null keeps `detail`.
 */
object ProblemText {
    @Volatile
    var localize: ((Problem) -> String?)? = null

    /** [problem]'s text for a screen; [fallback] is the server text shown when nothing translates it. */
    fun message(problem: Problem, fallback: String = problem.detail): String =
        localize?.let { runCatching { it(problem) }.getOrNull() } ?: fallback
}
