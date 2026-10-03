package com.kishan.careeros.data

import androidx.room.withTransaction
import com.kishan.careeros.domain.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI

class RoomDiscoveryEngine(
    private val db: CareerDatabase, private val client: HttpClient, private val catalog: () -> String,
): DiscoveryEngine, AutomaticSourceRunner {
    private val initializeMutex = Mutex(); private val runMutex = Mutex()
    private var initialized = false
    private val store = UnifiedOpportunityStore(db)
    private val remotive = RemotiveSource(client)
    private suspend fun initialize() = initializeMutex.withLock {
        if (!initialized) withContext(Dispatchers.IO) {
            val companies = CompanyCatalog.parse(catalog())
            db.withTransaction {
                companies.forEach { company ->
                    db.dao().insertCompany(company.toEntity())
                    if (db.dao().source(company.id) == null) db.dao().put(SourceConfig(company.id, company.companyName, SourceType.PUBLIC_API, CompanyCatalog.endpoint(company), company.enabled).toEntity())
                }
                if (db.dao().source(RemotiveSource.ID) == null) db.dao().put(RemotiveSource.config().toEntity())
                store.indexExisting()
            }
            initialized = true
        }
    }
    override fun observeSources(): Flow<List<SourceStatus>> = flow {
        initialize()
        emitAll(combine(db.dao().companySources(), db.dao().sources(), db.dao().syncs()) { companies, configs, syncs ->
            fun status(capabilities: SourceCapabilities, company: CompanyCareerSource? = null): SourceStatus {
                val sync = syncs.find { it.sourceId == capabilities.sourceId }?.toDomain()
                return SourceStatus(capabilities, configs.find { it.id == capabilities.sourceId }?.enabled ?: false, sync,
                    company?.copy(lastSuccessfulSync = sync?.lastSuccessAt, lastFailure = sync?.lastError, failureCount = sync?.failureCount ?: 0))
            }
            listOf(status(remotive.capabilities)) + companies.map { row ->
                val company = row.toDomain()
                val capabilities = if (company.providerType in setOf(ProviderType.GREENHOUSE, ProviderType.LEVER) && CompanyCatalog.validate(company))
                    PublicAtsSource(company, PublicBoardClient(client)).capabilities
                else SourceCapabilities(company.id, company.companyName, SourceKind.COMPANY_CAREER, DiscoveryMode.USER_SEARCH)
                status(capabilities, company)
            }
        })
    }
    override suspend fun setEnabled(sourceId: String, enabled: Boolean) = runMutex.withLock {
        initialize()
        db.withTransaction {
            val existing = requireNotNull(db.dao().source(sourceId))
            db.dao().put(existing.copy(enabled = enabled))
            db.dao().getCompanies().find { it.id == sourceId }?.let { db.dao().putCompany(it.copy(enabled = enabled)) }
        }
        Unit
    }
    override suspend fun discover(profile: CareerSearchProfile): DiscoveryRun = runMutex.withLock {
        initialize()
        OpportunityDiscoveryOrchestrator(this).discover(profile, SearchIntentBuilder.build(profile), observeSources().first())
    }
    override suspend fun run(source: SourceStatus, profile: CareerSearchProfile, intents: List<SearchIntent>): SourceRunResult {
        val id = source.capabilities.sourceId; val now = System.currentTimeMillis(); val policy = source.capabilities.refreshPolicy
        val previous = db.dao().getSync(id)?.toDomain() ?: SourceSync(id)
        val attempts = previous.attempts.filter { now - it < DAY }
        if (now < previous.nextAllowedAt || attempts.size >= policy.maxAttemptsPerDay) return SourceRunResult(id, deferred = "Refresh policy: waiting until next allowed attempt")
        val attempted = previous.copy(lastAttemptAt = now, attempts = attempts + now, nextAllowedAt = now + policy.failureDelayMillis)
        db.dao().put(attempted.toEntity())
        try {
            val connector = if (id == RemotiveSource.ID) remotive else PublicAtsSource(requireNotNull(source.company), PublicBoardClient(client))
            val config = requireNotNull(db.dao().source(id)).toDomain()
            val result = connector.discover(config, profile, intents)
            when (result) {
                is CapabilityResult.Available -> {
                    val outcome = db.withTransaction {
                        val rows = result.value.filter { it.sourceId == id }
                        val stored = store.saveBatch(rows)
                        if (connector is PublicAtsSource && connector.completeSnapshot) store.closeMissing(id, now)
                        val next = maxOf(now + policy.intervalMillis, if (attempted.attempts.size >= policy.maxAttemptsPerDay) attempted.attempts.first() + DAY else 0)
                        db.dao().put(attempted.copy(lastSuccessAt = now, lastError = null, nextAllowedAt = next, receivedCount = rows.size, failureCount = 0).toEntity())
                        db.dao().put(config.copy(lastFetchedAt = now).toEntity())
                        SourceRunResult(id, rows.size, stored.first)
                    }
                    return outcome
                }
                is CapabilityResult.Unavailable -> return fail(attempted, result.reason, policy)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return fail(attempted, "Could not complete source refresh; cached results retained.", policy) }
    }
    private suspend fun fail(attempt: SourceSync, message: String, policy: RefreshPolicy): SourceRunResult {
        val next = maxOf(attempt.nextAllowedAt, if (attempt.attempts.size >= policy.maxAttemptsPerDay) attempt.attempts.first() + DAY else 0)
        db.dao().put(attempt.copy(lastFailureAt = System.currentTimeMillis(), lastError = message, failureCount = attempt.failureCount + 1, nextAllowedAt = next).toEntity())
        return SourceRunResult(attempt.sourceId, error = message)
    }
    override suspend fun importOpportunity(review: ImportReview): String = runMutex.withLock {
        require(SharedOpportunityImporter.errors(review).isEmpty())
        initialize()
        val now = System.currentTimeMillis()
        val source = "import:${URI(review.url).host.lowercase()}"
        val raw = RawOpportunity(null, review.title, review.company, review.location.trim().ifBlank { null }, null, null, review.description, emptyList(), review.url, null)
        val job = requireNotNull(OpportunityNormalizer.normalize(raw, source, "Imported from ${SharedOpportunityImporter.sourceName(review.url)}", now, allowEmptyDescription = true))
            .copy(originalUrl = review.originalUrl.takeIf(Validation::httpUrl) ?: review.url, fetchedAt = null, lifecycle = OpportunityLifecycle.UNKNOWN)
        store.saveBatch(listOf(job)).second.single()
    }
    private companion object { const val DAY = 24 * 60 * 60_000L }
}
