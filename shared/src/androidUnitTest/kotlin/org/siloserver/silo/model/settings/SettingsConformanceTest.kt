package org.siloserver.silo.model.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin runner for the cross-platform settings conformance fixture — the
 * contract's named drift gate.
 *
 * The same hand-authored cases in `contracts/settings/v1/conformance.json` run
 * against the Go resolver (`internal/settingsresolve/conformance_test.go`), the
 * TypeScript one (`web/src/lib/settingsConformance.test.ts`), this one, and
 * Swift in the Apple clients. Four independently written resolvers agreeing on
 * every case is the whole point, so this runner takes the fixture at face
 * value: it decodes strictly, resolves through [resolveSettingValues] against
 * the real vendored manifest, and compares every declared expectation.
 *
 * Both JSON files are vendored byte-identically from the server repo; see the
 * SOURCE file beside them. Nothing here touches the network.
 *
 * Four things fail this suite, and each of them is drift:
 *
 * 1. A resolution disagreement — this client would show a user a different
 *    effective setting than the server resolves.
 * 2. A manifest revision mismatch between the fixture, the vendored manifest,
 *    and the generated [SettingKeys]. A revision bump changes definitions, so
 *    the expectations have to be re-derived rather than assumed to still hold.
 * 3. A key the bindings and the vendored manifest disagree about, which catches
 *    the two JSON files being vendored from different server commits — skew the
 *    revision check cannot see, since a revision only moves on a manifest PR
 *    and both copies would still read the same number.
 * 4. A fixture field this runner does not know. Schema drift in the fixture is
 *    itself drift: a field one platform reads and another silently skips means
 *    the platforms are no longer running the same cases, which is precisely the
 *    failure the fixture exists to prevent. Strictness here is not pedantry —
 *    it is the only thing keeping a silent skip from looking like a pass.
 */
class SettingsConformanceTest {

    // Strict by construction: kotlinx rejects unknown keys and missing required
    // fields at every level of the tree by default, which is the unknown-field
    // gate for everything below. The places JSON null is a *value* rather than
    // an absence — a stored row's value, and an expectation's value and
    // stored_value — are typed as non-nullable JsonElement so an authored null
    // decodes to JsonNull instead of collapsing onto the Kotlin null an
    // omission produces.
    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    @Serializable
    private data class ConformanceFixture(
        @SerialName("fixture_version") val fixtureVersion: Int,
        @SerialName("manifest_revision") val manifestRevision: Int,
        val description: String,
        val cases: List<ConformanceCase>,
    )

    @Serializable
    private data class ConformanceCase(
        val name: String,
        val description: String? = null,
        val keys: List<String>,
        val context: ConformanceContext? = null,
        val stored: List<ConformanceRow> = emptyList(),
        // Policy inputs by name, as the policy layer would supply them. Keys
        // here are data, not schema, so they are deliberately not field-checked.
        val constraints: Map<String, JsonElement> = emptyMap(),
        // Attaches a constraint to a copy of a real definition, so constraint
        // kinds no shipped definition carries stay testable.
        @SerialName("constraint_bindings") val constraintBindings: List<ConformanceBinding> = emptyList(),
        val expected: List<ConformanceExpected>,
    )

    @Serializable
    private data class ConformanceContext(
        @SerialName("profile_id") val profileId: String? = null,
        @SerialName("device_id") val deviceId: String? = null,
        @SerialName("library_ids") val libraryIds: List<Int> = emptyList(),
        @SerialName("series_ids") val seriesIds: List<String> = emptyList(),
    )

    @Serializable
    private data class ConformanceRow(
        val key: String,
        val scope: String,
        @SerialName("profile_id") val profileId: String? = null,
        @SerialName("device_id") val deviceId: String? = null,
        @SerialName("library_id") val libraryId: Int? = null,
        @SerialName("series_id") val seriesId: String? = null,
        val value: JsonElement,
    )

    @Serializable
    private data class ConformanceBinding(
        val key: String,
        @SerialName("policy_input") val policyInput: String,
        val constraint: SettingConstraintKind,
    )

    @Serializable
    private data class ConformanceExpected(
        val key: String,
        val value: JsonElement,
        val source: String,
        /** Defaults to false; when true, stored_value and constraint_kind must be present. */
        val constrained: Boolean = false,
        // Typed non-nullable so an authored `"stored_value": null` — which two
        // bitrate cases rely on — decodes to JsonNull rather than collapsing
        // onto the same Kotlin null an omission produces. Presence itself is
        // gated in [expectationsSpellNullRatherThanOmittingIt], which reads the
        // raw tree; kotlinx cannot express the distinction here.
        @SerialName("stored_value") val storedValue: JsonElement = JsonNull,
        @SerialName("constraint_kind") val constraintKind: SettingConstraintKind? = null,
    )

    private val manifest: SettingsManifest by lazy {
        // The manifest is a vendored copy pinned by its revision rather than by
        // field-level strictness, so unknown fields here are tolerated: the
        // server may add advisory metadata in a revision this client still
        // understands, and only the fields resolution reads are modelled.
        Json { ignoreUnknownKeys = true }
            .decodeFromString<SettingsManifest>(resource("settings/v1/manifest.json"))
    }

    private val fixtureRaw: String by lazy { resource("settings/v1/conformance.json") }

    private val fixture: ConformanceFixture by lazy {
        strictJson.decodeFromString<ConformanceFixture>(fixtureRaw)
    }

    @Test
    fun theFixtureTargetsThisBuildsManifestRevision() {
        assertEquals(1, fixture.fixtureVersion, "this runner understands fixture_version 1")

        // Three copies of one number, and they are only equal by maintenance:
        // the fixture, the manifest it was authored against, and the bindings
        // generated from that manifest. A bump to any one without the others is
        // a client resolving against a contract it no longer carries.
        assertEquals(
            manifest.revision,
            fixture.manifestRevision,
            "the fixture targets manifest revision ${fixture.manifestRevision} but the vendored " +
                "manifest is revision ${manifest.revision}; re-vendor both and re-derive the " +
                "fixture expectations",
        )
        assertEquals(
            SettingKeys.REVISION,
            manifest.revision,
            "the vendored manifest is revision ${manifest.revision} but the generated bindings " +
                "are revision ${SettingKeys.REVISION}; re-run make settings-bindings on the " +
                "server and re-vendor",
        )
        assertTrue(fixture.cases.isNotEmpty(), "the fixture declares no cases")
    }

    @Test
    fun theVendoredManifestCoversTheGeneratedBindings() {
        // The bindings are generated from this manifest, so every remote key
        // must resolve against it. A key in one and not the other means the two
        // files were vendored from different server commits — the exact drift
        // the revision check cannot catch, because a revision is only bumped by
        // a manifest PR and both copies would still read 1.
        for (key in SettingKeys.REMOTE) {
            val definition = assertNotNull(manifest.lookup(key), "$key is not in the manifest")
            assertTrue(definition.isRemote, "$key is remote in the bindings, not in the manifest")
        }
        for (key in SettingKeys.CLIENT_LOCAL) {
            val definition = assertNotNull(manifest.lookup(key), "$key is not in the manifest")
            assertFalse(
                definition.isRemote,
                "$key is client_local in the bindings but remote in the manifest",
            )
        }
    }

    @Test
    fun everyCaseIsDeclaredOnce() {
        val names = fixture.cases.map { it.name }
        assertFalse(names.any { it.isBlank() }, "a conformance case has no name")
        assertEquals(names.size, names.toSet().size, "duplicate conformance case name")
    }

    /**
     * The null-vs-absent gate that strict decoding cannot express.
     *
     * `value` is required, so decoding already caught an omission. `stored_value`
     * is the hard one: it may legitimately be JSON null when present, and
     * kotlinx collapses "absent" and "explicit null" onto the same Kotlin null.
     * So presence is read off the raw tree, matching what the Go runner gets
     * from a nil json.RawMessage and the web runner from an `in` check.
     */
    @Test
    fun expectationsSpellNullRatherThanOmittingIt() {
        val cases = strictJson.parseToJsonElement(fixtureRaw).jsonObject
            .getValue("cases").jsonArray
        assertEquals(fixture.cases.size, cases.size)

        cases.forEachIndexed { caseIndex, rawCase ->
            val case = fixture.cases[caseIndex]
            val rawObject = rawCase.jsonObject

            rawObject["stored"]?.jsonArray?.forEachIndexed { rowIndex, rawRow ->
                assertTrue(
                    "value" in rawRow.jsonObject,
                    "${case.name}: stored[$rowIndex] must spell an authored null as null",
                )
            }

            rawObject.getValue("expected").jsonArray.forEachIndexed { index, rawExpected ->
                val expectation = case.expected[index]
                val fields = rawExpected.jsonObject
                assertTrue(
                    "value" in fields,
                    "${case.name}: expected[$index] must spell an expected null as null",
                )
                if (expectation.constrained) {
                    assertTrue(
                        "stored_value" in fields,
                        "${case.name}: ${expectation.key}: a constrained expectation must " +
                            "declare stored_value",
                    )
                    assertNotNull(
                        expectation.constraintKind,
                        "${case.name}: ${expectation.key}: a constrained expectation must " +
                            "declare constraint_kind",
                    )
                } else {
                    assertFalse(
                        "stored_value" in fields,
                        "${case.name}: ${expectation.key}: an unconstrained expectation must " +
                            "not declare stored_value",
                    )
                    assertNull(
                        expectation.constraintKind,
                        "${case.name}: ${expectation.key}: an unconstrained expectation must " +
                            "not declare constraint_kind",
                    )
                }
            }
        }
    }

    @Test
    fun everyCaseResolvesToItsExpectedEffectiveValues() {
        for (case in fixture.cases) {
            assertTrue(case.keys.isNotEmpty(), "${case.name}: declares no keys")
            assertTrue(case.expected.isNotEmpty(), "${case.name}: declares no expectations")

            val bindings = mutableMapOf<String, SettingConstraintBinding>()
            for (binding in case.constraintBindings) {
                assertNotNull(
                    manifest.lookup(binding.key),
                    "${case.name}: constraint binding names unknown key ${binding.key}",
                )
                assertTrue(
                    binding.policyInput.isNotBlank(),
                    "${case.name}: constraint binding on ${binding.key} has no policy_input",
                )
                assertNull(
                    bindings.put(
                        binding.key,
                        SettingConstraintBinding(binding.policyInput, binding.constraint),
                    ),
                    "${case.name}: duplicate constraint binding for ${binding.key}",
                )
            }

            val resolved = resolveSettingValues(
                manifest = manifest,
                keys = case.keys,
                stored = case.stored.map { row ->
                    StoredSettingRow(
                        key = row.key,
                        scope = row.scope,
                        profileId = row.profileId,
                        deviceId = row.deviceId,
                        libraryId = row.libraryId,
                        seriesId = row.seriesId,
                        value = row.value,
                    )
                },
                context = SettingResolutionContext(
                    profileId = case.context?.profileId,
                    deviceId = case.context?.deviceId,
                    libraryIds = case.context?.libraryIds.orEmpty(),
                    seriesIds = case.context?.seriesIds.orEmpty(),
                ),
                constraints = case.constraints,
                constraintBindings = bindings,
            )

            assertEquals(
                case.expected.size,
                resolved.size,
                "${case.name}: resolved ${resolved.size} settings, the fixture expects " +
                    "${case.expected.size}",
            )
            val byKey = resolved.associateBy { it.key }

            for (expectation in case.expected) {
                val entry = assertNotNull(
                    byKey[expectation.key],
                    "${case.name}: no resolved value for ${expectation.key}",
                )
                val where = "${case.name}: ${expectation.key}"
                assertTrue(
                    jsonEquivalent(entry.value, expectation.value),
                    "$where: value = ${entry.value}, want ${expectation.value}",
                )
                assertEquals(expectation.source, entry.source, "$where: source")
                assertEquals(expectation.constrained, entry.constrained, "$where: constrained")
                assertEquals(
                    expectation.constraintKind,
                    entry.constraintKind,
                    "$where: constraint_kind",
                )
                if (expectation.constrained) {
                    val storedValue = assertNotNull(
                        entry.storedValue,
                        "$where: a constrained result must report stored_value",
                    )
                    assertTrue(
                        jsonEquivalent(storedValue, expectation.storedValue),
                        "$where: stored_value = $storedValue, want ${expectation.storedValue}",
                    )
                } else {
                    assertNull(
                        entry.storedValue,
                        "$where: stored_value reported without a constraint",
                    )
                }
            }
        }
    }

    @Test
    fun anUnknownFixtureFieldFails() {
        // The unknown-field gate is the one thing here that would otherwise be
        // untested — it only ever fires on a fixture this repo does not carry
        // yet, so its own regression would be invisible until the day it was
        // needed. Injecting a field proves strict decoding is actually on.
        val drifted = fixtureRaw.replaceFirst("\"cases\":", "\"cases_v2\": [], \"cases\":")
        assertFalse(drifted == fixtureRaw, "failed to inject a drifted field")
        val failure = runCatching { strictJson.decodeFromString<ConformanceFixture>(drifted) }
        assertTrue(
            failure.isFailure,
            "an unknown fixture field decoded cleanly; strict decoding is off and drift in the " +
                "fixture schema would pass silently",
        )
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource(path)) {
            "Missing test resource $path"
        }.readText()
}
