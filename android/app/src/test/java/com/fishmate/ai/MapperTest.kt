package com.fishmate.ai

import com.fishmate.ai.data.remote.dto.CatchDto
import com.fishmate.ai.data.remote.dto.CatchPhotoDto
import com.fishmate.ai.data.remote.dto.SessionDetailDto
import com.fishmate.ai.data.remote.dto.TackleItemDto
import com.fishmate.ai.data.remote.dto.WeatherSnapshotDto
import com.fishmate.ai.data.repository.parseInstantMillis
import com.fishmate.ai.data.repository.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTO -> entity mapping.
 *
 * The timestamp cases matter: a parse failure must never drop a catch, because
 * losing an angler's record is worse than storing a slightly wrong time.
 */
class MapperTest {

    @Test
    fun `parses iso instants`() {
        assertEquals(
            1_760_000_000_000L,
            parseInstantMillis("2025-10-09T08:53:20Z"),
        )
    }

    @Test
    fun `parses an offset-free timestamp by assuming utc`() {
        // FastAPI sometimes serialises without a trailing Z.
        val parsed = parseInstantMillis("2026-10-07T09:45:00")
        assertTrue(parsed > 0)
    }

    @Test
    fun `malformed timestamp falls back instead of dropping the row`() {
        val fallback = 12_345L
        assertEquals(fallback, parseInstantMillis("not-a-timestamp", fallback))
        assertEquals(fallback, parseInstantMillis(null, fallback))
        assertEquals(fallback, parseInstantMillis("", fallback))
    }

    @Test
    fun `tackle maps list fields to csv`() {
        val entity = TackleItemDto(
            id = "t1",
            category = "rod",
            name = "Light spin 3-15 g",
            weightMinG = 3.0,
            weightMaxG = 15.0,
            fishingStyles = listOf("light_spin", "spin"),
            targetSpecies = listOf("dicentrarchus_labrax"),
            isFavourite = true,
        ).toEntity()

        assertEquals("light_spin,spin", entity.fishingStylesCsv)
        assertEquals(listOf("light_spin", "spin"), entity.fishingStyles)
        assertTrue(entity.isFavourite)
    }

    @Test
    fun `tackle with no styles yields an empty list not a crash`() {
        val entity = TackleItemDto(id = "t2", category = "lure", name = "Jig").toEntity()
        assertNull(entity.fishingStylesCsv)
        assertTrue(entity.fishingStyles.isEmpty())
    }

    @Test
    fun `catch preserves ai provenance`() {
        val entity = CatchDto(
            id = "c1",
            speciesKey = "sparus_aurata",
            speciesLabel = "Cipura",
            aiConfidence = 0.62,
            aiReasoning = "Body profile",
            speciesConfirmedByUser = false,
            caughtAt = "2026-10-07T09:45:00Z",
        ).toEntity()

        assertEquals(0.62, entity.aiConfidence!!, 0.001)
        assertEquals("Body profile", entity.aiReasoning)
        assertTrue(!entity.speciesConfirmedByUser)
    }

    @Test
    fun `catch picks the primary photo`() {
        val entity = CatchDto(
            id = "c2",
            speciesLabel = "Levrek",
            caughtAt = "2026-10-07T09:45:00Z",
            photos = listOf(
                CatchPhotoDto(id = "p1", localUri = "file://a.jpg", isPrimary = false),
                CatchPhotoDto(id = "p2", localUri = "file://b.jpg", isPrimary = true),
            ),
        ).toEntity()
        assertEquals("file://b.jpg", entity.photoLocalUri)
    }

    @Test
    fun `catch falls back to the first photo when none is primary`() {
        val entity = CatchDto(
            id = "c3",
            speciesLabel = "Levrek",
            caughtAt = "2026-10-07T09:45:00Z",
            photos = listOf(CatchPhotoDto(id = "p1", localUri = "file://a.jpg")),
        ).toEntity()
        assertEquals("file://a.jpg", entity.photoLocalUri)
    }

    @Test
    fun `session detail takes the opening weather snapshot`() {
        // The first snapshot describes the conditions the session started in,
        // which is what the history list shows.
        val entity = SessionDetailDto(
            id = "s1",
            status = "completed",
            startedAt = "2026-10-07T06:00:00Z",
            endedAt = "2026-10-07T08:17:00Z",
            locationLabel = "Bostanli",
            catchCount = 2,
            weatherSnapshots = listOf(
                WeatherSnapshotDto(capturedAt = "2026-10-07T06:00:00Z", summary = "Calm, 19C"),
                WeatherSnapshotDto(capturedAt = "2026-10-07T08:00:00Z", summary = "Windy"),
            ),
        ).toEntity()

        assertEquals("Calm, 19C", entity.conditionsSummary)
        assertEquals(2, entity.catchCount)
        assertNotNull(entity.endedAtEpochMs)
    }
}
