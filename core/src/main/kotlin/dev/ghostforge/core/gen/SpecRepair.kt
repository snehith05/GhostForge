package dev.ghostforge.core.gen

import dev.ghostforge.core.spec.Admission
import dev.ghostforge.core.spec.AppSpec
import dev.ghostforge.core.spec.AssignmentBoard
import dev.ghostforge.core.spec.BooleanField
import dev.ghostforge.core.spec.Checklist
import dev.ghostforge.core.spec.Component
import dev.ghostforge.core.spec.EnumField
import dev.ghostforge.core.spec.ErrorCode
import dev.ghostforge.core.spec.Field
import dev.ghostforge.core.spec.Limits
import dev.ghostforge.core.spec.MergePolicy
import dev.ghostforge.core.spec.NumberField
import dev.ghostforge.core.spec.RecordForm
import dev.ghostforge.core.spec.RecordList
import dev.ghostforge.core.spec.SpecGate
import dev.ghostforge.core.spec.SpecValidator
import dev.ghostforge.core.spec.StatusBoard
import dev.ghostforge.core.spec.StatusCounter
import dev.ghostforge.core.spec.TextField
import dev.ghostforge.core.spec.TimestampField
import dev.ghostforge.core.spec.ValidationError
import dev.ghostforge.core.spec.ValidationReport

/** One mechanical change the repair loop made. [code] and [path] are the contract; [change] is prose for the user. */
data class RepairAction(val code: ErrorCode, val path: String, val change: String)

internal sealed interface RepairOutcome {
    val actions: List<RepairAction>

    data class Admitted(val admission: Admission.Admitted, override val actions: List<RepairAction>) : RepairOutcome
    data class Failed(val report: ValidationReport, override val actions: List<RepairAction>) : RepairOutcome
}

/**
 * Brings a generated candidate into compliance by dispatching on the
 * validator's own [ErrorCode]s and paths. The validator stays the single
 * source of truth for *what* is wrong; this only knows *how* to fix each kind.
 *
 * Two rules bound it (CLAUDE.md invariants 11 and 12):
 *  - **Generation-time only.** It is `internal`, so nothing outside `core` --
 *    no receive path in particular -- can reach it. A document from a peer is
 *    admitted or rejected exactly as its author wrote it: "fixing it up"
 *    would run something nobody wrote, and would break any signature over it.
 *  - **Never invents.** It shortens, slugs, dedupes, drops, and resets to a
 *    documented default. It never adds a dataset, field or enum option, never
 *    changes a field's type, and never touches `schema_version` -- coercing a
 *    version is precisely what invariant 2 forbids. Its one addition is
 *    default *views* (a form and a list) over data already declared, when
 *    every component has been removed.
 *
 * What cannot be fixed that way -- `MALFORMED`, `UNSUPPORTED_SCHEMA_VERSION`,
 * too few datasets, fields or enum options -- ends in [RepairOutcome.Failed].
 */
internal object SpecRepair {

    /** A trim can leave a dangling reference whose removal can empty the components; this is headroom over that cascade, not a tuning knob. */
    const val MAX_ROUNDS = 8

    fun run(candidate: AppSpec): RepairOutcome {
        var spec = candidate
        val actions = mutableListOf<RepairAction>()
        repeat(MAX_ROUNDS) {
            // The validator directly, not SpecGate.admit: the size stage would reject an
            // oversized candidate as MALFORMED before the lengths repair exists to shorten
            // were ever reported.
            val report = SpecValidator.validate(spec)
            if (report.isValid) return gate(spec, actions)
            val (next, roundActions) = Round(spec, report).apply()
            if (roundActions.isEmpty()) return RepairOutcome.Failed(report, actions)
            actions += roundActions
            spec = next
        }
        val report = SpecValidator.validate(spec)
        return if (report.isValid) gate(spec, actions) else RepairOutcome.Failed(report, actions)
    }

    /** The final word belongs to [SpecGate.admit] (invariant 1): repair only ever produces a candidate. */
    private fun gate(spec: AppSpec, actions: List<RepairAction>): RepairOutcome =
        when (val admission = SpecGate.admit(SpecGate.encode(spec))) {
            is Admission.Admitted -> RepairOutcome.Admitted(admission, actions)
            is Admission.Rejected -> RepairOutcome.Failed(admission.report, actions)
        }
}

// Every path shape SpecValidator emits that has a fix. Anything else -- "schema_version",
// "$" -- matches nothing, so no fix is attempted.
private val DATASET_ID = Regex("""datasets\[(\d+)]\.id""")
private val DATASET_FIELDS = Regex("""datasets\[(\d+)]\.fields""")
private val FIELD_ID = Regex("""datasets\[(\d+)]\.fields\[(\d+)]\.id""")
private val FIELD_OPTIONS = Regex("""datasets\[(\d+)]\.fields\[(\d+)]\.options""")
private val FIELD_OPTION = Regex("""datasets\[(\d+)]\.fields\[(\d+)]\.options\[(\d+)]""")
private val FIELD_ATTR = Regex("""datasets\[(\d+)]\.fields\[(\d+)]\.(label|merge|max_length|default|minimum)""")
private val COMPONENT_COLUMN = Regex("""components\[(\d+)]\.columns\[(\d+)]""")
private val COMPONENT_REF = Regex("""components\[(\d+)]\.(dataset|group_by|assigned_to|title_field|done_field)""")

/**
 * One pass over one report. In-place fixes happen as errors are read;
 * structural edits are recorded against the report's original indices and
 * applied at the end in a fixed order -- renames, then the references that
 * follow them, then removals, then trims, then additions -- so removing
 * component 1 can never make a later fix for component 2 land on component 3.
 * Anything one fix exposes (a trimmed field still referenced, say) is the
 * next round's report, not this one's problem.
 */
private class Round(private val original: AppSpec, private val report: ValidationReport) {

    private val actions = mutableListOf<RepairAction>()
    private var appId = original.appId
    private var name = original.name
    private var description = original.description
    private var specVersion = original.specVersion
    private val datasets = original.datasets.toMutableList()
    private val components = original.components.toMutableList()

    private var datasetIds = false
    private val fieldIdScopes = linkedSetOf<Int>()
    private val optionScopes = linkedSetOf<Pair<Int, Int>>()
    private val droppedComponents = linkedMapOf<Int, ValidationError>()
    private val droppedColumns = linkedMapOf<Int, MutableMap<Int, ValidationError>>()
    private var trimDatasets = false
    private val trimFields = linkedSetOf<Int>()
    private val trimOptions = linkedSetOf<Pair<Int, Int>>()
    private var trimComponents = false
    private var addViews = false

    fun apply(): Pair<AppSpec, List<RepairAction>> {
        report.errors.forEach(::mark)
        val datasetRenames = if (datasetIds) normalizeDatasetIds() else emptyMap()
        val fieldRenames = fieldIdScopes.associateWith(::normalizeFieldIds)
        optionScopes.forEach { (i, j) -> normalizeOptions(i, j) }
        remapReferences(datasetRenames, fieldRenames)
        sweep()
        trimToLimits()
        addDefaultViews()
        val spec = original.copy(
            appId = appId, name = name, description = description, specVersion = specVersion,
            datasets = datasets.toList(), components = components.toList(),
        )
        return spec to actions
    }

    // --- reading the report -----------------------------------------------------

    private fun mark(e: ValidationError) {
        when (e.path) {
            "app_id" -> fixAppId(e)
            "name" -> fixName(e)
            "description" -> fixDescription(e)
            "spec_version" -> fixSpecVersion(e)
            // TOO_MANY at "datasets" means too many datasets *or* too many fields in total; the
            // path is the same and invariant 9 rules out reading the message, so each trim checks
            // its own condition. TOO_FEW would mean inventing a dataset.
            "datasets" -> if (e.code == ErrorCode.TOO_MANY) trimDatasets = true
            "components" -> when (e.code) {
                ErrorCode.TOO_MANY -> trimComponents = true
                ErrorCode.TOO_FEW -> addViews = true
                else -> Unit
            }
            else -> markIndexed(e)
        }
    }

    private fun markIndexed(e: ValidationError) {
        val p = e.path
        DATASET_ID.matchEntire(p)?.let { datasetIds = true; return }
        DATASET_FIELDS.matchEntire(p)?.let { if (e.code == ErrorCode.TOO_MANY) trimFields += it.int(1); return }
        FIELD_ID.matchEntire(p)?.let { fieldIdScopes += it.int(1); return }
        FIELD_OPTIONS.matchEntire(p)?.let { if (e.code == ErrorCode.TOO_MANY) trimOptions += it.int(1) to it.int(2); return }
        FIELD_OPTION.matchEntire(p)?.let { optionScopes += it.int(1) to it.int(2); return }
        FIELD_ATTR.matchEntire(p)?.let { fixFieldAttr(it.int(1), it.int(2), it.groupValues[3], e); return }
        COMPONENT_COLUMN.matchEntire(p)?.let { droppedColumns.getOrPut(it.int(1)) { linkedMapOf() }[it.int(2)] = e; return }
        COMPONENT_REF.matchEntire(p)?.let { droppedComponents.putIfAbsent(it.int(1), e); return }
    }

    // --- in-place fixes -----------------------------------------------------------

    private fun fixAppId(e: ValidationError) {
        val fixed = Idents.appId(appId)
        act(e, "app id '${appId.preview()}' -> '$fixed'")
        appId = fixed
    }

    private fun fixName(e: ValidationError) {
        if (name.isEmpty()) {
            // The validator reports app_id before name, so appId is already the repaired one.
            val fixed = Idents.truncate(Idents.labelFromId(Idents.appId(appId)), Limits.MAX_NAME_LENGTH)
            act(e, "empty name filled in as '$fixed'")
            name = fixed
        } else {
            name = Idents.truncate(name, Limits.MAX_NAME_LENGTH)
            act(e, "name shortened to ${name.length} characters")
        }
    }

    private fun fixDescription(e: ValidationError) {
        description = Idents.truncate(description, Limits.MAX_DESCRIPTION_LENGTH)
        act(e, "description shortened to ${description.length} characters")
    }

    private fun fixSpecVersion(e: ValidationError) {
        act(e, "spec_version $specVersion -> ${Limits.MIN_SPEC_VERSION}")
        specVersion = Limits.MIN_SPEC_VERSION
    }

    private fun fixFieldAttr(i: Int, j: Int, attr: String, e: ValidationError) {
        val f = datasets[i].fields[j]
        when (attr) {
            "label" -> {
                val fixed = if (f.label.isEmpty()) {
                    Idents.labelFromId(f.id).ifEmpty { "Field ${j + 1}" }.let { Idents.truncate(it, Limits.MAX_LABEL_LENGTH) }
                } else {
                    Idents.truncate(f.label, Limits.MAX_LABEL_LENGTH)
                }
                setField(i, j, f.withLabel(fixed))
                act(e, if (f.label.isEmpty()) "empty label filled in as '$fixed'" else "label shortened to ${fixed.length} characters")
            }
            "merge" -> {
                val fixed = f.declaredMerge()
                setField(i, j, f.withMerge(fixed))
                act(e, "merge ${f.merge.wire()} -> ${fixed.wire()}, the default for this field type")
            }
            "max_length" -> if (f is TextField) {
                val fixed = f.maxLength.coerceIn(1, Limits.MAX_TEXT_LENGTH)
                setField(i, j, f.copy(maxLength = fixed))
                act(e, "max_length ${f.maxLength} -> $fixed")
            }
            // An inverted range can report the same default twice (below min *and* above max).
            "default" -> f.withoutDefault().takeIf { it != f }?.let {
                setField(i, j, it)
                act(e, "invalid default removed")
            }
            "minimum" -> if (f is NumberField && f.minimum != null && f.maximum != null) {
                setField(i, j, f.copy(minimum = f.maximum, maximum = f.minimum))
                act(e, "minimum and maximum swapped")
            }
        }
    }

    // --- identifiers ----------------------------------------------------------------

    private fun normalizeDatasetIds(): Map<String, String> {
        val ids = datasets.map { it.id }
        val (fixed, renames) = normalizeIds(ids) { k -> "dataset_${k + 1}" }
        fixed.forEachIndexed { k, new ->
            if (new != ids[k]) {
                datasets[k] = datasets[k].copy(id = new)
                act(idCode(ids[k]), "datasets[$k].id", "'${ids[k].preview()}' -> '$new'")
            }
        }
        return renames
    }

    private fun normalizeFieldIds(i: Int): Map<String, String> {
        val fields = datasets[i].fields
        val ids = fields.map { it.id }
        val (fixed, renames) = normalizeIds(ids) { k -> "field_${k + 1}" }
        datasets[i] = datasets[i].copy(fields = fields.mapIndexed { k, f -> if (fixed[k] != ids[k]) f.withId(fixed[k]) else f })
        fixed.forEachIndexed { k, new ->
            if (new != ids[k]) act(idCode(ids[k]), "datasets[$i].fields[$k].id", "'${ids[k].preview()}' -> '$new'")
        }
        return renames
    }

    private fun normalizeOptions(i: Int, j: Int) {
        val f = datasets[i].fields[j] as? EnumField ?: return
        val kept = linkedSetOf<String>()
        val mapping = mutableMapOf<String, String>()
        f.options.forEachIndexed { k, old ->
            val path = "datasets[$i].fields[$j].options[$k]"
            val norm = if (Limits.ENUM_OPTION.matches(old)) old else Idents.option(old)
            if (norm == null) {
                act(ErrorCode.INVALID_IDENTIFIER, path, "option '${old.preview()}' removed: nothing usable left after slugging")
                return@forEachIndexed
            }
            mapping.putIfAbsent(old, norm)
            when {
                !kept.add(norm) -> act(ErrorCode.DUPLICATE_ID, path, "option '${old.preview()}' removed: duplicate of '$norm'")
                norm != old -> act(ErrorCode.INVALID_IDENTIFIER, path, "option '${old.preview()}' -> '$norm'")
            }
        }
        val oldDefault = f.default
        val newDefault = oldDefault?.let(mapping::get)?.takeIf { it in kept }
        if (oldDefault != null && newDefault != oldDefault) {
            val change = if (newDefault == null) "default removed with its option" else "default '${oldDefault.preview()}' -> '$newDefault'"
            act(ErrorCode.INVALID_DEFAULT, "datasets[$i].fields[$j].default", change)
        }
        setField(i, j, f.copy(options = kept.toList(), default = newDefault))
    }

    // --- references, removals, trims, additions ------------------------------------

    /** Components follow renames of the dataset they read and the fields they name, resolved against the first declaration of each id. */
    private fun remapReferences(datasetRenames: Map<String, String>, fieldRenames: Map<Int, Map<String, String>>) {
        if (datasetRenames.isEmpty() && fieldRenames.isEmpty()) return
        val originalIds = original.datasets.map { it.id }
        for (c in components.indices) {
            val component = components[c]
            val ds = originalIds.indexOf(component.dataset)
            if (ds < 0) continue // unresolved: the report has already marked it for removal
            val fields = fieldRenames[ds].orEmpty()
            components[c] = component.remap(datasetRenames[component.dataset] ?: component.dataset) { fields[it] ?: it }
        }
    }

    private fun sweep() {
        for ((c, columns) in droppedColumns) {
            if (c in droppedComponents) continue
            val names = components[c].listedColumns()
            components[c] = components[c].withoutColumns(columns.keys)
            val nowEmpty = components[c].listedColumns().isEmpty()
            columns.forEach { (k, e) ->
                act(e, "column '${names.getOrNull(k)?.preview()}' removed" + if (nowEmpty) "; the list now shows every field" else "")
            }
        }
        for (c in droppedComponents.keys.sortedDescending()) {
            val e = droppedComponents.getValue(c)
            val why = if (e.code == ErrorCode.TYPE_MISMATCH) "it names a field of the wrong type" else "it names something this app does not declare"
            act(e, "${components[c].wireName()} removed: $why")
            components.removeAt(c)
        }
    }

    private fun trimToLimits() {
        for (i in trimFields) {
            val fields = datasets.getOrNull(i)?.fields ?: continue
            if (fields.size <= Limits.MAX_FIELDS_PER_DATASET) continue
            datasets[i] = datasets[i].copy(fields = fields.take(Limits.MAX_FIELDS_PER_DATASET))
            act(ErrorCode.TOO_MANY, "datasets[$i].fields", "kept the first ${Limits.MAX_FIELDS_PER_DATASET} of ${fields.size} fields")
        }
        for ((i, j) in trimOptions) {
            val f = datasets.getOrNull(i)?.fields?.getOrNull(j) as? EnumField ?: continue
            if (f.options.size <= Limits.MAX_ENUM_OPTIONS) continue
            setField(i, j, f.copy(options = f.options.take(Limits.MAX_ENUM_OPTIONS)))
            act(ErrorCode.TOO_MANY, "datasets[$i].fields[$j].options", "kept the first ${Limits.MAX_ENUM_OPTIONS} of ${f.options.size} options")
        }
        if (trimDatasets && datasets.size > Limits.MAX_DATASETS) {
            val before = datasets.size
            while (datasets.size > Limits.MAX_DATASETS) datasets.removeAt(datasets.lastIndex)
            act(ErrorCode.TOO_MANY, "datasets", "kept the first ${Limits.MAX_DATASETS} of $before datasets")
        }
        if (trimDatasets) trimTotalFields()
        if (trimComponents && components.size > Limits.MAX_COMPONENTS) {
            val before = components.size
            while (components.size > Limits.MAX_COMPONENTS) components.removeAt(components.lastIndex)
            act(ErrorCode.TOO_MANY, "components", "kept the first ${Limits.MAX_COMPONENTS} of $before components")
        }
    }

    /** Takes the last field of the largest dataset (the later one on a tie) until the total fits, so no dataset is ever emptied. */
    private fun trimTotalFields() {
        val before = datasets.sumOf { it.fields.size }
        var total = before
        while (total > Limits.MAX_TOTAL_FIELDS) {
            val i = datasets.indices.maxWith(compareBy<Int> { datasets[it].fields.size }.thenBy { it })
            datasets[i] = datasets[i].copy(fields = datasets[i].fields.dropLast(1))
            total--
        }
        if (total != before) {
            act(ErrorCode.TOO_MANY, "datasets", "dropped ${before - total} fields to fit the ${Limits.MAX_TOTAL_FIELDS}-field total")
        }
    }

    private fun addDefaultViews() {
        if (!addViews || components.isNotEmpty() || datasets.isEmpty()) return
        val ds = datasets.first().id
        components += RecordForm(ds)
        components += RecordList(ds)
        act(ErrorCode.TOO_FEW, "components", "added a form and a list over '$ds' so the app is usable")
    }

    // --- helpers --------------------------------------------------------------------

    private fun setField(i: Int, j: Int, f: Field) {
        datasets[i] = datasets[i].copy(fields = datasets[i].fields.toMutableList().also { it[j] = f })
    }

    private fun act(e: ValidationError, change: String) = act(e.code, e.path, change)

    private fun act(code: ErrorCode, path: String, change: String) {
        actions += RepairAction(code, path, change)
    }
}

/**
 * Ids that are valid and the first of their value keep it -- reserved before
 * anything is renamed, so a slug can never steal an id the author spelled
 * correctly. The rest become a unique slug, or [fallback] when nothing usable
 * survives slugging. Returns the new ids and, for each old id, the new id of
 * its *first* declaration: that is what a reference to a duplicated id means.
 */
private fun normalizeIds(ids: List<String>, fallback: (Int) -> String): Pair<List<String>, Map<String, String>> {
    val keep = BooleanArray(ids.size)
    val taken = mutableSetOf<String>()
    ids.forEachIndexed { k, id -> if (Limits.IDENTIFIER.matches(id) && taken.add(id)) keep[k] = true }
    val fixed = ids.mapIndexed { k, id ->
        if (keep[k]) id else Idents.unique(Idents.identifier(id) ?: fallback(k), taken).also { taken += it }
    }
    val renames = linkedMapOf<String, String>()
    ids.forEachIndexed { k, id -> renames.putIfAbsent(id, fixed[k]) }
    return fixed to renames
}

private fun idCode(old: String) =
    if (Limits.IDENTIFIER.matches(old)) ErrorCode.DUPLICATE_ID else ErrorCode.INVALID_IDENTIFIER

private fun MatchResult.int(group: Int) = groupValues[group].toInt()

private fun MergePolicy.wire() = name.lowercase()

private fun String.preview() = if (length <= 40) this else Idents.truncate(this, 37) + "..."

private fun Field.withId(id: String): Field = when (this) {
    is TextField -> copy(id = id)
    is NumberField -> copy(id = id)
    is BooleanField -> copy(id = id)
    is EnumField -> copy(id = id)
    is TimestampField -> copy(id = id)
}

private fun Field.withLabel(label: String): Field = when (this) {
    is TextField -> copy(label = label)
    is NumberField -> copy(label = label)
    is BooleanField -> copy(label = label)
    is EnumField -> copy(label = label)
    is TimestampField -> copy(label = label)
}

private fun Field.withMerge(merge: MergePolicy): Field = when (this) {
    is TextField -> copy(merge = merge)
    is NumberField -> copy(merge = merge)
    is BooleanField -> copy(merge = merge)
    is EnumField -> copy(merge = merge)
    is TimestampField -> copy(merge = merge)
}

private fun Field.withoutDefault(): Field = when (this) {
    is TextField -> copy(default = null)
    is NumberField -> copy(default = null)
    is BooleanField -> copy(default = null)
    is EnumField -> copy(default = null)
    is TimestampField -> this
}

/** The policy the schema itself defaults to for this field type, read off a fresh instance so it can never drift from AppSpec.kt. */
private fun Field.declaredMerge(): MergePolicy = when (this) {
    is TextField -> TextField(id, label).merge
    is NumberField -> NumberField(id, label).merge
    is BooleanField -> BooleanField(id, label).merge
    is EnumField -> EnumField(id, label).merge
    is TimestampField -> TimestampField(id, label).merge
}

private fun Component.remap(dataset: String, field: (String) -> String): Component = when (this) {
    is RecordForm -> copy(dataset = dataset)
    is RecordList -> copy(dataset = dataset, columns = columns.map(field))
    is StatusCounter -> copy(dataset = dataset, groupBy = field(groupBy))
    is Checklist -> copy(dataset = dataset, titleField = field(titleField), doneField = field(doneField))
    is StatusBoard -> copy(dataset = dataset, groupBy = field(groupBy), columns = columns.map(field))
    is AssignmentBoard -> copy(dataset = dataset, assignedTo = field(assignedTo), columns = columns.map(field))
}

private fun Component.listedColumns(): List<String> = when (this) {
    is RecordList -> columns
    is StatusBoard -> columns
    is AssignmentBoard -> columns
    is RecordForm, is StatusCounter, is Checklist -> emptyList()
}

private fun Component.withoutColumns(drop: Set<Int>): Component {
    fun List<String>.kept() = filterIndexed { k, _ -> k !in drop }
    return when (this) {
        is RecordList -> copy(columns = columns.kept())
        is StatusBoard -> copy(columns = columns.kept())
        is AssignmentBoard -> copy(columns = columns.kept())
        is RecordForm, is StatusCounter, is Checklist -> this
    }
}

private fun Component.wireName(): String = when (this) {
    is RecordForm -> "record_form"
    is RecordList -> "record_list"
    is StatusCounter -> "status_counter"
    is Checklist -> "checklist"
    is StatusBoard -> "status_board"
    is AssignmentBoard -> "assignment_board"
}
