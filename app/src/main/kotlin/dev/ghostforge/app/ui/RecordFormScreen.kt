package dev.ghostforge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ghostforge.core.runtime.RawValue
import dev.ghostforge.core.spec.BooleanField
import dev.ghostforge.core.spec.Dataset
import dev.ghostforge.core.spec.EnumField
import dev.ghostforge.core.spec.Field
import dev.ghostforge.core.spec.NumberField
import dev.ghostforge.core.spec.TextField as SpecTextField
import dev.ghostforge.core.spec.TimestampField

/**
 * A form that creates one new record. Timestamp fields are system-set (see
 * [dev.ghostforge.app.viewmodel.AppRuntimeViewModel.createRecord]) so they
 * never get an input of their own here.
 */
@Composable
fun RecordFormScreen(dataset: Dataset, onCreateRecord: (Map<String, RawValue>) -> Unit) {
    val editableFields = remember(dataset.id) { dataset.fields.filter { it !is TimestampField } }
    val inputs = remember(dataset.id) {
        editableFields.associate { f -> f.id to mutableStateOf(initialValue(f)) }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        editableFields.forEach { field -> FieldInput(field, inputs.getValue(field.id)) }
        Button(onClick = { onCreateRecord(inputs.mapValues { it.value.value }) }) {
            Text("Add")
        }
    }
}

private fun initialValue(field: Field): RawValue = when (field) {
    is SpecTextField -> RawValue.Text(field.default ?: "")
    is NumberField -> RawValue.Number(field.default ?: field.minimum ?: 0.0)
    is BooleanField -> RawValue.Flag(field.default ?: false)
    is EnumField -> RawValue.Text(field.default ?: field.options.firstOrNull().orEmpty())
    is TimestampField -> RawValue.Moment(0L)
}

@Composable
private fun FieldInput(field: Field, state: MutableState<RawValue>) {
    when (field) {
        is SpecTextField -> {
            val current = state.value as? RawValue.Text ?: RawValue.Text("")
            OutlinedTextField(
                value = current.value,
                onValueChange = { state.value = RawValue.Text(it) },
                label = { Text(field.label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is NumberField -> {
            // Typed text is tracked separately from the committed RawValue:
            // re-deriving the displayed string from state.value on every
            // keystroke would reformat "1" to "1.0" (Double.toString always
            // shows a decimal) and fight whatever the user just typed. An
            // intermediate state that doesn't parse ("", "-", "1.") just
            // doesn't update the commit; Add still sends the last valid one.
            var text by remember(field.id) {
                mutableStateOf(formatNumber((state.value as RawValue.Number).value))
            }
            OutlinedTextField(
                value = text,
                onValueChange = { typed ->
                    text = typed
                    typed.toDoubleOrNull()?.let { state.value = RawValue.Number(it) }
                },
                label = { Text(field.label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is BooleanField -> {
            val current = state.value as? RawValue.Flag ?: RawValue.Flag(false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = current.value, onCheckedChange = { state.value = RawValue.Flag(it) })
                Text(field.label)
            }
        }

        is EnumField -> {
            val current = (state.value as? RawValue.Text)?.value
            Column {
                Text(field.label, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field.options.forEach { option ->
                        FilterChip(
                            selected = current == option,
                            onClick = { state.value = RawValue.Text(option) },
                            label = { Text(option) },
                        )
                    }
                }
            }
        }

        is TimestampField -> Unit
    }
}
