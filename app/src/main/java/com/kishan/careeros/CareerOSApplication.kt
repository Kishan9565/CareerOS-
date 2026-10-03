package com.kishan.careeros

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.kishan.careeros.data.*
import com.kishan.careeros.data.intelligence.*
import com.kishan.careeros.domain.intelligence.*
import com.kishan.careeros.domain.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@HiltAndroidApp class CareerOSApplication: Application()
@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): CareerDatabase = Room.databaseBuilder(context, CareerDatabase::class.java, "careeros.db").addMigrations(DiscoveryMigration.MIGRATION_1_2, DiscoveryEngineMigration.MIGRATION_2_3, KnowledgeMigration.MIGRATION_3_4).build()
    @Provides @Singleton fun discovery(db: CareerDatabase, client: HttpClient, @ApplicationContext context: Context): DiscoveryEngine = RoomDiscoveryEngine(db, client) { context.assets.open("company_sources.json").bufferedReader().use { it.readText() } }
    @Provides @Singleton fun capability(@ApplicationContext context: Context): DeviceCapabilityChecker = AndroidDeviceCapability(context)
    @Provides @Singleton fun localModels(@ApplicationContext context: Context, checker: DeviceCapabilityChecker) = AndroidLocalModelManager(context, checker)
    @Provides fun modelManager(models: AndroidLocalModelManager): LocalModelManager = models
    @Provides @Singleton fun embedding(@ApplicationContext context: Context, models: AndroidLocalModelManager): EmbeddingEngine = MediaPipeEmbeddingEngine(context,models)
    @Provides @Singleton fun localLlm(models: AndroidLocalModelManager): LocalLlm = LiteRtLocalLlm(models)
    @Provides @Singleton fun knowledge(db: CareerDatabase): CareerKnowledgeRepository = RoomCareerKnowledge(db)
    @Provides fun vectors(db: CareerDatabase): VectorStore = RoomVectorStore(db)
    @Provides fun resume(@ApplicationContext context: Context): ResumeTextExtractor = PdfResumeTextExtractor(context)
    @Provides @Singleton fun intelligence(db: CareerDatabase, knowledge: CareerKnowledgeRepository, profiles: ProfileRepository, answers: AnswerBankRepository, embedding: EmbeddingEngine, vectors: VectorStore, llm: LocalLlm, models: LocalModelManager): CareerIntelligence = LocalCareerIntelligence(db,knowledge,profiles,answers,embedding,vectors,llm,models)
    @Provides fun tracker(db: CareerDatabase): OpportunityTracker = RoomOpportunityTracker(db)
    @Provides fun profiles(db: CareerDatabase): ProfileRepository = RoomProfileRepository(db)
    @Provides fun opportunities(db: CareerDatabase): OpportunityRepository = RoomOpportunityRepository(db)
    @Provides fun applications(db: CareerDatabase): ApplicationRepository = RoomApplicationRepository(db)
    @Provides fun answers(db: CareerDatabase): AnswerBankRepository = RoomAnswerBankRepository(db)
    @Provides fun sources(db: CareerDatabase): SourceRepository = RoomSourceRepository(db)
    @Provides @Singleton fun settings(@ApplicationContext context: Context): AppSettings = DataStoreSettings(context)
    @Provides @Singleton fun http(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = true
        followRedirects = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 30_000 }
        install(HttpRequestRetry) { retryOnServerErrors(maxRetries = 2); exponentialDelay() }
    }
}
