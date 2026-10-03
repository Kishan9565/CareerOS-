package com.kishan.careeros

import com.kishan.careeros.domain.*
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private fun profile() = CareerProfile(name = "User", headline = "Developer", preferences = CareerPreferences(targetRoles = listOf("Android")), skills = listOf(Skill(name = "Kotlin")))
    private fun answer() = SavedApplicationAnswer(normalizedQuestionKey = "willing_to_relocate", originalQuestion = "Are you willing to relocate?", answer = "YES", category = AnswerCategory.PREFERENCE, source = AnswerSource.USER, verification = Verification.VERIFIED, reusable = true)
    @Test fun profileRequiresRealInput() {
        assertTrue(Validation.profile(profile()).isEmpty())
        assertFalse(Validation.profile(profile().copy(name = " ", noticePeriodDays = -1)).isEmpty())
        assertNull(profile().experienceYears)
    }
    @Test fun urlsRejectExecutableSchemesAndCredentials() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "https://user:pass@example.com", "https:///missing", "intent://open").forEach { assertFalse(Validation.httpUrl(it)) }
        assertTrue(Validation.httpUrl("https://github.com/user"))
    }
    @Test fun keysNormalizeDeterministically() {
        assertEquals("willing_to_relocate", Validation.questionKey(" Willing to Relocate? "))
        assertEquals("willing_to_relocate", Validation.questionKey(Validation.questionKey("Willing to Relocate?")))
        assertFalse(Validation.answer(answer().copy(normalizedQuestionKey = " ")).isEmpty())
    }
    @Test fun factualAiAnswersCannotEnterMemory() {
        assertTrue(answer().eligibleForAutofill())
        assertFalse(Validation.answer(answer().copy(source = AnswerSource.AI_DRAFT)).isEmpty())
        assertFalse(answer().copy(verification = Verification.UNVERIFIED).eligibleForAutofill())
        val draft = answer().copy(source = AnswerSource.AI_DRAFT, category = AnswerCategory.GENERATIVE, verification = Verification.UNVERIFIED)
        assertTrue(Validation.answer(draft).isEmpty()); assertFalse(draft.eligibleForAutofill())
    }
    @Test fun submissionNeedsUserAndPreservesTimestamp() {
        val a = Application(opportunityId = "job")
        assertThrows(IllegalArgumentException::class.java) { a.transition(ApplicationStatus.APPLIED) }
        val applied = a.transition(ApplicationStatus.APPLIED, true, 100)
        assertEquals(100L, applied.appliedAt)
        assertEquals(100L, applied.transition(ApplicationStatus.INTERVIEW, now = 200).appliedAt)
        assertThrows(IllegalArgumentException::class.java) { applied.transition(ApplicationStatus.SAVED) }
        assertFalse(ApplicationStatus.CLOSED.canTransitionTo(ApplicationStatus.APPLIED))
    }
    @Test fun salaryRejectsInvalidRange() {
        assertThrows(IllegalArgumentException::class.java) { Salary(200, 100, "INR") }
        assertThrows(IllegalArgumentException::class.java) { Salary(-1, currency = "INR") }
    }
}
