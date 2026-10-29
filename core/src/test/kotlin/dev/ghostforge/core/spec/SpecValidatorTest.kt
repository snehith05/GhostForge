package dev.ghostforge.core.spec

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Semantic rules: references, bounds, defaults, merge-policy compatibility. */
class SpecValidatorTest {

    private fun codesFor(doc: String): Set<ErrorCode> {
        val result = SpecGate.admit(doc)
        assertIs<Admission.Rejected>(result, "expected rejection but document was admitted")
        return result.report.codes()
    }

    private fun pathsFor(doc: String): List<String> {
        val result = SpecGate.admit(doc)
        assertIs<Admission.Rejected>(result)
        return result.report.errors.map { it.path }
    }

    // --- header ------------------------------------------------------------

    @Test
    fun `newer schema version is rejected, not treated as forward compatible`() {
        val doc = Fixtures.variant("\"schema_version\": \"1.0\"", "\"schema_version\": \"1.1\"")
        assertContains(codesFor(doc), ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
    }

    @Test
    fun `app id with path traversal characters is rejected`() {
        val doc = Fixtures.variant("\"water-request-tracker\"", "\"../../etc/passwd\"")
        assertContains(codesFor(doc), ErrorCode.INVALID_IDENTIFIER)
    }

    @Test
    fun `empty name is rejected`() {
        val doc = Fixtures.variant("\"name\": \"Water Request Tracker\"", "\"name\": \"\"")
        assertContains(codesFor(doc), ErrorCode.LENGTH_OUT_OF_BOUNDS)
    }

    @Test
    fun `zero spec version is rejected`() {
        val doc = Fixtures.variant("\"spec_version\": 1", "\"spec_version\": 0")
        assertContains(codesFor(doc), ErrorCode.OUT_OF_RANGE)
    }

    // --- identifiers and uniqueness ---------------------------------------

    @Test
    fun `field id with sql metacharacters is rejected`() {
        val doc = Fixtures.variant("\"id\": \"household\"", "\"id\": \"house\\\"; DROP TABLE t--\"")
        assertContains(codesFor(doc), ErrorCode.INVALID_IDENTIFIER)
    }

    @Test
    fun `duplicate field id within a dataset is rejected`() {
        val doc = Fixtures.variant("\"id\": \"quantity\"", "\"id\": \"household\"")
        assertContains(codesFor(doc), ErrorCode.DUPLICATE_ID)
    }

    // --- references --------------------------------------------------------

    @Test
    fun `component referencing an undeclared dataset is rejected`() {
        val doc = Fixtures.variant(
            "{ \"type\": \"record_form\", \"dataset\": \"water_requests\" }",
            "{ \"type\": \"record_form\", \"dataset\": \"does_not_exist\" }",
        )
        assertContains(codesFor(doc), ErrorCode.UNRESOLVED_REFERENCE)
    }

    @Test
    fun `record list column that is not a field is rejected`() {
        val doc = Fixtures.variant("\"columns\": [\"household\"", "\"columns\": [\"nope\"")
        assertContains(codesFor(doc), ErrorCode.UNRESOLVED_REFERENCE)
    }

    @Test
    fun `error path points at the offending element`() {
        val doc = Fixtures.variant("\"columns\": [\"household\"", "\"columns\": [\"nope\"")
        assertContains(pathsFor(doc), "components[1].columns[0]")
    }

    // --- status counter grouping ------------------------------------------

    @Test
    fun `grouping by a text field is rejected as unbounded`() {
        val doc = Fixtures.variant("\"group_by\": \"status\"", "\"group_by\": \"household\"")
        assertContains(codesFor(doc), ErrorCode.TYPE_MISMATCH)
    }

    @Test
    fun `grouping by a missing field is rejected`() {
        val doc = Fixtures.variant("\"group_by\": \"status\"", "\"group_by\": \"absent\"")
        assertContains(codesFor(doc), ErrorCode.UNRESOLVED_REFERENCE)
    }

    // --- checklist -----------------------------------------------------------

    @Test
    fun `checklist done_field that is not boolean is rejected`() {
        val doc = Fixtures.variant("\"done_field\": \"confirmed\"", "\"done_field\": \"household\"")
        assertContains(codesFor(doc), ErrorCode.TYPE_MISMATCH)
    }

    @Test
    fun `checklist title_field that does not exist is rejected`() {
        val doc = Fixtures.variant("\"title_field\": \"household\"", "\"title_field\": \"absent\"")
        assertContains(codesFor(doc), ErrorCode.UNRESOLVED_REFERENCE)
    }

    // --- status board --------------------------------------------------------

    @Test
    fun `status board grouping by a non-enum field is rejected`() {
        val spec = AppSpec(
            schemaVersion = "1.0", appId = "a-b-c", name = "n",
            datasets = listOf(Dataset("d", listOf(TextField("f", "F")))),
            components = listOf(StatusBoard(dataset = "d", groupBy = "f")),
        )
        assertContains(SpecValidator.validate(spec).codes(), ErrorCode.TYPE_MISMATCH)
    }

    @Test
    fun `status board column that is not a field is rejected`() {
        val doc = Fixtures.variant("\"columns\": [\"household\", \"quantity\"]", "\"columns\": [\"nope\"]")
        assertContains(codesFor(doc), ErrorCode.UNRESOLVED_REFERENCE)
    }

    // --- assignment board ------------------------------------------------------

    @Test
    fun `assignment board grouping by a non-enum field is rejected`() {
        val doc = Fixtures.variant("\"assigned_to\": \"assigned_to\"", "\"assigned_to\": \"household\"")
        assertContains(codesFor(doc), ErrorCode.TYPE_MISMATCH)
    }

    @Test
    fun `assignment board referencing a missing field is rejected`() {
        val doc = Fixtures.variant("\"assigned_to\": \"assigned_to\"", "\"assigned_to\": \"absent\"")
        assertContains(codesFor(doc), ErrorCode.UNRESOLVED_REFERENCE)
    }

    // --- defaults ----------------------------------------------------------

    @Test
    fun `enum default outside the option set is rejected`() {
        val doc = Fixtures.variant("\"default\": \"requested\"", "\"default\": \"cancelled\"")
        assertContains(codesFor(doc), ErrorCode.INVALID_DEFAULT)
    }

    @Test
    fun `number default below the minimum is rejected`() {
        val doc = Fixtures.variant("\"maximum\": 200.0", "\"maximum\": 200.0, \"default\": 0.0")
        assertContains(codesFor(doc), ErrorCode.INVALID_DEFAULT)
    }

    @Test
    fun `inverted numeric range is rejected`() {
        val doc = Fixtures.variant("\"minimum\": 1.0", "\"minimum\": 500.0")
        assertContains(codesFor(doc), ErrorCode.OUT_OF_RANGE)
    }

    @Test
    fun `text max length above the cap is rejected`() {
        val doc = Fixtures.variant("\"max_length\": 80", "\"max_length\": 99999")
        assertContains(codesFor(doc), ErrorCode.OUT_OF_RANGE)
    }

    // --- enum options ------------------------------------------------------

    @Test
    fun `single option enum is rejected`() {
        val doc = Fixtures.variant("[\"requested\", \"assigned\", \"delivered\"]", "[\"requested\"]")
        assertContains(codesFor(doc), ErrorCode.TOO_FEW)
    }

    @Test
    fun `duplicate enum option is rejected`() {
        val doc = Fixtures.variant(
            "[\"requested\", \"assigned\", \"delivered\"]",
            "[\"requested\", \"requested\", \"delivered\"]",
        )
        assertContains(codesFor(doc), ErrorCode.DUPLICATE_ID)
    }

    // --- merge policy compatibility ---------------------------------------

    @Test
    fun `ordered merge on an enum field is rejected`() {
        val doc = Fixtures.variant("\"merge\": \"status_transition\"", "\"merge\": \"max\"")
        assertContains(codesFor(doc), ErrorCode.INCOMPATIBLE_MERGE_POLICY)
    }

    @Test
    fun `status transition on a text field is rejected`() {
        val spec = AppSpec(
            schemaVersion = "1.0", appId = "a-b-c", name = "n",
            datasets = listOf(
                Dataset("d", listOf(TextField("f", "F", merge = MergePolicy.STATUS_TRANSITION))),
            ),
            components = listOf(RecordForm("d")),
        )
        assertContains(SpecValidator.validate(spec).codes(), ErrorCode.INCOMPATIBLE_MERGE_POLICY)
    }

    // --- structural minimums ----------------------------------------------

    @Test
    fun `spec with no components is rejected`() {
        val spec = AppSpec(
            schemaVersion = "1.0", appId = "a-b-c", name = "n",
            datasets = listOf(Dataset("d", listOf(BooleanField("f", "F")))),
            components = emptyList(),
        )
        assertContains(SpecValidator.validate(spec).codes(), ErrorCode.TOO_FEW)
    }

    @Test
    fun `spec with no datasets is rejected and its component cannot resolve`() {
        val spec = AppSpec(
            schemaVersion = "1.0", appId = "a-b-c", name = "n",
            datasets = emptyList(), components = listOf(RecordForm("d")),
        )
        val codes = SpecValidator.validate(spec).codes()
        assertContains(codes, ErrorCode.TOO_FEW)
        assertContains(codes, ErrorCode.UNRESOLVED_REFERENCE)
    }

    @Test
    fun `too many datasets is rejected`() {
        val datasets = (0..Limits.MAX_DATASETS).map {
            Dataset("ds_$it", listOf(BooleanField("f", "F")))
        }
        val spec = AppSpec(
            schemaVersion = "1.0", appId = "a-b-c", name = "n",
            datasets = datasets, components = listOf(RecordForm("ds_0")),
        )
        assertContains(SpecValidator.validate(spec).codes(), ErrorCode.TOO_MANY)
    }

    @Test
    fun `total field budget is enforced across datasets`() {
        val wide = (0 until Limits.MAX_FIELDS_PER_DATASET).map { BooleanField("f_$it", "F") }
        val datasets = (0 until Limits.MAX_DATASETS).map { Dataset("ds_$it", wide) }
        val total = Limits.MAX_DATASETS * Limits.MAX_FIELDS_PER_DATASET
        assertTrue(total > Limits.MAX_TOTAL_FIELDS, "fixture must exceed the total budget")
        val spec = AppSpec(
            schemaVersion = "1.0", appId = "a-b-c", name = "n",
            datasets = datasets, components = listOf(RecordForm("ds_0")),
        )
        assertContains(SpecValidator.validate(spec).codes(), ErrorCode.TOO_MANY)
    }

    // --- reporting behaviour ----------------------------------------------

    @Test
    fun `all errors are collected in one pass, not just the first`() {
        val spec = AppSpec(
            schemaVersion = "9.9", appId = "BAD ID", name = "",
            datasets = emptyList(), components = emptyList(),
        )
        val report = SpecValidator.validate(spec)
        assertTrue(report.errors.size >= 4, "expected several errors, got: $report")
        assertContains(report.codes(), ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
        assertContains(report.codes(), ErrorCode.INVALID_IDENTIFIER)
    }

    @Test
    fun `valid spec reports no errors`() {
        val result = SpecGate.admit(Fixtures.WATER_TRACKER)
        assertIs<Admission.Admitted>(result)
        assertEquals(ValidationReport.VALID, SpecValidator.validate(result.spec))
    }
}
