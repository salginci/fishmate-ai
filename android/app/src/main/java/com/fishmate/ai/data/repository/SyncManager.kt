package com.fishmate.ai.data.repository

import com.fishmate.ai.core.AppResult
import com.fishmate.ai.core.Log2
import com.fishmate.ai.data.local.FishMateDatabase
import com.fishmate.ai.data.local.entity.PendingOpEntity
import com.fishmate.ai.data.local.entity.PendingOpKind
import com.fishmate.ai.data.remote.FishMateApi
import com.fishmate.ai.data.remote.dto.CatchCreate
import com.fishmate.ai.data.remote.dto.CatchUpdate
import com.fishmate.ai.data.remote.dto.ObservationCreate
import com.fishmate.ai.data.remote.dto.SessionFinishRequest
import com.fishmate.ai.data.remote.dto.TackleItemCreate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Replays writes that were made while offline, and signals when the local
 * mirror needs refreshing.
 *
 * The rule the brief sets is "do not silently lose data". So a write that
 * cannot reach the server is applied to Room immediately and queued here; the
 * UI shows it as pending, and [syncPending] drains the queue when connectivity
 * returns.
 *
 * Ordering is preserved (a catch must exist before its correction applies) by
 * draining strictly oldest-first and stopping at the first failure rather than
 * skipping ahead.
 */
class SyncManager(
    private val api: FishMateApi,
    private val database: FishMateDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val mutex = Mutex()

    private val _refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Emits when something changed server-side and caches should reload. */
    val refreshRequests: Flow<Unit> = _refreshRequests

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    val pendingCount: Flow<Int> = database.pendingOpDao().observeCount()

    fun requestRefresh() {
        _refreshRequests.tryEmit(Unit)
    }

    suspend fun enqueue(kind: String, targetId: String, payloadJson: String) {
        database.pendingOpDao().insert(
            PendingOpEntity(kind = kind, targetId = targetId, payloadJson = payloadJson),
        )
        Log2.i(TAG, "queued offline op kind=$kind target=$targetId")
    }

    /**
     * Drain the queue.
     *
     * Returns the number of operations successfully replayed. Stops at the
     * first failure: a later op may depend on an earlier one, and retrying out
     * of order could apply an edit to a row that does not exist yet.
     */
    suspend fun syncPending(): Int = mutex.withLock {
        val dao = database.pendingOpDao()
        val ops = dao.all()
        if (ops.isEmpty()) return 0

        _syncing.value = true
        var replayed = 0
        try {
            for (op in ops) {
                if (op.attempts >= MAX_ATTEMPTS) {
                    // Give up loudly rather than retrying forever; the row
                    // stays in Room so the angler keeps their data.
                    Log2.w(TAG, "dropping op after $MAX_ATTEMPTS attempts kind=${op.kind}")
                    dao.delete(op.localId)
                    continue
                }
                val outcome = replay(op)
                if (outcome) {
                    dao.delete(op.localId)
                    replayed += 1
                } else {
                    dao.recordFailure(op.localId, "replay failed")
                    break
                }
            }
        } finally {
            _syncing.value = false
        }
        if (replayed > 0) {
            Log2.i(TAG, "replayed $replayed offline op(s)")
            requestRefresh()
        }
        return replayed
    }

    private suspend fun replay(op: PendingOpEntity): Boolean = when (op.kind) {
        PendingOpKind.CREATE_CATCH -> {
            val body = json.decodeFromString(CatchCreate.serializer(), op.payloadJson)
            apiCall(TAG) { api.createCatch(body) }.isOk
        }
        PendingOpKind.UPDATE_CATCH -> {
            val body = json.decodeFromString(CatchUpdate.serializer(), op.payloadJson)
            apiCall(TAG) { api.updateCatch(op.targetId, body) }.isOk
        }
        PendingOpKind.CREATE_TACKLE -> {
            val body = json.decodeFromString(TackleItemCreate.serializer(), op.payloadJson)
            apiCall(TAG) { api.createTackle(body) }.isOk
        }
        PendingOpKind.UPDATE_TACKLE -> {
            val body = json.decodeFromString(TackleItemCreate.serializer(), op.payloadJson)
            apiCall(TAG) { api.updateTackle(op.targetId, body) }.isOk
        }
        PendingOpKind.ADD_OBSERVATION -> {
            val body = json.decodeFromString(ObservationCreate.serializer(), op.payloadJson)
            apiCall(TAG) { api.addObservation(op.targetId, body) }.isOk
        }
        PendingOpKind.FINISH_SESSION -> {
            val body = json.decodeFromString(SessionFinishRequest.serializer(), op.payloadJson)
            apiCall(TAG) { api.finishSession(op.targetId, body) }.isOk
        }
        else -> {
            Log2.w(TAG, "unknown pending op kind=${op.kind}, discarding")
            true // unknown kinds would otherwise block the queue forever
        }
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val MAX_ATTEMPTS = 5
    }
}
