package com.kishan.careeros

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kishan.careeros.data.*
import com.kishan.careeros.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DiscoveryPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun discoveryDeduplicatesPreservesTrackerAndRetainsDataAfterNetworkFailure() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, CareerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val job = OpportunityNormalizer.normalize(RawOpportunity("123", "Developer", "Fixture", null, true, null, "Build software", emptyList(), "https://remotive.com/job/123", null), "remotive", "Remotive", 1)!!
            var response: CapabilityResult<List<Opportunity>> = CapabilityResult.Available(listOf(job, job))
            val source = object: OpportunitySource {
                override val sourceId = "remotive"
                override suspend fun discover(config: SourceConfig) = response
            }
            val discovery = RoomDiscoveryRepository(db, source)
            val tracker = RoomOpportunityTracker(db)
            assertEquals(DiscoveryResult.Success(1, 1), discovery.discover())
            val app = tracker.save(job.id, false)
            assertEquals(app.id, tracker.save(job.id, true).id)
            assertEquals(ApplicationStatus.PREPARING, tracker.observe().first().single().application.status)
            assertTrue(discovery.discover() is DiscoveryResult.Deferred)
            db.dao().put(db.dao().getSync("remotive")!!.copy(nextAllowedAt = 0))
            assertEquals(DiscoveryResult.Success(1, 0), discovery.discover())
            db.dao().put(db.dao().getSync("remotive")!!.copy(nextAllowedAt = 0))
            response = CapabilityResult.Available(listOf(job.copy(description = "Changed", contentHash = "changed")))
            assertEquals(DiscoveryResult.Success(1, 1), discovery.discover())
            assertEquals(OpportunityStatus.SAVED, RoomOpportunityRepository(db).get(job.id)!!.status)
            db.dao().put(db.dao().getSync("remotive")!!.copy(nextAllowedAt = 0))
            response = CapabilityResult.Unavailable("Offline")
            assertTrue(discovery.discover() is DiscoveryResult.Failure)
            assertEquals("Changed", RoomOpportunityRepository(db).get(job.id)!!.description)
            assertEquals(1, tracker.observe().first().size)
            assertEquals("Offline", discovery.observeStatus().first().sync!!.lastError)
            discovery.setEnabled(false)
            assertTrue(discovery.discover() is DiscoveryResult.Deferred)
        } finally { db.close() }
    }

    @Test fun migrationPreservesVersionOneProfileAndValidatesNewSchema() = runTest {
        val name = "discovery-migration-test.db"
        context.deleteDatabase(name)
        val schema = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/com.kishan.careeros.data.CareerDatabase/1.json")).bufferedReader().use { it.readText() }).jsonObject["database"]!!.jsonObject
        context.openOrCreateDatabase(name, 0, null).use { old ->
            schema["entities"]!!.jsonArray.forEach { element ->
                val entity = element.jsonObject
                val table = entity["tableName"]!!.jsonPrimitive.content
                old.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index -> old.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            old.execSQL("INSERT INTO career_profile(id,name,headline,updatedAt) VALUES('primary','User','Developer',1)")
            old.execSQL("INSERT INTO opportunities(id,title,company,description,skills,sourceId,sourceName,canonicalUrl,discoveredAt,contentHash,status) VALUES('job','Developer','Fixture','Description','[]','source','Source','https://example.com/job',1,'hash','NEW')")
            old.execSQL("INSERT INTO applications(id,opportunityId,status,createdAt,updatedAt) VALUES('old','job','SAVED',1,1),('latest','job','PREPARING',1,2)")
            old.execSQL("INSERT INTO application_answers(id,applicationId,normalizedQuestionKey,originalQuestion,answer,category,source,verification,evidenceIds,createdAt,updatedAt) VALUES('answer','old','relocation','Relocate?','YES','PREFERENCE','USER','VERIFIED','[]',1,1)")
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, CareerDatabase::class.java, name).addMigrations(DiscoveryMigration.MIGRATION_1_2, DiscoveryEngineMigration.MIGRATION_2_3, com.kishan.careeros.data.intelligence.KnowledgeMigration.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals("User", RoomProfileRepository(migrated).observe().first()!!.name)
            assertNull(migrated.dao().getSync("remotive"))
            assertEquals("latest", migrated.dao().applications().first().single().id)
            assertEquals("answer", migrated.dao().answers("latest").first().single().id)
            assertEquals(4, migrated.openHelper.writableDatabase.version)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
