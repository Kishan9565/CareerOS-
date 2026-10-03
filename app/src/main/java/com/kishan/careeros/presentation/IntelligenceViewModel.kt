package com.kishan.careeros.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kishan.careeros.domain.*
import com.kishan.careeros.domain.intelligence.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class IntelligenceState(val busy: Boolean = false, val message: String? = null, val review: ResumeReview? = null, val knowledge: List<CareerKnowledge> = emptyList(), val models: List<LocalModelStatus> = emptyList(), val chunks: Int = 0, val indexed: Int = 0, val analysis: EvidenceBasedMatchAnalysis? = null)
@HiltViewModel class IntelligenceViewModel @Inject constructor(private val extractor: ResumeTextExtractor, private val knowledge: CareerKnowledgeRepository, private val modelManager: LocalModelManager, private val intelligence: CareerIntelligence, private val vectors: VectorStore, private val embedding: EmbeddingEngine, private val llm: LocalLlm, private val profiles: ProfileRepository, private val answers: AnswerBankRepository): ViewModel() {
    private val mutable = MutableStateFlow(IntelligenceState()); val state = mutable.asStateFlow()
    init {
        viewModelScope.launch { combine(profiles.observe(), answers.observeAll()) { p, a -> p to a }.collect { (p,a) ->
            try { knowledge.synchronizeProfile(p,a); mutable.update { it.copy(analysis = null) } } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(message = "Could not synchronize career knowledge") } }
        } }
        viewModelScope.launch { combine(knowledge.observe(), modelManager.observe()) { k,m -> k to m }.collect { (k,m) ->
            mutable.update { it.copy(knowledge = k, models = m, analysis = null) }; counts()
        } }
    }
    private suspend fun counts() { val chunks = knowledge.chunks().size; val indexed = vectors.count(modelManager.version(ModelKind.EMBEDDING)); mutable.update { it.copy(chunks = chunks, indexed = indexed) } }
    private fun work(label: String, action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = label) }
        viewModelScope.launch { try { action(); counts() } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(message = "Operation failed. Your existing saved data remains available.") } } finally { mutable.update { it.copy(busy = false) } } }
    }
    fun importResume(uri: String) = work("Extracting PDF locally...") { when (val result = extractor.extract(uri)) {
        is CapabilityResult.Unavailable -> mutable.update { it.copy(message = result.reason) }
        is CapabilityResult.Available -> mutable.update { it.copy(review = ResumeReviewMapper.review(result.value), message = "Review each section. Only confirmed text becomes career evidence.") }
    } }
    fun editReview(index: Int, candidate: ResumeCandidate) { mutable.update { s -> s.copy(review = s.review?.copy(candidates = s.review.candidates.mapIndexed { i,c -> if (i == index) candidate else c })) } }
    fun removeReview(index: Int) { mutable.update { s -> s.copy(review = s.review?.copy(candidates = s.review.candidates.filterIndexed { i,_ -> i != index })) } }
    fun cancelReview() { mutable.update { it.copy(review = null, message = null) } }
    fun reviewSaved() { val rows = state.value.knowledge.filter { it.sourceId == "resume" }; mutable.update { it.copy(review = ResumeReview(rows.map { ResumeCandidate(it.kind,it.label,it.text) }, "")) } }
    fun saveResume() = work("Saving verified evidence...") { val review = requireNotNull(state.value.review); knowledge.replaceResume(review.candidates); mutable.update { it.copy(review = null, analysis = null, message = "Resume evidence saved locally. Existing profile facts were not overwritten.") } }
    fun removeResume() = work("Removing resume evidence...") { knowledge.removeResume(); mutable.update { it.copy(review = null, analysis = null, message = "Resume evidence and its vectors removed.") } }
    fun importModel(kind: ModelKind, uri: String, hash: String) = work("Importing and checking model checksum...") {
        try {
            modelManager.importModel(kind, uri, hash)
            mutable.update { it.copy(message = "Model imported. Validate runtime on this device.", analysis = null) }
        } catch (e: ModelImportException) {
            mutable.update { it.copy(message = e.message) }
        }
    }
    fun removeModel(kind: ModelKind) = work("Removing model...") { modelManager.remove(kind); mutable.update { it.copy(message = "Model removed", analysis = null) } }
    fun validate(kind: ModelKind) = work("Validating local runtime...") {
        val result = if (kind == ModelKind.EMBEDDING) embedding.embed("Local runtime validation") else llm.generate(LlmRequest("Reply OK only.", listOf("Validate runtime")))
        mutable.update { it.copy(message = when(result) { is CapabilityResult.Available -> "Runtime loaded and inference completed locally."; is CapabilityResult.Unavailable -> result.reason }) }
    }
    fun index() = work("Indexing verified career knowledge locally...") { when (val result = intelligence.index()) {
        is CapabilityResult.Available -> mutable.update { it.copy(message = "${result.value} chunks indexed locally", analysis = null) }
        is CapabilityResult.Unavailable -> mutable.update { it.copy(message = result.reason) }
    } }
    fun cached(job: Opportunity) { mutable.update { it.copy(analysis = null) }; viewModelScope.launch { try { val result = intelligence.cached(job); mutable.update { it.copy(analysis = result) } } catch (_: Exception) { } } }
    fun analyze(job: Opportunity) = work("Checking requirements and verified career evidence...") { val result = intelligence.analyze(job); mutable.update { it.copy(analysis = result, message = result.status) } }
}
