package dev.ghostforge.core.gen

import dev.ghostforge.core.spec.Limits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Free text to spec-legal identifiers. Non-ASCII input is built from code points so the source stays plain ASCII. */
class IdentsTest {

    private fun cp(vararg codePoints: Int) = String(codePoints, 0, codePoints.size)

    private val kannadaWater = cp(0x0CA8, 0x0CC0, 0x0CB0, 0x0CC1)
    private val grinning = cp(0x1F600)

    @Test
    fun `identifier slugs spaces and case`() = assertEquals("water_requests", Idents.identifier("Water Requests"))

    @Test
    fun `identifier folds accents instead of dropping the letter`() =
        assertEquals("cafe_orders", Idents.identifier("Caf" + cp(0xE9) + " orders"))

    @Test
    fun `identifier cannot start with a digit`() = assertEquals("id_2024_survey", Idents.identifier("2024 survey"))

    @Test
    fun `identifier with no latin letters is null rather than empty`() = assertNull(Idents.identifier(kannadaWater))

    @Test
    fun `one letter is too short for an identifier but fine as an option`() {
        assertNull(Idents.identifier("x"))
        assertEquals("x", Idents.option("X"))
    }

    @Test
    fun `identifier is capped at the identifier limit`() =
        assertEquals(Idents.IDENTIFIER_MAX, Idents.identifier("a".repeat(100))!!.length)

    @Test
    fun `sql metacharacters never survive`() =
        assertEquals("house_drop_table_t", Idents.identifier("house\"; DROP TABLE t--"))

    @Test
    fun `app id uses hyphens`() = assertEquals("water-request-tracker", Idents.appId("Water Request Tracker"))

    @Test
    fun `app id ends on a whole word`() = assertEquals(
        "household-water-requests-from-the-northern-villages-of-udupi",
        Idents.appId("Household Water Requests From The Northern Villages Of Udupi District"),
    )

    @Test
    fun `app id with nothing latin falls back to a stable hash`() {
        val id = Idents.appId(kannadaWater)
        assertTrue(Limits.APP_ID.matches(id), id)
        assertTrue(id.startsWith("app-"))
        assertEquals(id, Idents.appId(kannadaWater))
        assertNotEquals(id, Idents.appId(grinning))
    }

    @Test
    fun `unique takes the smallest free suffix`() =
        assertEquals("items_3", Idents.unique("items", setOf("items", "items_2")))

    @Test
    fun `unique stays within the identifier limit`() {
        val base = "a".repeat(Idents.IDENTIFIER_MAX)
        val u = Idents.unique(base, setOf(base))
        assertEquals(Idents.IDENTIFIER_MAX, u.length)
        assertTrue(u.endsWith("_2") && Limits.IDENTIFIER.matches(u), u)
    }

    @Test
    fun `truncate backs off to a word boundary`() = assertEquals("alpha beta", Idents.truncate("alpha beta gamma", 12))

    @Test
    fun `truncate never splits a surrogate pair`() {
        val t = Idents.truncate("a".repeat(63) + grinning + grinning, 64)
        assertEquals("a".repeat(63), t)
        assertFalse(t.last().isHighSurrogate())
    }

    @Test
    fun `label from id reads like a label`() = assertEquals("Litres requested", Idents.labelFromId("litres_requested"))
}
