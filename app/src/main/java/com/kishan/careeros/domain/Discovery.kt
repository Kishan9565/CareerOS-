package com.kishan.careeros.domain

import kotlinx.coroutines.flow.Flow

/** Source-neutral, untrusted input. Unknown values remain null. */
data class RawOpportunity(
    val externalId: String?, val title: String, val company: String,
    val location: String?, val remote: Boolean?, val employmentType: EmploymentType?,
    val description: String, val skills: List<String>, val url: String,
    val publishedAt: Long?, val workplace: Workplace = Workplace.UNKNOWN, val salary: Salary? = null,
)

data class SourceSync(
    val sourceId: String, val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null, val lastFailureAt: Long? = null,
    val lastError: String? = null, val nextAllowedAt: Long = 0,
    val receivedCount: Int = 0, val attempts: List<Long> = emptyList(), val failureCount: Int = 0,
)
data class DiscoveryStatus(val enabled: Boolean = true, val sync: SourceSync? = null)
sealed interface DiscoveryResult {
    data class Success(val received: Int, val changed: Int): DiscoveryResult
    data class Failure(val message: String): DiscoveryResult
    data class Deferred(val message: String): DiscoveryResult
}
interface DiscoveryRepository {
    fun observeStatus(): Flow<DiscoveryStatus>
    suspend fun discover(): DiscoveryResult
    suspend fun setEnabled(enabled: Boolean)
}
data class TrackedOpportunity(val application: Application, val opportunity: Opportunity)
interface OpportunityTracker {
    fun observe(): Flow<List<TrackedOpportunity>>
    suspend fun save(opportunityId: String, prepare: Boolean): Application
}
