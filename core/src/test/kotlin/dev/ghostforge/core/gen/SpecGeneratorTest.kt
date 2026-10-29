package dev.ghostforge.core.gen

import dev.ghostforge.core.spec.Admission
import dev.ghostforge.core.spec.AssignmentBoard
import dev.ghostforge.core.spec.EnumField
import dev.ghostforge.core.spec.ErrorCode
import dev.ghostforge.core.spec.Limits
import dev.ghostforge.core.spec.SpecGate
import dev.ghostforge.core.spec.StatusBoard
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The whole deterministic path, description to admitted spec. Its contract:
 * any input yields an admitted app, the same input always yields the same
 * bytes, and the only repairs ever needed are to the user's own words.
 */
class SpecGeneratorTest {

    private fun generated(text: String): GenerationResult.Generated {
        val result = SpecGenerator.generate(text)
        assertIs<GenerationResult.Generated>(result, "generation failed for '$text': $result")
        return result
    }

    /** Template output only ever needs the user's own words shortened; any other repair is a template bug. */
    private fun assertOnlyUserTextRepaired(result: GenerationResult, input: String) {
        val unexpected = result.repairs.filterNot { it.code == ErrorCode.LENGTH_OUT_OF_BOUNDS && it.path in USER_TEXT }
        assertTrue(unexpected.isEmpty(), "unexpected repairs for '$input': $unexpected")
    }

    /** A document that does not survive a UTF-8 round trip cannot travel between devices. */
    private fun assertUtf8Safe(result: GenerationResult.Generated) {
        val doc = SpecGate.encode(result.admission.spec)
        assertEquals(doc, String(doc.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `the canonical description yields a request tracker`() {
        val result = generated("Track household water requests")
        val spec = result.admission.spec
        assertEquals(TemplateId.REQUEST_TRACKER, result.interpretation.template)
        assertEquals("household-water-requests", spec.appId)
        assertEquals("Household Water Requests", spec.name)
        assertEquals(listOf("requester", "details", "quantity", "status", "updated_at"), spec.datasets.single().fields.map { it.id })
        assertTrue(spec.components.any { it is StatusBoard })
        assertTrue(result.repairs.isEmpty())
    }

    @Test
    fun `listed statuses and people shape the app`() {
        val spec = generated("Track water requests. Statuses: requested, on the way, delivered. Volunteers: Asha, Ravi").admission.spec
        val fields = spec.datasets.single().fields.associateBy { it.id }
        assertEquals(listOf("requested", "on_the_way", "delivered"), (fields.getValue("status") as EnumField).options)
        assertEquals(listOf("unassigned", "asha", "ravi"), (fields.getValue("assigned_to") as EnumField).options)
        assertTrue(spec.components.any { it is AssignmentBoard })
    }

    @Test
    fun `generation is deterministic down to the byte`() {
        val text = "Assign volunteers to shifts: morning, night. Volunteers: Asha, Ravi"
        val a = generated(text)
        val b = generated(text)
        assertEquals(a, b)
        assertEquals(SpecGate.encode(a.admission.spec), SpecGate.encode(b.admission.spec))
    }

    @Test
    fun `a long description is shortened, and that is the only repair`() {
        val text = "Track water requests " + "from the villages ".repeat(40)
        val result = generated(text)
        assertTrue(result.admission.spec.description.length <= Limits.MAX_DESCRIPTION_LENGTH)
        assertTrue(result.repairs.isNotEmpty())
        assertOnlyUserTextRepaired(result, text)
    }

    @Test
    fun `the user's words are data, never structure`() {
        val hostile = "\", \"on_open\": \"rm -rf /\", \"x\": \"' ; DROP TABLE ds_records; --"
        val spec = generated(hostile).admission.spec
        assertTrue(spec.description.contains("\"on_open\""))
        val again = SpecGate.admit(SpecGate.encode(spec))
        assertIs<Admission.Admitted>(again)
        assertEquals(spec, again.spec)
    }

    @Test
    fun `awkward input still yields an admitted app`() {
        val grin = String(Character.toChars(0x1F600))
        val inputs = listOf(
            "", " ", "!!!", "a", "please", "Track",
            String(intArrayOf(0x0CA8, 0x0CC0, 0x0CB0, 0x0CC1), 0, 4), // Kannada
            String(intArrayOf(0x092A, 0x093E, 0x0928, 0x0940), 0, 4), // Hindi
            grin.repeat(100),
            grin.substring(0, 1) + " half an emoji",
            "" + Char(0) + Char(7) + Char(0x202E) + " reversed",
            "status: ,,,,", "volunteers: a, a, a", "teams: unassigned",
            "status: " + (1..50).joinToString(", ") { "s$it" },
            "x".repeat(20_000),
            "Track requests ".repeat(1_000),
        )
        for (text in inputs) {
            val result = generated(text)
            assertTrue(Limits.APP_ID.matches(result.admission.spec.appId), "bad app id for '$text'")
            assertOnlyUserTextRepaired(result, text)
            assertUtf8Safe(result)
        }
    }

    @Test
    fun `seeded fuzz never fails and only ever shortens the user's words`() {
        val random = Random(20261029)
        val pieces = listOf(
            "request", "status:", "statuses -", "volunteers:", "shifts:", "teams", "stock", "report",
            "broken", "checklist", "register", "and", "or", ",", ".", ";", "(", ")", "/", "&",
            "Asha", "ON", "the", "2024", "x", "  ", "\n", "\t", String(Character.toChars(0x1F600)),
            String(intArrayOf(0x0CA8, 0x0CC0), 0, 2), "Caf" + Char(0xE9),
        )
        repeat(500) {
            val text = buildString { repeat(random.nextInt(0, 40)) { append(pieces[random.nextInt(pieces.size)]).append(' ') } }
            val result = SpecGenerator.generate(text)
            assertIs<GenerationResult.Generated>(result, "generation failed for '$text': $result")
            assertOnlyUserTextRepaired(result, text)
            assertUtf8Safe(result)
        }
    }

    private companion object {
        val USER_TEXT = setOf("name", "description")
    }
}
