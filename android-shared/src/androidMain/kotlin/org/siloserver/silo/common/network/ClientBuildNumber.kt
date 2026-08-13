package org.siloserver.silo.common.network

/** Gradle's `siloBuildNumber` default, i.e. a build CI did not stamp. */
private const val UNSET_BUILD_NUMBER = "0"

/**
 * Normalizes an app module's `BuildConfig.BUILD_NUMBER` for reporting to the
 * server, on any carrier (the `X-Silo-Client-Build` header, the v3 client
 * playback context, the Cast request).
 *
 * A build CI never stamped carries the Gradle default `"0"`, which must be
 * reported as *absent* rather than as build zero: the server treats the build
 * as an opaque string, so a placeholder would surface verbatim as "(build 0)"
 * in admin Activity. Nothing is lost — the channel already says `dev`.
 */
fun normalizedClientBuildNumber(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() && it != UNSET_BUILD_NUMBER }
