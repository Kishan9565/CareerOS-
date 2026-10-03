package com.kishan.careeros

import com.kishan.careeros.domain.*
import org.junit.Assert.*
import org.junit.Test

class SearchAndRelevanceTest {
    private fun profile(role: String = "Android Developer") = CareerProfile(name = "Candidate", headline = "Engineer", preferences = CareerPreferences(targetRoles = listOf(role), locations = listOf("Bangalore"), remote = RemotePreference.REMOTE), skills = listOf("Kotlin", "Jetpack Compose", "Room", "Hilt").map { Skill(name = it) })
    private fun job(title: String = "Android Engineer", description: String = "Develop Android software with Kotlin, Jetpack Compose, Room and Hilt.") = Opportunity(title = title, company = "Fixture", description = description, sourceId = "fixture", sourceName = "Fixture", canonicalUrl = "https://example.com/job", contentHash = "test", location = "Bangalore", remote = true, workplace = Workplace.REMOTE, lifecycle = OpportunityLifecycle.ACTIVE)
    @Test fun unknownExperienceDoesNotBecomeJuniorFactOrInternPreference() {
        val search = SearchIntentBuilder.profile(profile())
        val roles = SearchIntentBuilder.build(search).map { it.role }
        assertNull(search.experienceYears)
        assertTrue("Android Engineer" in roles); assertTrue("Jetpack Compose Developer" in roles)
        assertFalse(roles.any { it.contains("iOS") || it.contains("Junior") || it.contains("Intern") })
        assertEquals(setOf("kotlin", "jetpack compose", "room", "hilt"), search.primarySkills)
    }
    @Test fun familiesGeneralizeWithoutTurningFrontendIntoAllSoftware() {
        val p = profile("Frontend Developer").copy(preferences = CareerPreferences(targetRoles = listOf("Frontend Developer"), employmentTypes = setOf(EmploymentType.INTERNSHIP)), skills = listOf(Skill(name = "React")))
        val intents = SearchIntentBuilder.build(SearchIntentBuilder.profile(p))
        assertTrue(intents.any { it.role == "Frontend Engineer" }); assertTrue(intents.any { it.role.endsWith("Intern") })
        assertFalse(intents.any { it.role.contains("Android") || it.role.contains("Backend") })
    }
    @Test fun relevanceHasTraceableEvidenceAndBoundsUnrelatedRoles() {
        val search = SearchIntentBuilder.profile(profile())
        val result = ProfileRelevanceEngine.evaluate(search, job())
        assertEquals(RelevanceBand.STRONG_MATCH, result.band)
        assertTrue(result.matched.any { it.contains("kotlin") }); assertTrue(result.preferences.any { it.contains("Bangalore") })
        val unrelated = ProfileRelevanceEngine.evaluate(search, job("Account Manager", "Manage customer relationships. Room for growth."))
        assertEquals(RelevanceBand.LOW_RELEVANCE, unrelated.band)
        assertFalse(unrelated.matched.any { it.startsWith("room:") })
    }
    @Test fun constraintsUseKnownFactsAndNeverTreatUnknownExperienceAsZero() {
        val p = profile().copy(preferences = profile().preferences.copy(minimumSalary = Salary(100, currency = "USD")))
        val unknown = SearchIntentBuilder.profile(p)
        val result = ProfileRelevanceEngine.evaluate(unknown, job(description = "Android Kotlin Jetpack Compose Room Hilt. Minimum 5 years of experience. AWS. No visa sponsorship.").copy(salary = Salary(50, 80, "USD")))
        assertTrue(result.cautions.any { it.contains("Experience unknown") }); assertTrue(result.cautions.any { it.contains("below your minimum") })
        assertTrue("aws" in result.notDetected)
        val fact = SavedApplicationAnswer(normalizedQuestionKey = "requires_visa_sponsorship", originalQuestion = "Need sponsorship?", answer = "YES", category = AnswerCategory.LEGAL_OR_ELIGIBILITY, source = AnswerSource.USER, verification = Verification.VERIFIED, reusable = true)
        val verified = SearchIntentBuilder.profile(p.copy(experienceYears = 1.0), listOf(fact))
        val mismatch = ProfileRelevanceEngine.evaluate(verified, job(description = "Android Kotlin. Minimum 5 years of experience. No visa sponsorship."))
        assertTrue(mismatch.cautions.any { it.contains("sponsorship preference conflicts") })
        assertTrue(mismatch.cautions.any { it.contains("Profile 1.0 years") })
        assertFalse(CareerVocabulary.contains("JavaScript developer", "Java"))
    }
}
