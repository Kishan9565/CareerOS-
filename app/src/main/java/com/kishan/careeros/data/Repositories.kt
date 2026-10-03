package com.kishan.careeros.data

import androidx.room.withTransaction
import com.kishan.careeros.domain.*
import kotlinx.coroutines.flow.map
import javax.inject.Inject

fun ProfileAggregate.toDomain(): CareerProfile {
    val pref = preferences
    return CareerProfile(profile.id, profile.name, profile.headline,
        pref?.let { CareerPreferences(decodeList(it.targetRoles), decodeList(it.locations), RemotePreference.valueOf(it.remote), decodeList(it.employmentTypes).map(EmploymentType::valueOf).toSet(), it.salaryCurrency?.let { currency -> Salary(pref.salaryMinimum, pref.salaryMaximum, currency, SalaryPeriod.valueOf(requireNotNull(pref.salaryPeriod))) }) } ?: CareerPreferences(),
        skills.map { it.toDomain() }, projects.map { it.toDomain() }, education.map { it.toDomain() }, profile.experienceYears, profile.noticePeriodDays, profile.githubUrl, profile.linkedinUrl, profile.updatedAt)
}
class RoomProfileRepository @Inject constructor(private val db: CareerDatabase): ProfileRepository {
    override fun observe() = db.dao().profile().map { it?.toDomain() }
    override suspend fun save(profile: CareerProfile) {
        require(Validation.profile(profile).isEmpty())
        db.withTransaction {
            val d = db.dao(); val p = profile; val pref = p.preferences
            d.put(ProfileEntity(p.id, p.name, p.headline, p.experienceYears, p.noticePeriodDays, p.githubUrl, p.linkedinUrl, p.updatedAt))
            d.put(PreferencesEntity(p.id, encodeList(pref.targetRoles), encodeList(pref.locations), pref.remote.name, encodeList(pref.employmentTypes.map { it.name }), pref.minimumSalary?.minimum, pref.minimumSalary?.maximum, pref.minimumSalary?.currency, pref.minimumSalary?.period?.name))
            d.clearSkills(p.id); d.skills(p.skills.map { it.toEntity(p.id) })
            d.clearProjects(p.id); d.projects(p.projects.map { it.toEntity(p.id) })
            d.clearEducation(p.id); d.education(p.education.map { it.toEntity(p.id) })
        }
    }
}
class RoomOpportunityRepository @Inject constructor(private val db: CareerDatabase): OpportunityRepository {
    override fun observeAll() = db.dao().opportunities().map { list -> list.map { it.opportunity.toDomain().copy(requirements = it.requirements.map { r -> r.toDomain() }, provenance = it.provenance.map { p -> p.toDomain() }) } }
    override suspend fun get(id: String) = db.dao().opportunity(db.dao().lookup(listOf("id:$id")).firstOrNull() ?: id)?.let { it.opportunity.toDomain().copy(requirements = it.requirements.map { r -> r.toDomain() }, provenance = it.provenance.map { p -> p.toDomain() }) }
    override suspend fun save(opportunity: Opportunity) {
        require(opportunity.title.isNotBlank() && opportunity.company.isNotBlank() && opportunity.contentHash.isNotBlank() && Validation.httpUrl(opportunity.canonicalUrl))
        db.withTransaction { db.dao().put(opportunity.toEntity()); db.dao().clearRequirements(opportunity.id); db.dao().requirements(opportunity.requirements.map { it.toEntity(opportunity.id) }) }
    }
}
class RoomApplicationRepository @Inject constructor(private val db: CareerDatabase): ApplicationRepository {
    override fun observeAll() = db.dao().applications().map { it.map { row -> row.toDomain() } }
    override suspend fun save(application: Application) = db.withTransaction {
        val old = db.dao().application(application.id)?.toDomain()
        require(if (old == null) application.status == ApplicationStatus.SAVED && application.appliedAt == null else old.status == application.status && old.appliedAt == application.appliedAt && old.opportunityId == application.opportunityId) { "Use transition to change application status" }
        db.dao().put(application.toEntity())
    }
    override suspend fun transition(id: String, status: ApplicationStatus, userConfirmedSubmission: Boolean) = db.withTransaction {
        val current = requireNotNull(db.dao().application(id)).toDomain()
        db.dao().put(current.transition(status, userConfirmedSubmission).toEntity())
    }
}
class RoomAnswerBankRepository @Inject constructor(private val db: CareerDatabase): AnswerBankRepository {
    override fun observeAll() = db.dao().savedAnswers().map { it.map { row -> row.toDomain() } }
    override suspend fun save(answer: SavedApplicationAnswer) { require(Validation.answer(answer).isEmpty()); db.dao().put(answer.toEntity()) }
    override suspend fun delete(id: String) = db.dao().deleteAnswer(id)
    override fun observeForApplication(id: String) = db.dao().answers(id).map { it.map { row -> row.toDomain() } }
    override suspend fun saveForApplication(answer: ApplicationAnswer) {
        require(Validation.answer(SavedApplicationAnswer(answer.id, answer.normalizedQuestionKey, answer.originalQuestion, answer.answer, answer.category, answer.source, answer.verification, false, answer.createdAt, answer.updatedAt)).isEmpty())
        db.dao().put(answer.toEntity())
    }
}
class RoomSourceRepository @Inject constructor(private val db: CareerDatabase): SourceRepository {
    override fun observeAll() = db.dao().sources().map { it.map { row -> row.toDomain() } }
    override suspend fun save(source: SourceConfig) { require(source.name.isNotBlank() && Validation.httpUrl(source.url)); db.dao().put(source.toEntity()) }
}
