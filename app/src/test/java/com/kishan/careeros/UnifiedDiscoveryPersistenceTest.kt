package com.kishan.careeros

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kishan.careeros.data.*
import com.kishan.careeros.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class UnifiedDiscoveryPersistenceTest {
    @Test fun officialMergePreservesSavedImportAndClosesOnlyMissingOfficialListing() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CareerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = UnifiedOpportunityStore(db)
            val imported = OpportunityNormalizer.normalize(RawOpportunity(null, "Engineer", "Fixture", "London", null, null, "", emptyList(), "https://example.com/job/1", null), "import:example.com", "Imported", 1, allowEmptyDescription = true)!!
            val id = store.saveBatch(listOf(imported)).second.single()
            val tracker = RoomOpportunityTracker(db)
            val application = tracker.save(id, true)
            val official = imported.copy(id = "official", sourceId = "lever:fixture", sourceName = "Fixture / Lever", externalId = "123", canonicalUrl = "https://jobs.lever.co/fixture/123", description = "Build software", contentHash = "new", lastSeenAt = 2,
                provenance = listOf(OpportunityProvenance("lever:fixture", "Fixture / Lever", "123", "https://jobs.lever.co/fixture/123", "https://jobs.lever.co/fixture/123", null, 2, null, 2, OpportunityLifecycle.ACTIVE, true)))
            assertEquals(id, store.saveBatch(listOf(official, official)).second.distinct().single())
            val repository = RoomOpportunityRepository(db)
            assertEquals(1, repository.observeAll().first().size)
            assertEquals(2, repository.get(id)!!.provenance.size)
            assertEquals(official.canonicalUrl, repository.get(id)!!.canonicalUrl)
            assertEquals(application.id, tracker.observe().first().single().application.id)
            assertEquals(ApplicationStatus.PREPARING, tracker.observe().first().single().application.status)
            assertEquals(OpportunityStatus.SAVED, repository.get(id)!!.status)
            store.closeMissing("lever:fixture", 3)
            assertEquals(OpportunityLifecycle.CLOSED, repository.get(id)!!.lifecycle)
            assertEquals(id, repository.get("official")!!.id)
        } finally { db.close() }
    }
}
