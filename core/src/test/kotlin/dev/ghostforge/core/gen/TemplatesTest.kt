package dev.ghostforge.core.gen

import dev.ghostforge.core.spec.AssignmentBoard
import dev.ghostforge.core.spec.EnumField
import dev.ghostforge.core.spec.Limits
import dev.ghostforge.core.spec.MergePolicy
import dev.ghostforge.core.spec.NetworkPermission
import dev.ghostforge.core.spec.Permissions
import dev.ghostforge.core.spec.RecordForm
import dev.ghostforge.core.spec.SpecValidator
import dev.ghostforge.core.spec.StatusBoard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Templates are valid by construction: everything here holds before repair runs at all. */
class TemplatesTest {

    private fun interp(t: TemplateId, lists: Map<ListKind, List<String>> = emptyMap()) =
        Interpretation(t, emptyList(), "Test App", "A test", lists)

    private val everyList = mapOf(
        ListKind.STATUS to listOf("new", "doing", "finished"),
        ListKind.ASSIGNEES to listOf("asha", "ravi"),
        ListKind.CATEGORIES to listOf("food", "water"),
        ListKind.SEVERITY to listOf("minor", "major"),
        ListKind.SHIFTS to listOf("day", "night"),
    )

    @Test
    fun `every template is valid with and without lists`() {
        for (t in TemplateId.entries) for (lists in listOf(emptyMap(), everyList)) {
            val report = SpecValidator.validate(Templates.build(interp(t, lists)))
            assertTrue(report.isValid, "$t with ${lists.keys}:\n$report")
        }
    }

    @Test
    fun `every template opens with a form`() {
        for (t in TemplateId.entries) assertTrue(Templates.build(interp(t)).components.first() is RecordForm, "$t")
    }

    @Test
    fun `generated apps may reach sync and nothing else`() {
        for (t in TemplateId.entries) {
            assertEquals(Permissions(network = NetworkPermission.GHOSTFORGE_SYNC_ONLY), Templates.build(interp(t)).permissions)
        }
    }

    @Test
    fun `a named team gets unassigned first, as the default`() {
        val spec = Templates.build(interp(TemplateId.ROSTER, mapOf(ListKind.ASSIGNEES to listOf("asha", "ravi"))))
        val assigned = spec.datasets.single().fields.first { it.id == "assigned_to" } as EnumField
        assertEquals(listOf("unassigned", "asha", "ravi"), assigned.options)
        assertEquals("unassigned", assigned.default)
    }

    @Test
    fun `an assignment board appears only when people are named`() {
        val unnamed = Templates.build(interp(TemplateId.REQUEST_TRACKER))
        val named = Templates.build(interp(TemplateId.REQUEST_TRACKER, mapOf(ListKind.ASSIGNEES to listOf("asha"))))
        assertTrue(unnamed.components.none { it is AssignmentBoard })
        assertTrue(named.components.any { it is AssignmentBoard })
    }

    @Test
    fun `listed statuses replace the defaults and keep their order`() {
        val spec = Templates.build(interp(TemplateId.INCIDENT_LOG, mapOf(ListKind.STATUS to listOf("reported", "fixing", "fixed"))))
        val status = spec.datasets.single().fields.first { it.id == "status" } as EnumField
        assertEquals(listOf("reported", "fixing", "fixed"), status.options)
        assertEquals(MergePolicy.STATUS_TRANSITION, status.merge)
    }

    @Test
    fun `a single listed status is not enough to replace the defaults`() {
        val spec = Templates.build(interp(TemplateId.REQUEST_TRACKER, mapOf(ListKind.STATUS to listOf("pending"))))
        val status = spec.datasets.single().fields.first { it.id == "status" } as EnumField
        assertEquals(listOf("requested", "assigned", "delivered"), status.options)
    }

    @Test
    fun `the fallback is shaped only by lists the user spelled out`() {
        val bare = Templates.build(interp(TemplateId.RECORDS))
        assertEquals(listOf("title", "details", "updated_at"), bare.datasets.single().fields.map { it.id })
        val shaped = Templates.build(interp(TemplateId.RECORDS, mapOf(ListKind.STATUS to listOf("open", "closed"))))
        assertTrue(shaped.components.any { it is StatusBoard })
    }

    @Test
    fun `a full team still fits the enum limit`() {
        val names = (1..Limits.MAX_ENUM_OPTIONS).map { "p$it" }
        val spec = Templates.build(interp(TemplateId.CHECKLIST, mapOf(ListKind.ASSIGNEES to names)))
        assertTrue(SpecValidator.validate(spec).isValid)
    }
}
