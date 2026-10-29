package dev.ghostforge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ghostforge.app.ui.ComponentScreen
import dev.ghostforge.app.viewmodel.AppRuntimeViewModel
import dev.ghostforge.app.viewmodel.WriteResult
import dev.ghostforge.core.spec.Admission
import dev.ghostforge.core.spec.AssignmentBoard
import dev.ghostforge.core.spec.Checklist
import dev.ghostforge.core.spec.Component
import dev.ghostforge.core.spec.RecordForm
import dev.ghostforge.core.spec.RecordList
import dev.ghostforge.core.spec.SpecGate
import dev.ghostforge.core.spec.StatusBoard
import dev.ghostforge.core.spec.StatusCounter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    GhostForgeApp()
                }
            }
        }
    }
}

@Composable
private fun GhostForgeApp() {
    // No generator (milestone 3) or install flow (milestone 4) exists yet, so
    // a bundled sample is the only way to get a spec in front of the runtime.
    val admission = remember { SpecGate.admit(SampleSpecs.WATER_TRACKER) }
    when (admission) {
        is Admission.Rejected ->
            Text("Sample spec was rejected:\n${admission.report}", Modifier.padding(16.dp))

        is Admission.Admitted -> AdmittedApp(admission)
    }
}

@Composable
private fun AdmittedApp(admission: Admission.Admitted) {
    val spec = admission.spec
    val context = LocalContext.current.applicationContext
    val viewModel: AppRuntimeViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppRuntimeViewModel(spec, context) as T
        },
    )
    val records by viewModel.records.collectAsState()
    var selected by remember { mutableIntStateOf(0) }
    val datasetsById = remember(spec) { spec.datasets.associateBy { it.id } }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // A rejected write is invisible otherwise: RenderGate correctly keeps bad
    // data out of storage, but silence makes "Add" look broken rather than
    // telling the user why nothing happened.
    val reportResult: (WriteResult) -> Unit = { result ->
        if (result is WriteResult.Rejected) {
            scope.launch { snackbarHostState.showSnackbar(result.reason.detail) }
        }
    }

    Scaffold(
        topBar = {
            ScrollableTabRow(selectedTabIndex = selected) {
                spec.components.forEachIndexed { index, component ->
                    Tab(
                        selected = selected == index,
                        onClick = { selected = index },
                        text = { Text(componentLabel(component)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val component = spec.components[selected]
        val dataset = datasetsById.getValue(component.dataset)
        Box(Modifier.padding(padding).fillMaxSize()) {
            ComponentScreen(
                component = component,
                dataset = dataset,
                records = records[dataset.id].orEmpty(),
                onCreateRecord = { input -> viewModel.createRecord(dataset.id, input, reportResult) },
                onFieldEdit = { recordId, fieldId, value ->
                    viewModel.updateField(dataset.id, recordId, fieldId, value, reportResult)
                },
            )
        }
    }
}

private fun componentLabel(component: Component): String = when (component) {
    is RecordForm -> "Add"
    is RecordList -> "List"
    is StatusCounter -> "Counts"
    is Checklist -> "Checklist"
    is StatusBoard -> "Status board"
    is AssignmentBoard -> "Assignments"
}
