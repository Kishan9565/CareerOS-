package com.kishan.careeros.data

import androidx.room.withTransaction
import com.kishan.careeros.domain.*

class UnifiedOpportunityStore(private val db: CareerDatabase) {
    suspend fun indexExisting() = db.withTransaction {
        db.dao().unindexed().forEach { row -> db.dao().keys(CrossSourceDedupe.keys(row.toDomain()).map { OpportunityKeyEntity(it, row.id) }) }
    }
    suspend fun saveBatch(rows: List<Opportunity>): Pair<Int, List<String>> = db.withTransaction {
        var changed = 0; val ids = mutableListOf<String>()
        for (raw in rows) {
            if (raw.title.isBlank() || raw.company.isBlank() || !Validation.httpUrl(raw.canonicalUrl)) continue
            val incoming = raw.copy(provenance = raw.provenance.ifEmpty { listOf(OpportunityProvenance(raw.sourceId, raw.sourceName, raw.externalId, raw.originalUrl, raw.canonicalUrl, raw.companyCareerUrl, raw.fetchedAt, raw.publishedAt, raw.lastSeenAt, raw.lifecycle)) })
            val keys = CrossSourceDedupe.keys(incoming)
            val matches = db.dao().lookup(keys).mapNotNull { load(it) }.sortedBy { it.discoveredAt }
            val keeper = matches.firstOrNull()
            var merged = keeper ?: incoming
            for (duplicate in matches.drop(1)) {
                val oldApplication = db.dao().applicationForOpportunity(duplicate.id)
                if (oldApplication != null) {
                    val keptApplication = db.dao().applicationForOpportunity(merged.id)
                    if (keptApplication == null) db.dao().put(oldApplication.copy(opportunityId = merged.id))
                    else {
                        val latest = if (oldApplication.updatedAt > keptApplication.updatedAt) oldApplication else keptApplication
                        val notes = listOfNotNull(keptApplication.notes, oldApplication.notes).distinct().joinToString("\n\n").ifBlank { null }
                        db.dao().put(latest.copy(id = keptApplication.id, opportunityId = merged.id, notes = notes))
                        db.dao().moveAnswers(oldApplication.id, keptApplication.id)
                        db.dao().deleteApplication(oldApplication.id)
                    }
                }
                merged = CrossSourceDedupe.merge(merged, duplicate)
                db.dao().moveKeys(duplicate.id, merged.id); db.dao().moveProvenance(duplicate.id, merged.id)
                db.dao().deleteOpportunity(duplicate.id)
            }
            if (keeper != null) merged = CrossSourceDedupe.merge(merged, incoming)
            if (keeper == null || keeper.contentHash != merged.contentHash || matches.size > 1) changed++
            db.dao().put(merged.toEntity())
            db.dao().provenance(merged.provenance.map { it.toEntity(merged.id) })
            db.dao().clearRequirements(merged.id)
            db.dao().requirements(merged.requirements.map { it.toEntity(merged.id) })
            db.dao().keys((keys + CrossSourceDedupe.keys(merged)).distinct().map { OpportunityKeyEntity(it, merged.id) })
            ids += merged.id
        }
        changed to ids
    }
    private suspend fun load(id: String): Opportunity? = db.dao().opportunity(id)?.let { it.opportunity.toDomain().copy(provenance = it.provenance.map(ProvenanceEntity::toDomain), requirements = it.requirements.map(RequirementEntity::toDomain)) }
    suspend fun closeMissing(sourceId: String, now: Long) = db.withTransaction {
        db.dao().closeMissing(sourceId, now)
        db.dao().sourceOpportunityIds(sourceId).forEach { id ->
            db.dao().opportunity(id)?.let { row -> db.dao().put(row.opportunity.copy(lifecycle = CrossSourceDedupe.lifecycle(row.provenance.map(ProvenanceEntity::toDomain)).name)) }
        }
    }
}
