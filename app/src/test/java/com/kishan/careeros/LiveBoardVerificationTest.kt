package com.kishan.careeros

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kishan.careeros.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Opt-in local captures of real public endpoints; no network calls or bundled job fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LiveBoardVerificationTest {
    @Test fun capturedPublicResponsesPassProductionParsingAndRoomStorage() = runTest {
        val directory = System.getProperty("careeros.liveBoardsDir")
        assumeNotNull(directory)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val companies = CompanyCatalog.parse(context.assets.open("company_sources.json").bufferedReader().use { it.readText() })
        val db = Room.inMemoryDatabaseBuilder(context, CareerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = UnifiedOpportunityStore(db)
            for (company in companies) {
                val body = File(directory!!, "live-${company.providerIdentifier}.json").readText().removePrefix("\uFEFF")
                val parsed = AtsParser(::sourcePlainText).parse(body, company, System.currentTimeMillis())
                assertTrue("Empty real board: ${company.id}", parsed.opportunities.isNotEmpty())
                store.saveBatch(parsed.opportunities)
                println("LIVE ${company.id}: ${parsed.opportunities.size} valid published jobs; complete=${parsed.complete}")
            }
            val body = File(directory!!, "live-remotive.json").readText().removePrefix("\uFEFF")
            val jobs = RemotiveParser(allCategories = true, plainText = ::sourcePlainText).parse(body).mapNotNull { OpportunityNormalizer.normalize(it, "remotive", "Remotive", System.currentTimeMillis()) }
            assertTrue(jobs.isNotEmpty())
            store.saveBatch(jobs)
            println("LIVE remotive: ${jobs.size} valid jobs")
            println("LIVE unified Room feed: ${RoomOpportunityRepository(db).observeAll().first().size} opportunities after dedupe")
        } finally { db.close() }
    }
}
