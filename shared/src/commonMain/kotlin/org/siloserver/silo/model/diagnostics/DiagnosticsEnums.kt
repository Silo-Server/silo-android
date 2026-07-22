package org.siloserver.silo.model.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client-diagnostics wire enums, v1.
 *
 * These mirror the server's authoritative registry in
 * `silo-server/internal/diagnostics/contract/contract.go` (and the checked-in
 * JSON Schema at `docs/design/schemas/client-diagnostics/v1/`). The server
 * rejects any value outside these sets, so additions here must land server-side
 * first. Apple/iOS-only values (metrickit, exit_sentinel, ios/tvos) are kept so
 * the shared golden fixtures decode under the same models the server validates.
 */

@Serializable
enum class DiagnosticsReportType {
    @SerialName("crash") CRASH,
    @SerialName("anr") ANR,
    @SerialName("native_crash") NATIVE_CRASH,
    @SerialName("hang") HANG,
    @SerialName("abnormal_exit") ABNORMAL_EXIT,
    @SerialName("manual") MANUAL,
}

@Serializable
enum class DiagnosticsPlatform {
    @SerialName("android") ANDROID,
    @SerialName("android-tv") ANDROID_TV,
    @SerialName("ios") IOS,
    @SerialName("tvos") TVOS,
}

@Serializable
enum class DiagnosticsConsentMode {
    @SerialName("prompt") PROMPT,
    @SerialName("always") ALWAYS,
    @SerialName("manual") MANUAL,
}

@Serializable
enum class DiagnosticsCrashSource {
    @SerialName("ueh") UEH,
    @SerialName("exit_info") EXIT_INFO,
    @SerialName("metrickit") METRICKIT,
    @SerialName("exit_sentinel") EXIT_SENTINEL,
}

@Serializable
enum class DiagnosticsCrashProvenance {
    @SerialName("pre_failure") PRE_FAILURE,
    @SerialName("post_restart") POST_RESTART,
    @SerialName("metric_reporting_period") METRIC_REPORTING_PERIOD,
}

/** Narrower than [DiagnosticsCrashProvenance]: device.json never carries `metric_reporting_period`. */
@Serializable
enum class DiagnosticsDeviceProvenance {
    @SerialName("pre_failure") PRE_FAILURE,
    @SerialName("post_restart") POST_RESTART,
}

@Serializable
enum class DiagnosticsLogLevel {
    @SerialName("V") V,
    @SerialName("D") D,
    @SerialName("I") I,
    @SerialName("W") W,
    @SerialName("E") E,
}

@Serializable
enum class DiagnosticsLogCategory(val wire: String) {
    @SerialName("playback") PLAYBACK("playback"),
    @SerialName("focus") FOCUS("focus"),
    @SerialName("network") NETWORK("network"),
    @SerialName("lifecycle") LIFECYCLE("lifecycle"),
    @SerialName("browse") BROWSE("browse"),
    @SerialName("cast") CAST("cast"),
    @SerialName("download") DOWNLOAD("download"),
    @SerialName("crash") CRASH("crash"),
    @SerialName("other") OTHER("other"),
}
