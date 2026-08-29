package org.siloserver.silo.network

data class SiloDeviceMetadata(
    val id: String,
    val name: String,
    val platform: String,
    val clientName: String? = null,
    val clientVersion: String? = null,
    /**
     * The build counter behind [clientVersion] (CI's per-marketing-version
     * build number), so the server can distinguish two builds that share a
     * version name. Null when the platform has no such value.
     */
    val clientBuild: String? = null,
    /**
     * How this build was distributed — "release", "beta", "sideload", "dev".
     * Opaque to the server, which stores it as reported.
     */
    val clientChannel: String? = null,
    /**
     * Closed like-client identity for `profile_client`-scoped settings —
     * one of "tv", "mobile", "tablet", "desktop", "web" (lower-case exact;
     * the server never derives it from the platform string). Null skips the
     * `X-Silo-Client-Family` header, which drops the like-client resolution
     * layer server-side.
     */
    val clientFamily: String? = null,
)

interface DeviceMetadataProvider {
    suspend fun current(): SiloDeviceMetadata?
}
