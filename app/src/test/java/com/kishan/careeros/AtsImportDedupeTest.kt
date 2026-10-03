package com.kishan.careeros

import com.kishan.careeros.domain.*
import com.kishan.careeros.data.*
import org.junit.Assert.*
import org.junit.Test

class AtsImportDedupeTest {
    private fun company(provider: ProviderType) = CompanyCareerSource("${provider.name.lowercase()}:fixture", "Fixture", provider, "fixture", "https://example.com/careers", true, emptyList(), emptyList(), EndpointVerification.VERIFIED_ENDPOINT, 1, if (provider == ProviderType.GREENHOUSE) "https://boards-api.greenhouse.io/v1/boards/fixture/jobs?content=true" else "https://api.lever.co/v0/postings/fixture?mode=json")
    @Test fun greenhouseSkipsBadRecordKeepsDescriptionAndDoesNotInventWorkplace() {
        val result = AtsParser { it }.parse("""{"jobs":[{"id":1,"title":"Backend Engineer","absolute_url":"https://job-boards.greenhouse.io/fixture/jobs/1","location":{"name":"Bangalore"},"content":"Build software with Kotlin","first_published":"2026-09-01T10:00:00Z"},false]}""", company(ProviderType.GREENHOUSE), 1)
        assertEquals(1, result.opportunities.size); assertFalse(result.complete)
        val job = result.opportunities.single()
        assertEquals(Workplace.UNKNOWN, job.workplace); assertNull(job.remote); assertNotNull(job.publishedAt)
        assertTrue(job.provenance.single().official); assertEquals("Build software with Kotlin", job.description)
    }
    @Test fun leverUsesExplicitWorkplaceAndSalaryButDoesNotPretendCreatedMeansPublished() {
        val result = AtsParser { it }.parse("""[{"id":"id","text":"Android Engineer","hostedUrl":"https://jobs.lever.co/fixture/id","descriptionPlain":"Build Android apps","categories":{"commitment":"Internship","location":"London"},"workplaceType":"hybrid","createdAt":1234,"salaryRange":{"min":20,"max":30,"currency":"USD","interval":"per-hour"},"lists":[{"text":"Requirements","content":"Kotlin and Compose"}]}]""", company(ProviderType.LEVER), 2)
        val job = result.opportunities.single()
        assertEquals(Workplace.HYBRID, job.workplace); assertEquals(EmploymentType.INTERNSHIP, job.employmentType)
        assertNull(job.publishedAt); assertEquals(Salary(20, 30, "USD", SalaryPeriod.HOUR), job.salary)
        assertTrue(job.description.contains("Kotlin and Compose"))
        assertFalse(CompanyCatalog.validate(company(ProviderType.LEVER).copy(providerIdentifier = "../../private")))
    }
    @Test fun urlOnlyShareNeedsReviewAndUnknownLocationIsExplicit() {
        val review = SharedOpportunityImporter.review("https://www.linkedin.com/jobs/view/123?utm_source=share")
        assertEquals("", review.title); assertEquals("", review.company); assertEquals("", review.location)
        assertTrue(SharedOpportunityImporter.errors(review).isNotEmpty())
        val confirmed = review.copy(title = "Engineer", company = "Company", locationUnknown = true, confirmed = true)
        assertTrue(SharedOpportunityImporter.errors(confirmed).isEmpty())
        assertEquals("LinkedIn", SharedOpportunityImporter.sourceName(confirmed.url))
        assertTrue(SharedOpportunityImporter.errors(confirmed.copy(url = "javascript:alert(1)")).isNotEmpty())
        assertEquals("https://www.linkedin.com/jobs/view/123", OpportunityNormalizer.canonicalUrl(review.url))
        assertThrows(IllegalArgumentException::class.java) { SharedOpportunityImporter.review("x".repeat(SharedOpportunityImporter.MAX_TEXT + 1)) }
    }
    @Test fun dedupePreservesProvenanceAndPrefersOfficialApplicationOverAggregator() {
        fun job(source: String, url: String, official: Boolean) = Opportunity(id = source, title = "Android Engineer", company = "Fixture", location = "London", description = "Build Android apps", sourceId = source, sourceName = source, canonicalUrl = url, externalId = "id", contentHash = source, provenance = listOf(OpportunityProvenance(source, source, "id", url, url, null, 1, null, 1, OpportunityLifecycle.ACTIVE, official)))
        val aggregator = job("remotive", "https://remotive.com/job/id", false)
        val official = job("lever:fixture", "https://jobs.lever.co/fixture/id", true)
        assertTrue(CrossSourceDedupe.keys(aggregator).intersect(CrossSourceDedupe.keys(official).toSet()).any { it.startsWith("identity:") })
        val merged = CrossSourceDedupe.merge(aggregator, official)
        assertEquals(aggregator.id, merged.id); assertEquals(official.canonicalUrl, merged.canonicalUrl); assertEquals(2, merged.provenance.size)
        assertEquals(official.canonicalUrl, CrossSourceDedupe.applicationUrl(official.canonicalUrl + "/apply?utm_source=test"))
        assertTrue(CrossSourceDedupe.keys(official.copy(location = null)).none { it.startsWith("identity:") })
    }
}
