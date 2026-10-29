package dev.ghostforge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ghostforge.core.runtime.RawValue
import dev.ghostforge.core.runtime.RecordRow
import dev.ghostforge.core.runtime.bucketByEnum
import dev.ghostforge.core.spec.Dataset
import dev.ghostforge.core.spec.EnumField

/**
 * [StatusBoard][dev.ghostforge.core.spec.StatusBoard] and
 * [AssignmentBoard][dev.ghostforge.core.spec.AssignmentBoard] both lay full
 * records out in enum-keyed lanes; only which field they group by differs, so
 * they share this renderer.
 */
@Composable
fun StatusBoardScreen(dataset: Dataset, groupBy: String, columns: List<String>, records: List<RecordRow>) {
    val field = dataset.fields.firstOrNull { it.id == groupBy } as? EnumField ?: return
    LaneBoard(dataset, field, columns, records)
}

@Composable
fun AssignmentBoardScreen(dataset: Dataset, assignedTo: String, columns: List<String>, records: List<RecordRow>) {
    val field = dataset.fields.firstOrNull { it.id == assignedTo } as? EnumField ?: return
    LaneBoard(dataset, field, columns, records)
}

@Composable
private fun LaneBoard(dataset: Dataset, groupField: EnumField, columns: List<String>, records: List<RecordRow>) {
    val buckets = bucketByEnum(groupField, records)
    val fieldsById = dataset.fields.associateBy { it.id }
    val shown = columns.ifEmpty { dataset.fields.map { it.id } }.mapNotNull { fieldsById[it] }

    LazyRow(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(buckets.entries.toList(), key = { it.key }) { (lane, laneRecords) ->
            Column(Modifier.width(220.dp).padding(8.dp)) {
                Text("$lane (${laneRecords.size})", style = MaterialTheme.typography.titleSmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(laneRecords, key = { it.id }) { record ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp)) {
                                shown.forEach { field ->
                                    Text(safeDisplayText(field, record.values[field.id] ?: RawValue.Null))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
