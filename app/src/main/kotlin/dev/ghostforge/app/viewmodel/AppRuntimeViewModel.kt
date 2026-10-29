package dev.ghostforge.app.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ghostforge.app.data.RecordStore
import dev.ghostforge.app.data.db.ChangeLogEntry
import dev.ghostforge.app.data.db.GhostForgeDatabase
import dev.ghostforge.app.data.db.toColumns
import dev.ghostforge.core.runtime.FieldRender
import dev.ghostforge.core.runtime.RawValue
import dev.ghostforge.core.runtime.RecordRow
import dev.ghostforge.core.runtime.RenderGate
import dev.ghostforge.core.spec.AppSpec
import dev.ghostforge.core.spec.Dataset
import dev.ghostforge.core.spec.TimestampField
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface WriteResult {
    data object Accepted : WriteResult
    data class Rejected(val reason: FieldRender.Unsafe) : WriteResult
}

/**
 * Owns one admitted app's storage and exposes its records reactively.
 *
 * Every write -- not just every read -- goes through [RenderGate] first.
 * Milestone 2 requires the runtime to "reject at render what the validator
 * rejected at admission, and fail safe on bad data"; the same bound a field
 * enforces on display must hold on the way in, or bad data would only ever
 * surface one write later, on someone else's screen.
 */
class AppRuntimeViewModel(private val spec: AppSpec, context: Context) : ViewModel() {

    private val datasetsById: Map<String, Dataset> = spec.datasets.associateBy { it.id }
    private val db: GhostForgeDatabase = GhostForgeDatabase.open(context, spec.appId)

    // Lazy, not eager: db.openHelper.writableDatabase performs real disk I/O
    // to open the file (unlike a suspend DAO call, Room does not dispatch this
    // raw escape hatch off-thread for us). Every access below happens inside
    // viewModelScope.launch(Dispatchers.IO), so deferring construction to
    // first use keeps that I/O off the main thread that constructs this class.
    private val recordStore by lazy { RecordStore(db.openHelper.writableDatabase) }

    // Placeholder device identity. Milestone 4 owns real cryptographic
    // identity; ANDROID_ID only needs to be stable enough to attribute events
    // to "this install" until then.
    @SuppressLint("HardwareIds")
    private val originDeviceId: String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    private val _records = MutableStateFlow<Map<String, List<RecordRow>>>(emptyMap())
    val records: StateFlow<Map<String, List<RecordRow>>> = _records.asStateFlow()

    // createRecord/updateField run as their own independent launch(Dispatchers.IO)
    // calls, with no ordering guarantee against init's table setup otherwise.
    // Awaiting this first makes "tables exist before any write" structural
    // rather than "fast enough in practice."
    private val ready = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            spec.datasets.forEach { recordStore.ensureTable(it) }
            refreshAll()
            ready.complete(Unit)
        }
    }

    private suspend fun refreshAll() {
        _records.value = spec.datasets.associate { it.id to recordStore.queryRecords(it) }
    }

    private suspend fun refresh(datasetId: String) {
        val dataset = datasetsById.getValue(datasetId)
        val updated = recordStore.queryRecords(dataset)
        // update{}, not value = value + (...): refresh for one dataset must not
        // clobber a concurrent refresh of another dataset racing on the same read.
        _records.update { it + (datasetId to updated) }
    }

    fun createRecord(
        datasetId: String,
        input: Map<String, RawValue>,
        onResult: (WriteResult) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            ready.await()
            val dataset = datasetsById.getValue(datasetId)
            val now = System.currentTimeMillis()
            // Timestamp fields are system-set, never hand-typed into a form.
            val filled = input + dataset.fields.filterIsInstance<TimestampField>()
                .associate { it.id to RawValue.Moment(now) }

            val unsafe = dataset.fields.firstNotNullOfOrNull { field ->
                RenderGate.checkField(field, filled[field.id] ?: RawValue.Null) as? FieldRender.Unsafe
            }
            if (unsafe != null) {
                withContext(Dispatchers.Main) { onResult(WriteResult.Rejected(unsafe)) }
                return@launch
            }

            val recordId = recordStore.insertRecord(dataset, filled)
            filled.forEach { (fieldId, value) -> appendEvent(dataset, recordId, fieldId, value, now) }
            refresh(datasetId)
            withContext(Dispatchers.Main) { onResult(WriteResult.Accepted) }
        }
    }

    fun updateField(
        datasetId: String,
        recordId: String,
        fieldId: String,
        value: RawValue,
        onResult: (WriteResult) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            ready.await()
            val dataset = datasetsById.getValue(datasetId)
            val field = dataset.fields.first { it.id == fieldId }
            val check = RenderGate.checkField(field, value)
            if (check is FieldRender.Unsafe) {
                withContext(Dispatchers.Main) { onResult(WriteResult.Rejected(check)) }
                return@launch
            }
            recordStore.updateField(datasetId, recordId, fieldId, value)
            appendEvent(dataset, recordId, fieldId, value, System.currentTimeMillis())
            refresh(datasetId)
            withContext(Dispatchers.Main) { onResult(WriteResult.Accepted) }
        }
    }

    private suspend fun appendEvent(dataset: Dataset, recordId: String, fieldId: String, value: RawValue, now: Long) {
        val field = dataset.fields.first { it.id == fieldId }
        db.changeLogDao().record(
            ChangeLogEntry(
                eventId = UUID.randomUUID().toString(),
                originDeviceId = originDeviceId,
                datasetId = dataset.id,
                recordId = recordId,
                fieldId = fieldId,
                mergePolicy = field.merge.name,
                value = value.toColumns(),
                wallClockMillis = now,
                recordedAt = System.currentTimeMillis(),
            ),
        )
    }
}
