package dev.ghostforge.core.runtime

import dev.ghostforge.core.spec.BooleanField
import dev.ghostforge.core.spec.Dataset
import dev.ghostforge.core.spec.EnumField
import dev.ghostforge.core.spec.NumberField
import dev.ghostforge.core.spec.TextField
import dev.ghostforge.core.spec.TimestampField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Render-time re-validation of stored values against an already-admitted
 * spec. Unlike [dev.ghostforge.core.spec.SpecValidatorTest], these fixtures
 * are plain field/value pairs: there is no document to parse, only a value
 * that may have gone stale since it was written.
 */
class RenderGateTest {

    @Test
    fun `missing value on a required field is unsafe`() {
        val field = TextField("f", "F", required = true)
        val result = RenderGate.checkField(field, RawValue.Null)
        assertIs<FieldRender.Unsafe>(result)
        assertEquals(RenderErrorCode.MISSING_REQUIRED, result.code)
    }

    @Test
    fun `missing value on an optional field is fine`() {
        val field = TextField("f", "F", required = false)
        assertEquals(FieldRender.Ok(RawValue.Null), RenderGate.checkField(field, RawValue.Null))
    }

    @Test
    fun `text within its length bound is safe`() {
        val field = TextField("f", "F", maxLength = 10)
        val result = RenderGate.checkField(field, RawValue.Text("short"))
        assertIs<FieldRender.Ok>(result)
    }

    @Test
    fun `text longer than its current max_length is unsafe`() {
        val field = TextField("f", "F", maxLength = 3)
        val result = RenderGate.checkField(field, RawValue.Text("too long now"))
        assertIs<FieldRender.Unsafe>(result)
        assertEquals(RenderErrorCode.OUT_OF_RANGE, result.code)
    }

    @Test
    fun `empty string on a required text field is unsafe, not just a stored null`() {
        val field = TextField("f", "F", required = true)
        val result = RenderGate.checkField(field, RawValue.Text(""))
        assertIs<FieldRender.Unsafe>(result)
        assertEquals(RenderErrorCode.MISSING_REQUIRED, result.code)
    }

    @Test
    fun `empty string on an optional text field is fine`() {
        val field = TextField("f", "F", required = false)
        assertIs<FieldRender.Ok>(RenderGate.checkField(field, RawValue.Text("")))
    }

    @Test
    fun `wrong stored shape for a text field is unsafe`() {
        val field = TextField("f", "F")
        val result = RenderGate.checkField(field, RawValue.Number(1.0))
        assertIs<FieldRender.Unsafe>(result)
        assertEquals(RenderErrorCode.TYPE_MISMATCH, result.code)
    }

    @Test
    fun `number within bounds is safe`() {
        val field = NumberField("f", "F", minimum = 1.0, maximum = 10.0)
        assertIs<FieldRender.Ok>(RenderGate.checkField(field, RawValue.Number(5.0)))
    }

    @Test
    fun `number below its current minimum is unsafe`() {
        val field = NumberField("f", "F", minimum = 1.0, maximum = 200.0)
        val result = RenderGate.checkField(field, RawValue.Number(0.0))
        assertIs<FieldRender.Unsafe>(result)
        assertEquals(RenderErrorCode.OUT_OF_RANGE, result.code)
    }

    @Test
    fun `number above its current maximum is unsafe`() {
        val field = NumberField("f", "F", minimum = 1.0, maximum = 200.0)
        val result = RenderGate.checkField(field, RawValue.Number(500.0))
        assertIs<FieldRender.Unsafe>(result)
        assertEquals(RenderErrorCode.OUT_OF_RANGE, result.code)
    }

    @Test
    fun `wrong stored shape for a boolean field is unsafe`() {
        val field = BooleanField("f", "F")
        val result = RenderGate.checkField(field, RawValue.Text("true"))
        assertIs<FieldRender.Unsafe>(result)
        assertEquals(RenderErrorCode.TYPE_MISMATCH, result.code)
    }

    @Test
    fun `enum value still in the option set is safe`() {
        val field = EnumField("f", "F", options = listOf("a", "b"))
        assertIs<FieldRender.Ok>(RenderGate.checkField(field, RawValue.Text("a")))
    }

    @Test
    fun `enum value no longer in the option set is unsafe`() {
        // Models a field edit between spec versions: the stored value predates
        // the narrower option set now declared.
        val field = EnumField("f", "F", options = listOf("a", "b"))
        val result = RenderGate.checkField(field, RawValue.Text("retired_option"))
        assertIs<FieldRender.Unsafe>(result)
        assertEquals(RenderErrorCode.UNKNOWN_ENUM_OPTION, result.code)
    }

    @Test
    fun `wrong stored shape for a timestamp field is unsafe`() {
        val field = TimestampField("f", "F")
        val result = RenderGate.checkField(field, RawValue.Text("not-a-moment"))
        assertIs<FieldRender.Unsafe>(result)
        assertEquals(RenderErrorCode.TYPE_MISMATCH, result.code)
    }

    @Test
    fun `checkRecord isolates one bad field without failing the others`() {
        val dataset = Dataset(
            "d",
            listOf(
                TextField("name", "Name", required = true),
                NumberField("age", "Age", minimum = 0.0, maximum = 150.0),
            ),
        )
        val values = mapOf(
            "name" to RawValue.Text("ok"),
            "age" to RawValue.Number(999.0),
        )
        val result = RenderGate.checkRecord(dataset, values)
        assertIs<FieldRender.Ok>(result["name"])
        assertIs<FieldRender.Unsafe>(result["age"])
    }

    @Test
    fun `checkRecord treats an absent column the same as a stored null`() {
        val dataset = Dataset("d", listOf(TextField("optional", "Optional", required = false)))
        val result = RenderGate.checkRecord(dataset, emptyMap())
        assertEquals(FieldRender.Ok(RawValue.Null), result["optional"])
    }

    // --- bucketByEnum --------------------------------------------------------

    @Test
    fun `bucketByEnum creates an empty lane for every declared option`() {
        val status = EnumField("status", "Status", options = listOf("requested", "assigned", "delivered"))
        val buckets = bucketByEnum(status, emptyList())
        assertEquals(setOf("requested", "assigned", "delivered"), buckets.keys)
        assertEquals(emptyList(), buckets["assigned"])
    }

    @Test
    fun `bucketByEnum places each record in its own lane`() {
        val status = EnumField("status", "Status", options = listOf("requested", "delivered"))
        val records = listOf(
            RecordRow("r1", mapOf("status" to RawValue.Text("requested"))),
            RecordRow("r2", mapOf("status" to RawValue.Text("delivered"))),
            RecordRow("r3", mapOf("status" to RawValue.Text("requested"))),
        )
        val buckets = bucketByEnum(status, records)
        assertEquals(listOf("r1", "r3"), buckets.getValue("requested").map { it.id })
        assertEquals(listOf("r2"), buckets.getValue("delivered").map { it.id })
    }

    @Test
    fun `bucketByEnum routes a stale option value to the unsafe lane`() {
        // Models a record written before the field's options were narrowed.
        val status = EnumField("status", "Status", options = listOf("requested", "delivered"))
        val records = listOf(RecordRow("r1", mapOf("status" to RawValue.Text("retired_option"))))
        val buckets = bucketByEnum(status, records)
        assertEquals(listOf("r1"), buckets.getValue(UNSAFE_LANE).map { it.id })
    }

    @Test
    fun `bucketByEnum routes a missing value on a required field to the unsafe lane`() {
        val status = EnumField("status", "Status", required = true, options = listOf("requested", "delivered"))
        val records = listOf(RecordRow("r1", emptyMap()))
        val buckets = bucketByEnum(status, records)
        assertEquals(listOf("r1"), buckets.getValue(UNSAFE_LANE).map { it.id })
    }
}
