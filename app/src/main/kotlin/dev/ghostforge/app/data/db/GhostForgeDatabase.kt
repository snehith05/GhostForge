package dev.ghostforge.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * One database file per installed mini-app. Isolation at the file level, not
 * just the table level, means a wiped/uninstalled app's data is a single file
 * delete and two unrelated apps can never collide on a dataset name.
 */
@Database(entities = [ChangeLogEntry::class], version = 1, exportSchema = true)
abstract class GhostForgeDatabase : RoomDatabase() {
    abstract fun changeLogDao(): ChangeLogDao

    companion object {
        /** `appId` is already validated by [dev.ghostforge.core.spec.Limits.APP_ID] before this is called. */
        fun open(context: Context, appId: String): GhostForgeDatabase =
            Room.databaseBuilder(context, GhostForgeDatabase::class.java, "app_$appId.db").build()
    }
}
