package com.fishmate.ai.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire contracts for the FishMate backend.
 *
 * Field names mirror `backend/app/schemas/api.py`. Keeping them explicit with
 * [SerialName] means renaming a Kotlin property cannot silently break the API
 * contract.
 */

// ---- errors ---------------------------------------------------------------
@Serializable
data class ErrorEnvelope(@SerialName("error") val error: ErrorBody)

@Serializable
data class ErrorBody(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String,
    @SerialName("details") val details: JsonObject? = null,
)

// ---- auth -----------------------------------------------------------------
@Serializable
data class RegisterRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("language") val language: String = "en",
)

@Serializable
data class LoginRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("user") val user: UserDto,
)

@Serializable
data class UserDto(
    @SerialName("id") val id: String,
    @SerialName("email") val email: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("preferences") val preferences: PreferencesDto? = null,
)

@Serializable
data class PreferencesDto(
    @SerialName("language") val language: String = "en",
    @SerialName("units") val units: String = "metric",
    @SerialName("location_precision") val locationPrecision: String = "approximate",
    @SerialName("default_target_species") val defaultTargetSpecies: List<String> = emptyList(),
    @SerialName("default_fishing_style") val defaultFishingStyle: String? = null,
    @SerialName("voice_name") val voiceName: String? = null,
    @SerialName("verbosity") val verbosity: String = "concise",
    @SerialName("camera_fps") val cameraFps: Double = 0.5,
    @SerialName("share_anonymous_stats") val shareAnonymousStats: Boolean = false,
)

@Serializable
data class PreferencesUpdate(
    @SerialName("language") val language: String? = null,
    @SerialName("location_precision") val locationPrecision: String? = null,
    @SerialName("default_target_species") val defaultTargetSpecies: List<String>? = null,
    @SerialName("default_fishing_style") val defaultFishingStyle: String? = null,
    @SerialName("voice_name") val voiceName: String? = null,
    @SerialName("verbosity") val verbosity: String? = null,
    @SerialName("camera_fps") val cameraFps: Double? = null,
    @SerialName("share_anonymous_stats") val shareAnonymousStats: Boolean? = null,
)

// ---- live -----------------------------------------------------------------
@Serializable
data class LiveSessionRequest(
    @SerialName("language") val language: String? = null,
    @SerialName("voice_name") val voiceName: String? = null,
    @SerialName("expects_video") val expectsVideo: Boolean = false,
    /**
     * Where the angler is, so the session can open knowing the spot by name
     * instead of asking. The backend coarsens or drops it according to the
     * user's consent setting before using it for anything.
     */
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
)

@Serializable
data class TranslateRequest(
    @SerialName("text") val text: String,
    @SerialName("target_language") val targetLanguage: String = "tr",
    @SerialName("source_language") val sourceLanguage: String = "auto",
)

@Serializable
data class TranslateResponse(
    @SerialName("text") val text: String,
    @SerialName("target_language") val targetLanguage: String,
)

@Serializable
data class LiveSessionResponse(
    @SerialName("token") val token: String,
    @SerialName("ws_url") val wsUrl: String,
    @SerialName("model") val model: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("new_session_expires_at") val newSessionExpiresAt: String,
    /** Sent verbatim as the first WebSocket frame. */
    @SerialName("setup") val setup: JsonObject,
    @SerialName("input_sample_rate_hz") val inputSampleRateHz: Int,
    @SerialName("output_sample_rate_hz") val outputSampleRateHz: Int,
    @SerialName("input_audio_mime") val inputAudioMime: String,
    @SerialName("user_brief") val userBrief: JsonObject? = null,
)

// ---- tools ----------------------------------------------------------------
@Serializable
data class ToolCallDto(
    @SerialName("name") val name: String,
    @SerialName("call_id") val callId: String? = null,
    @SerialName("args") val args: JsonObject,
    @SerialName("language") val language: String? = null,
    @SerialName("live_session_ref") val liveSessionRef: String? = null,
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
)

@Serializable
data class ToolCallBatchRequest(@SerialName("calls") val calls: List<ToolCallDto>)

@Serializable
data class ToolCallResultDto(
    @SerialName("name") val name: String,
    @SerialName("call_id") val callId: String? = null,
    @SerialName("ok") val ok: Boolean,
    @SerialName("response") val response: JsonObject,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class ToolCallBatchResponse(@SerialName("results") val results: List<ToolCallResultDto>)

// ---- tackle ---------------------------------------------------------------
@Serializable
data class TackleItemDto(
    @SerialName("id") val id: String,
    @SerialName("category") val category: String,
    @SerialName("name") val name: String,
    @SerialName("brand") val brand: String? = null,
    @SerialName("model") val model: String? = null,
    @SerialName("weight_min_g") val weightMinG: Double? = null,
    @SerialName("weight_max_g") val weightMaxG: Double? = null,
    @SerialName("length_cm") val lengthCm: Double? = null,
    @SerialName("line_rating") val lineRating: String? = null,
    @SerialName("colour") val colour: String? = null,
    @SerialName("fishing_styles") val fishingStyles: List<String>? = null,
    @SerialName("target_species") val targetSpecies: List<String>? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("is_favourite") val isFavourite: Boolean = false,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("ai_identified") val aiIdentified: Boolean = false,
)

@Serializable
data class TackleItemCreate(
    @SerialName("category") val category: String,
    @SerialName("name") val name: String,
    @SerialName("brand") val brand: String? = null,
    @SerialName("weight_min_g") val weightMinG: Double? = null,
    @SerialName("weight_max_g") val weightMaxG: Double? = null,
    @SerialName("length_cm") val lengthCm: Double? = null,
    @SerialName("line_rating") val lineRating: String? = null,
    @SerialName("fishing_styles") val fishingStyles: List<String>? = null,
    @SerialName("target_species") val targetSpecies: List<String>? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("is_favourite") val isFavourite: Boolean = false,
    @SerialName("ai_identified") val aiIdentified: Boolean = false,
)

// ---- sessions -------------------------------------------------------------
@Serializable
data class SessionStartRequest(
    @SerialName("target_species") val targetSpecies: List<String> = emptyList(),
    @SerialName("location_label") val locationLabel: String? = null,
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("fishing_style") val fishingStyle: String? = null,
    @SerialName("tackle_used") val tackleUsed: List<String> = emptyList(),
)

@Serializable
data class SessionFinishRequest(@SerialName("notes") val notes: String? = null)

@Serializable
data class SessionDto(
    @SerialName("id") val id: String,
    @SerialName("status") val status: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("location_label") val locationLabel: String? = null,
    @SerialName("target_species") val targetSpecies: List<String>? = null,
    @SerialName("fishing_style") val fishingStyle: String? = null,
    @SerialName("tackle_used") val tackleUsed: List<String>? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("summary") val summary: String? = null,
    @SerialName("catch_count") val catchCount: Int = 0,
)

@Serializable
data class SessionDetailDto(
    @SerialName("id") val id: String,
    @SerialName("status") val status: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("location_label") val locationLabel: String? = null,
    @SerialName("target_species") val targetSpecies: List<String>? = null,
    @SerialName("fishing_style") val fishingStyle: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("summary") val summary: String? = null,
    @SerialName("catch_count") val catchCount: Int = 0,
    @SerialName("observations") val observations: List<ObservationDto> = emptyList(),
    @SerialName("weather_snapshots") val weatherSnapshots: List<WeatherSnapshotDto> = emptyList(),
    @SerialName("catches") val catches: List<CatchDto> = emptyList(),
)

@Serializable
data class ObservationDto(
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: String,
    @SerialName("text") val text: String,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("from_ai") val fromAi: Boolean = false,
)

@Serializable
data class ObservationCreate(
    @SerialName("kind") val kind: String,
    @SerialName("text") val text: String,
)

@Serializable
data class WeatherSnapshotDto(
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("summary") val summary: String? = null,
    @SerialName("air_temp_c") val airTempC: Double? = null,
    @SerialName("water_temp_c") val waterTempC: Double? = null,
    @SerialName("wind_speed_kmh") val windSpeedKmh: Double? = null,
    @SerialName("wave_height_m") val waveHeightM: Double? = null,
)

// ---- catches --------------------------------------------------------------
@Serializable
data class CatchDto(
    @SerialName("id") val id: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("species_key") val speciesKey: String? = null,
    @SerialName("species_label") val speciesLabel: String,
    @SerialName("ai_confidence") val aiConfidence: Double? = null,
    @SerialName("ai_reasoning") val aiReasoning: String? = null,
    @SerialName("species_confirmed_by_user") val speciesConfirmedByUser: Boolean = false,
    @SerialName("length_cm") val lengthCm: Double? = null,
    @SerialName("weight_g") val weightG: Double? = null,
    @SerialName("released") val released: Boolean = true,
    @SerialName("lure_label") val lureLabel: String? = null,
    @SerialName("technique") val technique: String? = null,
    @SerialName("retrieve_style") val retrieveStyle: String? = null,
    @SerialName("caught_at") val caughtAt: String,
    @SerialName("location_label") val locationLabel: String? = null,
    @SerialName("weather_summary") val weatherSummary: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("photos") val photos: List<CatchPhotoDto> = emptyList(),
)

@Serializable
data class CatchPhotoDto(
    @SerialName("id") val id: String,
    @SerialName("local_uri") val localUri: String? = null,
    @SerialName("is_primary") val isPrimary: Boolean = false,
)

@Serializable
data class CatchCreate(
    @SerialName("species") val species: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("length_cm") val lengthCm: Double? = null,
    @SerialName("weight_g") val weightG: Double? = null,
    @SerialName("lure_label") val lureLabel: String? = null,
    @SerialName("technique") val technique: String? = null,
    @SerialName("released") val released: Boolean = true,
    @SerialName("notes") val notes: String? = null,
    @SerialName("ai_confidence") val aiConfidence: Double? = null,
    @SerialName("ai_reasoning") val aiReasoning: String? = null,
    @SerialName("photo_local_uri") val photoLocalUri: String? = null,
)

@Serializable
data class CatchUpdate(
    @SerialName("species") val species: String? = null,
    @SerialName("length_cm") val lengthCm: Double? = null,
    @SerialName("weight_g") val weightG: Double? = null,
    @SerialName("lure_label") val lureLabel: String? = null,
    @SerialName("technique") val technique: String? = null,
    @SerialName("released") val released: Boolean? = null,
    @SerialName("notes") val notes: String? = null,
)

@Serializable
data class SpeciesListResponse(@SerialName("species") val species: List<SpeciesDto>)

@Serializable
data class SpeciesDto(
    @SerialName("key") val key: String,
    @SerialName("label") val label: String,
    @SerialName("name_tr") val nameTr: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("scientific_name") val scientificName: String,
    @SerialName("typical_styles") val typicalStyles: List<String> = emptyList(),
)

// ---- conditions / home ----------------------------------------------------
@Serializable
data class ConditionsDto(
    @SerialName("summary") val summary: String,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("provider") val provider: String,
    @SerialName("air_temp_c") val airTempC: Double? = null,
    @SerialName("water_temp_c") val waterTempC: Double? = null,
    @SerialName("wind_speed_kmh") val windSpeedKmh: Double? = null,
    @SerialName("wind_gust_kmh") val windGustKmh: Double? = null,
    @SerialName("wind_direction") val windDirection: String? = null,
    @SerialName("wind_beaufort") val windBeaufort: Int? = null,
    @SerialName("wave_height_m") val waveHeightM: Double? = null,
    @SerialName("sunrise") val sunrise: String? = null,
    @SerialName("sunset") val sunset: String? = null,
    @SerialName("sea_data_available") val seaDataAvailable: Boolean = true,
)

@Serializable
data class HomeSummaryDto(
    @SerialName("display_name") val displayName: String,
    @SerialName("active_session") val activeSession: SessionDto? = null,
    @SerialName("last_session") val lastSession: SessionDto? = null,
    @SerialName("recent_catches") val recentCatches: List<CatchDto> = emptyList(),
    @SerialName("tackle_count") val tackleCount: Int = 0,
    @SerialName("total_catches") val totalCatches: Int = 0,
    @SerialName("conditions") val conditions: ConditionsDto? = null,
    @SerialName("conditions_error") val conditionsError: String? = null,
)

@Serializable
data class RecommendationRequest(
    @SerialName("target_species") val targetSpecies: String? = null,
    @SerialName("fishing_style") val fishingStyle: String? = null,
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("notes") val notes: String? = null,
)

// ---- demo -----------------------------------------------------------------
@Serializable
data class DemoScenariosResponse(
    @SerialName("spot") val spot: DemoSpotDto,
    @SerialName("scenarios") val scenarios: List<DemoScenarioDto>,
    @SerialName("fallback") val fallback: String,
)

@Serializable
data class DemoSpotDto(
    @SerialName("name") val name: String,
    @SerialName("latitude") val latitude: Double,
    @SerialName("longitude") val longitude: Double,
)

@Serializable
data class DemoScenarioDto(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String,
    @SerialName("say") val say: String,
    @SerialName("capability") val capability: String,
    @SerialName("expect") val expect: String,
)
