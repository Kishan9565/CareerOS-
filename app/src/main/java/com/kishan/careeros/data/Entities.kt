package com.kishan.careeros.data

import androidx.room.*
import com.kishan.careeros.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun encodeList(value: List<String>): String = Json.encodeToString(value)
internal fun decodeList(value: String): List<String> = Json.decodeFromString(value)

@Entity(tableName = "career_profile")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val headline: String,
    val experienceYears: Double?,
    val noticePeriodDays: Int?,
    val githubUrl: String?,
    val linkedinUrl: String?,
    val updatedAt: Long,
)

@Entity(tableName = "career_preferences", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId")])
data class PreferencesEntity(
    @PrimaryKey val profileId: String,
    val targetRoles: String,
    val locations: String,
    val remote: String,
    val employmentTypes: String,
    val salaryMinimum: Long? = null, val salaryMaximum: Long? = null, val salaryCurrency: String? = null, val salaryPeriod: String? = null,
)

@Entity(tableName = "skills", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId")])
data class SkillEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val name: String,
)
fun SkillEntity.toDomain(): Skill = Skill(
    id = id,
    name = name,
)
fun Skill.toEntity(profileId: String): SkillEntity = SkillEntity(
    id = id,
    profileId = profileId,
    name = name,
)

@Entity(tableName = "projects", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId")])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val name: String,
    val description: String,
    val url: String?,
)
fun ProjectEntity.toDomain(): ProjectReference = ProjectReference(
    id = id,
    name = name,
    description = description,
    url = url,
)
fun ProjectReference.toEntity(profileId: String): ProjectEntity = ProjectEntity(
    id = id,
    profileId = profileId,
    name = name,
    description = description,
    url = url,
)

@Entity(tableName = "education", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId")])
data class EducationEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val institution: String,
    val qualification: String,
    val startYear: Int?,
    val endYear: Int?,
)
fun EducationEntity.toDomain(): Education = Education(
    id = id,
    institution = institution,
    qualification = qualification,
    startYear = startYear,
    endYear = endYear,
)
fun Education.toEntity(profileId: String): EducationEntity = EducationEntity(
    id = id,
    profileId = profileId,
    institution = institution,
    qualification = qualification,
    startYear = startYear,
    endYear = endYear,
)

@Entity(tableName = "opportunities", indices = [Index(value = ["sourceId", "externalId"], unique = true), Index("canonicalUrl")])
data class OpportunityEntity(
    @PrimaryKey val id: String,
    val title: String,
    val company: String,
    val location: String?,
    val remote: Boolean?,
    val employmentType: String?,
    val description: String,
    val skills: String,
    val sourceId: String,
    val sourceName: String,
    val canonicalUrl: String,
    val externalId: String?,
    val publishedAt: Long?,
    val deadline: Long?,
    val discoveredAt: Long,
    val contentHash: String,
    val status: String,
    val analysisVersion: String?,
    val analyzedAt: Long?,
    val salaryMinimum: Long? = null, val salaryMaximum: Long? = null, val salaryCurrency: String? = null, val salaryPeriod: String? = null,
    @ColumnInfo(defaultValue = "''") val originalUrl: String = "",
    val companyCareerUrl: String? = null, val fetchedAt: Long? = null, val lastSeenAt: Long? = null,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val lifecycle: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val workplace: String = "UNKNOWN",
)
fun OpportunityEntity.toDomain(): Opportunity = Opportunity(
    originalUrl = originalUrl.ifEmpty { canonicalUrl }, companyCareerUrl = companyCareerUrl, fetchedAt = fetchedAt, lastSeenAt = lastSeenAt, lifecycle = OpportunityLifecycle.valueOf(lifecycle), workplace = Workplace.valueOf(workplace),
    id = id,
    title = title,
    company = company,
    location = location,
    remote = remote,
    employmentType = employmentType?.let { EmploymentType.valueOf(it) },
    description = description,
    skills = decodeList(skills),
    sourceId = sourceId,
    sourceName = sourceName,
    canonicalUrl = canonicalUrl,
    externalId = externalId,
    publishedAt = publishedAt,
    deadline = deadline,
    discoveredAt = discoveredAt,
    contentHash = contentHash,
    status = OpportunityStatus.valueOf(status),
    analysisVersion = analysisVersion,
    analyzedAt = analyzedAt,
    salary = salaryCurrency?.let { Salary(salaryMinimum, salaryMaximum, it, SalaryPeriod.valueOf(requireNotNull(salaryPeriod))) },
)
fun Opportunity.toEntity(): OpportunityEntity = OpportunityEntity(
    originalUrl = originalUrl, companyCareerUrl = companyCareerUrl, fetchedAt = fetchedAt, lastSeenAt = lastSeenAt, lifecycle = lifecycle.name, workplace = workplace.name,
    id = id,
    title = title,
    company = company,
    location = location,
    remote = remote,
    employmentType = employmentType?.name,
    description = description,
    skills = encodeList(skills),
    sourceId = sourceId,
    sourceName = sourceName,
    canonicalUrl = canonicalUrl,
    externalId = externalId,
    publishedAt = publishedAt,
    deadline = deadline,
    discoveredAt = discoveredAt,
    contentHash = contentHash,
    status = status.name,
    analysisVersion = analysisVersion,
    analyzedAt = analyzedAt,
    salaryMinimum = salary?.minimum, salaryMaximum = salary?.maximum, salaryCurrency = salary?.currency, salaryPeriod = salary?.period?.name,
)

@Entity(tableName = "opportunity_requirements", foreignKeys = [ForeignKey(entity = OpportunityEntity::class, parentColumns = ["id"], childColumns = ["opportunityId"], onDelete = ForeignKey.CASCADE)], indices = [Index("opportunityId")])
data class RequirementEntity(
    @PrimaryKey val id: String,
    val opportunityId: String,
    val text: String,
    val required: Boolean,
)
fun RequirementEntity.toDomain(): OpportunityRequirement = OpportunityRequirement(
    id = id,
    text = text,
    required = required,
)
fun OpportunityRequirement.toEntity(opportunityId: String): RequirementEntity = RequirementEntity(
    id = id,
    opportunityId = opportunityId,
    text = text,
    required = required,
)

@Entity(tableName = "applications", foreignKeys = [ForeignKey(entity = OpportunityEntity::class, parentColumns = ["id"], childColumns = ["opportunityId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["opportunityId"], unique = true)])
data class ApplicationEntity(
    @PrimaryKey val id: String,
    val opportunityId: String,
    val status: String,
    val appliedAt: Long?,
    val followUpAt: Long?,
    val interviewAt: Long?,
    val notes: String?,
    val resumeVersionId: String?,
    val coverLetter: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
fun ApplicationEntity.toDomain(): Application = Application(
    id = id,
    opportunityId = opportunityId,
    status = ApplicationStatus.valueOf(status),
    appliedAt = appliedAt,
    followUpAt = followUpAt,
    interviewAt = interviewAt,
    notes = notes,
    resumeVersionId = resumeVersionId,
    coverLetter = coverLetter,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun Application.toEntity(): ApplicationEntity = ApplicationEntity(
    id = id,
    opportunityId = opportunityId,
    status = status.name,
    appliedAt = appliedAt,
    followUpAt = followUpAt,
    interviewAt = interviewAt,
    notes = notes,
    resumeVersionId = resumeVersionId,
    coverLetter = coverLetter,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

@Entity(tableName = "saved_application_answers", indices = [Index("normalizedQuestionKey")])
data class SavedAnswerEntity(
    @PrimaryKey val id: String,
    val normalizedQuestionKey: String,
    val originalQuestion: String,
    val answer: String,
    val category: String,
    val source: String,
    val verification: String,
    val reusable: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
fun SavedAnswerEntity.toDomain(): SavedApplicationAnswer = SavedApplicationAnswer(
    id = id,
    normalizedQuestionKey = normalizedQuestionKey,
    originalQuestion = originalQuestion,
    answer = answer,
    category = AnswerCategory.valueOf(category),
    source = AnswerSource.valueOf(source),
    verification = Verification.valueOf(verification),
    reusable = reusable,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun SavedApplicationAnswer.toEntity(): SavedAnswerEntity = SavedAnswerEntity(
    id = id,
    normalizedQuestionKey = normalizedQuestionKey,
    originalQuestion = originalQuestion,
    answer = answer,
    category = category.name,
    source = source.name,
    verification = verification.name,
    reusable = reusable,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

@Entity(tableName = "application_answers", foreignKeys = [ForeignKey(entity = ApplicationEntity::class, parentColumns = ["id"], childColumns = ["applicationId"], onDelete = ForeignKey.CASCADE)], indices = [Index("applicationId")])
data class AnswerEntity(
    @PrimaryKey val id: String,
    val applicationId: String,
    val normalizedQuestionKey: String,
    val originalQuestion: String,
    val answer: String,
    val category: String,
    val source: String,
    val verification: String,
    val savedAnswerId: String?,
    val evidenceIds: String,
    val createdAt: Long,
    val updatedAt: Long,
)
fun AnswerEntity.toDomain(): ApplicationAnswer = ApplicationAnswer(
    id = id,
    applicationId = applicationId,
    normalizedQuestionKey = normalizedQuestionKey,
    originalQuestion = originalQuestion,
    answer = answer,
    category = AnswerCategory.valueOf(category),
    source = AnswerSource.valueOf(source),
    verification = Verification.valueOf(verification),
    savedAnswerId = savedAnswerId,
    evidenceIds = decodeList(evidenceIds),
    createdAt = createdAt,
    updatedAt = updatedAt,
)
fun ApplicationAnswer.toEntity(): AnswerEntity = AnswerEntity(
    id = id,
    applicationId = applicationId,
    normalizedQuestionKey = normalizedQuestionKey,
    originalQuestion = originalQuestion,
    answer = answer,
    category = category.name,
    source = source.name,
    verification = verification.name,
    savedAnswerId = savedAnswerId,
    evidenceIds = encodeList(evidenceIds),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

@Entity(tableName = "source_configs")
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val url: String,
    val enabled: Boolean,
    val lastFetchedAt: Long?,
)
fun SourceEntity.toDomain(): SourceConfig = SourceConfig(
    id = id,
    name = name,
    type = SourceType.valueOf(type),
    url = url,
    enabled = enabled,
    lastFetchedAt = lastFetchedAt,
)
fun SourceConfig.toEntity(): SourceEntity = SourceEntity(
    id = id,
    name = name,
    type = type.name,
    url = url,
    enabled = enabled,
    lastFetchedAt = lastFetchedAt,
)

