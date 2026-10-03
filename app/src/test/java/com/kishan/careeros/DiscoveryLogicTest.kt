package com.kishan.careeros

import com.kishan.careeros.data.*
import com.kishan.careeros.domain.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryLogicTest {
    private fun raw() = RawOpportunity("123", "Developer", "Company", "Worldwide", true, EmploymentType.FULL_TIME, "Build software", listOf("Kotlin"), "https://remotive.com/remote-jobs/software-development/developer-123", 1_000)
    @Test fun canonicalizationPreservesApplicationParametersAndEscaping() {
        assertEquals("https://example.com/jobs/a%20b?job=42&ref=careers", OpportunityNormalizer.canonicalUrl("HTTPS://EXAMPLE.COM:443/jobs/a%20b?utm_source=email&ref=careers&job=42&fbclid=x#apply"))
        assertNull(OpportunityNormalizer.canonicalUrl("javascript:alert(1)"))
        assertNull(OpportunityNormalizer.canonicalUrl("https://user:secret@example.com"))
    }
    @Test fun identityPrefersExternalIdAndFallbackIgnoresCaseWhitespaceAndTimeOfDay() {
        val a = raw(); val normalized = OpportunityNormalizer.normalize(a, "source", "Source", 1)!!
        assertEquals(normalized.id, OpportunityNormalizer.normalize(a.copy(title = "Changed"), "source", "Source", 2)!!.id)
        assertEquals(OpportunityNormalizer.identity("a", null, " Company ", "Software  Developer", "REMOTE", 1_000), OpportunityNormalizer.identity("b", null, "company", "software developer", "remote", 2_000))
        assertNotEquals(normalized.id, OpportunityNormalizer.normalize(a, "other", "Other", 1)!!.id)
    }
    @Test fun hashesIgnoreFetchTimeTrackingAndTagOrderButDetectDescriptionChanges() {
        val a = raw().copy(skills = listOf("Kotlin", "Android"))
        val first = OpportunityNormalizer.normalize(a, "source", "Source", 1)!!
        val again = OpportunityNormalizer.normalize(a.copy(url = a.url + "?utm_source=email", skills = a.skills.reversed()), "source", "Source", 999)!!
        assertEquals(first.contentHash, again.contentHash)
        assertNotEquals(first.contentHash, OpportunityNormalizer.normalize(a.copy(description = "Different responsibilities"), "source", "Source", 1)!!.contentHash)
        assertNull(OpportunityNormalizer.normalize(a.copy(company = " "), "source", "Source", 1))
    }
    @Test fun parserIsolatesBadRecordsAndDoesNotInventOptionalFields() {
        val body = """{"jobs":[null,{"title":true},{"category":"Sales"},{"category":"Software Development","title":"Developer","company_name":"Company","description":"Build software","url":"javascript:alert(1)"},{"category":"Software Development","id":123,"title":"Developer","company_name":"Company","description":"Build software","url":"https://remotive.com/job/123","publication_date":"bad date"}]}"""
        val records = RemotiveParser { it }.parse(body)
        assertEquals(1, records.size)
        assertNull(records.single().publishedAt); assertNull(records.single().employmentType)
        assertTrue(records.single().skills.isEmpty()); assertEquals("123", records.single().externalId)
    }
    @Test fun parserDistinguishesEmptyResultsFromBrokenEnvelope() {
        val parser = RemotiveParser { it }
        assertTrue(parser.parse("""{"jobs":[]}""").isEmpty())
        assertThrows(IllegalStateException::class.java) { parser.parse("""{"error":"unavailable"}""") }
        assertThrows(IllegalArgumentException::class.java) { parser.parse("not json") }
    }
}
