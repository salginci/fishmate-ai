package com.fishmate.ai.di

import android.content.Context
import com.fishmate.ai.BuildConfig
import com.fishmate.ai.audio.AudioCapture
import com.fishmate.ai.audio.AudioPlayer
import com.fishmate.ai.audio.AudioRouting
import com.fishmate.ai.core.AppDispatchers
import com.fishmate.ai.core.DefaultAppDispatchers
import com.fishmate.ai.data.local.FishMateDatabase
import com.fishmate.ai.data.remote.AuthInterceptor
import com.fishmate.ai.data.remote.AuthStore
import com.fishmate.ai.data.remote.FishMateApi
import com.fishmate.ai.data.remote.RetryInterceptor
import com.fishmate.ai.data.remote.UnauthorizedInterceptor
import com.fishmate.ai.data.repository.AuthRepository
import com.fishmate.ai.data.repository.FishingRepository
import com.fishmate.ai.data.repository.LiveCredentialsRepository
import com.fishmate.ai.data.repository.SyncManager
import com.fishmate.ai.data.repository.ToolRepository
import com.fishmate.ai.data.repository.TranslationRepository
import com.fishmate.ai.live.GeminiLiveSession
import com.fishmate.ai.live.LiveAiSession
import com.fishmate.ai.live.ToolOrchestrator
import com.fishmate.ai.location.FusedLocationProvider
import com.fishmate.ai.location.LocationProvider
import com.fishmate.ai.wake.NoOpWakeWordEngine
import com.fishmate.ai.wake.WakeWordEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun dispatchers(): AppDispatchers = DefaultAppDispatchers()

    @Provides
    @Singleton
    fun authStore(@ApplicationContext context: Context): AuthStore = AuthStore(context)

    @Provides
    @Singleton
    fun okHttp(authStore: AuthStore): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(authStore))
        .addInterceptor(UnauthorizedInterceptor(authStore))
        .addInterceptor(RetryInterceptor())
        .apply {
            if (BuildConfig.DEBUG) {
                // BASIC, not BODY: request bodies carry catch notes and
                // positions, and BODY would also print the Live token.
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("Authorization")
                    },
                )
            }
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun api(retrofit: Retrofit): FishMateApi = retrofit.create(FishMateApi::class.java)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): FishMateDatabase =
        FishMateDatabase.build(context)

    @Provides
    @Singleton
    fun syncManager(api: FishMateApi, database: FishMateDatabase): SyncManager =
        SyncManager(api, database)

    @Provides
    @Singleton
    fun authRepository(
        api: FishMateApi,
        authStore: AuthStore,
        database: FishMateDatabase,
    ): AuthRepository = AuthRepository(api, authStore, database)

    @Provides
    @Singleton
    fun fishingRepository(
        api: FishMateApi,
        database: FishMateDatabase,
        syncManager: SyncManager,
    ): FishingRepository = FishingRepository(api, database, syncManager)

    @Provides
    @Singleton
    fun liveCredentialsRepository(api: FishMateApi): LiveCredentialsRepository =
        LiveCredentialsRepository(api)

    @Provides
    @Singleton
    fun translationRepository(api: FishMateApi): TranslationRepository =
        TranslationRepository(api)

    @Provides
    @Singleton
    fun toolRepository(api: FishMateApi, syncManager: SyncManager): ToolRepository =
        ToolRepository(api, syncManager)

    @Provides
    @Singleton
    fun toolOrchestrator(toolRepository: ToolRepository): ToolOrchestrator =
        ToolOrchestrator(toolRepository)

    /**
     * A new session per injection point, not a singleton: a session owns a
     * socket and its resumption handle, and reusing a released one would
     * reconnect into a dead conversation.
     */
    @Provides
    fun liveSession(): LiveAiSession = GeminiLiveSession()

    @Provides
    fun audioCapture(): AudioCapture = AudioCapture()

    @Provides
    fun audioPlayer(): AudioPlayer = AudioPlayer()

    @Provides
    @Singleton
    fun audioRouting(@ApplicationContext context: Context): AudioRouting = AudioRouting(context)

    @Provides
    @Singleton
    fun locationProvider(@ApplicationContext context: Context): LocationProvider =
        FusedLocationProvider(context)

    /**
     * No-op by default.
     *
     * Android does not permit arbitrary background microphone access, and we
     * will not hold a cloud connection open just to spot a keyword, so wake
     * word is an extension point rather than a shipped feature
     * (see [com.fishmate.ai.wake.WakeWordEngine]).
     */
    @Provides
    @Singleton
    fun wakeWordEngine(): WakeWordEngine = NoOpWakeWordEngine()
}
