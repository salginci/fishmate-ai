package com.fishmate.ai.data.repository

import com.fishmate.ai.core.AppError
import com.fishmate.ai.core.AppResult
import com.fishmate.ai.core.Log2
import com.fishmate.ai.data.local.FishMateDatabase
import com.fishmate.ai.data.local.entity.CatchEntity
import com.fishmate.ai.data.local.entity.PendingOpKind
import com.fishmate.ai.data.local.entity.SessionEntity
import com.fishmate.ai.data.local.entity.TackleEntity
import com.fishmate.ai.data.remote.FishMateApi
import com.fishmate.ai.data.remote.dto.*
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * Offline-first access to tackle, sessions and catches.
 *
 * Reads always come from Room, so every screen renders instantly and works
 * with no signal. Writes go to Room first, then to the network; if the network
 * write fails the operation is queued for [SyncManager] rather than lost.
 *
 * This is one repository rather than three because the three aggregates are
 * written together — logging a catch touches the catch row *and* the session's
 * count — and splitting them would mean a cross-repository transaction.
 */
class FishingRepository(
    private val api: FishMateApi,
    private val database: FishMateDatabase,
    private val syncManager: SyncManager,
    private val json: Json = Json { encodeDefaults = true; explicitNulls = false },
) {
    private val tackleDao get() = database.tackleDao()
    private val sessionDao get() = database.sessionDao()
    private val catchDao get() = database.catchDao()

    // ---- observation (local, always available) ----------------------------
    fun observeTackle(): Flow<List<TackleEntity>> = tackleDao.observeAll()
    fun observeTackleCount(): Flow<Int> = tackleDao.observeCount()
    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()
    fun observeActiveSession(): Flow<SessionEntity?> = sessionDao.observeActive()
    fun observeSession(id: String): Flow<SessionEntity?> = sessionDao.observeById(id)
    fun observeCatches(): Flow<List<CatchEntity>> = catchDao.observeAll()
    fun observeRecentCatches(limit: Int = 5): Flow<List<CatchEntity>> =
        catchDao.observeRecent(limit)
    fun observeCatch(id: String): Flow<CatchEntity?> = catchDao.observeById(id)
    fun observeCatchesForSession(id: String): Flow<List<CatchEntity>> =
        catchDao.observeForSession(id)
    fun observeCatchCount(): Flow<Int> = catchDao.observeCount()
    fun observePendingWrites(): Flow<Int> = syncManager.pendingCount

    // ---- refresh ----------------------------------------------------------
    /**
     * Pull everything into the local mirror.
     *
     * Partial success is deliberate: if tackle loads but catches time out, the
     * angler still gets their tackle box. Only a total failure is reported.
     */
    suspend fun refreshAll(): AppResult<Unit> {
        var anySucceeded = false
        var lastError: AppError? = null

        when (val result = apiCall(TAG) { api.listTackle() }) {
            is AppResult.Ok -> {
                val entities = result.value.map { it.toEntity() }
                tackleDao.upsert(entities)
                if (entities.isNotEmpty()) tackleDao.deleteMissing(entities.map { it.id })
                anySucceeded = true
            }
            is AppResult.Err -> lastError = result.error
        }

        when (val result = apiCall(TAG) { api.listSessions(limit = 50) }) {
            is AppResult.Ok -> {
                sessionDao.upsert(result.value.map { it.toEntity() })
                anySucceeded = true
            }
            is AppResult.Err -> lastError = result.error
        }

        when (val result = apiCall(TAG) { api.listCatches(limit = 200) }) {
            is AppResult.Ok -> {
                catchDao.upsert(result.value.map { it.toEntity() })
                anySucceeded = true
            }
            is AppResult.Err -> lastError = result.error
        }

        // Active session detail carries the weather snapshot the list omits.
        when (val result = apiCall(TAG) { api.activeSession() }) {
            is AppResult.Ok -> sessionDao.upsert(result.value.toEntity())
            is AppResult.Err ->
                // 404 simply means no session is running.
                if (result.error.code != "not_found") lastError = result.error
        }

        return if (anySucceeded) AppResult.Ok(Unit)
        else AppResult.Err(lastError ?: AppError.offline())
    }

    suspend fun refreshSession(id: String): AppResult<SessionDetailDto> =
        apiCall(TAG) { api.session(id) }.also { result ->
            result.getOrNull()?.let { detail ->
                sessionDao.upsert(detail.toEntity())
                catchDao.upsert(detail.catches.map { it.toEntity() })
            }
        }

    suspend fun syncPending(): Int = syncManager.syncPending()

    // ---- tackle -----------------------------------------------------------
    suspend fun addTackle(body: TackleItemCreate): AppResult<Unit> {
        val localId = UUID.randomUUID().toString()
        // Write locally first so the item appears immediately, online or not.
        tackleDao.upsert(
            TackleEntity(
                id = localId,
                category = body.category,
                name = body.name,
                brand = body.brand,
                weightMinG = body.weightMinG,
                weightMaxG = body.weightMaxG,
                lengthCm = body.lengthCm,
                lineRating = body.lineRating,
                fishingStylesCsv = body.fishingStyles?.joinToString(","),
                targetSpeciesCsv = body.targetSpecies?.joinToString(","),
                notes = body.notes,
                isFavourite = body.isFavourite,
                aiIdentified = body.aiIdentified,
            ),
        )
        return when (val result = apiCall(TAG) { api.createTackle(body) }) {
            is AppResult.Ok -> {
                // Replace the optimistic row with the server's canonical one.
                tackleDao.deleteById(localId)
                tackleDao.upsert(result.value.toEntity())
                AppResult.Ok(Unit)
            }
            is AppResult.Err -> {
                syncManager.enqueue(
                    PendingOpKind.CREATE_TACKLE,
                    localId,
                    json.encodeToString(TackleItemCreate.serializer(), body),
                )
                // Not an error for the user: it is saved and will sync.
                AppResult.Ok(Unit)
            }
        }
    }

    suspend fun updateTackle(id: String, body: TackleItemCreate): AppResult<Unit> {
        tackleDao.byId(id)?.let { existing ->
            tackleDao.upsert(
                existing.copy(
                    name = body.name,
                    category = body.category,
                    brand = body.brand,
                    weightMinG = body.weightMinG,
                    weightMaxG = body.weightMaxG,
                    lengthCm = body.lengthCm,
                    lineRating = body.lineRating,
                    notes = body.notes,
                    isFavourite = body.isFavourite,
                    // A user edit confirms an AI-suggested item.
                    aiIdentified = false,
                ),
            )
        }
        return when (val result = apiCall(TAG) { api.updateTackle(id, body) }) {
            is AppResult.Ok -> {
                tackleDao.upsert(result.value.toEntity())
                AppResult.Ok(Unit)
            }
            is AppResult.Err -> {
                syncManager.enqueue(
                    PendingOpKind.UPDATE_TACKLE,
                    id,
                    json.encodeToString(TackleItemCreate.serializer(), body),
                )
                AppResult.Ok(Unit)
            }
        }
    }

    suspend fun deleteTackle(id: String): AppResult<Unit> {
        tackleDao.deleteById(id)
        return apiCall(TAG) { api.deleteTackle(id) }
    }

    suspend fun tackleTemplates(): AppResult<List<TackleTemplateDto>> =
        apiCall(TAG) { api.tackleTemplates() }.let { result ->
            when (result) {
                is AppResult.Ok -> AppResult.Ok(result.value.templates)
                is AppResult.Err -> result
            }
        }

    suspend fun applyTackleTemplates(keys: List<String>): AppResult<Unit> =
        when (val result = apiCall(TAG) { api.applyTackleTemplates(keys) }) {
            is AppResult.Ok -> {
                tackleDao.upsert(result.value.map { it.toEntity() })
                AppResult.Ok(Unit)
            }
            is AppResult.Err -> result
        }

    // ---- sessions ---------------------------------------------------------
    suspend fun startSession(body: SessionStartRequest): AppResult<String> =
        when (val result = apiCall(TAG) { api.startSession(body) }) {
            is AppResult.Ok -> {
                sessionDao.upsert(result.value.toEntity())
                AppResult.Ok(result.value.id)
            }
            is AppResult.Err -> {
                if (result.error.retryable) {
                    // Start it locally: the angler is already fishing, and a
                    // session that refuses to start because of signal is worse
                    // than one that syncs later.
                    val localId = UUID.randomUUID().toString()
                    sessionDao.upsert(
                        SessionEntity(
                            id = localId,
                            status = "active",
                            startedAtEpochMs = System.currentTimeMillis(),
                            locationLabel = body.locationLabel,
                            targetSpeciesCsv = body.targetSpecies.joinToString(","),
                            fishingStyle = body.fishingStyle,
                        ),
                    )
                    Log2.i(TAG, "session started offline id=$localId")
                    AppResult.Ok(localId)
                } else {
                    result
                }
            }
        }

    suspend fun finishSession(id: String, notes: String?): AppResult<Unit> {
        val body = SessionFinishRequest(notes)
        sessionDao.byId(id)?.let {
            sessionDao.upsert(
                it.copy(status = "completed", endedAtEpochMs = System.currentTimeMillis()),
            )
        }
        return when (val result = apiCall(TAG) { api.finishSession(id, body) }) {
            is AppResult.Ok -> {
                sessionDao.upsert(result.value.toEntity())
                catchDao.upsert(result.value.catches.map { it.toEntity() })
                AppResult.Ok(Unit)
            }
            is AppResult.Err -> {
                syncManager.enqueue(
                    PendingOpKind.FINISH_SESSION,
                    id,
                    json.encodeToString(SessionFinishRequest.serializer(), body),
                )
                AppResult.Ok(Unit)
            }
        }
    }

    suspend fun addObservation(sessionId: String, kind: String, text: String): AppResult<Unit> {
        val body = ObservationCreate(kind, text)
        return when (val result = apiCall(TAG) { api.addObservation(sessionId, body) }) {
            is AppResult.Ok -> AppResult.Ok(Unit)
            is AppResult.Err -> {
                syncManager.enqueue(
                    PendingOpKind.ADD_OBSERVATION,
                    sessionId,
                    json.encodeToString(ObservationCreate.serializer(), body),
                )
                AppResult.Ok(Unit)
            }
        }
    }

    // ---- catches ----------------------------------------------------------
    suspend fun logCatch(body: CatchCreate): AppResult<String> {
        val localId = UUID.randomUUID().toString()
        val activeSessionId = body.sessionId ?: sessionDao.activeOnce()?.id
        catchDao.upsert(
            CatchEntity(
                id = localId,
                sessionId = activeSessionId,
                speciesKey = null,
                speciesLabel = body.species,
                aiConfidence = body.aiConfidence,
                aiReasoning = body.aiReasoning,
                speciesConfirmedByUser = body.aiConfidence == null,
                lengthCm = body.lengthCm,
                weightG = body.weightG,
                released = body.released,
                lureLabel = body.lureLabel,
                technique = body.technique,
                caughtAtEpochMs = System.currentTimeMillis(),
                notes = body.notes,
                photoLocalUri = body.photoLocalUri,
            ),
        )
        return when (val result = apiCall(TAG) { api.createCatch(body) }) {
            is AppResult.Ok -> {
                catchDao.deleteById(localId)
                catchDao.upsert(result.value.toEntity())
                result.value.sessionId?.let { refreshSession(it) }
                AppResult.Ok(result.value.id)
            }
            is AppResult.Err -> {
                syncManager.enqueue(
                    PendingOpKind.CREATE_CATCH,
                    localId,
                    json.encodeToString(CatchCreate.serializer(), body),
                )
                Log2.i(TAG, "catch saved offline id=$localId")
                AppResult.Ok(localId)
            }
        }
    }

    suspend fun updateCatch(id: String, body: CatchUpdate): AppResult<Unit> {
        catchDao.byId(id)?.let { existing ->
            catchDao.upsert(
                existing.copy(
                    speciesLabel = body.species ?: existing.speciesLabel,
                    // Correcting the species makes it user-confirmed and
                    // retires the model's confidence for this row.
                    speciesKey = if (body.species != null) null else existing.speciesKey,
                    speciesConfirmedByUser =
                        if (body.species != null) true else existing.speciesConfirmedByUser,
                    aiConfidence = if (body.species != null) null else existing.aiConfidence,
                    lengthCm = body.lengthCm ?: existing.lengthCm,
                    weightG = body.weightG ?: existing.weightG,
                    lureLabel = body.lureLabel ?: existing.lureLabel,
                    technique = body.technique ?: existing.technique,
                    released = body.released ?: existing.released,
                    notes = body.notes ?: existing.notes,
                ),
            )
        }
        return when (val result = apiCall(TAG) { api.updateCatch(id, body) }) {
            is AppResult.Ok -> {
                catchDao.upsert(result.value.toEntity())
                AppResult.Ok(Unit)
            }
            is AppResult.Err -> {
                syncManager.enqueue(
                    PendingOpKind.UPDATE_CATCH,
                    id,
                    json.encodeToString(CatchUpdate.serializer(), body),
                )
                AppResult.Ok(Unit)
            }
        }
    }

    suspend fun confirmSpecies(id: String): AppResult<Unit> {
        catchDao.byId(id)?.let { catchDao.upsert(it.copy(speciesConfirmedByUser = true)) }
        return when (val result = apiCall(TAG) { api.confirmSpecies(id) }) {
            is AppResult.Ok -> {
                catchDao.upsert(result.value.toEntity())
                AppResult.Ok(Unit)
            }
            is AppResult.Err -> AppResult.Ok(Unit) // local state already correct
        }
    }

    suspend fun deleteCatch(id: String): AppResult<Unit> {
        catchDao.deleteById(id)
        return apiCall(TAG) { api.deleteCatch(id) }
    }

    suspend fun speciesCatalogue(): AppResult<List<SpeciesDto>> =
        when (val result = apiCall(TAG) { api.speciesCatalogue() }) {
            is AppResult.Ok -> AppResult.Ok(result.value.species)
            is AppResult.Err -> result
        }

    // ---- conditions -------------------------------------------------------
    suspend fun home(latitude: Double?, longitude: Double?): AppResult<HomeSummaryDto> =
        apiCall(TAG) { api.home(latitude, longitude) }

    suspend fun conditions(latitude: Double, longitude: Double): AppResult<ConditionsDto> =
        apiCall(TAG) { api.conditions(latitude, longitude) }

    suspend fun recommendation(
        body: RecommendationRequest,
    ): AppResult<kotlinx.serialization.json.JsonObject> =
        apiCall(TAG) { api.recommendation(body) }

    companion object {
        private const val TAG = "FishingRepository"
    }
}
