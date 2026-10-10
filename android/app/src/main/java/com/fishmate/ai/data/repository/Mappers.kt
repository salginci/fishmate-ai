package com.fishmate.ai.data.repository

import com.fishmate.ai.data.local.entity.CatchEntity
import com.fishmate.ai.data.local.entity.SessionEntity
import com.fishmate.ai.data.local.entity.TackleEntity
import com.fishmate.ai.data.remote.dto.CatchDto
import com.fishmate.ai.data.remote.dto.SessionDetailDto
import com.fishmate.ai.data.remote.dto.SessionDto
import com.fishmate.ai.data.remote.dto.TackleItemDto
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * DTO <-> entity mapping.
 *
 * Timestamps are stored as epoch millis locally so Room can index and sort
 * them; the API speaks ISO-8601. A malformed timestamp falls back to "now"
 * rather than dropping the row — losing an angler's catch over a parse error
 * would be the worse failure.
 */

internal fun parseInstantMillis(value: String?, fallback: Long = System.currentTimeMillis()): Long {
    if (value.isNullOrBlank()) return fallback
    return try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        // FastAPI emits offsets without a trailing 'Z'; try appending one.
        try {
            Instant.parse(value + "Z").toEpochMilli()
        } catch (_: DateTimeParseException) {
            fallback
        }
    }
}

internal fun formatInstant(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

fun TackleItemDto.toEntity() = TackleEntity(
    id = id,
    category = category,
    name = name,
    brand = brand,
    weightMinG = weightMinG,
    weightMaxG = weightMaxG,
    lengthCm = lengthCm,
    lineRating = lineRating,
    fishingStylesCsv = fishingStyles?.joinToString(","),
    targetSpeciesCsv = targetSpecies?.joinToString(","),
    notes = notes,
    isFavourite = isFavourite,
    aiIdentified = aiIdentified,
)

fun SessionDto.toEntity(conditionsSummary: String? = null) = SessionEntity(
    id = id,
    status = status,
    startedAtEpochMs = parseInstantMillis(startedAt),
    endedAtEpochMs = endedAt?.let { parseInstantMillis(it) },
    locationLabel = locationLabel,
    targetSpeciesCsv = targetSpecies?.joinToString(","),
    fishingStyle = fishingStyle,
    notes = notes,
    summary = summary,
    catchCount = catchCount,
    conditionsSummary = conditionsSummary,
)

fun SessionDetailDto.toEntity() = SessionEntity(
    id = id,
    status = status,
    startedAtEpochMs = parseInstantMillis(startedAt),
    endedAtEpochMs = endedAt?.let { parseInstantMillis(it) },
    locationLabel = locationLabel,
    targetSpeciesCsv = targetSpecies?.joinToString(","),
    fishingStyle = fishingStyle,
    notes = notes,
    summary = summary,
    catchCount = catchCount,
    // The first snapshot describes the conditions the session started in,
    // which is what the report and history list show.
    conditionsSummary = weatherSnapshots.firstOrNull()?.summary,
)

fun CatchDto.toEntity() = CatchEntity(
    id = id,
    sessionId = sessionId,
    speciesKey = speciesKey,
    speciesLabel = speciesLabel,
    aiConfidence = aiConfidence,
    aiReasoning = aiReasoning,
    speciesConfirmedByUser = speciesConfirmedByUser,
    lengthCm = lengthCm,
    weightG = weightG,
    released = released,
    lureLabel = lureLabel,
    technique = technique,
    caughtAtEpochMs = parseInstantMillis(caughtAt),
    locationLabel = locationLabel,
    weatherSummary = weatherSummary,
    notes = notes,
    photoLocalUri = photos.firstOrNull { it.isPrimary }?.localUri
        ?: photos.firstOrNull()?.localUri,
)
