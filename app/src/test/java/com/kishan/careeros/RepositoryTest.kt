package com.kishan.careeros

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kishan.careeros.data.*
import com.kishan.careeros.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class RepositoryTest {
    private lateinit var db: CareerDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CareerDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }
    @Test fun profileAndPreferencesRoundTripAndReplaceChildren() = runTest {
        val repo = RoomProfileRepository(db)
        assertNull(repo.observe().first())
        val p = CareerProfile(name = "User", headline = "Engineer", preferences = CareerPreferences(listOf("Android"), listOf("Pune"), RemotePreference.REMOTE, setOf(EmploymentType.FULL_TIME), Salary(900000, currency = "INR")), skills = listOf(Skill(name = "Kotlin")), projects = listOf(ProjectReference(name = "Portfolio", description = "My work")), education = listOf(Education(institution = "College", qualification = "BSc")))
        repo.save(p); assertEquals(p, repo.observe().first())
        val edited = p.copy(skills = listOf(Skill(name = "Compose")), projects = emptyList())
        repo.save(edited); assertEquals(edited, repo.observe().first())
    }
    @Test fun invalidProfileDoesNotOverwriteSavedData() = runTest {
        val repo = RoomProfileRepository(db)
        val p = CareerProfile(name = "User", headline = "Engineer", preferences = CareerPreferences(targetRoles = listOf("Android")), skills = listOf(Skill(name = "Kotlin")))
        repo.save(p)
        try { repo.save(p.copy(name = "")); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(p, repo.observe().first())
    }
    @Test fun opportunitiesApplicationsAndAnswersPersist() = runTest {
        val jobs = RoomOpportunityRepository(db); val apps = RoomApplicationRepository(db); val answers = RoomAnswerBankRepository(db)
        assertTrue(jobs.observeAll().first().isEmpty()); assertTrue(apps.observeAll().first().isEmpty())
        val job = Opportunity(title = "Developer", company = "Test fixture", description = "Real persistence test", sourceId = "source", sourceName = "Fixture", canonicalUrl = "https://example.com/job", contentHash = "hash", requirements = listOf(OpportunityRequirement(text = "Kotlin")), salary = Salary(1, 2, "INR"))
        jobs.save(job); assertEquals(job, jobs.get(job.id))
        val a = Application(opportunityId = job.id); apps.save(a)
        try { apps.transition(a.id, ApplicationStatus.APPLIED); fail() } catch (_: IllegalArgumentException) { }
        apps.transition(a.id, ApplicationStatus.APPLIED, true)
        assertNotNull(apps.observeAll().first().single().appliedAt)
        val saved = SavedApplicationAnswer(normalizedQuestionKey = "relocation", originalQuestion = "Relocate?", answer = "YES", category = AnswerCategory.PREFERENCE, source = AnswerSource.USER, verification = Verification.VERIFIED, reusable = true)
        answers.save(saved); assertEquals(saved, answers.observeAll().first().single())
        val answer = ApplicationAnswer(applicationId = a.id, normalizedQuestionKey = saved.normalizedQuestionKey, originalQuestion = saved.originalQuestion, answer = saved.answer, category = saved.category, source = saved.source, verification = saved.verification, savedAnswerId = saved.id)
        answers.saveForApplication(answer); answers.delete(saved.id)
        assertEquals(answer, answers.observeForApplication(a.id).first().single())
    }
    @Test fun sourceConfigRoundTripsWithoutSeeding() = runTest {
        val repo = RoomSourceRepository(db); assertTrue(repo.observeAll().first().isEmpty())
        val source = SourceConfig(name = "User source", type = SourceType.RSS, url = "https://example.com/feed")
        repo.save(source); assertEquals(source, repo.observeAll().first().single())
    }
}
