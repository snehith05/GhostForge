package dev.ghostforge.core.gen

import dev.ghostforge.core.spec.Limits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plain language to an [Interpretation]: which template, what name, which lists. */
class InterpreterTest {

    private fun template(text: String) = Interpreter.interpret(text).template
    private fun name(text: String) = Interpreter.interpret(text).name
    private fun lists(text: String) = Interpreter.interpret(text).lists

    // --- choosing a template -------------------------------------------------------

    @Test
    fun `requests and deliveries choose the request tracker`() {
        assertEquals(TemplateId.REQUEST_TRACKER, template("Track household water requests"))
        assertEquals(TemplateId.REQUEST_TRACKER, template("Distribute food rations to families who need them"))
    }

    @Test
    fun `problems and damage choose the incident log`() {
        assertEquals(TemplateId.INCIDENT_LOG, template("Report broken water pumps in the village"))
        assertEquals(TemplateId.INCIDENT_LOG, template("Log road blockages and power outages after the flood"))
    }

    @Test
    fun `stock chooses inventory`() {
        assertEquals(TemplateId.INVENTORY, template("Keep count of blankets and food packets in the godown"))
        assertEquals(TemplateId.INVENTORY, template("Medicine stock at the health camp"))
    }

    @Test
    fun `shifts and duties choose the roster`() {
        assertEquals(TemplateId.ROSTER, template("Assign volunteers to shifts at the relief camp"))
        assertEquals(TemplateId.ROSTER, template("Night duty rota for the shelter"))
    }

    @Test
    fun `tasks choose the checklist`() {
        assertEquals(TemplateId.CHECKLIST, template("Checklist for setting up the evacuation shelter"))
        assertEquals(TemplateId.CHECKLIST, template("To-do list before the medical camp opens"))
    }

    @Test
    fun `registration and headcounts choose the registry`() {
        assertEquals(TemplateId.REGISTRY, template("Register families arriving at the shelter"))
        assertEquals(TemplateId.REGISTRY, template("Daily attendance at the community kitchen"))
    }

    @Test
    fun `nothing recognisable falls back to plain records`() {
        assertEquals(TemplateId.RECORDS, template("Notes about the river"))
        assertEquals(TemplateId.RECORDS, template(""))
    }

    @Test
    fun `non-latin input falls back rather than failing`() =
        assertEquals(TemplateId.RECORDS, template(String(intArrayOf(0x0CA8, 0x0CC0, 0x0CB0, 0x0CC1), 0, 4)))

    @Test
    fun `the matched keywords explain the choice`() =
        assertEquals(listOf("assign", "volunteer", "shift"), Interpreter.interpret("Assign volunteers to shifts").matched)

    @Test
    fun `plurals fold to their singular`() {
        assertEquals("request", Interpreter.stem("requests"))
        assertEquals("family", Interpreter.stem("families"))
        assertEquals("box", Interpreter.stem("boxes"))
        assertEquals("status", Interpreter.stem("status"))
    }

    // --- the name ----------------------------------------------------------------------

    @Test
    fun `name drops the preamble and title-cases what is left`() =
        assertEquals("Household Water Requests", name("Please help me make an app to track household water requests."))

    @Test
    fun `name keeps small words lowercase and acronyms intact`() =
        assertEquals("Shelter Roster for NGO Volunteers", name("shelter roster for NGO volunteers"))

    @Test
    fun `name ends where a list begins`() =
        assertEquals("Assign Volunteers to Shifts", name("Assign volunteers to shifts: morning, night"))

    @Test
    fun `name is the first sentence only`() =
        assertEquals("Water Requests", name("Track water requests. Volunteers deliver them."))

    @Test
    fun `nothing left after the preamble falls back to the template name`() =
        assertEquals("Records", name("please"))

    // --- cleaning ------------------------------------------------------------------------

    @Test
    fun `whitespace collapses and control characters disappear`() {
        val raw = "  a" + Char(9) + Char(10) + "b " + Char(0) + Char(7) + " c  "
        assertEquals("a b c", Interpreter.clean(raw))
    }

    @Test
    fun `an unpaired surrogate is dropped and a real pair kept`() {
        val pair = String(Character.toChars(0x1F600))
        assertEquals("a" + pair + "b", Interpreter.clean("a" + pair + pair.substring(0, 1) + "b"))
    }

    // --- lists ---------------------------------------------------------------------------

    @Test
    fun `a colon list becomes slugged options`() = assertEquals(
        listOf("pending", "in_progress", "done"),
        lists("Track jobs. Statuses: Pending, In progress and Done")[ListKind.STATUS],
    )

    @Test
    fun `the keyword must sit right before the colon`() {
        val l = lists("Assign volunteers to shifts: morning, evening and night")
        assertEquals(listOf("morning", "evening", "night"), l[ListKind.SHIFTS])
        assertNull(l[ListKind.ASSIGNEES])
    }

    @Test
    fun `two lists on one line stay two lists`() {
        val l = lists("statuses: pending, delivered and volunteers: Asha, Ravi")
        assertEquals(listOf("pending", "delivered"), l[ListKind.STATUS])
        assertEquals(listOf("asha", "ravi"), l[ListKind.ASSIGNEES])
    }

    @Test
    fun `a line break ends a list`() {
        val l = lists("status: open, closed" + Char(10) + "teams: red, blue")
        assertEquals(listOf("open", "closed"), l[ListKind.STATUS])
        assertEquals(listOf("red", "blue"), l[ListKind.ASSIGNEES])
    }

    @Test
    fun `a parenthesised list needs at least two items`() {
        assertEquals(listOf("low", "medium", "high"), lists("priority (low, medium, high)")[ListKind.SEVERITY])
        assertNull(lists("volunteers (about 20)")[ListKind.ASSIGNEES])
    }

    @Test
    fun `a long phrase is not a list item`() =
        assertEquals(listOf("done"), lists("status: pending until someone picks it up, done")[ListKind.STATUS])

    @Test
    fun `duplicates collapse and long lists are capped`() {
        assertEquals(listOf("open"), lists("status: Open, open, OPEN")[ListKind.STATUS])
        val many = (1..20).joinToString(", ") { "s$it" }
        assertEquals(Limits.MAX_ENUM_OPTIONS, lists("status: $many")[ListKind.STATUS]!!.size)
    }

    @Test
    fun `an unknown keyword before a colon is ignored`() =
        assertTrue(lists("Note: this is for the north camp").isEmpty())

    @Test
    fun `interpretation is deterministic`() {
        val text = "Assign volunteers to shifts: morning, night. Volunteers: Asha, Ravi"
        assertEquals(Interpreter.interpret(text), Interpreter.interpret(text))
    }
}
