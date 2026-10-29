package dev.ghostforge.core.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A mini-app specification.
 *
 * This type is the whole contract between the generator, the verifier, the
 * runtime and the sync engine. It describes *data and composition only*: there
 * is no expression language, no script field and no free-form string that any
 * layer is permitted to evaluate. If a future feature appears to need one, it
 * belongs in a new component type with its own validation, not in a string.
 *
 * Collections are declared permissive here (defaulting to empty) so that an
 * empty `datasets` or `components` is reported by the validator with a precise
 * path, rather than surfacing as an opaque parser exception.
 */
@Serializable
data class AppSpec(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("app_id") val appId: String,
    val name: String,
    val description: String = "",
    /** The app's own revision, bumped on every edit. Drives updates and revocation. */
    @SerialName("spec_version") val specVersion: Int = Limits.MIN_SPEC_VERSION,
    val datasets: List<Dataset> = emptyList(),
    val components: List<Component> = emptyList(),
    val permissions: Permissions = Permissions(),
)

@Serializable
data class Dataset(
    val id: String,
    val fields: List<Field> = emptyList(),
)

/**
 * How concurrent edits to one field are reconciled after a disconnection.
 *
 * Declared per field because a single policy for a whole app is unsafe: a free
 * text note tolerates last-write-wins, while a delivery status does not.
 * Consumed by the sync engine in milestone 5.
 */
@Serializable
enum class MergePolicy {
    /** Highest observed timestamp wins. Safe for independent notes, unsafe for scarce resources. */
    @SerialName("last_write_wins") LAST_WRITE_WINS,

    /** Numeric/temporal monotonic merge. Converges without a clock authority. */
    @SerialName("max") MAX,
    @SerialName("min") MIN,

    /** Enum-only: advance along a declared state order, never retreat. */
    @SerialName("status_transition") STATUS_TRANSITION,

    /** Divergence is preserved and surfaced to a human. Never silently resolved. */
    @SerialName("manual") MANUAL,
}

// ---------------------------------------------------------------------------
// Fields. Sealed: an unrecognised `type` fails to deserialize, so the closed
// world is enforced by the type system rather than by a validator rule.
// ---------------------------------------------------------------------------

@Serializable
sealed class Field {
    abstract val id: String
    abstract val label: String
    abstract val required: Boolean
    abstract val merge: MergePolicy
}

@Serializable
@SerialName("text")
data class TextField(
    override val id: String,
    override val label: String,
    override val required: Boolean = false,
    override val merge: MergePolicy = MergePolicy.LAST_WRITE_WINS,
    @SerialName("max_length") val maxLength: Int = 120,
    val default: String? = null,
) : Field()

@Serializable
@SerialName("number")
data class NumberField(
    override val id: String,
    override val label: String,
    override val required: Boolean = false,
    override val merge: MergePolicy = MergePolicy.LAST_WRITE_WINS,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val default: Double? = null,
) : Field()

@Serializable
@SerialName("boolean")
data class BooleanField(
    override val id: String,
    override val label: String,
    override val required: Boolean = false,
    override val merge: MergePolicy = MergePolicy.LAST_WRITE_WINS,
    val default: Boolean? = null,
) : Field()

@Serializable
@SerialName("enum")
data class EnumField(
    override val id: String,
    override val label: String,
    override val required: Boolean = false,
    override val merge: MergePolicy = MergePolicy.LAST_WRITE_WINS,
    val options: List<String> = emptyList(),
    val default: String? = null,
) : Field()

@Serializable
@SerialName("timestamp")
data class TimestampField(
    override val id: String,
    override val label: String,
    override val required: Boolean = false,
    override val merge: MergePolicy = MergePolicy.MAX,
) : Field()

// ---------------------------------------------------------------------------
// Components. Milestone 1 ships the three that prove the format end to end;
// checklist, status board and assignment board arrive with the runtime in M2.
// ---------------------------------------------------------------------------

@Serializable
sealed class Component {
    /** Every component reads and writes exactly one dataset. */
    abstract val dataset: String
}

@Serializable
@SerialName("record_form")
data class RecordForm(
    override val dataset: String,
) : Component()

@Serializable
@SerialName("record_list")
data class RecordList(
    override val dataset: String,
    /** Field ids to show. Empty means every field in the dataset. */
    val columns: List<String> = emptyList(),
) : Component()

@Serializable
@SerialName("status_counter")
data class StatusCounter(
    override val dataset: String,
    /** Must name an enum field: grouping by unbounded text is a cost attack. */
    @SerialName("group_by") val groupBy: String,
) : Component()

// ---------------------------------------------------------------------------
// Permissions
// ---------------------------------------------------------------------------

/**
 * Default deny. An absent permission block grants nothing, and there is no
 * value meaning "general internet access" in v1 -- a mini-app can reach the
 * sync transport or nothing at all.
 */
@Serializable
data class Permissions(
    val camera: Boolean = false,
    val contacts: Boolean = false,
    @SerialName("precise_location") val preciseLocation: Boolean = false,
    val network: NetworkPermission = NetworkPermission.NONE,
)

@Serializable
enum class NetworkPermission {
    @SerialName("none") NONE,
    @SerialName("ghostforge_sync_only") GHOSTFORGE_SYNC_ONLY,
}
