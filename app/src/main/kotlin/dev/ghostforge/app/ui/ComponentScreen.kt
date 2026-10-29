package dev.ghostforge.app.ui

import androidx.compose.runtime.Composable
import dev.ghostforge.core.runtime.RawValue
import dev.ghostforge.core.runtime.RecordRow
import dev.ghostforge.core.spec.AssignmentBoard
import dev.ghostforge.core.spec.Checklist
import dev.ghostforge.core.spec.Component
import dev.ghostforge.core.spec.Dataset
import dev.ghostforge.core.spec.RecordForm
import dev.ghostforge.core.spec.RecordList
import dev.ghostforge.core.spec.StatusBoard
import dev.ghostforge.core.spec.StatusCounter

/** Dispatches one [Component] to its renderer. Six branches, exhaustive by construction. */
@Composable
fun ComponentScreen(
    component: Component,
    dataset: Dataset,
    records: List<RecordRow>,
    onCreateRecord: (Map<String, RawValue>) -> Unit,
    onFieldEdit: (recordId: String, fieldId: String, value: RawValue) -> Unit,
) {
    when (component) {
        is RecordForm -> RecordFormScreen(dataset, onCreateRecord)
        is RecordList -> RecordListScreen(dataset, component.columns, records)
        is StatusCounter -> StatusCounterScreen(dataset, component.groupBy, records)
        is Checklist -> ChecklistScreen(dataset, component.titleField, component.doneField, records, onFieldEdit)
        is StatusBoard -> StatusBoardScreen(dataset, component.groupBy, component.columns, records)
        is AssignmentBoard -> AssignmentBoardScreen(dataset, component.assignedTo, component.columns, records)
    }
}
