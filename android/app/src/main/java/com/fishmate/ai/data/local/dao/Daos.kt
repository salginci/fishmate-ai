package com.fishmate.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.fishmate.ai.data.local.entity.CatchEntity
import com.fishmate.ai.data.local.entity.PendingOpEntity
import com.fishmate.ai.data.local.entity.SessionEntity
import com.fishmate.ai.data.local.entity.TackleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TackleDao {
    @Query("SELECT * FROM tackle ORDER BY category, isFavourite DESC, name")
    fun observeAll(): Flow<List<TackleEntity>>

    @Query("SELECT * FROM tackle WHERE category = :category ORDER BY name")
    fun observeByCategory(category: String): Flow<List<TackleEntity>>

    @Query("SELECT * FROM tackle WHERE id = :id")
    suspend fun byId(id: String): TackleEntity?

    @Query("SELECT COUNT(*) FROM tackle")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(items: List<TackleEntity>)

    @Upsert
    suspend fun upsert(item: TackleEntity)

    @Delete
    suspend fun delete(item: TackleEntity)

    @Query("DELETE FROM tackle WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Replace the cache wholesale after a successful refresh. */
    @Query("DELETE FROM tackle WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<String>)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE status = 'active' ORDER BY startedAtEpochMs DESC LIMIT 1")
    fun observeActive(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE status = 'active' ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun activeOnce(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Upsert
    suspend fun upsert(sessions: List<SessionEntity>)

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface CatchDao {
    @Query("SELECT * FROM catches ORDER BY caughtAtEpochMs DESC")
    fun observeAll(): Flow<List<CatchEntity>>

    @Query("SELECT * FROM catches ORDER BY caughtAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CatchEntity>>

    @Query("SELECT * FROM catches WHERE sessionId = :sessionId ORDER BY caughtAtEpochMs")
    fun observeForSession(sessionId: String): Flow<List<CatchEntity>>

    @Query("SELECT * FROM catches WHERE id = :id")
    fun observeById(id: String): Flow<CatchEntity?>

    @Query("SELECT * FROM catches WHERE id = :id")
    suspend fun byId(id: String): CatchEntity?

    @Query("SELECT COUNT(*) FROM catches")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(items: List<CatchEntity>)

    @Upsert
    suspend fun upsert(item: CatchEntity)

    @Query("DELETE FROM catches WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface PendingOpDao {
    @Query("SELECT * FROM pending_ops ORDER BY createdAtEpochMs")
    suspend fun all(): List<PendingOpEntity>

    @Query("SELECT COUNT(*) FROM pending_ops")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(op: PendingOpEntity): Long

    @Query("DELETE FROM pending_ops WHERE localId = :localId")
    suspend fun delete(localId: Long)

    @Query("UPDATE pending_ops SET attempts = attempts + 1, lastError = :error WHERE localId = :localId")
    suspend fun recordFailure(localId: Long, error: String?)

    @Query("DELETE FROM pending_ops")
    suspend fun clear()
}
