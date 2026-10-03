package com.kishan.careeros.domain

import java.net.URI
import java.util.Locale
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
enum class RemotePreference { UNSPECIFIED, REMOTE, HYBRID, ONSITE, FLEXIBLE }
enum class EmploymentType { FULL_TIME, PART_TIME, CONTRACT, INTERNSHIP, FREELANCE }
enum class SalaryPeriod { YEAR, MONTH, HOUR, PROJECT }
data class Salary(val minimum: Long?, val maximum: Long? = null, val currency: String, val period: SalaryPeriod = SalaryPeriod.YEAR) {
    init { require(minimum == null || minimum >= 0); require(maximum == null || maximum >= 0); require(minimum == null || maximum == null || maximum >= minimum); require(currency.matches(Regex("[A-Z]{3}"))) }
}
data class Skill(val id: String = newId(), val name: String)
data class ProjectReference(val id: String = newId(), val name: String, val description: String, val url: String? = null)
data class Education(val id: String = newId(), val institution: String, val qualification: String, val startYear: Int? = null, val endYear: Int? = null)
data class CareerPreferences(val targetRoles: List<String> = emptyList(), val locations: List<String> = emptyList(), val remote: RemotePreference = RemotePreference.UNSPECIFIED, val employmentTypes: Set<EmploymentType> = emptySet(), val minimumSalary: Salary? = null)
data class CareerProfile(val id: String = "primary", val name: String, val headline: String, val preferences: CareerPreferences = CareerPreferences(), val skills: List<Skill> = emptyList(), val projects: List<ProjectReference> = emptyList(), val education: List<Education> = emptyList(), val experienceYears: Double? = null, val noticePeriodDays: Int? = null, val githubUrl: String? = null, val linkedinUrl: String? = null, val updatedAt: Long = System.currentTimeMillis())
enum class OpportunityStatus { NEW, SAVED, ARCHIVED, EXPIRED }
data class OpportunityRequirement(val id: String = newId(), val text: String, val required: Boolean = true)
data class Opportunity(val id: String = newId(), val title: String, val company: String, val location: String? = null, val remote: Boolean? = null, val employmentType: EmploymentType? = null, val salary: Salary? = null, val description: String, val requirements: List<OpportunityRequirement> = emptyList(), val skills: List<String> = emptyList(), val sourceId: String, val sourceName: String, val canonicalUrl: String, val externalId: String? = null, val publishedAt: Long? = null, val deadline: Long? = null, val discoveredAt: Long = System.currentTimeMillis(), val contentHash: String, val status: OpportunityStatus = OpportunityStatus.NEW, val analysisVersion: String? = null, val analyzedAt: Long? = null, val originalUrl: String = canonicalUrl, val companyCareerUrl: String? = null, val fetchedAt: Long? = null, val lastSeenAt: Long? = null, val lifecycle: OpportunityLifecycle = OpportunityLifecycle.UNKNOWN, val workplace: Workplace = Workplace.UNKNOWN, val provenance: List<OpportunityProvenance> = emptyList())
enum class ApplicationStatus {
    SAVED, PREPARING, READY_TO_APPLY, APPLIED, ASSESSMENT, INTERVIEW, OFFER, REJECTED, WITHDRAWN, CLOSED;
    fun canTransitionTo(next: ApplicationStatus): Boolean = next == this || when (this) {
        SAVED -> next in setOf(PREPARING, READY_TO_APPLY, APPLIED, WITHDRAWN, CLOSED)
        PREPARING -> next in setOf(SAVED, READY_TO_APPLY, APPLIED, WITHDRAWN, CLOSED)
        READY_TO_APPLY -> next in setOf(PREPARING, APPLIED, WITHDRAWN, CLOSED)
        APPLIED -> next in setOf(ASSESSMENT, INTERVIEW, OFFER, REJECTED, WITHDRAWN, CLOSED)
        ASSESSMENT -> next in setOf(INTERVIEW, OFFER, REJECTED, WITHDRAWN, CLOSED)
        INTERVIEW -> next in setOf(ASSESSMENT, OFFER, REJECTED, WITHDRAWN, CLOSED)
        OFFER -> next in setOf(REJECTED, WITHDRAWN, CLOSED)
        REJECTED, WITHDRAWN -> next == CLOSED
        CLOSED -> false
    }
}
data class Application(val id: String = newId(), val opportunityId: String, val status: ApplicationStatus = ApplicationStatus.SAVED, val appliedAt: Long? = null, val followUpAt: Long? = null, val interviewAt: Long? = null, val notes: String? = null, val resumeVersionId: String? = null, val coverLetter: String? = null, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt) {
    fun transition(next: ApplicationStatus, userConfirmedSubmission: Boolean = false, now: Long = System.currentTimeMillis()): Application {
        require(status.canTransitionTo(next)) { "Invalid status transition" }
        require(next != ApplicationStatus.APPLIED || status == next || userConfirmedSubmission) { "Submission must be confirmed by the user" }
        return copy(status = next, appliedAt = if (next == ApplicationStatus.APPLIED) appliedAt ?: now else appliedAt, updatedAt = now)
    }
}
enum class AnswerCategory { PROFILE_FACT, CAREER_FACT, PREFERENCE, LEGAL_OR_ELIGIBILITY, JOB_SPECIFIC, GENERATIVE, UNKNOWN }
enum class AnswerSource { USER, IMPORTED, AI_DRAFT }
enum class Verification { VERIFIED, UNVERIFIED, UNKNOWN }
data class SavedApplicationAnswer(val id: String = newId(), val normalizedQuestionKey: String, val originalQuestion: String, val answer: String, val category: AnswerCategory, val source: AnswerSource, val verification: Verification, val reusable: Boolean, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt) {
    fun eligibleForAutofill(): Boolean = reusable && verification == Verification.VERIFIED && source != AnswerSource.AI_DRAFT && category !in setOf(AnswerCategory.UNKNOWN, AnswerCategory.GENERATIVE, AnswerCategory.JOB_SPECIFIC)
}
data class ApplicationAnswer(val id: String = newId(), val applicationId: String, val normalizedQuestionKey: String, val originalQuestion: String, val answer: String, val category: AnswerCategory, val source: AnswerSource, val verification: Verification, val savedAnswerId: String? = null, val evidenceIds: List<String> = emptyList(), val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt)
enum class SourceType { RSS, PUBLIC_API, PUBLIC_FEED, USER_URL }
data class SourceConfig(val id: String = newId(), val name: String, val type: SourceType, val url: String, val enabled: Boolean = false, val lastFetchedAt: Long? = null)

object Validation {
    fun httpUrl(value: String): Boolean = runCatching { val uri = URI(value); uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null }.getOrDefault(false)
    fun questionKey(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    fun profile(value: CareerProfile): List<String> = buildList {
        if (value.id != "primary") add("Unsupported profile ID")
        if (value.name.isBlank()) add("Enter your name")
        if (value.headline.isBlank()) add("Enter a headline")
        if (value.preferences.targetRoles.isEmpty()) add("Enter at least one target role")
        if (value.skills.isEmpty() || value.skills.any { it.name.isBlank() }) add("Enter at least one skill")
        if (value.noticePeriodDays != null && value.noticePeriodDays < 0) add("Notice period cannot be negative")
        if (value.experienceYears != null && (!value.experienceYears.isFinite() || value.experienceYears < 0)) add("Invalid experience")
        listOf(value.githubUrl, value.linkedinUrl).filterNotNull().forEach { if (!httpUrl(it)) add("Use a valid HTTP or HTTPS profile URL") }
    }
    fun answer(value: SavedApplicationAnswer): List<String> = buildList {
        if (value.normalizedQuestionKey.isBlank() || questionKey(value.normalizedQuestionKey) != value.normalizedQuestionKey) add("Use a normalized question key")
        if (value.originalQuestion.isBlank() || value.answer.isBlank()) add("Question and answer are required")
        if (value.updatedAt < value.createdAt) add("Invalid timestamps")
        if (value.source == AnswerSource.AI_DRAFT && (value.category != AnswerCategory.GENERATIVE || value.verification == Verification.VERIFIED)) add("AI may only supply unverified descriptive drafts")
        if (value.category == AnswerCategory.UNKNOWN && value.verification == Verification.VERIFIED) add("Unknown answers cannot be verified")
    }
}
