package dev.ghostforge.app.ui

import dev.ghostforge.core.runtime.FieldRender
import dev.ghostforge.core.runtime.RawValue
import dev.ghostforge.core.runtime.RenderGate
import dev.ghostforge.core.spec.Field
import java.time.Instant

/**
 * How a field's current stored value should appear on screen: re-checked by
 * [RenderGate] first, so a value that no longer matches what the field
 * declares renders as a visible placeholder instead of crashing or silently
 * showing something wrong.
 */
fun safeDisplayText(field: Field, raw: RawValue): String =
    when (val checked = RenderGate.checkField(field, raw)) {
        is FieldRender.Ok -> displayText(checked.value)
        is FieldRender.Unsafe -> "(unsafe: ${checked.code.name.lowercase().replace('_', ' ')})"
    }

private fun displayText(value: RawValue): String = when (value) {
    is RawValue.Text -> value.value
    is RawValue.Number -> formatNumber(value.value)
    is RawValue.Flag -> if (value.value) "Yes" else "No"
    is RawValue.Moment -> Instant.ofEpochMilli(value.value).toString()
    RawValue.Null -> "—"
}

/** "1" rather than "1.0" for a value that happens to be whole -- used wherever a number is shown, not just here. */
fun formatNumber(value: Double): String {
    val whole = value.toLong()
    return if (whole.toDouble() == value) whole.toString() else value.toString()
}
