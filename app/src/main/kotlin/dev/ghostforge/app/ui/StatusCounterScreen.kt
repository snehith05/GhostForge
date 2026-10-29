package dev.ghostforge.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ghostforge.core.runtime.RecordRow
import dev.ghostforge.core.runtime.bucketByEnum
import dev.ghostforge.core.spec.Dataset
import dev.ghostforge.core.spec.EnumField

@Composable
fun StatusCounterScreen(dataset: Dataset, groupBy: String, records: List<RecordRow>) {
    val field = dataset.fields.firstOrNull { it.id == groupBy } as? EnumField ?: return
    val buckets = bucketByEnum(field, records)
    LazyColumn(Modifier.padding(16.dp)) {
        items(buckets.entries.toList(), key = { it.key }) { (lane, laneRecords) ->
            Card(Modifier.padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp)) {
                    Text(lane)
                    Text(" — ${laneRecords.size}")
                }
            }
        }
    }
}
