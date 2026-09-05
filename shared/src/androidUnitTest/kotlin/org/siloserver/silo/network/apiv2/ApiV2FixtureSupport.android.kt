package org.siloserver.silo.network.apiv2

actual fun readApiV2FixtureResource(name: String): String =
    checkNotNull(ApiV2Fixtures::class.java.classLoader?.getResource("api/v2/fixtures/$name")) {
        "Missing vendored fixture api/v2/fixtures/$name; run scripts/sync-apiv2-fixtures.sh"
    }.readText()
