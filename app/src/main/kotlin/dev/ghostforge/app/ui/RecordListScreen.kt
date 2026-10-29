package dev.ghostforge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ghostforge.core.runtime.RawValue
import dev.ghostforge.core.runtime.RecordRow
import dev.ghostforge.core.spec.Dataset

@Composable
fun RecordListScreen(dataset: Dataset, columns: List<String>, records: List<RecordRow>) {
    val fieldsById = dataset.fields.associateBy { it.id }
    val shown = columns.ifEmpty { dataset.fields.map { it.id } }.mapNotNull { fieldsById[it] }
    LazyColumn {
        items(records, key = { it.id }) { record ->
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                shown.forEach { field ->
                    Text(safeDisplayText(field, record.values[field.id] ?: RawValue.Null))
                }
            }
        }
    }
}
