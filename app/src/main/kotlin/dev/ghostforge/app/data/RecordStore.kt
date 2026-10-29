package dev.ghostforge.app.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.ghostforge.core.runtime.RawValue
import dev.ghostforge.core.runtime.RecordRow
import dev.ghostforge.core.spec.BooleanField
import dev.ghostforge.core.spec.Dataset
import dev.ghostforge.core.spec.EnumField
import dev.ghostforge.core.spec.Field
import dev.ghostforge.core.spec.NumberField
import dev.ghostforge.core.spec.TextField
import dev.ghostforge.core.spec.TimestampField
import java.util.UUID

/**
 * Stores records in a real SQLite table per dataset, one column per field --
 * not a generic entity/attribute/value table. This is what
 * [dev.ghostforge.core.spec.Limits.IDENTIFIER]'s own doc comment anticipates:
 * identifiers become SQLite column names and storage keys.
 *
 * Room cannot generate entities for a schema it only learns at runtime, so
 * this talks to the database's [SupportSQLiteDatabase] handle directly. The
 * static [dev.ghostforge.app.data.db.ChangeLogEntry] table stays a normal Room
 * entity; only the dynamic per-dataset tables go through raw SQL here.
 *
 * Table and column names are built by direct string interpolation. That is
 * safe only because every identifier reaching this class already passed
 * [dev.ghostforge.core.spec.SpecGate.admit]: [dev.ghostforge.core.spec.Limits.IDENTIFIER]
 * forbids quotes, semicolons and backticks, so SQL injection via a dataset or
 * field id is structurally impossible by the time a [Dataset] exists. Values
 * are never interpolated -- they are always bound as query arguments.
 */
class RecordStore(private val db: SupportSQLiteDatabase) {

    private fun tableName(datasetId: String) = "ds_$datasetId"

    private fun sqlType(field: Field): String = when (field) {
        is TextField, is EnumField -> "TEXT"
        is NumberField -> "REAL"
        is BooleanField, is TimestampField -> "INTEGER"
    }

    /** Idempotent; safe to call every time a dataset is opened. */
    fun ensureTable(dataset: Dataset) {
        val columns = dataset.fields.joinToString(",\n  ") { f -> columnDefinition(f) }
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS "${tableName(dataset.id)}" (
              record_id TEXT NOT NULL PRIMARY KEY,
              $columns
            )
            """.trimIndent(),
        )
    }

    private fun columnDefinition(field: Field): String {
        val notNull = if (field.required) " NOT NULL" else ""
        val enumCheck = (field as? EnumField)?.let { ef ->
            val options = ef.options.joinToString(", ") { "'$it'" }
            " CHECK(\"${field.id}\" IS NULL OR \"${field.id}\" IN ($options))"
        }.orEmpty()
        return "\"${field.id}\" ${sqlType(field)}$notNull$enumCheck"
    }

    /** Inserts a new record, returning its generated id. */
    fun insertRecord(dataset: Dataset, values: Map<String, RawValue>): String {
        val recordId = UUID.randomUUID().toString()
        val fieldIds = dataset.fields.map { it.id }
        val columnList = (listOf("record_id") + fieldIds).joinToString(", ") { "\"$it\"" }
        val placeholders = (listOf("record_id") + fieldIds).joinToString(", ") { "?" }
        val args = arrayOf<Any?>(recordId, *fieldIds.map { toBindArg(values[it] ?: RawValue.Null) }.toTypedArray())
        db.execSQL("INSERT INTO \"${tableName(dataset.id)}\" ($columnList) VALUES ($placeholders)", args)
        return recordId
    }

    fun updateField(datasetId: String, recordId: String, fieldId: String, value: RawValue) {
        db.execSQL(
            "UPDATE \"${tableName(datasetId)}\" SET \"$fieldId\" = ? WHERE record_id = ?",
            arrayOf(toBindArg(value), recordId),
        )
    }

    fun queryRecords(dataset: Dataset): List<RecordRow> {
        val fieldIds = dataset.fields.map { it.id }
        val fieldsById = dataset.fields.associateBy { it.id }
        val columnList = (listOf("record_id") + fieldIds).joinToString(", ") { "\"$it\"" }
        val cursor = db.query("SELECT $columnList FROM \"${tableName(dataset.id)}\"")
        return cursor.use {
            val out = mutableListOf<RecordRow>()
            while (it.moveToNext()) {
                val recordId = it.getString(it.getColumnIndexOrThrow("record_id"))
                val values = fieldIds.associateWith { fid -> fromCursor(it, fid, fieldsById.getValue(fid)) }
                out += RecordRow(recordId, values)
            }
            out
        }
    }

    private fun toBindArg(value: RawValue): Any? = when (value) {
        is RawValue.Text -> value.value
        is RawValue.Number -> value.value
        is RawValue.Flag -> if (value.value) 1L else 0L
        is RawValue.Moment -> value.value
        RawValue.Null -> null
    }

    private fun fromCursor(cursor: Cursor, columnName: String, field: Field): RawValue {
        val index = cursor.getColumnIndexOrThrow(columnName)
        if (cursor.isNull(index)) return RawValue.Null
        return when (field) {
            is TextField, is EnumField -> RawValue.Text(cursor.getString(index))
            is NumberField -> RawValue.Number(cursor.getDouble(index))
            is BooleanField -> RawValue.Flag(cursor.getLong(index) != 0L)
            is TimestampField -> RawValue.Moment(cursor.getLong(index))
        }
    }
}
