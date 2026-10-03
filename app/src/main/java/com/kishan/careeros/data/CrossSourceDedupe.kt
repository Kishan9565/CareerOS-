package com.kishan.careeros.data

import com.kishan.careeros.domain.*
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object CrossSourceDedupe {
    fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun applicationUrl(value: String): String? = OpportunityNormalizer.canonicalUrl(value)?.let { canonical ->
        val uri = URI(canonical)
        when (uri.host) {
            "jobs.lever.co" -> canonical.replace(Regex("/apply(?=\\?|$)"), "")
            "boards.greenhouse.io" -> canonical.replace("boards.greenhouse.io", "job-boards.greenhouse.io")
            else -> canonical
        }
    }
    fun providerKey(url: String): String? = runCatching {
        val uri = URI(url); val parts = uri.path.trim('/').split('/')
        when {
            uri.host == "jobs.lever.co" && parts.size >= 2 -> "external:lever:${parts[0]}:${parts[1]}"
            uri.host in setOf("boards.greenhouse.io", "job-boards.greenhouse.io") && parts.size >= 3 && parts[1] == "jobs" -> "external:greenhouse:${parts[0]}:${parts[2]}"
            else -> null
        }
    }.getOrNull()
    /** Ordered strongest to weakest. Never merge missing-company/title placeholders. */
    fun keys(job: Opportunity): List<String> = buildList {
        add("id:${job.id}")
        (listOf(job.canonicalUrl) + job.provenance.map { it.canonicalUrl }).mapNotNull(::applicationUrl).distinct().forEach { add("url:$it") }
        job.externalId?.let { add("external:${job.sourceId}:$it") }
        job.provenance.forEach { p -> p.externalId?.let { add("external:${p.sourceId}:$it") } }
        (listOf(job.canonicalUrl) + job.provenance.map { it.canonicalUrl }).mapNotNull(::providerKey).forEach(::add)
        if (job.company.isNotBlank() && job.title.isNotBlank() && !job.location.isNullOrBlank()) add("identity:" + digest(encodeList(listOf(normalize(job.company), normalize(job.title), normalize(job.location)))))
        if (job.description.length >= 200) add("content:" + digest(encodeList(listOf(normalize(job.company), normalize(job.title), normalize(job.description)))))
    }.distinct()
    fun merge(existing: Opportunity, incoming: Opportunity): Opportunity {
        val oldOfficial = existing.provenance.any { it.official && it.canonicalUrl == existing.canonicalUrl }
        val newOfficial = incoming.provenance.any { it.official }
        val preferIncoming = newOfficial || !oldOfficial && incoming.description.isNotBlank()
        val primary = if (preferIncoming) incoming else existing
        val provenance = (existing.provenance + incoming.provenance).associateBy { it.sourceId to (it.externalId ?: it.canonicalUrl) }.values.toList()
        return primary.copy(id = existing.id, discoveredAt = minOf(existing.discoveredAt, incoming.discoveredAt), status = if (existing.status == OpportunityStatus.SAVED || incoming.status == OpportunityStatus.SAVED) OpportunityStatus.SAVED else existing.status,
            description = primary.description.ifBlank { existing.description.ifBlank { incoming.description } },
            provenance = provenance, lastSeenAt = listOfNotNull(existing.lastSeenAt, incoming.lastSeenAt).maxOrNull(),
            lifecycle = lifecycle(provenance), analysisVersion = if (existing.contentHash == primary.contentHash) existing.analysisVersion else null,
            analyzedAt = if (existing.contentHash == primary.contentHash) existing.analyzedAt else null)
    }
    fun lifecycle(provenance: List<OpportunityProvenance>): OpportunityLifecycle {
        val official = provenance.filter { it.official }
        val authoritative = official.ifEmpty { provenance }
        return when { authoritative.any { it.lifecycle == OpportunityLifecycle.ACTIVE } -> OpportunityLifecycle.ACTIVE
            authoritative.isNotEmpty() && authoritative.all { it.lifecycle == OpportunityLifecycle.CLOSED } -> OpportunityLifecycle.CLOSED
            else -> OpportunityLifecycle.UNKNOWN }
    }
}
