package org.siloserver.silo.cast

object SiloCastProtocol {
    const val version: Int = 2
    val supportedVersions: List<Int> = listOf(version)
    const val serviceType: String = "_silocast._tcp"

    fun negotiatedVersion(peerVersions: List<Int>): Int? =
        supportedVersions.filter(peerVersions::contains).maxOrNull()
}
