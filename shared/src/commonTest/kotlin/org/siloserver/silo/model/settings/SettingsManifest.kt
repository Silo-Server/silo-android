package org.siloserver.silo.model.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The parts of `contracts/settings/v1/manifest.json` that resolution depends on.
 *
 * The generated bindings in [SettingKeys] carry key names and a coarse type
 * table, which is all production code needs — Android writes through
 * `/settings/values` and reads effective values back from the server. They do
 * not carry the facts a *resolver* needs: each definition's resolution order,
 * its default value, whether its enum members form a ranked progression, and
 * what policy input constrains it. Rather than hand-copy those into Kotlin
 * (which is exactly the drift the contract exists to remove), the conformance
 * runner parses the vendored manifest and is driven by it.
 *
 * Only the fields resolution reads are modelled, and parsing tolerates unknown
 * ones. That asymmetry with the fixture — which is decoded strictly — is
 * deliberate: the manifest is a vendored copy pinned by [revision], and the
 * revision check is its drift gate. The fixture has no such pin, so an
 * unrecognized field there is the only signal that its schema moved.
 *
 * Fields skipped on purpose, because nothing here ranks or validates values:
 * `allowed_scopes` (a write-side concern, and its entries may be either a
 * scope name or an object), the numeric `minimum`/`maximum` bounds (which may
 * be either a bare number or a widening history array), and all of the UI
 * metadata except the language option-set fields, which are retained to gate
 * the generated picker presentation against the vendored manifest.
 */
@Serializable
data class SettingsManifest(
    @SerialName("api_version") val apiVersion: Int,
    val revision: Int,
    @SerialName("option_sets") val optionSets: Map<String, ContractOptionSet> = emptyMap(),
    val definitions: List<SettingDefinition>,
) {
    private val byKey: Map<String, SettingDefinition> = definitions.associateBy { it.key }

    /** The definition for [key], or null when this manifest does not declare it. */
    fun lookup(key: String): SettingDefinition? = byKey[key]
}

@Serializable
data class SettingDefinition(
    val key: String,
    val persistence: String,
    @SerialName("resolution_order") val resolutionOrder: List<String>,
    @SerialName("value_schema") val valueSchema: SettingValueSchema,
    // Required by the manifest schema and never absent, but always present as
    // an explicit JSON value that may itself be null — so it is typed as a
    // JsonElement and JsonNull is a real default, not a missing one.
    @SerialName("default_value") val defaultValue: JsonElement,
    @SerialName("constrained_by") val constrainedBy: SettingConstraintBinding? = null,
    @SerialName("suggested_options") val suggestedOptions: String? = null,
    @SerialName("unset_label") val unsetLabel: String? = null,
) {
    /** True when the server stores this setting; client_local keys never resolve. */
    val isRemote: Boolean get() = persistence == PERSISTENCE_REMOTE

    /** True for the types that rank numerically rather than by enum position. */
    val isNumeric: Boolean
        get() = valueSchema.type == TYPE_INTEGER || valueSchema.type == TYPE_NUMBER

    companion object {
        const val PERSISTENCE_REMOTE = "remote"
        const val TYPE_INTEGER = "integer"
        const val TYPE_NUMBER = "number"
        const val TYPE_ENUM = "enum"
    }
}

@Serializable
data class ContractOptionSet(
    val type: String,
    val options: List<ContractSuggestedOption>,
)

@Serializable
data class ContractSuggestedOption(
    val value: String,
    @SerialName("introduced_in") val introducedIn: Int,
)

@Serializable
data class SettingValueSchema(
    val type: String,
    /** Members in declared order; ranking uses the position, not the label. */
    val values: List<SettingEnumMember> = emptyList(),
    /** Set when the members form a progression a ceiling or floor can cap along. */
    val ordered: Boolean = false,
    val nullable: Boolean = false,
)

@Serializable
data class SettingEnumMember(
    val value: JsonElement,
)

/** A definition's binding to the policy input that may narrow it. */
@Serializable
data class SettingConstraintBinding(
    @SerialName("policy_input") val policyInput: String,
    val constraint: SettingConstraintKind,
)

/** How a policy input narrows a resolved value. */
@Serializable
enum class SettingConstraintKind {
    @SerialName("ceiling")
    CEILING,

    @SerialName("floor")
    FLOOR,

    @SerialName("allowlist")
    ALLOWLIST,

    @SerialName("locked")
    LOCKED,
}
