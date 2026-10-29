package dev.ghostforge.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import dev.ghostforge.core.runtime.RawValue

/**
 * One field-level edit, append-only. `eventId` is the sole dedup key: it is
 * generated once at the origin device and never reused, so re-delivery of the
 * same event (expected once milestone 5's sync transport exists) is a no-op
 * insert-or-ignore rather than a double-apply -- see [ChangeLogDao.record].
 *
 * `mergePolicy` and `wallClockMillis` are snapshotted at write time rather
 * than looked up live from the current spec, so a later edit to the field's
 * merge policy does not retroactively reinterpret history written under the
 * old one.
 *
 * Deliberately NOT included yet: a logical/vector clock. Milestone 5 owns the
 * merge algorithm and may need one for cross-device ordering; adding it now,
 * with no consumer, would be guessing at a shape milestone 5 hasn't decided.
 * See PROGRESS.md.
 */
@Entity(
    tableName = "change_log",
    primaryKeys = ["eventId"],
    indices = [Index(value = ["datasetId", "recordId", "fieldId"])],
)
data class ChangeLogEntry(
    val eventId: String,
    val originDeviceId: String,
    val datasetId: String,
    val recordId: String,
    val fieldId: String,
    val mergePolicy: String,
    @Embedded val value: RawValueColumns,
    val wallClockMillis: Long,
    val recordedAt: Long,
)

/**
 * [RawValue] flattened into nullable SQLite-native columns -- the same
 * typed-column approach [dev.ghostforge.app.data.RecordStore] uses for
 * records, kept consistent rather than encoding values as a JSON blob here
 * and real columns there. Exactly one property is non-null, except for
 * [RawValue.Null] where all four are null.
 */
data class RawValueColumns(
    val text: String? = null,
    val number: Double? = null,
    val flag: Boolean? = null,
    val moment: Long? = null,
)

fun RawValue.toColumns(): RawValueColumns = when (this) {
    is RawValue.Text -> RawValueColumns(text = value)
    is RawValue.Number -> RawValueColumns(number = value)
    is RawValue.Flag -> RawValueColumns(flag = value)
    is RawValue.Moment -> RawValueColumns(moment = value)
    RawValue.Null -> RawValueColumns()
}

fun RawValueColumns.toRawValue(): RawValue = when {
    text != null -> RawValue.Text(text)
    number != null -> RawValue.Number(number)
    flag != null -> RawValue.Flag(flag)
    moment != null -> RawValue.Moment(moment)
    else -> RawValue.Null
}
