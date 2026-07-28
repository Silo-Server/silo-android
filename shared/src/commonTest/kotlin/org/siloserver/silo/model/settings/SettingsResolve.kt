package org.siloserver.silo.model.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Client-side settings resolution, mirroring the server's
 * `internal/settingsresolve` semantics: the definition's declared resolution
 * order decides which stored value wins, an identity absent from the context
 * drops the scopes that need it, and policy constraints narrow the answer
 * without destroying what the user authored.
 *
 * Android does not resolve settings in production — it writes through
 * `/settings/values` and reads effective values back from
 * `/settings/values/effective`, so the server stays the single authority. This
 * resolver exists for one reason: the cross-platform conformance fixture in
 * `contracts/settings/v1/conformance.json` names a Kotlin implementation as one
 * of the four that must agree, and four independently written resolvers
 * agreeing is what makes the fixture a drift gate rather than a tautology. It
 * therefore lives in test sources, and every behavioral choice in it is pinned
 * by [SettingsConformanceTest] — do not change one without the fixture
 * agreeing.
 *
 * The Go implementation is `internal/settingsresolve/resolve.go` and the
 * TypeScript one is `web/src/lib/settingsResolve.ts`; the three are meant to be
 * readable side by side.
 */

/** Where a resolved value came from, or "default" when nothing was stored. */
object SettingSource {
    const val DEFAULT = "default"
    const val ACCOUNT = "account"
    const val PROFILE = "profile"
    const val PROFILE_DEVICE = "profile_device"
    const val PROFILE_LIBRARY = "profile_library"
    const val PROFILE_SERIES = "profile_series"
}

/** One stored row, as the server's values API reports it. */
data class StoredSettingRow(
    val key: String,
    val scope: String,
    val profileId: String? = null,
    val deviceId: String? = null,
    val libraryId: Int? = null,
    val seriesId: String? = null,
    val value: JsonElement,
)

/** The identity a resolution happens against. Absent fields drop their scopes. */
data class SettingResolutionContext(
    val profileId: String? = null,
    val deviceId: String? = null,
    val libraryIds: List<Int> = emptyList(),
    val seriesIds: List<String> = emptyList(),
)

/** One resolved setting. */
data class ResolvedSetting(
    val key: String,
    val value: JsonElement,
    val source: String,
    /** True when a policy constraint narrowed [value] away from what was stored. */
    val constrained: Boolean = false,
    /** What the user authored (may be [JsonNull]); present only when constrained. */
    val storedValue: JsonElement? = null,
    val constraintKind: SettingConstraintKind? = null,
)

/**
 * Resolves the effective value for each requested key against stored rows.
 *
 * Unknown and `client_local` keys are omitted rather than erroring, matching the
 * server: they have no server-resolved answer, so a newer client asking for a
 * setting this contract does not carry gets a short answer, not a failure.
 *
 * [constraintBindings] lets a caller — in practice the conformance runner —
 * attach a constraint to a key the shipped manifest does not bind, so
 * constraint kinds no definition currently carries stay testable. A key with no
 * entry uses the manifest's own `constrained_by`.
 */
fun resolveSettingValues(
    manifest: SettingsManifest,
    keys: List<String>,
    stored: List<StoredSettingRow>,
    context: SettingResolutionContext,
    constraints: Map<String, JsonElement> = emptyMap(),
    constraintBindings: Map<String, SettingConstraintBinding> = emptyMap(),
): List<ResolvedSetting> {
    val seen = mutableSetOf<String>()
    return keys.mapNotNull { key ->
        if (!seen.add(key)) return@mapNotNull null
        val definition = manifest.lookup(key) ?: return@mapNotNull null
        if (!definition.isRemote) return@mapNotNull null
        resolveOne(definition, stored, context, constraints, constraintBindings[key])
    }
}

private fun resolveOne(
    definition: SettingDefinition,
    stored: List<StoredSettingRow>,
    context: SettingResolutionContext,
    constraints: Map<String, JsonElement>,
    bindingOverride: SettingConstraintBinding?,
): ResolvedSetting {
    val candidates = stored.filter { it.key == definition.key }

    var value = definition.defaultValue
    var source = SettingSource.DEFAULT
    for (scope in definition.resolutionOrder) {
        if (scope == SettingSource.DEFAULT) break
        val row = pickForScope(scope, candidates, context) ?: continue
        value = row.value
        source = scope
        break
    }

    return applyConstraint(
        definition,
        ResolvedSetting(key = definition.key, value = value, source = source),
        constraints,
        bindingOverride,
    )
}

/**
 * Returns the candidate row for one scope, mirroring the server: an identity
 * missing from the context matches nothing, and a tie between several content
 * rows breaks deterministically by (libraryId, seriesId).
 *
 * The device case checks the context's device id is non-empty as well as equal.
 * Without that, a caller with no device identity — the anonymous jellycompat
 * seed — matches every row whose own device id is also empty, and one device's
 * settings leak to every client.
 *
 * That non-empty guard is currently unpinned by the fixture, in every language:
 * `missing_device_identity_drops_device_scope` supplies no device id in the
 * context but every stored profile_device row it carries names "d1", so plain
 * equality already excludes them and removing the guard still passes. It is
 * kept because the Go and TypeScript resolvers both have it and a row with an
 * empty device_id is reachable in production. Closing the gap means a case
 * upstream in contracts/settings/v1/conformance.json — a stored profile_device
 * row with an empty device_id against a context with none — so that all four
 * runners gain it at once; fixing it only here would defeat the point.
 */
private fun pickForScope(
    scope: String,
    candidates: List<StoredSettingRow>,
    context: SettingResolutionContext,
): StoredSettingRow? {
    val profileId = context.profileId.orEmpty()
    val deviceId = context.deviceId.orEmpty()
    val matches = candidates.filter { row ->
        if (row.scope != scope) return@filter false
        when (scope) {
            SettingSource.ACCOUNT -> true
            SettingSource.PROFILE -> row.profileId.orEmpty() == profileId
            SettingSource.PROFILE_DEVICE ->
                row.profileId.orEmpty() == profileId &&
                    row.deviceId.orEmpty() == deviceId &&
                    deviceId.isNotEmpty()
            SettingSource.PROFILE_LIBRARY ->
                row.profileId.orEmpty() == profileId &&
                    (row.libraryId ?: 0) in context.libraryIds
            SettingSource.PROFILE_SERIES ->
                row.profileId.orEmpty() == profileId &&
                    row.seriesId.orEmpty() in context.seriesIds
            else -> false
        }
    }
    if (matches.size <= 1) return matches.firstOrNull()
    // Deterministic rather than arbitrary: a batch spanning several libraries or
    // series has no single right answer and the caller is expected to resolve
    // per item, but two identical requests must not disagree.
    return matches.sortedWith(
        compareBy({ it.libraryId ?: 0 }, { it.seriesId.orEmpty() }),
    ).first()
}

/**
 * Narrows an effective value to what policy permits without destroying the
 * authored one: a preference capped today must take effect the day the cap
 * lifts, so the stored value is reported alongside the cap rather than replaced.
 */
private fun applyConstraint(
    definition: SettingDefinition,
    resolved: ResolvedSetting,
    constraints: Map<String, JsonElement>,
    bindingOverride: SettingConstraintBinding?,
): ResolvedSetting {
    val binding = bindingOverride ?: definition.constrainedBy ?: return resolved
    val limit = constraints[binding.policyInput] ?: return resolved

    val narrowed = narrowValue(definition, binding.constraint, resolved.value, limit)
        ?: return resolved
    return resolved.copy(
        value = narrowed,
        storedValue = resolved.value,
        constrained = true,
        constraintKind = binding.constraint,
    )
}

/** Applies one constraint kind, returning the narrowed value or null when it stands. */
private fun narrowValue(
    definition: SettingDefinition,
    kind: SettingConstraintKind,
    value: JsonElement,
    limit: JsonElement,
): JsonElement? = when (kind) {
    // The policy value replaces the user's outright. An already-equal value is
    // not a narrowing, so clients do not tell the user their own choice was
    // overridden.
    SettingConstraintKind.LOCKED ->
        if (jsonEquivalent(value, limit)) null else limit

    SettingConstraintKind.CEILING -> when {
        // null on a nullable numeric means "no cap of my own" — unbounded
        // above, which is exactly what a ceiling exists to bring down. It has
        // no numeric rank, so a plain comparison reports 0 and the one value
        // that most needs capping would slip past.
        value is JsonNull && definition.isNumeric -> limit
        compareValues(definition, value, limit) <= 0 -> null
        else -> limit
    }

    // The mirror rule: unbounded above already satisfies any floor.
    SettingConstraintKind.FLOOR -> when {
        value is JsonNull && definition.isNumeric -> null
        compareValues(definition, value, limit) >= 0 -> null
        else -> limit
    }

    SettingConstraintKind.ALLOWLIST -> {
        val allowed = limit as? JsonArray
        when {
            allowed == null || allowed.isEmpty() -> null
            allowed.any { jsonEquivalent(it, value) } -> null
            // Falling back to the first allowed member rather than the
            // definition default: the default may itself be outside the
            // allowlist, and an effective value the policy forbids is the one
            // thing this must never return.
            else -> allowed.first()
        }
    }
}

/**
 * Ranks two values through the definition's own schema: numbers numerically,
 * ordered enums by declared member position. Anything unrankable compares equal,
 * so an unrecognized value is never silently narrowed — validation is a separate
 * concern and has already rejected it by the time a constraint applies.
 */
private fun compareValues(definition: SettingDefinition, a: JsonElement, b: JsonElement): Int {
    if (definition.isNumeric) {
        val left = a.asDoubleOrNull() ?: return 0
        val right = b.asDoubleOrNull() ?: return 0
        return left.compareTo(right)
    }
    if (definition.valueSchema.type == SettingDefinition.TYPE_ENUM &&
        definition.valueSchema.ordered
    ) {
        val members = definition.valueSchema.values
        val left = members.indexOfFirst { jsonEquivalent(it.value, a) }
        val right = members.indexOfFirst { jsonEquivalent(it.value, b) }
        if (left < 0 || right < 0) return 0
        return left.compareTo(right)
    }
    return 0
}

private fun JsonElement.asDoubleOrNull(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull || primitive.isString) return null
    return primitive.content.toDoubleOrNull()
}

/**
 * Structural equality over JSON values, ignoring object key order and numeric
 * spelling.
 *
 * [JsonElement] already compares structurally, but it compares numbers by their
 * source text: `8000` and `8000.0` are the same JSON number and must not be
 * treated as different values. The Go runner decodes to `any` before comparing,
 * which collapses both to a float64; this does the same by hand.
 */
fun jsonEquivalent(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
    a is JsonObject && b is JsonObject ->
        a.keys == b.keys && a.all { (key, value) -> jsonEquivalent(value, b.getValue(key)) }
    a is JsonArray && b is JsonArray ->
        a.size == b.size && a.indices.all { jsonEquivalent(a[it], b[it]) }
    a is JsonPrimitive && b is JsonPrimitive -> when {
        a.isString != b.isString -> false
        a.isString -> a.content == b.content
        else -> {
            val left = a.asDoubleOrNull()
            val right = b.asDoubleOrNull()
            if (left != null && right != null) left == right else a.content == b.content
        }
    }
    else -> false
}
