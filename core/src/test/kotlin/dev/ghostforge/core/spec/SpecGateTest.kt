package dev.ghostforge.core.spec

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Admission of whole documents: size, shape, and the happy path. */
class SpecGateTest {

    private fun admit(doc: String) = SpecGate.admit(doc)

    private fun rejectionCodes(doc: String): Set<ErrorCode> {
        val result = admit(doc)
        assertIs<Admission.Rejected>(result, "expected rejection but document was admitted")
        return result.report.codes()
    }

    @Test
    fun `canonical water tracker is admitted`() {
        val result = admit(Fixtures.WATER_TRACKER)
        assertIs<Admission.Admitted>(result, "expected admission, got: $result")
        val spec = result.spec
        assertEquals("water-request-tracker", spec.appId)
        assertEquals(1, spec.datasets.size)
        assertEquals(4, spec.datasets[0].fields.size)
        assertEquals(3, spec.components.size)
    }

    @Test
    fun `admitted spec survives an encode and re-admit round trip`() {
        val first = admit(Fixtures.WATER_TRACKER)
        assertIs<Admission.Admitted>(first)
        val reEncoded = SpecGate.encode(first.spec)

        val second = admit(reEncoded)
        assertIs<Admission.Admitted>(second, "re-encoded spec was rejected: $second")
        assertEquals(first.spec, second.spec, "round trip changed the specification")
    }

    // --- stage 1: size -----------------------------------------------------

    @Test
    fun `oversized document is rejected before parsing`() {
        // Valid JSON, just far too large: padding goes in the description so the
        // document stays well-formed and only the size rule can fire.
        val padding = "x".repeat(Limits.MAX_SPEC_BYTES + 1024)
        val doc = Fixtures.variant("Track household water requests", padding)
        assertTrue(doc.toByteArray().size > Limits.MAX_SPEC_BYTES)
        assertContains(rejectionCodes(doc), ErrorCode.MALFORMED)
    }

    // --- stage 2: shape ----------------------------------------------------

    @Test
    fun `unknown component type is rejected`() {
        val doc = Fixtures.variant("\"type\": \"status_counter\"", "\"type\": \"shell_exec\"")
        assertContains(rejectionCodes(doc), ErrorCode.MALFORMED)
    }

    @Test
    fun `unknown field type is rejected`() {
        val doc = Fixtures.variant("\"type\": \"timestamp\"", "\"type\": \"blob\"")
        assertContains(rejectionCodes(doc), ErrorCode.MALFORMED)
    }

    @Test
    fun `unknown key is rejected rather than ignored`() {
        val doc = Fixtures.variant(
            "\"app_id\": \"water-request-tracker\"",
            "\"app_id\": \"water-request-tracker\", \"on_open\": \"rm -rf /\"",
        )
        assertContains(rejectionCodes(doc), ErrorCode.MALFORMED)
    }

    @Test
    fun `unknown permission key is rejected`() {
        val doc = Fixtures.variant("\"camera\": false", "\"camera\": false, \"root\": true")
        assertContains(rejectionCodes(doc), ErrorCode.MALFORMED)
    }

    @Test
    fun `unknown network permission value is rejected`() {
        val doc = Fixtures.variant("\"network\": \"ghostforge_sync_only\"", "\"network\": \"internet\"")
        assertContains(rejectionCodes(doc), ErrorCode.MALFORMED)
    }

    @Test
    fun `malformed json is rejected`() {
        assertContains(rejectionCodes("{ not json at all"), ErrorCode.MALFORMED)
    }

    @Test
    fun `empty document is rejected`() {
        assertContains(rejectionCodes(""), ErrorCode.MALFORMED)
    }

    // --- defaults ----------------------------------------------------------

    @Test
    fun `absent permission block grants nothing`() {
        val spec = AppSpec(schemaVersion = "1.0", appId = "x-y-z", name = "n")
        val p = spec.permissions
        assertEquals(false, p.camera)
        assertEquals(false, p.contacts)
        assertEquals(false, p.preciseLocation)
        assertEquals(NetworkPermission.NONE, p.network)
    }
}
