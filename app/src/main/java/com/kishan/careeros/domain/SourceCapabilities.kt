package com.kishan.careeros.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

enum class DiscoveryMode { AUTOMATIC, USER_IMPORT, USER_SEARCH }
enum class SourceKind { AGGREGATOR, ATS, COMPANY_CAREER, PLATFORM, MANUAL }
enum class ProviderType { GREENHOUSE, LEVER, PUBLIC_FEED, SUPPORTED_COMPANY_PAGE, EXTERNAL_SEARCH_ONLY }
enum class EndpointVerification { VERIFIED_ENDPOINT, UNVERIFIED }
data class RefreshPolicy(val intervalMillis: Long = 6 * 60 * 60_000L, val maxAttemptsPerDay: Int = 4, val failureDelayMillis: Long = 15 * 60_000L)
data class SourceCapabilities(
    val sourceId: String, val displayName: String, val sourceType: SourceKind, val discoveryMode: DiscoveryMode,
    val supportsQuery: Boolean = false, val supportsLocation: Boolean = false, val supportsRemote: Boolean = false,
    val supportsInternships: Boolean = false, val supportsJobs: Boolean = true, val supportsFreelance: Boolean = false,
    val refreshPolicy: RefreshPolicy = RefreshPolicy(), val attributionRequirement: String = "Link to the original listing",
)
data class CompanyCareerSource(
    val id: String, val companyName: String, val providerType: ProviderType, val providerIdentifier: String,
    val careersUrl: String, val enabled: Boolean, val tags: List<String>, val supportedLocations: List<String>,
    val verificationState: EndpointVerification, val lastVerifiedAt: Long?, val verificationUrl: String,
    val lastSuccessfulSync: Long? = null, val lastFailure: String? = null, val failureCount: Int = 0,
)
data class SourceStatus(val capabilities: SourceCapabilities, val enabled: Boolean, val sync: SourceSync?, val company: CompanyCareerSource? = null)
data class SourceSearchAction(val id: String, val displayName: String, val url: String, val suggestedTerms: List<String>, val mode: DiscoveryMode = DiscoveryMode.USER_SEARCH) {
    val capabilities get() = SourceCapabilities(id, displayName, SourceKind.PLATFORM, mode, attributionRequirement = "User opens official search destination; no automatic search performed")
}
object UserSearchDestinations {
    fun actions(profile: CareerSearchProfile?): List<SourceSearchAction> {
        val terms = profile?.let { SearchIntentBuilder.build(it).map(SearchIntent::role) }.orEmpty()
        return listOf("linkedin" to ("LinkedIn" to "https://www.linkedin.com/jobs/"), "indeed" to ("Indeed" to "https://www.indeed.com/"), "naukri" to ("Naukri" to "https://www.naukri.com/"), "internshala" to ("Internshala" to "https://internshala.com/internships/"), "wellfound" to ("Wellfound" to "https://wellfound.com/jobs")).map { (id, info) -> SourceSearchAction(id, info.first, info.second, terms) }
    }
}
data class SourceRunResult(val sourceId: String, val received: Int = 0, val changed: Int = 0, val error: String? = null, val deferred: String? = null)
data class DiscoveryRun(val sources: List<SourceRunResult>) {
    val received get() = sources.sumOf { it.received }
    val changed get() = sources.sumOf { it.changed }
    val failures get() = sources.count { it.error != null }
}
interface AutomaticSourceRunner { suspend fun run(source: SourceStatus, profile: CareerSearchProfile, intents: List<SearchIntent>): SourceRunResult }
/** Framework-independent entry point for UI or a future WorkManager worker. */
class OpportunityDiscoveryOrchestrator(private val runner: AutomaticSourceRunner) {
    suspend fun discover(profile: CareerSearchProfile, intents: List<SearchIntent>, sources: List<SourceStatus>): DiscoveryRun {
        val results = mutableListOf<SourceRunResult>()
        for (source in sources.filter { it.enabled && it.capabilities.discoveryMode == DiscoveryMode.AUTOMATIC }) {
            results += try { runner.run(source, profile, intents) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { SourceRunResult(source.capabilities.sourceId, error = "Source unavailable; cached results retained.") }
        }
        return DiscoveryRun(results)
    }
}
interface DiscoveryEngine {
    fun observeSources(): Flow<List<SourceStatus>>
    suspend fun discover(profile: CareerSearchProfile): DiscoveryRun
    suspend fun setEnabled(sourceId: String, enabled: Boolean)
    suspend fun importOpportunity(review: ImportReview): String
}
enum class OpportunityLifecycle { ACTIVE, UNKNOWN, CLOSED }
enum class Workplace { REMOTE, HYBRID, ONSITE, UNKNOWN }
data class OpportunityProvenance(
    val sourceId: String, val sourceName: String, val externalId: String?, val originalUrl: String, val canonicalUrl: String,
    val companyCareerUrl: String?, val fetchedAt: Long?, val publishedAt: Long?, val lastSeenAt: Long?,
    val lifecycle: OpportunityLifecycle, val official: Boolean = false,
)
