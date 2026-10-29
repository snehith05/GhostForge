package dev.ghostforge.core.spec

/**
 * Every bound in the specification format, in one auditable place.
 *
 * These are a security boundary, not style preferences. A specification arrives
 * from an untrusted peer or from a language model, so each limit exists to cap
 * what a hostile or malformed spec can cost the device that renders it.
 */
object Limits {

    /** Only this exact value is accepted. Not a minimum, not a range. */
    const val SCHEMA_VERSION: String = "1.0"

    /** Reject before parsing: an oversized document must never reach the parser. */
    const val MAX_SPEC_BYTES: Int = 16 * 1024

    const val MAX_DATASETS: Int = 4
    const val MAX_COMPONENTS: Int = 8
    const val MAX_FIELDS_PER_DATASET: Int = 16

    /** Caps total render + storage cost even when per-dataset limits are respected. */
    const val MAX_TOTAL_FIELDS: Int = 48

    const val MIN_ENUM_OPTIONS: Int = 2
    const val MAX_ENUM_OPTIONS: Int = 12

    const val MAX_TEXT_LENGTH: Int = 512
    const val MAX_NAME_LENGTH: Int = 64
    const val MAX_DESCRIPTION_LENGTH: Int = 256
    const val MAX_LABEL_LENGTH: Int = 64

    const val MIN_SPEC_VERSION: Int = 1

    /**
     * Identifiers are deliberately narrow. They become SQLite column names and
     * storage keys downstream, so anything outside this set is rejected at the
     * boundary rather than escaped at each use site.
     */
    val IDENTIFIER: Regex = Regex("^[a-z][a-z0-9_]{1,47}$")

    /** App ids additionally appear in filenames and manifests, so hyphens not underscores. */
    val APP_ID: Regex = Regex("^[a-z][a-z0-9-]{2,63}$")

    val ENUM_OPTION: Regex = Regex("^[a-z][a-z0-9_]{0,31}$")
}
