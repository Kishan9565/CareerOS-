package com.kishan.careeros.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kishan.careeros.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "source_sync")
data class SourceSyncEntity(
    @PrimaryKey val sourceId: String, val lastAttemptAt: Long?, val lastSuccessAt: Long?,
    val lastFailureAt: Long?, val lastError: String?, val nextAllowedAt: Long,
    val receivedCount: Int, val attempts: String,
    @ColumnInfo(defaultValue = "0") val failureCount: Int = 0,
) {
    fun toDomain() = SourceSync(sourceId, lastAttemptAt, lastSuccessAt, lastFailureAt, lastError, nextAllowedAt, receivedCount, decodeList(attempts).map(String::toLong), failureCount)
}
fun SourceSync.toEntity() = SourceSyncEntity(sourceId, lastAttemptAt, lastSuccessAt, lastFailureAt, lastError, nextAllowedAt, receivedCount, encodeList(attempts.map(Long::toString)), failureCount)

object DiscoveryMigration {
    val MIGRATION_1_2 = object: Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS source_sync (sourceId TEXT NOT NULL PRIMARY KEY, lastAttemptAt INTEGER, lastSuccessAt INTEGER, lastFailureAt INTEGER, lastError TEXT, nextAllowedAt INTEGER NOT NULL, receivedCount INTEGER NOT NULL, attempts TEXT NOT NULL)")
            // Preserve answers when consolidating any old duplicate tracker rows. Keep latest activity.
            db.execSQL("UPDATE application_answers SET applicationId = (SELECT keep.id FROM applications old JOIN applications keep ON old.opportunityId = keep.opportunityId WHERE old.id = application_answers.applicationId ORDER BY keep.updatedAt DESC, keep.id LIMIT 1)")
            db.execSQL("DELETE FROM applications WHERE id NOT IN (SELECT (SELECT keep.id FROM applications keep WHERE keep.opportunityId = a.opportunityId ORDER BY keep.updatedAt DESC, keep.id LIMIT 1) FROM applications a)")
            db.execSQL("DROP INDEX IF EXISTS index_applications_opportunityId")
            db.execSQL("CREATE UNIQUE INDEX index_applications_opportunityId ON applications(opportunityId)")
        }
    }
}

class RoomDiscoveryRepository(private val db: CareerDatabase, private val source: OpportunitySource): DiscoveryRepository {
    private val mutex = Mutex()
    override fun observeStatus() = combine(db.dao().sources(), db.dao().sync(source.sourceId)) { configs, sync ->
        DiscoveryStatus(configs.find { it.id == source.sourceId }?.enabled ?: true, sync?.toDomain())
    }
    override suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
        val config = db.dao().source(source.sourceId)?.toDomain() ?: RemotiveSource.config()
        db.dao().put(config.copy(enabled = enabled).toEntity())
    }
    override suspend fun discover(): DiscoveryResult = mutex.withLock {
        val now = System.currentTimeMillis()
        try {
            val config = db.dao().source(source.sourceId)?.toDomain() ?: RemotiveSource.config().also { db.dao().put(it.toEntity()) }
            if (!config.enabled) return@withLock DiscoveryResult.Deferred("Enable Remotive to discover opportunities.")
            val previous = db.dao().getSync(source.sourceId)?.toDomain() ?: SourceSync(source.sourceId)
            val attempts = previous.attempts.filter { now - it < DAY }
            if (now < previous.nextAllowedAt || attempts.size >= 4) return@withLock DiscoveryResult.Deferred("Refresh is limited to protect the source. Check the next refresh time below.")
            val attempted = previous.copy(lastAttemptAt = now, attempts = attempts + now, nextAllowedAt = now + 15 * 60_000)
            db.dao().put(attempted.toEntity())
            when (val result = source.discover(config)) {
                is CapabilityResult.Unavailable -> {
                    val next = if (attempted.attempts.size >= 4) attempted.attempts.first() + DAY else attempted.nextAllowedAt
                    db.dao().put(attempted.copy(lastFailureAt = now, lastError = result.reason, nextAllowedAt = next).toEntity())
                    DiscoveryResult.Failure(result.reason)
                }
                is CapabilityResult.Available -> db.withTransaction {
                    var changed = 0
                    val valid = result.value.filter { it.sourceId == source.sourceId && it.title.isNotBlank() && it.company.isNotBlank() && it.description.isNotBlank() && Validation.httpUrl(it.canonicalUrl) }.distinctBy { it.id }
                    for (incoming in valid) {
                        val old = incoming.externalId?.let { db.dao().byExternal(incoming.sourceId, it) }
                            ?: db.dao().opportunity(incoming.id)?.opportunity
                        if (old?.contentHash == incoming.contentHash) continue
                        val merged = if (old == null) incoming else incoming.copy(id = old.id, status = OpportunityStatus.valueOf(old.status), discoveredAt = old.discoveredAt, analysisVersion = null, analyzedAt = null)
                        db.dao().put(merged.toEntity())
                        db.dao().clearRequirements(merged.id)
                        db.dao().requirements(merged.requirements.map { it.toEntity(merged.id) })
                        changed++
                    }
                    val next = maxOf(now + 6 * 60 * 60_000, if (attempted.attempts.size >= 4) attempted.attempts.first() + DAY else 0)
                    db.dao().put(attempted.copy(lastSuccessAt = now, lastError = null, nextAllowedAt = next, receivedCount = valid.size).toEntity())
                    db.dao().put(config.copy(lastFetchedAt = now).toEntity())
                    DiscoveryResult.Success(valid.size, changed)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            val message = "Could not complete discovery. Existing opportunities are unchanged. Please try again later."
            runCatching { db.dao().getSync(source.sourceId)?.let { db.dao().put(it.copy(lastFailureAt = now, lastError = message)) } }
            DiscoveryResult.Failure(message)
        }
    }
    private companion object { const val DAY = 24 * 60 * 60_000L }
}

class RoomOpportunityTracker(private val db: CareerDatabase): OpportunityTracker {
    override fun observe() = db.dao().tracked().map { list -> list.map { TrackedOpportunity(it.application.toDomain(), it.opportunity.toDomain()) } }
    override suspend fun save(opportunityId: String, prepare: Boolean): Application = db.withTransaction {
        val opportunity = requireNotNull(db.dao().opportunity(opportunityId)).opportunity
        val existing = db.dao().applicationForOpportunity(opportunityId)?.toDomain()
        val saved = existing ?: Application(opportunityId = opportunityId)
        val result = if (prepare && saved.status == ApplicationStatus.SAVED) saved.transition(ApplicationStatus.PREPARING) else saved
        db.dao().put(result.toEntity())
        db.dao().put(opportunity.copy(status = OpportunityStatus.SAVED.name))
        result
    }
}
