package dev.ghostforge.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChangeLogDao {
    /** IGNORE, not REPLACE: `eventId` is the dedup key, so a re-delivered event is a no-op. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun record(entry: ChangeLogEntry)

    @Query(
        "SELECT * FROM change_log WHERE datasetId = :datasetId AND recordId = :recordId " +
            "AND fieldId = :fieldId ORDER BY wallClockMillis ASC",
    )
    suspend fun historyFor(datasetId: String, recordId: String, fieldId: String): List<ChangeLogEntry>

    @Query("SELECT COUNT(*) FROM change_log")
    suspend fun count(): Int
}
