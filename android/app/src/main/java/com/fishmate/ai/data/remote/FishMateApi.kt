package com.fishmate.ai.data.remote

import com.fishmate.ai.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * FishMate backend API.
 *
 * Returns `Response<T>` rather than bare `T` so the repository layer can read
 * the `{error: {code, message}}` envelope and turn it into an [com.fishmate.ai.core.AppError]
 * instead of throwing — the UI never sees an HttpException.
 */
interface FishMateApi {

    // ---- auth -------------------------------------------------------------
    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<TokenResponse>

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<TokenResponse>

    @GET("api/v1/me")
    suspend fun me(): Response<UserDto>

    @PATCH("api/v1/me/preferences")
    suspend fun updatePreferences(@Body body: PreferencesUpdate): Response<PreferencesDto>

    @DELETE("api/v1/me")
    suspend fun deleteAccount(): Response<Unit>

    // ---- live -------------------------------------------------------------
    @POST("api/v1/live/session")
    suspend fun createLiveSession(@Body body: LiveSessionRequest): Response<LiveSessionResponse>

    @POST("api/v1/live/translate")
    suspend fun translate(@Body body: TranslateRequest): Response<TranslateResponse>

    // ---- tools ------------------------------------------------------------
    @POST("api/v1/tools/execute-batch")
    suspend fun executeTools(@Body body: ToolCallBatchRequest): Response<ToolCallBatchResponse>

    @POST("api/v1/tools/execute")
    suspend fun executeTool(@Body body: ToolCallDto): Response<ToolCallResultDto>

    // ---- tackle -----------------------------------------------------------
    @GET("api/v1/tackle")
    suspend fun listTackle(
        @Query("category") category: String? = null,
        @Query("fishing_style") fishingStyle: String? = null,
    ): Response<List<TackleItemDto>>

    @POST("api/v1/tackle")
    suspend fun createTackle(@Body body: TackleItemCreate): Response<TackleItemDto>

    @PATCH("api/v1/tackle/{id}")
    suspend fun updateTackle(
        @Path("id") id: String,
        @Body body: TackleItemCreate,
    ): Response<TackleItemDto>

    @DELETE("api/v1/tackle/{id}")
    suspend fun deleteTackle(@Path("id") id: String): Response<Unit>

    @GET("api/v1/tackle/templates")
    suspend fun tackleTemplates(): Response<TackleTemplatesResponse>

    @POST("api/v1/tackle/templates")
    suspend fun applyTackleTemplates(
        @Query("keys") keys: List<String>,
    ): Response<List<TackleItemDto>>

    // ---- sessions ---------------------------------------------------------
    @POST("api/v1/sessions")
    suspend fun startSession(@Body body: SessionStartRequest): Response<SessionDetailDto>

    @GET("api/v1/sessions")
    suspend fun listSessions(
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): Response<List<SessionDto>>

    @GET("api/v1/sessions/active")
    suspend fun activeSession(): Response<SessionDetailDto>

    @GET("api/v1/sessions/{id}")
    suspend fun session(@Path("id") id: String): Response<SessionDetailDto>

    @POST("api/v1/sessions/{id}/finish")
    suspend fun finishSession(
        @Path("id") id: String,
        @Body body: SessionFinishRequest,
    ): Response<SessionDetailDto>

    @POST("api/v1/sessions/{id}/observations")
    suspend fun addObservation(
        @Path("id") id: String,
        @Body body: ObservationCreate,
    ): Response<ObservationDto>

    // ---- catches ----------------------------------------------------------
    @GET("api/v1/catches")
    suspend fun listCatches(
        @Query("limit") limit: Int = 100,
        @Query("species") species: String? = null,
        @Query("session_id") sessionId: String? = null,
    ): Response<List<CatchDto>>

    @POST("api/v1/catches")
    suspend fun createCatch(@Body body: CatchCreate): Response<CatchDto>

    @GET("api/v1/catches/{id}")
    suspend fun catchDetail(@Path("id") id: String): Response<CatchDto>

    @PATCH("api/v1/catches/{id}")
    suspend fun updateCatch(
        @Path("id") id: String,
        @Body body: CatchUpdate,
    ): Response<CatchDto>

    @POST("api/v1/catches/{id}/confirm-species")
    suspend fun confirmSpecies(@Path("id") id: String): Response<CatchDto>

    @DELETE("api/v1/catches/{id}")
    suspend fun deleteCatch(@Path("id") id: String): Response<Unit>

    @GET("api/v1/catches/species")
    suspend fun speciesCatalogue(): Response<SpeciesListResponse>

    // ---- conditions / home ------------------------------------------------
    @GET("api/v1/conditions")
    suspend fun conditions(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
    ): Response<ConditionsDto>

    @GET("api/v1/home")
    suspend fun home(
        @Query("latitude") latitude: Double? = null,
        @Query("longitude") longitude: Double? = null,
    ): Response<HomeSummaryDto>

    @POST("api/v1/recommendation")
    suspend fun recommendation(
        @Body body: RecommendationRequest,
    ): Response<kotlinx.serialization.json.JsonObject>

    // ---- demo -------------------------------------------------------------
    @GET("api/v1/demo/scenarios")
    suspend fun demoScenarios(): Response<DemoScenariosResponse>

    @POST("api/v1/demo/seed")
    suspend fun demoSeed(
        @Query("reset") reset: Boolean = false,
    ): Response<kotlinx.serialization.json.JsonObject>

    @POST("api/v1/demo/mock-weather")
    suspend fun demoMockWeather(
        @Query("enabled") enabled: Boolean = true,
    ): Response<kotlinx.serialization.json.JsonObject>
}
