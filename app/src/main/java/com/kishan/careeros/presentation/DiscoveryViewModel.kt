package com.kishan.careeros.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kishan.careeros.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoveryState(
    val loading: Boolean = true, val dataError: Boolean = false,
    val jobs: List<Opportunity> = emptyList(), val tracked: List<TrackedOpportunity> = emptyList(),
    val sources: List<SourceStatus> = emptyList(), val discovering: Boolean = false,
    val profile: CareerSearchProfile? = null, val intents: List<SearchIntent> = emptyList(),
    val relevance: Map<String, ProfileRelevance> = emptyMap(),
    val message: String? = null, val detail: Opportunity? = null, val detailLoading: Boolean = false,
    val detailError: Boolean = false, val saving: Boolean = false,
    val importReview: ImportReview? = null, val importError: String? = null, val importedId: String? = null,
)
private data class FeedSnapshot(val jobs: List<Opportunity>, val tracked: List<TrackedOpportunity>, val sources: List<SourceStatus>, val profile: CareerSearchProfile?, val relevance: Map<String, ProfileRelevance>)
@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val discovery: DiscoveryEngine, private val opportunities: OpportunityRepository,
    private val tracker: OpportunityTracker, private val profiles: ProfileRepository,
    private val answers: AnswerBankRepository, private val savedState: SavedStateHandle,
): ViewModel() {
    private val mutable = MutableStateFlow(DiscoveryState(importReview = restoreImport()))
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var detailJob: Job? = null
    init { observe() }
    private fun restoreImport(): ImportReview? {
        val url = savedState.get<String>("import_url") ?: return null
        return ImportReview(url, savedState["import_original"] ?: url, savedState["import_title"] ?: "", savedState["import_company"] ?: "", savedState["import_location"] ?: "", savedState["import_unknown"] ?: false, savedState["import_description"] ?: "", savedState["import_confirmed"] ?: false)
    }
    fun observe() {
        observation?.cancel()
        observation = viewModelScope.launch {
            mutable.update { it.copy(loading = true, dataError = false) }
            combine(opportunities.observeAll(), tracker.observe(), discovery.observeSources(), profiles.observe(), answers.observeAll()) { jobs, tracked, sources, profile, answers ->
                val searchProfile = profile?.let { SearchIntentBuilder.profile(it, answers) }
                FeedSnapshot(jobs, tracked, sources, searchProfile, searchProfile?.let { p -> jobs.associate { it.id to ProfileRelevanceEngine.evaluate(p, it) } }.orEmpty())
            }.flowOn(Dispatchers.Default)
                .catch { mutable.update { it.copy(loading = false, dataError = true) } }
                .collect { snapshot -> mutable.update { it.copy(loading = false, jobs = snapshot.jobs, tracked = snapshot.tracked, sources = snapshot.sources, profile = snapshot.profile, relevance = snapshot.relevance, intents = snapshot.profile?.let(SearchIntentBuilder::build).orEmpty()) } }
        }
    }
    fun discover() {
        if (state.value.discovering) return
        val profile = state.value.profile ?: run { mutable.update { it.copy(message = "Save your career profile first so CareerOS can derive relevant search intents.") }; return }
        mutable.update { it.copy(discovering = true, message = null) }
        viewModelScope.launch {
            try {
                val run = discovery.discover(profile)
                val completed = run.sources.count { it.error == null && it.deferred == null }
                val waiting = run.sources.count { it.deferred != null }
                mutable.update { it.copy(message = if (run.sources.isEmpty()) "No enabled automatic sources. Enable sources below." else "$completed sources refreshed; ${run.received} source records, ${run.changed} new/changed records. ${run.failures} failed, $waiting waiting for refresh limits. Coverage details are in Sources.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "Discovery is unavailable. Your existing results are still available.") } }
            finally { mutable.update { it.copy(discovering = false) } }
        }
    }
    fun enabled(id: String, value: Boolean) { viewModelScope.launch {
        try { discovery.setEnabled(id, value) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(message = "Could not change source settings.") } }
    } }
    fun detail(id: String) {
        detailJob?.cancel()
        mutable.update { it.copy(detail = null, detailLoading = true, detailError = false, message = null) }
        detailJob = viewModelScope.launch {
            try { val job = opportunities.get(id); mutable.update { it.copy(detail = job, detailLoading = false, detailError = job == null) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(detailLoading = false, detailError = true) } }
        }
    }
    fun save(prepare: Boolean) {
        val job = state.value.detail ?: return
        if (state.value.saving) return
        mutable.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            try {
                tracker.save(job.id, prepare)
                mutable.update { it.copy(message = if (prepare) "Saved in Applications. AI preparation is not configured; no content has been generated." else "Saved in Applications.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "Could not save this opportunity. Please retry.") } }
            finally { mutable.update { it.copy(saving = false) } }
        }
    }
    fun receiveShare(text: String, subject: String?) {
        try { editImport(SharedOpportunityImporter.review(text, subject)) }
        catch (e: IllegalArgumentException) { mutable.update { it.copy(importReview = ImportReview(), importError = e.message, importedId = null) } }
    }
    fun editImport(review: ImportReview) {
        savedState["import_url"] = review.url; savedState["import_original"] = review.originalUrl
        savedState["import_title"] = review.title; savedState["import_company"] = review.company
        savedState["import_location"] = review.location; savedState["import_unknown"] = review.locationUnknown
        savedState["import_description"] = review.description; savedState["import_confirmed"] = review.confirmed
        mutable.update { it.copy(importReview = review, importError = null, importedId = null) }
    }
    fun cancelImport() {
        listOf("url", "original", "title", "company", "location", "unknown", "description", "confirmed").forEach { savedState.remove<Any>("import_$it") }
        mutable.update { it.copy(importReview = null, importError = null, importedId = null) }
    }
    fun saveImport() {
        val review = state.value.importReview ?: return
        val errors = SharedOpportunityImporter.errors(review)
        if (errors.isNotEmpty()) { mutable.update { it.copy(importError = errors.joinToString("\n")) }; return }
        if (state.value.saving) return
        mutable.update { it.copy(saving = true, importError = null) }
        viewModelScope.launch {
            try { val id = discovery.importOpportunity(review); cancelImport(); mutable.update { it.copy(importedId = id) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(importError = "Could not save the import. Your review is retained; please retry.") } }
            finally { mutable.update { it.copy(saving = false) } }
        }
    }
}
