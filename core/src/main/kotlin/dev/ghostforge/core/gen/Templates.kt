package dev.ghostforge.core.gen

import dev.ghostforge.core.spec.AppSpec
import dev.ghostforge.core.spec.AssignmentBoard
import dev.ghostforge.core.spec.BooleanField
import dev.ghostforge.core.spec.Checklist
import dev.ghostforge.core.spec.Component
import dev.ghostforge.core.spec.Dataset
import dev.ghostforge.core.spec.EnumField
import dev.ghostforge.core.spec.Limits
import dev.ghostforge.core.spec.MergePolicy
import dev.ghostforge.core.spec.NetworkPermission
import dev.ghostforge.core.spec.NumberField
import dev.ghostforge.core.spec.Permissions
import dev.ghostforge.core.spec.RecordForm
import dev.ghostforge.core.spec.RecordList
import dev.ghostforge.core.spec.StatusBoard
import dev.ghostforge.core.spec.StatusCounter
import dev.ghostforge.core.spec.TextField
import dev.ghostforge.core.spec.TimestampField

/**
 * The deterministic template catalog: what each kind of mini-app looks like,
 * and the words that suggest it.
 *
 * A template decides *meaning* -- which fields, types, options and
 * components an app gets -- and is valid by construction for all of it:
 * fixed identifiers, at least two options per enum, every component naming a
 * field it declares. The only thing it passes through unchecked is the
 * user's own words in the name and description; bounding those is
 * [SpecRepair]'s job, so "shorten this" has one definition, not one per
 * template. Any other repair on template output is a template bug, and the
 * tests treat it as one.
 *
 * Optional fields appear only when the user spelled out the list behind them:
 * an app gets an assignment board when people were named, never placeholder
 * people it invented -- except [TemplateId.ROSTER], which is meaningless
 * without someone to assign.
 */
internal object Templates {

    const val UNASSIGNED = "unassigned"

    fun displayName(t: TemplateId): String = when (t) {
        TemplateId.REQUEST_TRACKER -> "Request Tracker"
        TemplateId.INCIDENT_LOG -> "Incident Log"
        TemplateId.INVENTORY -> "Inventory"
        TemplateId.ROSTER -> "Volunteer Roster"
        TemplateId.CHECKLIST -> "Checklist"
        TemplateId.REGISTRY -> "Registry"
        TemplateId.RECORDS -> "Records"
    }

    /** Words that suggest each template: 3 is a strong signal, 1 a weak one. RECORDS is the fallback and has none. */
    fun keywords(t: TemplateId): Map<String, Int> = when (t) {
        TemplateId.REQUEST_TRACKER -> mapOf(
            "request" to 3, "requested" to 2, "requesting" to 2, "deliver" to 2, "delivery" to 2,
            "distribute" to 2, "distribution" to 2, "ration" to 2, "demand" to 2, "fulfil" to 2,
            "fulfill" to 2, "need" to 1, "relief" to 1, "aid" to 1,
        )
        TemplateId.INCIDENT_LOG -> mapOf(
            "incident" to 3, "broken" to 3, "outage" to 3, "complaint" to 3, "hazard" to 3, "leak" to 3,
            "report" to 2, "reported" to 2, "issue" to 2, "problem" to 2, "damage" to 2, "damaged" to 2,
            "fault" to 2, "blocked" to 2, "repair" to 2,
        )
        TemplateId.INVENTORY -> mapOf(
            "stock" to 3, "inventory" to 3, "warehouse" to 3, "godown" to 3, "supply" to 2,
            "shortage" to 2, "item" to 1, "store" to 1, "quantity" to 1, "available" to 1,
        )
        TemplateId.ROSTER -> mapOf(
            "roster" to 3, "rota" to 3, "shift" to 3, "duty" to 3, "volunteer" to 2, "assign" to 2,
            "assignment" to 2, "schedule" to 2, "team" to 1, "staff" to 1,
        )
        TemplateId.CHECKLIST -> mapOf(
            "checklist" to 3, "todo" to 3, "task" to 2, "step" to 2, "packing" to 2, "pack" to 1,
            "prepare" to 1, "preparation" to 1, "setup" to 1, "done" to 1, "complete" to 1,
        )
        TemplateId.REGISTRY -> mapOf(
            "registration" to 3, "registry" to 3, "census" to 3, "survey" to 3, "headcount" to 3,
            "attendance" to 3, "enroll" to 3, "enrol" to 3, "beneficiary" to 3, "register" to 2,
            "tally" to 2, "family" to 1, "household" to 1, "people" to 1, "count" to 1,
        )
        TemplateId.RECORDS -> emptyMap()
    }

    fun build(i: Interpretation): AppSpec = when (i.template) {
        TemplateId.REQUEST_TRACKER -> requestTracker(i)
        TemplateId.INCIDENT_LOG -> incidentLog(i)
        TemplateId.INVENTORY -> inventory(i)
        TemplateId.ROSTER -> roster(i)
        TemplateId.CHECKLIST -> checklist(i)
        TemplateId.REGISTRY -> registry(i)
        TemplateId.RECORDS -> records(i)
    }

    private fun requestTracker(i: Interpretation): AppSpec {
        val ds = "requests"
        val statuses = i.list(ListKind.STATUS) ?: listOf("requested", "assigned", "delivered")
        val team = i.team()
        val fields = listOfNotNull(
            TextField("requester", "Requested by", required = true, maxLength = 80),
            TextField("details", "What is needed", maxLength = 200),
            NumberField("quantity", "Quantity", minimum = 1.0),
            status(statuses),
            team?.let(::assignedTo),
            updatedAt(),
        )
        val components = listOfNotNull(
            RecordForm(ds),
            RecordList(ds, listOf("requester", "details", "status")),
            StatusCounter(ds, "status"),
            StatusBoard(ds, "status", listOf("requester", "details")),
            team?.let { AssignmentBoard(ds, "assigned_to", listOf("requester", "status")) },
        )
        return app(i, Dataset(ds, fields), components)
    }

    private fun incidentLog(i: Interpretation): AppSpec {
        val ds = "reports"
        val severity = i.list(ListKind.SEVERITY) ?: listOf("low", "medium", "high", "critical")
        val statuses = i.list(ListKind.STATUS) ?: listOf("open", "in_progress", "resolved")
        val kinds = i.list(ListKind.CATEGORIES)
        val team = i.team()
        val fields = listOfNotNull(
            TextField("summary", "What happened", required = true, maxLength = 120),
            TextField("location", "Location", maxLength = 80),
            // Severity can be revised down as well as up, so last-write-wins.
            EnumField("severity", "Severity", options = severity),
            status(statuses),
            kinds?.let { EnumField("category", "Type", options = it) },
            team?.let(::assignedTo),
            updatedAt(),
        )
        val components = listOfNotNull(
            RecordForm(ds),
            StatusBoard(ds, "status", listOf("summary", "severity", "location")),
            StatusCounter(ds, "severity"),
            RecordList(ds, listOf("summary", "location", "status")),
            kinds?.let { StatusCounter(ds, "category") },
            team?.let { AssignmentBoard(ds, "assigned_to", listOf("summary", "status")) },
        )
        return app(i, Dataset(ds, fields), components)
    }

    private fun inventory(i: Interpretation): AppSpec {
        val ds = "items"
        val levels = i.list(ListKind.STATUS) ?: listOf("in_stock", "low", "out")
        val categories = i.list(ListKind.CATEGORIES)
        val fields = listOfNotNull(
            TextField("item", "Item", required = true, maxLength = 80),
            // Last-write-wins on a shared count is the scarce-resource problem M5 must solve; see PROGRESS.md.
            NumberField("quantity", "Quantity", minimum = 0.0),
            TextField("unit", "Unit", maxLength = 20),
            TextField("location", "Location", maxLength = 80),
            // Stock goes back up when restocked, so last-write-wins, not a forward-only status_transition.
            EnumField("stock", "Stock level", options = levels, default = levels.first()),
            categories?.let { EnumField("category", "Category", options = it) },
            updatedAt(),
        )
        val components = listOfNotNull(
            RecordForm(ds),
            RecordList(ds, listOf("item", "quantity", "unit", "stock")),
            StatusCounter(ds, "stock"),
            StatusBoard(ds, "stock", listOf("item", "quantity")),
            categories?.let { StatusCounter(ds, "category") },
        )
        return app(i, Dataset(ds, fields), components)
    }

    private fun roster(i: Interpretation): AppSpec {
        val ds = "duties"
        val team = i.team() ?: listOf(UNASSIGNED, "team_1", "team_2", "team_3")
        val shifts = i.list(ListKind.SHIFTS) ?: listOf("morning", "afternoon", "night")
        val statuses = i.list(ListKind.STATUS) ?: listOf("planned", "on_duty", "done")
        val fields = listOf(
            TextField("duty", "Duty", required = true, maxLength = 80),
            EnumField("shift", "Shift", options = shifts),
            assignedTo(team),
            status(statuses),
            TextField("notes", "Notes", maxLength = 200),
            updatedAt(),
        )
        val components = listOf(
            RecordForm(ds),
            AssignmentBoard(ds, "assigned_to", listOf("duty", "shift", "status")),
            StatusBoard(ds, "status", listOf("duty", "assigned_to")),
            RecordList(ds, listOf("duty", "shift", "assigned_to", "status")),
            StatusCounter(ds, "shift"),
        )
        return app(i, Dataset(ds, fields), components)
    }

    private fun checklist(i: Interpretation): AppSpec {
        val ds = "tasks"
        val team = i.team()
        val fields = listOfNotNull(
            TextField("task", "Task", required = true, maxLength = 120),
            BooleanField("done", "Done", default = false),
            TextField("notes", "Notes", maxLength = 200),
            team?.let(::assignedTo),
            updatedAt(),
        )
        val components = listOfNotNull(
            RecordForm(ds),
            Checklist(ds, titleField = "task", doneField = "done"),
            RecordList(ds, listOf("task", "done")),
            team?.let { AssignmentBoard(ds, "assigned_to", listOf("task", "done")) },
        )
        return app(i, Dataset(ds, fields), components)
    }

    private fun registry(i: Interpretation): AppSpec {
        val ds = "entries"
        val categories = i.list(ListKind.CATEGORIES)
        val fields = listOfNotNull(
            TextField("name", "Name", required = true, maxLength = 80),
            NumberField("people", "People", minimum = 1.0),
            TextField("location", "Location", maxLength = 80),
            categories?.let { EnumField("category", "Category", options = it) },
            TextField("notes", "Notes", maxLength = 200),
            updatedAt(),
        )
        val components = listOfNotNull(
            RecordForm(ds),
            RecordList(ds, listOfNotNull("name", "people", "location", categories?.let { "category" })),
            categories?.let { StatusCounter(ds, "category") },
        )
        return app(i, Dataset(ds, fields), components)
    }

    /** The fallback when nothing matched: a plain record keeper, shaped further only by lists the user spelled out. */
    private fun records(i: Interpretation): AppSpec {
        val ds = "records"
        val statuses = i.list(ListKind.STATUS)
        val categories = i.list(ListKind.CATEGORIES)
        val team = i.team()
        val fields = listOfNotNull(
            TextField("title", "Title", required = true, maxLength = 120),
            TextField("details", "Details", maxLength = Limits.MAX_TEXT_LENGTH),
            statuses?.let(::status),
            categories?.let { EnumField("category", "Category", options = it) },
            team?.let(::assignedTo),
            updatedAt(),
        )
        val components = listOfNotNull(
            RecordForm(ds),
            RecordList(ds, listOfNotNull("title", statuses?.let { "status" }, categories?.let { "category" })),
            statuses?.let { StatusBoard(ds, "status", listOf("title")) },
            statuses?.let { StatusCounter(ds, "status") },
            categories?.let { StatusCounter(ds, "category") },
            team?.let { AssignmentBoard(ds, "assigned_to", listOf("title")) },
        )
        return app(i, Dataset(ds, fields), components)
    }

    // --- shared pieces ----------------------------------------------------------

    /** The user's list for [kind], if there is enough of one to make an enum. */
    private fun Interpretation.list(kind: ListKind): List<String>? = lists[kind]?.takeIf { it.size >= 2 }

    /** Named people, with [UNASSIGNED] first so a new record has a lane to start in. Null if nobody was named. */
    private fun Interpretation.team(): List<String>? = lists[ListKind.ASSIGNEES]?.let { names ->
        (listOf(UNASSIGNED) + names.filter { it != UNASSIGNED }).take(Limits.MAX_ENUM_OPTIONS).takeIf { it.size >= 2 }
    }

    /**
     * Workflow states advance and never retreat. Their order is the order the
     * user listed them in -- the best signal there is, though a branching
     * workflow ("approved" vs "rejected") is not really linear; that belongs
     * to M5's merge semantics, see PROGRESS.md.
     */
    private fun status(options: List<String>) = EnumField(
        "status", "Status", options = options, default = options.first(), merge = MergePolicy.STATUS_TRANSITION,
    )

    private fun assignedTo(options: List<String>) =
        EnumField("assigned_to", "Assigned to", options = options, default = UNASSIGNED)

    private fun updatedAt() = TimestampField("updated_at", "Last updated")

    private fun app(i: Interpretation, dataset: Dataset, components: List<Component>) = AppSpec(
        schemaVersion = Limits.SCHEMA_VERSION,
        appId = Idents.appId(i.name),
        name = i.name,
        description = i.description,
        datasets = listOf(dataset),
        components = components,
        // A generated app exists to be shared, so it may reach the sync transport -- and nothing else.
        permissions = Permissions(network = NetworkPermission.GHOSTFORGE_SYNC_ONLY),
    )
}
