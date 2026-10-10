package com.fishmate.ai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.fishmate.ai.data.local.dao.CatchDao
import com.fishmate.ai.data.local.dao.PendingOpDao
import com.fishmate.ai.data.local.dao.SessionDao
import com.fishmate.ai.data.local.dao.TackleDao
import com.fishmate.ai.data.local.entity.CatchEntity
import com.fishmate.ai.data.local.entity.PendingOpEntity
import com.fishmate.ai.data.local.entity.SessionEntity
import com.fishmate.ai.data.local.entity.TackleEntity

@Database(
    entities = [
        TackleEntity::class,
        SessionEntity::class,
        CatchEntity::class,
        PendingOpEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class FishMateDatabase : RoomDatabase() {
    abstract fun tackleDao(): TackleDao
    abstract fun sessionDao(): SessionDao
    abstract fun catchDao(): CatchDao
    abstract fun pendingOpDao(): PendingOpDao

    companion object {
        private const val NAME = "fishmate.db"

        fun build(context: Context): FishMateDatabase =
            Room.databaseBuilder(context, FishMateDatabase::class.java, NAME)
                // No destructive fallback: this cache holds the angler's
                // unsynced catches. Losing them on a schema bump would be data
                // loss, so future versions must ship a real migration.
                .build()
    }
}
