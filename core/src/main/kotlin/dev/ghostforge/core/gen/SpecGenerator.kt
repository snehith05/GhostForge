package dev.ghostforge.core.gen

import dev.ghostforge.core.spec.Admission
import dev.ghostforge.core.spec.ValidationReport

sealed interface GenerationResult {
    val interpretation: Interpretation

    /** What repair changed on the way, e.g. a name shortened to fit -- worth showing, never silent. */
    val repairs: List<RepairAction>

    data class Generated(
        val admission: Admission.Admitted,
        override val interpretation: Interpretation,
        override val repairs: List<RepairAction>,
    ) : GenerationResult

    data class Failed(
        val report: ValidationReport,
        override val interpretation: Interpretation,
        override val repairs: List<RepairAction>,
    ) : GenerationResult
}

/**
 * Plain-language description in, admitted mini-app out, with no language
 * model anywhere on the path -- the deterministic route the MVP must always
 * have, on any device, offline.
 *
 * Same text in, same spec out, byte for byte: no randomness, clock or locale
 * reaches any step, which is what lets M4 hash and sign what this produces.
 *
 * [GenerationResult.Failed] exists because the repair loop can fail in
 * principle, but from this generator it would be a bug: templates are valid
 * by construction apart from the user's own words, and repair can always
 * shorten those.
 */
object SpecGenerator {

    fun generate(description: String): GenerationResult {
        val interpretation = Interpreter.interpret(description)
        return when (val outcome = SpecRepair.run(Templates.build(interpretation))) {
            is RepairOutcome.Admitted -> GenerationResult.Generated(outcome.admission, interpretation, outcome.actions)
            is RepairOutcome.Failed -> GenerationResult.Failed(outcome.report, interpretation, outcome.actions)
        }
    }
}
