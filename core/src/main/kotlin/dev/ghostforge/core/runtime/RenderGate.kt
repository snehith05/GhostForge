package dev.ghostforge.core.runtime

import dev.ghostforge.core.spec.BooleanField
import dev.ghostforge.core.spec.Dataset
import dev.ghostforge.core.spec.EnumField
import dev.ghostforge.core.spec.Field
import dev.ghostforge.core.spec.NumberField
import dev.ghostforge.core.spec.TextField
import dev.ghostforge.core.spec.TimestampField

/**
 * A field value as read back from storage, before it is known to still match
 * what the field currently declares.
 *
 * Storage (SQLite via the app module) only has text/real/integer/null columns,
 * so this is the full set of shapes a stored value can arrive in -- distinct
 * from [dev.ghostforge.core.spec.Field], which describes what a value *should*
 * be, not what it *is*.
 */
sealed class RawValue {
    data class Text(val value: String) : RawValue()
    data class Number(val value: Double) : RawValue()
    data class Flag(val value: Boolean) : RawValue()
    data class Moment(val value: Long) : RawValue()
    data object Null : RawValue()
}

enum class RenderErrorCode {
    MISSING_REQUIRED,
    TYPE_MISMATCH,
    OUT_OF_RANGE,
    UNKNOWN_ENUM_OPTION,
}

/** Whether one field's stored value is still safe to render as-is. */
sealed interface FieldRender {
    data class Ok(val value: RawValue) : FieldRender
    data class Unsafe(val code: RenderErrorCode, val detail: String) : FieldRender
}

/**
 * Re-checks stored values against an already-admitted spec at render time.
 *
 * [dev.ghostforge.core.spec.SpecGate] proves the *spec* is internally
 * consistent once, at admission. It proves nothing about *data* written
 * afterwards: a value can go stale if a field's bounds change across a
 * `spec_version`, or arrive corrupt from a bug elsewhere in the pipeline. The
 * runtime must fail safe on that, per the milestone 2 requirement -- render a
 * placeholder for the one bad field, not crash the whole screen.
 */
object RenderGate {

    fun checkRecord(dataset: Dataset, values: Map<String, RawValue>): Map<String, FieldRender> =
        dataset.fields.associate { it.id to checkField(it, values[it.id] ?: RawValue.Null) }

    fun checkField(field: Field, value: RawValue): FieldRender {
        if (value is RawValue.Null) {
            return if (field.required) {
                FieldRender.Unsafe(RenderErrorCode.MISSING_REQUIRED, "${field.id} is required but has no value")
            } else {
                FieldRender.Ok(value)
            }
        }
        return when (field) {
            is TextField -> checkText(field, value)
            is NumberField -> checkNumber(field, value)
            is BooleanField -> checkFlag(field, value)
            is EnumField -> checkEnum(field, value)
            is TimestampField -> checkMoment(field, value)
        }
    }

    private fun mismatch(field: Field, expected: String, value: RawValue) =
        FieldRender.Unsafe(
            RenderErrorCode.TYPE_MISMATCH,
            "${field.id} expected $expected, got ${value::class.simpleName}",
        )

    private fun checkText(field: TextField, value: RawValue): FieldRender {
        if (value !is RawValue.Text) return mismatch(field, "text", value)
        // An empty string is not RawValue.Null, so the generic required check
        // in checkField never sees it -- required has to mean non-empty here
        // too, matching SpecValidator's own spec.name.isEmpty() convention.
        if (field.required && value.value.isEmpty()) {
            return FieldRender.Unsafe(RenderErrorCode.MISSING_REQUIRED, "${field.id} is required but is empty")
        }
        if (value.value.length > field.maxLength) {
            return FieldRender.Unsafe(
                RenderErrorCode.OUT_OF_RANGE,
                "${field.id} is ${value.value.length} chars, limit is ${field.maxLength}",
            )
        }
        return FieldRender.Ok(value)
    }

    private fun checkNumber(field: NumberField, value: RawValue): FieldRender {
        if (value !is RawValue.Number) return mismatch(field, "number", value)
        val min = field.minimum
        val max = field.maximum
        if (min != null && value.value < min) {
            return FieldRender.Unsafe(RenderErrorCode.OUT_OF_RANGE, "${field.id} ${value.value} is below minimum $min")
        }
        if (max != null && value.value > max) {
            return FieldRender.Unsafe(RenderErrorCode.OUT_OF_RANGE, "${field.id} ${value.value} is above maximum $max")
        }
        return FieldRender.Ok(value)
    }

    private fun checkFlag(field: BooleanField, value: RawValue): FieldRender {
        if (value !is RawValue.Flag) return mismatch(field, "boolean", value)
        return FieldRender.Ok(value)
    }

    private fun checkEnum(field: EnumField, value: RawValue): FieldRender {
        if (value !is RawValue.Text) return mismatch(field, "enum", value)
        if (value.value !in field.options) {
            return FieldRender.Unsafe(
                RenderErrorCode.UNKNOWN_ENUM_OPTION,
                "${field.id} value '${value.value}' is not one of ${field.options}",
            )
        }
        return FieldRender.Ok(value)
    }

    private fun checkMoment(field: TimestampField, value: RawValue): FieldRender {
        if (value !is RawValue.Moment) return mismatch(field, "timestamp", value)
        return FieldRender.Ok(value)
    }
}

/** One stored record: its id plus whatever values storage currently holds for it. */
data class RecordRow(val id: String, val values: Map<String, RawValue>)

/** The catch-all lane for a value [RenderGate] cannot place in a declared option. */
const val UNSAFE_LANE: String = "—unsafe—"

/**
 * Buckets records by [groupField]'s current value, for [StatusCounter],
 * [StatusBoard] and [AssignmentBoard] alike -- all three lay records out in
 * enum-keyed lanes, only the presentation differs.
 *
 * Every option [groupField] declares gets a lane, even an empty one, so the
 * UI reflects the spec's shape and not just whatever data happened to arrive.
 * A value [RenderGate] rejects lands in [UNSAFE_LANE] instead of being
 * silently dropped or crashing the screen.
 */
fun bucketByEnum(groupField: EnumField, records: List<RecordRow>): Map<String, List<RecordRow>> {
    val buckets = linkedMapOf<String, MutableList<RecordRow>>()
    groupField.options.forEach { buckets[it] = mutableListOf() }

    records.forEach { record ->
        val raw = record.values[groupField.id] ?: RawValue.Null
        val lane = when (val checked = RenderGate.checkField(groupField, raw)) {
            is FieldRender.Ok -> (checked.value as? RawValue.Text)?.value ?: UNSAFE_LANE
            is FieldRender.Unsafe -> UNSAFE_LANE
        }
        buckets.getOrPut(lane) { mutableListOf() }.add(record)
    }
    return buckets
}
