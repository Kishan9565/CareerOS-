package com.kishan.careeros

import com.kishan.careeros.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrchestratorIsolationTest {
    private val profile = SearchIntentBuilder.profile(CareerProfile(name = "Candidate", headline = "Engineer", preferences = CareerPreferences(targetRoles = listOf("Backend Engineer"))))
    private fun source(id: String, enabled: Boolean = true, mode: DiscoveryMode = DiscoveryMode.AUTOMATIC) = SourceStatus(SourceCapabilities(id, id, SourceKind.ATS, mode), enabled, null)
    @Test fun failureDoesNotStopOtherSourcesAndUserSearchIsNeverFetched() = runTest {
        val calls = mutableListOf<String>()
        val orchestrator = OpportunityDiscoveryOrchestrator(object: AutomaticSourceRunner {
            override suspend fun run(source: SourceStatus, profile: CareerSearchProfile, intents: List<SearchIntent>): SourceRunResult {
                assertTrue(intents.isNotEmpty()); calls += source.capabilities.sourceId
                if (source.capabilities.sourceId == "broken") error("network failure")
                return SourceRunResult(source.capabilities.sourceId, received = 2, changed = 1)
            }
        })
        val result = orchestrator.discover(profile, SearchIntentBuilder.build(profile), listOf(source("broken"), source("good"), source("disabled", false), source("linkedin", mode = DiscoveryMode.USER_SEARCH)))
        assertEquals(listOf("broken", "good"), calls); assertEquals(1, result.failures); assertEquals(2, result.received)
    }
    @Test fun cancellationIsPropagatedRatherThanRecordedAsSourceFailure() = runTest {
        val orchestrator = OpportunityDiscoveryOrchestrator(object: AutomaticSourceRunner {
            override suspend fun run(source: SourceStatus, profile: CareerSearchProfile, intents: List<SearchIntent>): SourceRunResult = throw CancellationException()
        })
        try { orchestrator.discover(profile, emptyList(), listOf(source("cancel"))); fail() } catch (_: CancellationException) { }
    }
}
