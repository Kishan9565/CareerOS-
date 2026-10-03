package com.kishan.careeros.domain

import kotlinx.coroutines.flow.Flow

interface ProfileRepository { fun observe(): Flow<CareerProfile?>; suspend fun save(profile: CareerProfile) }
interface OpportunityRepository { fun observeAll(): Flow<List<Opportunity>>; suspend fun get(id: String): Opportunity?; suspend fun save(opportunity: Opportunity) }
interface ApplicationRepository { fun observeAll(): Flow<List<Application>>; suspend fun save(application: Application); suspend fun transition(id: String, status: ApplicationStatus, userConfirmedSubmission: Boolean = false) }
interface AnswerBankRepository { fun observeAll(): Flow<List<SavedApplicationAnswer>>; suspend fun save(answer: SavedApplicationAnswer); suspend fun delete(id: String); fun observeForApplication(id: String): Flow<List<ApplicationAnswer>>; suspend fun saveForApplication(answer: ApplicationAnswer) }
interface SourceRepository { fun observeAll(): Flow<List<SourceConfig>>; suspend fun save(source: SourceConfig) }
interface AppSettings { val darkTheme: Flow<Boolean>; suspend fun setDarkTheme(enabled: Boolean) }

sealed interface CapabilityResult<out T> {
    data class Available<T>(val value: T): CapabilityResult<T>
    data class Unavailable(val reason: String): CapabilityResult<Nothing>
}
interface OpportunitySource {
    val sourceId: String
    val capabilities: SourceCapabilities get() = SourceCapabilities(sourceId, sourceId, SourceKind.AGGREGATOR, DiscoveryMode.AUTOMATIC)
    suspend fun discover(config: SourceConfig): CapabilityResult<List<Opportunity>>
    suspend fun discover(config: SourceConfig, profile: CareerSearchProfile, intents: List<SearchIntent>): CapabilityResult<List<Opportunity>> = discover(config)
}
data class LlmRequest(val instruction: String, val untrustedContext: List<String>)
interface LocalLlm { suspend fun generate(request: LlmRequest): CapabilityResult<String> }
interface EmbeddingEngine {
    suspend fun embed(text: String): CapabilityResult<FloatArray>
    suspend fun embedDocument(text: String, title: String): CapabilityResult<FloatArray> = embed(text)
    suspend fun embedDocuments(texts: List<Pair<String, String>>): CapabilityResult<List<FloatArray>> {
        val vectors = mutableListOf<FloatArray>()
        for ((text,title) in texts) when (val result = embedDocument(text,title)) { is CapabilityResult.Available -> vectors += result.value; is CapabilityResult.Unavailable -> return result }
        return CapabilityResult.Available(vectors)
    }
    suspend fun embedBatch(texts: List<String>): CapabilityResult<List<FloatArray>> {
        val vectors = mutableListOf<FloatArray>()
        for (text in texts) when (val result = embed(text)) { is CapabilityResult.Available -> vectors += result.value; is CapabilityResult.Unavailable -> return result }
        return CapabilityResult.Available(vectors)
    }
}
data class RagEvidence(val id: String, val text: String, val sourceDocumentId: String)
interface RagEngine { suspend fun retrieve(query: String, limit: Int): CapabilityResult<List<RagEvidence>> }
data class ApplicationPacket(val applicationId: String, val draftAnswers: List<ApplicationAnswer>, val missingQuestionKeys: List<String>, val evidence: List<RagEvidence>, val requiresUserReview: Boolean = true)
interface ApplicationPreparationEngine {
    suspend fun prepare(application: Application, opportunity: Opportunity, profile: CareerProfile, preferences: CareerPreferences, savedAnswers: List<SavedApplicationAnswer>): CapabilityResult<ApplicationPacket>
}
