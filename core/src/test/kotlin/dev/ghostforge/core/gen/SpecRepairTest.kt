package dev.ghostforge.core.gen

import dev.ghostforge.core.spec.AppSpec
import dev.ghostforge.core.spec.BooleanField
import dev.ghostforge.core.spec.Checklist
import dev.ghostforge.core.spec.Component
import dev.ghostforge.core.spec.Dataset
import dev.ghostforge.core.spec.EnumField
import dev.ghostforge.core.spec.ErrorCode
import dev.ghostforge.core.spec.Field
import dev.ghostforge.core.spec.Limits
import dev.ghostforge.core.spec.MergePolicy
import dev.ghostforge.core.spec.NumberField
import dev.ghostforge.core.spec.RecordForm
import dev.ghostforge.core.spec.RecordList
import dev.ghostforge.core.spec.StatusCounter
import dev.ghostforge.core.spec.TextField
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ErrorCode-dispatched repair. Each test breaks one thing in an otherwise
 * valid spec and checks the repaired spec plus which (code, path) pairs were
 * acted on -- never the prose, which is free to change (invariant 9).
 */
class SpecRepairTest {

    private val base = AppSpec(
        schemaVersion = "1.0", appId = "test-app", name = "Test",
        datasets = listOf(
            Dataset(
                "items",
                listOf(
                    TextField("title", "Title", required = true),
                    EnumField("status", "Status", options = listOf("open", "done"), default = "open", merge = MergePolicy.STATUS_TRANSITION),
                    BooleanField("done", "Done"),
                    NumberField("qty", "Qty", minimum = 0.0, maximum = 10.0),
                ),
            ),
        ),
        components = listOf(RecordForm("items"), RecordList("items", listOf("title", "status")), StatusCounter("items", "status")),
    )

    private fun fields(spec: AppSpec) = spec.datasets.single().fields

    private fun withField(index: Int, f: Field): AppSpec {
        val ds = base.datasets.single()
        return base.copy(datasets = listOf(ds.copy(fields = ds.fields.toMutableList().also { it[index] = f })))
    }

    private fun withFields(vararg f: Field, components: List<Component>) =
        base.copy(datasets = listOf(Dataset("items", f.toList())), components = components)

    private fun admitted(spec: AppSpec): Pair<AppSpec, List<RepairAction>> {
        val outcome = SpecRepair.run(spec)
        assertIs<RepairOutcome.Admitted>(outcome, "expected repair to succeed: $outcome")
        return outcome.admission.spec to outcome.actions
    }

    private fun failed(spec: AppSpec): RepairOutcome.Failed {
        val outcome = SpecRepair.run(spec)
        assertIs<RepairOutcome.Failed>(outcome, "expected repair to give up: $outcome")
        return outcome
    }

    private fun List<RepairAction>.at() = map { it.code to it.path }

    @Test
    fun `a valid spec passes through untouched`() {
        val (spec, actions) = admitted(base)
        assertEquals(base, spec)
        assertTrue(actions.isEmpty())
    }

    // --- header -------------------------------------------------------------------

    @Test
    fun `a long name is shortened on a word boundary`() {
        val (spec, actions) = admitted(base.copy(name = "Household water requests from the northern villages of Udupi district"))
        assertEquals("Household water requests from the northern villages of Udupi", spec.name)
        assertEquals(listOf(ErrorCode.LENGTH_OUT_OF_BOUNDS to "name"), actions.at())
    }

    @Test
    fun `an empty name is filled in from the app id`() =
        assertEquals("Test app", admitted(base.copy(name = "")).first.name)

    @Test
    fun `a long description is shortened`() =
        assertTrue(admitted(base.copy(description = "word ".repeat(100))).first.description.length <= Limits.MAX_DESCRIPTION_LENGTH)

    @Test
    fun `an invalid app id is slugged`() =
        assertEquals("water-tracker", admitted(base.copy(appId = "Water Tracker!")).first.appId)

    @Test
    fun `an app id with nothing latin falls back to a hash`() {
        val id = admitted(base.copy(appId = String(intArrayOf(0x0CA8, 0x0CC0, 0x0CB0, 0x0CC1), 0, 4))).first.appId
        assertTrue(Limits.APP_ID.matches(id) && id.startsWith("app-"), id)
    }

    @Test
    fun `spec version zero becomes the minimum`() =
        assertEquals(Limits.MIN_SPEC_VERSION, admitted(base.copy(specVersion = 0)).first.specVersion)

    @Test
    fun `schema version is never repaired`() {
        val outcome = failed(base.copy(schemaVersion = "2.0"))
        assertContains(outcome.report.codes(), ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
        assertTrue(outcome.actions.isEmpty())
    }

    @Test
    fun `it fixes what it can but still refuses a foreign schema version`() {
        val outcome = failed(base.copy(schemaVersion = "2.0", name = ""))
        assertEquals(setOf(ErrorCode.UNSUPPORTED_SCHEMA_VERSION), outcome.report.codes())
        assertEquals(listOf(ErrorCode.LENGTH_OUT_OF_BOUNDS to "name"), outcome.actions.at())
    }

    // --- identifiers, and the references that follow them ----------------------------

    @Test
    fun `renaming a dataset carries every reference with it`() {
        val broken = base.copy(
            datasets = listOf(base.datasets.single().copy(id = "Water Requests")),
            components = listOf(RecordForm("Water Requests"), StatusCounter("Water Requests", "status")),
        )
        val (spec, actions) = admitted(broken)
        assertEquals("water_requests", spec.datasets.single().id)
        assertEquals(listOf("water_requests", "water_requests"), spec.components.map { it.dataset })
        assertEquals(listOf(ErrorCode.INVALID_IDENTIFIER to "datasets[0].id"), actions.at())
    }

    @Test
    fun `renaming a field carries group_by and columns with it`() {
        val broken = withFields(
            TextField("title", "Title"),
            EnumField("Work Status", "Status", options = listOf("open", "done")),
            components = listOf(RecordList("items", listOf("title", "Work Status")), StatusCounter("items", "Work Status")),
        )
        val (spec, _) = admitted(broken)
        assertEquals(listOf("title", "work_status"), fields(spec).map { it.id })
        assertEquals(listOf(RecordList("items", listOf("title", "work_status")), StatusCounter("items", "work_status")), spec.components)
    }

    @Test
    fun `a duplicate id is suffixed and references keep meaning the first`() {
        val broken = withFields(
            TextField("title", "Title"),
            EnumField("title", "Also title", options = listOf("a", "b")),
            components = listOf(RecordList("items", listOf("title"))),
        )
        val (spec, actions) = admitted(broken)
        assertEquals(listOf("title", "title_2"), fields(spec).map { it.id })
        assertEquals(RecordList("items", listOf("title")), spec.components.single())
        assertEquals(listOf(ErrorCode.DUPLICATE_ID to "datasets[0].fields[1].id"), actions.at())
    }

    @Test
    fun `a slug never steals an id the author spelled correctly`() {
        val broken = withFields(
            TextField("Status", "Shown status"),
            EnumField("status", "Status", options = listOf("open", "done")),
            components = listOf(StatusCounter("items", "status")),
        )
        val (spec, _) = admitted(broken)
        assertEquals(listOf("status_2", "status"), fields(spec).map { it.id })
        assertEquals(StatusCounter("items", "status"), spec.components.single())
    }

    @Test
    fun `an id with nothing usable gets a positional id`() =
        assertEquals("field_3", fields(admitted(withField(2, BooleanField("!!", "Done"))).first)[2].id)

    // --- enum options -------------------------------------------------------------------

    @Test
    fun `options are slugged and deduplicated, and the default follows its option`() {
        val (spec, _) = admitted(withField(1, EnumField("status", "Status", options = listOf("Open", "open", "Done!"), default = "Open")))
        val status = fields(spec)[1] as EnumField
        assertEquals(listOf("open", "done"), status.options)
        assertEquals("open", status.default)
    }

    @Test
    fun `a default whose option is removed goes with it`() {
        val (spec, _) = admitted(withField(1, EnumField("status", "Status", options = listOf("open", "done", "???"), default = "???")))
        val status = fields(spec)[1] as EnumField
        assertEquals(listOf("open", "done"), status.options)
        assertNull(status.default)
    }

    @Test
    fun `too many options keeps the first twelve`() {
        val options = (1..15).map { "s$it" }
        val (spec, _) = admitted(withField(1, EnumField("status", "Status", options = options)))
        assertEquals(options.take(Limits.MAX_ENUM_OPTIONS), (fields(spec)[1] as EnumField).options)
    }

    @Test
    fun `an enum left with one option is refused rather than given an invented one`() {
        val outcome = failed(withField(1, EnumField("status", "Status", options = listOf("open", "Open"))))
        assertContains(outcome.report.codes(), ErrorCode.TOO_FEW)
    }

    // --- field attributes ------------------------------------------------------------------

    @Test
    fun `an incompatible merge policy resets to the type's default`() {
        val (spec, _) = admitted(withField(1, EnumField("status", "Status", options = listOf("open", "done"), merge = MergePolicy.MAX)))
        assertEquals(MergePolicy.LAST_WRITE_WINS, fields(spec)[1].merge)
    }

    @Test
    fun `an out-of-range max_length is clamped`() {
        val (spec, _) = admitted(withField(0, TextField("title", "Title", maxLength = 99_999)))
        assertEquals(Limits.MAX_TEXT_LENGTH, (fields(spec)[0] as TextField).maxLength)
    }

    @Test
    fun `an inverted range is swapped`() {
        val qty = fields(admitted(withField(3, NumberField("qty", "Qty", minimum = 10.0, maximum = 1.0))).first)[3] as NumberField
        assertEquals(1.0 to 10.0, qty.minimum to qty.maximum)
    }

    @Test
    fun `an invalid default is removed`() {
        val (spec, _) = admitted(withField(1, EnumField("status", "Status", options = listOf("open", "done"), default = "cancelled")))
        assertNull((fields(spec)[1] as EnumField).default)
    }

    @Test
    fun `clamping max_length can expose a default that no longer fits, and the next round removes it`() {
        val (spec, actions) = admitted(withField(0, TextField("title", "Title", maxLength = 99_999, default = "x".repeat(600))))
        assertNull((fields(spec)[0] as TextField).default)
        assertEquals(listOf(ErrorCode.OUT_OF_RANGE, ErrorCode.INVALID_DEFAULT), actions.map { it.code })
    }

    @Test
    fun `an empty label is derived from the field id`() =
        assertEquals("Litres requested", fields(admitted(withField(3, NumberField("litres_requested", ""))).first)[3].label)

    // --- components -------------------------------------------------------------------------

    @Test
    fun `a component on an undeclared dataset is removed`() =
        assertEquals(base.components, admitted(base.copy(components = base.components + RecordForm("ghost"))).first.components)

    @Test
    fun `an unresolved column is dropped and the rest kept`() {
        val (spec, _) = admitted(base.copy(components = listOf(RecordList("items", listOf("title", "nope", "status")))))
        assertEquals(RecordList("items", listOf("title", "status")), spec.components.single())
    }

    @Test
    fun `grouping by a non-enum field removes the component`() {
        val (spec, _) = admitted(base.copy(components = listOf(RecordForm("items"), StatusCounter("items", "title"))))
        assertEquals(listOf<Component>(RecordForm("items")), spec.components)
    }

    @Test
    fun `a checklist whose done_field is not boolean is removed`() {
        val (spec, _) = admitted(base.copy(components = listOf(RecordForm("items"), Checklist("items", "title", "status"))))
        assertEquals(listOf<Component>(RecordForm("items")), spec.components)
    }

    @Test
    fun `removing every component adds a default form and list`() {
        val (spec, actions) = admitted(base.copy(components = listOf(StatusCounter("items", "title"))))
        assertEquals(listOf(RecordForm("items"), RecordList("items")), spec.components)
        assertEquals(ErrorCode.TOO_FEW to "components", actions.at().last())
    }

    @Test
    fun `fixes at several indices land on the right components`() {
        val broken = base.copy(
            components = listOf(
                RecordForm("ghost"), // removed
                RecordList("items", listOf("title", "nope")), // loses a column
                StatusCounter("items", "title"), // removed
                StatusCounter("items", "status"), // untouched
            ),
        )
        val (spec, _) = admitted(broken)
        assertEquals(listOf(RecordList("items", listOf("title")), StatusCounter("items", "status")), spec.components)
    }

    @Test
    fun `too many components keeps the first eight`() =
        assertEquals(Limits.MAX_COMPONENTS, admitted(base.copy(components = List(10) { RecordForm("items") })).first.components.size)

    // --- budgets -------------------------------------------------------------------------------

    @Test
    fun `too many datasets keeps the first four, and views over the rest follow them out`() {
        val datasets = (0 until 6).map { Dataset("ds_$it", listOf(TextField("title", "Title"))) }
        val (spec, _) = admitted(base.copy(datasets = datasets, components = listOf(RecordForm("ds_5"), RecordForm("ds_0"))))
        assertEquals((0 until 4).map { "ds_$it" }, spec.datasets.map { it.id })
        assertEquals(listOf<Component>(RecordForm("ds_0")), spec.components)
    }

    @Test
    fun `too many fields keeps the first sixteen, and a column on a dropped field follows it out`() {
        val wide = (0 until 20).map { BooleanField("f_$it", "F") }
        val broken = base.copy(datasets = listOf(Dataset("items", wide)), components = listOf(RecordList("items", listOf("f_0", "f_19"))))
        val (spec, _) = admitted(broken)
        assertEquals(Limits.MAX_FIELDS_PER_DATASET, fields(spec).size)
        assertEquals(RecordList("items", listOf("f_0")), spec.components.single())
    }

    @Test
    fun `the total field budget is met without emptying any dataset`() {
        val wide = (0 until 16).map { BooleanField("f_$it", "F") }
        val (spec, _) = admitted(base.copy(datasets = (0 until 4).map { Dataset("ds_$it", wide) }, components = listOf(RecordForm("ds_0"))))
        assertEquals(List(4) { 12 }, spec.datasets.map { it.fields.size })
    }

    // --- what it refuses -------------------------------------------------------------------------

    @Test
    fun `a dataset with no fields is refused`() {
        val outcome = failed(base.copy(datasets = listOf(Dataset("items", emptyList())), components = listOf(RecordForm("items"))))
        assertContains(outcome.report.codes(), ErrorCode.TOO_FEW)
    }

    @Test
    fun `no datasets at all is refused`() {
        failed(base.copy(datasets = emptyList(), components = emptyList()))
    }

    // --- properties --------------------------------------------------------------------------------

    @Test
    fun `repair is deterministic`() {
        val broken = base.copy(name = "", appId = "Bad App", components = listOf(StatusCounter("items", "title")))
        assertEquals(SpecRepair.run(broken), SpecRepair.run(broken))
    }

    @Test
    fun `repair never adds a field or an option, and never changes a field's type`() {
        val broken = withFields(
            TextField("Title!", "", maxLength = 0),
            EnumField("Status", "Status", options = listOf("Open", "open", "Done", "In Progress"), default = "Open", merge = MergePolicy.MAX),
            NumberField("qty", "Qty", minimum = 5.0, maximum = 1.0, default = 9.0),
            BooleanField("done", "Done"),
            components = listOf(
                StatusCounter("items", "Status"),
                RecordList("items", listOf("Title!", "missing")),
                StatusCounter("items", "qty"),
            ),
        )
        val (spec, _) = admitted(broken)
        val before = fields(broken)
        val after = fields(spec)
        assertEquals(before.map { it::class }, after.map { it::class })
        val slugged = (before[1] as EnumField).options.mapNotNull(Idents::option).toSet()
        assertTrue((after[1] as EnumField).options.all { it in slugged })
        assertEquals(broken.schemaVersion, spec.schemaVersion)
    }
}
