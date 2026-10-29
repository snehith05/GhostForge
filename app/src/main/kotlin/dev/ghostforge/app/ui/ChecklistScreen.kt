package dev.ghostforge.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ghostforge.core.runtime.FieldRender
import dev.ghostforge.core.runtime.RawValue
import dev.ghostforge.core.runtime.RecordRow
import dev.ghostforge.core.runtime.RenderGate
import dev.ghostforge.core.spec.BooleanField
import dev.ghostforge.core.spec.Dataset

/**
 * `doneFieldDecl` is cast, not checked, because [dev.ghostforge.core.spec.SpecValidator]
 * already guarantees a [Checklist][dev.ghostforge.core.spec.Checklist]'s
 * `done_field` names a [BooleanField] -- that is a fact about the admitted
 * spec, fixed forever, not stored data that can go stale. [RenderGate] still
 * re-checks the *value* in that column, which can.
 */
@Composable
fun ChecklistScreen(
    dataset: Dataset,
    titleField: String,
    doneField: String,
    records: List<RecordRow>,
    onFieldEdit: (recordId: String, fieldId: String, value: RawValue) -> Unit,
) {
    val titleFieldDecl = dataset.fields.first { it.id == titleField }
    val doneFieldDecl = dataset.fields.first { it.id == doneField } as BooleanField
    LazyColumn {
        items(records, key = { it.id }) { record ->
            val doneRaw = record.values[doneField] ?: RawValue.Null
            val checkedOk = RenderGate.checkField(doneFieldDecl, doneRaw) as? FieldRender.Ok
            val checked = (checkedOk?.value as? RawValue.Flag)?.value ?: false
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onFieldEdit(record.id, doneField, RawValue.Flag(it)) },
                )
                Text(safeDisplayText(titleFieldDecl, record.values[titleField] ?: RawValue.Null))
            }
        }
    }
}
