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
 * as an opaque string, so a placeholder would surface verbatim as "(0)" in
 * admin Activity. Nothing is lost — the channel already says `dev`.
 */
fun normalizedClientBuildNumber(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() && it != UNSET_BUILD_NUMBER }

/**
 * The About-row label for a version and its build: `"1.0.0 (5)"`, or the bare
 * version when the build is unstamped. One helper so the phone and TV Settings
 * screens can't drift, and so both match the form Play, TestFlight and the
 * server's own diagnostics page render.
 */
fun clientVersionLabel(version: String, rawBuildNumber: String?): String =
    normalizedClientBuildNumber(rawBuildNumber)
        ?.let { build -> "$version ($build)" }
        ?: version

/**
 * The two build facts only an app module's `BuildConfig` knows, resolved once
 * per process and handed to every `android-shared` collaborator that reports
 * client identity: the metadata provider behind the `X-Silo-Client-*` headers,
 * the playback capability detector behind the v3 client context, and the
 * diagnostics exit-report environment.
 *
 * It exists so those three agree. Deriving the channel independently — say,
 * from `ApplicationInfo.FLAG_DEBUGGABLE` — makes a `debuggable true` release
 * build report `dev` down one path and `release` down another for the same
 * install, and there is no way at all to recover [buildNumber] from the
 * installed package: `versionCode` is the form-factor-doubled release code
 * (`base*2` phone, `base*2+1` TV), not CI's counter.
 *
 * @param buildNumber the app module's `BuildConfig.BUILD_NUMBER` — CI's
 *   per-marketing-version build counter. The Gradle default `"0"` means "not
 *   built by CI"; prefer [reportedBuildNumber] on any path where absence is
 *   representable.
 * @param channel the app module's `BuildConfig.RELEASE_CHANNEL` — how the build
 *   was distributed. Play's own track vocabulary where the build came from a
 *   track ("internal" / "alpha" / "beta" / "production"), otherwise "sideload"
 *   for an APK installed outside Play or "dev" for a local debug build. Opaque
 *   to the server, which stores it as reported.
 */
data class SiloClientBuildIdentity(
    val buildNumber: String,
    val channel: String,
) {
    /** [buildNumber] with the unstamped placeholder collapsed to null. */
    val reportedBuildNumber: String? = normalizedClientBuildNumber(buildNumber)

    /** [channel] with blank input collapsed to null, for optional carriers. */
    val reportedChannel: String? = channel.trim().takeIf { it.isNotBlank() }
}
