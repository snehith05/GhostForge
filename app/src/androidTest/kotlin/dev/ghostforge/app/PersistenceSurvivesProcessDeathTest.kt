package dev.ghostforge.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ghostforge.app.data.RecordStore
import dev.ghostforge.app.data.db.ChangeLogEntry
import dev.ghostforge.app.data.db.GhostForgeDatabase
import dev.ghostforge.app.data.db.RawValueColumns
import dev.ghostforge.core.runtime.RawValue
import dev.ghostforge.core.spec.Dataset
import dev.ghostforge.core.spec.TextField
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A local database file is the one piece of milestone 2 that must survive
 * process death on its own -- nothing here restores in-memory state, only
 * what actually reached disk. Simulated by closing the [GhostForgeDatabase]
 * and reopening a fresh instance against the same file, rather than an
 * actual OS-level kill, which instrumented test code cannot trigger on
 * itself.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceSurvivesProcessDeathTest {

    private val dataset = Dataset("items", listOf(TextField("name", "Name", required = true)))

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun recordAndChangeLogSurviveReopeningTheDatabase() = runBlocking {
        val appId = "persistence-test-${UUID.randomUUID()}"
        val eventId = UUID.randomUUID().toString()
        val recordId: String

        val firstOpen = GhostForgeDatabase.open(context(), appId)
        try {
            val store = RecordStore(firstOpen.openHelper.writableDatabase)
            store.ensureTable(dataset)
            recordId = store.insertRecord(dataset, mapOf("name" to RawValue.Text("surveys the household")))
            firstOpen.changeLogDao().record(
                ChangeLogEntry(
                    eventId = eventId,
                    originDeviceId = "device-a",
                    datasetId = dataset.id,
                    recordId = recordId,
                    fieldId = "name",
                    mergePolicy = "last_write_wins",
                    value = RawValueColumns(text = "surveys the household"),
                    wallClockMillis = 1000L,
                    recordedAt = 1000L,
                ),
            )
        } finally {
            firstOpen.close() // simulates process death: nothing past this line is in memory
        }

        val secondOpen = GhostForgeDatabase.open(context(), appId)
        try {
            val store = RecordStore(secondOpen.openHelper.writableDatabase)
            val records = store.queryRecords(dataset)
            assertEquals(1, records.size)
            assertEquals(RawValue.Text("surveys the household"), records.single().values["name"])

            val history = secondOpen.changeLogDao().historyFor(dataset.id, recordId, "name")
            assertEquals(1, history.size)
            assertEquals(eventId, history.single().eventId)
        } finally {
            secondOpen.close()
            context().deleteDatabase("app_$appId.db")
        }
    }

    @Test
    fun redeliveredEventIsIgnoredNotDuplicated() = runBlocking {
        val appId = "dedup-test-${UUID.randomUUID()}"
        val db = GhostForgeDatabase.open(context(), appId)
        try {
            val entry = ChangeLogEntry(
                eventId = "fixed-event-id",
                originDeviceId = "device-a",
                datasetId = dataset.id,
                recordId = "r1",
                fieldId = "name",
                mergePolicy = "last_write_wins",
                value = RawValueColumns(text = "first delivery"),
                wallClockMillis = 1000L,
                recordedAt = 1000L,
            )
            db.changeLogDao().record(entry)
            db.changeLogDao().record(entry) // re-delivery, once milestone 5's transport exists
            assertEquals(1, db.changeLogDao().count())
        } finally {
            db.close()
            context().deleteDatabase("app_$appId.db")
        }
    }
}
