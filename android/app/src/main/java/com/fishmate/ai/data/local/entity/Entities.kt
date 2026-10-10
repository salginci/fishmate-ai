package com.fishmate.ai.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local mirror of the server's data, so the app is useful with no signal.
 *
 * Fishing happens where connectivity is poor, and the brief is explicit: the
 * angler must still be able to view tackle, sessions and catches, and record
 * notes, offline. So reads are served from Room and the network refreshes it
 * (docs/ARCHITECTURE.md -> "Offline-first").
 *
 * Ids are the server's string UUIDs. Rows created offline generate their own
 * UUID locally and keep it after sync, so nothing has to be re-keyed.
 */

@Entity(tableName = "tackle", indices = [Index("category")])
data class TackleEntity(
    @PrimaryKey val id: String,
    val category: String,
    val name: String,
    val brand: String? = null,
    val weightMinG: Double? = null,
    val weightMaxG: Double? = null,
    val lengthCm: Double? = null,
    val lineRating: String? = null,
    val fishingStylesCsv: String? = null,
    val targetSpeciesCsv: String? = null,
    val notes: String? = null,
    val isFavourite: Boolean = false,
    val aiIdentified: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    val fishingStyles: List<String>
        get() = fishingStylesCsv?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
}

@Entity(tableName = "sessions", indices = [Index("startedAtEpochMs"), Index("status")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val status: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val locationLabel: String? = null,
    val targetSpeciesCsv: String? = null,
    val fishingStyle: String? = null,
    val notes: String? = null,
    val summary: String? = null,
    val catchCount: Int = 0,
    val conditionsSummary: String? = null,
) {
    val targetSpecies: List<String>
        get() = targetSpeciesCsv?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
}

@Entity(tableName = "catches", indices = [Index("caughtAtEpochMs"), Index("speciesKey")])
data class CatchEntity(
    @PrimaryKey val id: String,
    val sessionId: String? = null,
    val speciesKey: String? = null,
    val speciesLabel: String,
    val aiConfidence: Double? = null,
    val aiReasoning: String? = null,
    val speciesConfirmedByUser: Boolean = false,
    val lengthCm: Double? = null,
    val weightG: Double? = null,
    val released: Boolean = true,
    val lureLabel: String? = null,
    val technique: String? = null,
    val caughtAtEpochMs: Long,
    val locationLabel: String? = null,
    val weatherSummary: String? = null,
    val notes: String? = null,
    val photoLocalUri: String? = null,
)

/**
 * A write that could not reach the server yet.
 *
 * The brief is explicit that data must not be silently lost. Rather than
 * failing a catch log because a wave took the signal, the write is applied
 * locally and queued here, then replayed by
 * [com.fishmate.ai.data.repository.SyncManager].
 */
@Entity(tableName = "pending_ops", indices = [Index("createdAtEpochMs")])
data class PendingOpEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    /** One of [PendingOpKind]. */
    val kind: String,
    /** Local row this refers to, so the UI can show "pending". */
    val targetId: String,
    /** JSON body to replay. */
    val payloadJson: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null,
)

object PendingOpKind {
    const val CREATE_CATCH = "create_catch"
    const val UPDATE_CATCH = "update_catch"
    const val CREATE_TACKLE = "create_tackle"
    const val UPDATE_TACKLE = "update_tackle"
    const val ADD_OBSERVATION = "add_observation"
    const val FINISH_SESSION = "finish_session"
}
