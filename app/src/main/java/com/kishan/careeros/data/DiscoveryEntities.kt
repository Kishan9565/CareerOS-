package com.kishan.careeros.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kishan.careeros.domain.*

@Entity(tableName = "company_sources")
data class CompanySourceEntity(
    @PrimaryKey val id: String, val companyName: String, val providerType: String, val providerIdentifier: String,
    val careersUrl: String, val enabled: Boolean, val tags: String, val supportedLocations: String,
    val verificationState: String, val lastVerifiedAt: Long?, val verificationUrl: String,
) {
    fun toDomain() = CompanyCareerSource(id, companyName, ProviderType.valueOf(providerType), providerIdentifier, careersUrl, enabled, decodeList(tags), decodeList(supportedLocations), EndpointVerification.valueOf(verificationState), lastVerifiedAt, verificationUrl)
}
fun CompanyCareerSource.toEntity() = CompanySourceEntity(id, companyName, providerType.name, providerIdentifier, careersUrl, enabled, encodeList(tags), encodeList(supportedLocations), verificationState.name, lastVerifiedAt, verificationUrl)

@Entity(tableName = "opportunity_provenance", primaryKeys = ["sourceId", "recordKey"], foreignKeys = [ForeignKey(entity = OpportunityEntity::class, parentColumns = ["id"], childColumns = ["opportunityId"], onDelete = ForeignKey.CASCADE)], indices = [Index("opportunityId"), Index("canonicalUrl")])
data class ProvenanceEntity(
    val sourceId: String, val recordKey: String, val opportunityId: String, val sourceName: String, val externalId: String?,
    val originalUrl: String, val canonicalUrl: String, val companyCareerUrl: String?, val fetchedAt: Long?,
    val publishedAt: Long?, val lastSeenAt: Long?, val lifecycle: String, val official: Boolean,
) {
    fun toDomain() = OpportunityProvenance(sourceId, sourceName, externalId, originalUrl, canonicalUrl, companyCareerUrl, fetchedAt, publishedAt, lastSeenAt, OpportunityLifecycle.valueOf(lifecycle), official)
}
fun OpportunityProvenance.toEntity(id: String) = ProvenanceEntity(sourceId, externalId ?: canonicalUrl, id, sourceName, externalId, originalUrl, canonicalUrl, companyCareerUrl, fetchedAt, publishedAt, lastSeenAt, lifecycle.name, official)

@Entity(tableName = "opportunity_keys", foreignKeys = [ForeignKey(entity = OpportunityEntity::class, parentColumns = ["id"], childColumns = ["opportunityId"], onDelete = ForeignKey.CASCADE)], indices = [Index("opportunityId")])
data class OpportunityKeyEntity(@PrimaryKey val lookupKey: String, val opportunityId: String)

object DiscoveryEngineMigration {
    val MIGRATION_2_3 = object: Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE source_sync ADD COLUMN failureCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE opportunities ADD COLUMN originalUrl TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE opportunities ADD COLUMN companyCareerUrl TEXT")
            db.execSQL("ALTER TABLE opportunities ADD COLUMN fetchedAt INTEGER")
            db.execSQL("ALTER TABLE opportunities ADD COLUMN lastSeenAt INTEGER")
            db.execSQL("ALTER TABLE opportunities ADD COLUMN lifecycle TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE opportunities ADD COLUMN workplace TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("UPDATE opportunities SET originalUrl=canonicalUrl, workplace=CASE WHEN remote=1 THEN 'REMOTE' ELSE 'UNKNOWN' END")
            db.execSQL("CREATE TABLE company_sources (id TEXT NOT NULL PRIMARY KEY, companyName TEXT NOT NULL, providerType TEXT NOT NULL, providerIdentifier TEXT NOT NULL, careersUrl TEXT NOT NULL, enabled INTEGER NOT NULL, tags TEXT NOT NULL, supportedLocations TEXT NOT NULL, verificationState TEXT NOT NULL, lastVerifiedAt INTEGER, verificationUrl TEXT NOT NULL)")
            db.execSQL("CREATE TABLE opportunity_provenance (sourceId TEXT NOT NULL, recordKey TEXT NOT NULL, opportunityId TEXT NOT NULL, sourceName TEXT NOT NULL, externalId TEXT, originalUrl TEXT NOT NULL, canonicalUrl TEXT NOT NULL, companyCareerUrl TEXT, fetchedAt INTEGER, publishedAt INTEGER, lastSeenAt INTEGER, lifecycle TEXT NOT NULL, official INTEGER NOT NULL, PRIMARY KEY(sourceId,recordKey), FOREIGN KEY(opportunityId) REFERENCES opportunities(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_opportunity_provenance_opportunityId ON opportunity_provenance(opportunityId)")
            db.execSQL("CREATE INDEX index_opportunity_provenance_canonicalUrl ON opportunity_provenance(canonicalUrl)")
            db.execSQL("INSERT OR IGNORE INTO opportunity_provenance SELECT sourceId,COALESCE(externalId,canonicalUrl),id,sourceName,externalId,canonicalUrl,canonicalUrl,NULL,NULL,publishedAt,NULL,'UNKNOWN',0 FROM opportunities")
            db.execSQL("CREATE TABLE opportunity_keys (lookupKey TEXT NOT NULL PRIMARY KEY, opportunityId TEXT NOT NULL, FOREIGN KEY(opportunityId) REFERENCES opportunities(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_opportunity_keys_opportunityId ON opportunity_keys(opportunityId)")
        }
    }
}
