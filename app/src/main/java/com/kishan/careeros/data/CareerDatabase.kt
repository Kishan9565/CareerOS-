package com.kishan.careeros.data

import androidx.room.*
import com.kishan.careeros.data.intelligence.*
import kotlinx.coroutines.flow.Flow

 data class ProfileAggregate(
    @Embedded val profile: ProfileEntity,
    @Relation(parentColumn = "id", entityColumn = "profileId") val preferences: PreferencesEntity?,
    @Relation(parentColumn = "id", entityColumn = "profileId") val skills: List<SkillEntity>,
    @Relation(parentColumn = "id", entityColumn = "profileId") val projects: List<ProjectEntity>,
    @Relation(parentColumn = "id", entityColumn = "profileId") val education: List<EducationEntity>,
)
data class OpportunityAggregate(
    @Embedded val opportunity: OpportunityEntity,
    @Relation(parentColumn = "id", entityColumn = "opportunityId") val requirements: List<RequirementEntity>,
    @Relation(parentColumn = "id", entityColumn = "opportunityId") val provenance: List<ProvenanceEntity>,
)
data class TrackedAggregate(
    @Embedded val application: ApplicationEntity,
    @Relation(parentColumn = "opportunityId", entityColumn = "id") val opportunity: OpportunityEntity,
)
@Dao
interface CareerDao {
    @Query("SELECT * FROM company_sources ORDER BY companyName") fun companySources(): Flow<List<CompanySourceEntity>>
    @Query("SELECT * FROM company_sources ORDER BY companyName") suspend fun getCompanies(): List<CompanySourceEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCompany(value: CompanySourceEntity)
    @Upsert suspend fun putCompany(value: CompanySourceEntity)
    @Query("SELECT * FROM source_sync") fun syncs(): Flow<List<SourceSyncEntity>>
    @Query("SELECT DISTINCT opportunityId FROM opportunity_keys WHERE lookupKey IN (:keys)") suspend fun lookup(keys: List<String>): List<String>
    @Upsert suspend fun keys(values: List<OpportunityKeyEntity>)
    @Query("UPDATE opportunity_keys SET opportunityId = :keep WHERE opportunityId = :old") suspend fun moveKeys(old: String, keep: String)
    @Upsert suspend fun provenance(values: List<ProvenanceEntity>)
    @Query("UPDATE opportunity_provenance SET opportunityId = :keep WHERE opportunityId = :old") suspend fun moveProvenance(old: String, keep: String)
    @Query("SELECT * FROM opportunity_provenance WHERE opportunityId = :id") suspend fun provenanceFor(id: String): List<ProvenanceEntity>
    @Query("SELECT DISTINCT opportunityId FROM opportunity_provenance WHERE sourceId = :source") suspend fun sourceOpportunityIds(source: String): List<String>
    @Query("UPDATE opportunity_provenance SET lifecycle = 'CLOSED' WHERE sourceId = :source AND lastSeenAt < :now") suspend fun closeMissing(source: String, now: Long)
    @Query("DELETE FROM opportunities WHERE id = :id") suspend fun deleteOpportunity(id: String)
    @Query("DELETE FROM applications WHERE id = :id") suspend fun deleteApplication(id: String)
    @Query("UPDATE application_answers SET applicationId = :keep WHERE applicationId = :old") suspend fun moveAnswers(old: String, keep: String)
    @Query("SELECT * FROM opportunities WHERE id NOT IN (SELECT opportunityId FROM opportunity_keys)") suspend fun unindexed(): List<OpportunityEntity>

    @Query("SELECT * FROM source_sync WHERE sourceId = :id") fun sync(id: String): Flow<SourceSyncEntity?>
    @Query("SELECT * FROM source_sync WHERE sourceId = :id") suspend fun getSync(id: String): SourceSyncEntity?
    @Upsert suspend fun put(value: SourceSyncEntity)
    @Query("SELECT * FROM source_configs WHERE id = :id") suspend fun source(id: String): SourceEntity?
    @Query("SELECT * FROM opportunities WHERE sourceId = :source AND externalId = :external LIMIT 1") suspend fun byExternal(source: String, external: String): OpportunityEntity?
    @Query("SELECT * FROM applications WHERE opportunityId = :id LIMIT 1") suspend fun applicationForOpportunity(id: String): ApplicationEntity?
    @Transaction @Query("SELECT * FROM applications ORDER BY updatedAt DESC LIMIT 500") fun tracked(): Flow<List<TrackedAggregate>>

    @Transaction @Query("SELECT * FROM career_profile WHERE id = 'primary'") fun profile(): Flow<ProfileAggregate?>
    @Upsert suspend fun put(value: ProfileEntity)
    @Upsert suspend fun put(value: PreferencesEntity)
    @Upsert suspend fun skills(value: List<SkillEntity>)
    @Upsert suspend fun projects(value: List<ProjectEntity>)
    @Upsert suspend fun education(value: List<EducationEntity>)
    @Query("DELETE FROM skills WHERE profileId = :id") suspend fun clearSkills(id: String)
    @Query("DELETE FROM projects WHERE profileId = :id") suspend fun clearProjects(id: String)
    @Query("DELETE FROM education WHERE profileId = :id") suspend fun clearEducation(id: String)
    @Transaction @Query("SELECT * FROM opportunities ORDER BY discoveredAt DESC") fun opportunities(): Flow<List<OpportunityAggregate>>
    @Transaction @Query("SELECT * FROM opportunities WHERE id = :id") suspend fun opportunity(id: String): OpportunityAggregate?
    @Upsert suspend fun put(value: OpportunityEntity)
    @Upsert suspend fun requirements(value: List<RequirementEntity>)
    @Query("DELETE FROM opportunity_requirements WHERE opportunityId = :id") suspend fun clearRequirements(id: String)
    @Query("SELECT * FROM applications ORDER BY updatedAt DESC LIMIT 500") fun applications(): Flow<List<ApplicationEntity>>
    @Query("SELECT * FROM applications WHERE id = :id") suspend fun application(id: String): ApplicationEntity?
    @Upsert suspend fun put(value: ApplicationEntity)
    @Query("SELECT * FROM saved_application_answers ORDER BY updatedAt DESC") fun savedAnswers(): Flow<List<SavedAnswerEntity>>
    @Upsert suspend fun put(value: SavedAnswerEntity)
    @Query("DELETE FROM saved_application_answers WHERE id = :id") suspend fun deleteAnswer(id: String)
    @Query("SELECT * FROM application_answers WHERE applicationId = :id ORDER BY createdAt") fun answers(id: String): Flow<List<AnswerEntity>>
    @Upsert suspend fun put(value: AnswerEntity)
    @Query("SELECT * FROM source_configs ORDER BY name") fun sources(): Flow<List<SourceEntity>>
    @Upsert suspend fun put(value: SourceEntity)
}
@Database(entities = [ProfileEntity::class, PreferencesEntity::class, SkillEntity::class, ProjectEntity::class, EducationEntity::class, OpportunityEntity::class, RequirementEntity::class, ApplicationEntity::class, SavedAnswerEntity::class, AnswerEntity::class, SourceEntity::class, SourceSyncEntity::class, CompanySourceEntity::class, ProvenanceEntity::class, OpportunityKeyEntity::class, KnowledgeEntity::class, ChunkEntity::class, VectorEntity::class, AnalysisEntity::class], version = 4, exportSchema = true)
abstract class CareerDatabase : RoomDatabase() { abstract fun dao(): CareerDao; abstract fun knowledge(): KnowledgeDao }
