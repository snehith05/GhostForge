package dev.ghostforge.core.spec

/**
 * Why a specification was rejected.
 *
 * Codes are the stable contract: tests assert on them, and the repair loop in
 * milestone 3 dispatches on them. Human-readable messages may change freely.
 */
enum class ErrorCode {
    /** Oversized input, bad JSON, unknown key, or unknown field/component type. */
    MALFORMED,
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_IDENTIFIER,
    LENGTH_OUT_OF_BOUNDS,
    OUT_OF_RANGE,
    TOO_MANY,
    TOO_FEW,
    DUPLICATE_ID,
    UNRESOLVED_REFERENCE,
    TYPE_MISMATCH,
    INVALID_DEFAULT,
    INCOMPATIBLE_MERGE_POLICY,
}

/**
 * @param path location in the document, e.g. `datasets[0].fields[2].default`
 * @param detail diagnostic text; for [ErrorCode.MALFORMED] this carries the
 *   parser's own message, which is fed back to the generator during repair.
 */
data class ValidationError(
    val code: ErrorCode,
    val path: String,
    val detail: String,
) {
    override fun toString(): String = "$path: $code -- $detail"
}

/**
 * The outcome of validating one specification.
 *
 * All errors are collected rather than failing on the first, so the repair loop
 * can address a whole document in one pass instead of one error per round trip.
 */
data class ValidationReport(val errors: List<ValidationError>) {
    val isValid: Boolean get() = errors.isEmpty()

    fun codes(): Set<ErrorCode> = errors.map { it.code }.toSet()

    override fun toString(): String =
        if (isValid) "valid" else errors.joinToString("\n") { "  $it" }

    companion object {
        val VALID: ValidationReport = ValidationReport(emptyList())
    }
}

/**
 * The result of admitting an untrusted document through [SpecGate].
 *
 * A specification only exists in [Admitted] form, so a caller that holds one
 * has proof it passed both parsing and validation.
 */
sealed interface Admission {
    data class Admitted(val spec: AppSpec) : Admission
    data class Rejected(val report: ValidationReport) : Admission
}

/** Collects errors while tracking the current document path. */
internal class ErrorCollector {
    private val errors = mutableListOf<ValidationError>()

    fun add(code: ErrorCode, path: String, detail: String) {
        errors += ValidationError(code, path, detail)
    }

    fun report(): ValidationReport =
        if (errors.isEmpty()) ValidationReport.VALID else ValidationReport(errors.toList())
}
