package com.kishan.careeros.domain.intelligence

import com.kishan.careeros.domain.*
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

fun knowledgeHash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
enum class KnowledgeKind { SUMMARY, SKILLS, PROJECT, EDUCATION, EXPERIENCE, CERTIFICATION, LINKS, PREFERENCES, FACT }
enum class EvidenceOrigin { USER_VERIFIED, RESUME_VERIFIED, PROFILE_VERIFIED }
enum class EvidenceState { VERIFIED, INFERRED, UNKNOWN }
data class CareerKnowledge(val id: String, val sourceId: String, val kind: KnowledgeKind, val label: String, val text: String, val origin: EvidenceOrigin, val state: EvidenceState = EvidenceState.VERIFIED, val updatedAt: Long = System.currentTimeMillis())
data class KnowledgeChunk(val id: String, val sourceId: String, val kind: KnowledgeKind, val label: String, val text: String, val origin: EvidenceOrigin, val hash: String, val updatedAt: Long)
data class ResumeCandidate(val kind: KnowledgeKind, val label: String, val text: String, val confirmed: Boolean = false)
data class ResumeReview(val candidates: List<ResumeCandidate>, val extractedText: String)
interface ResumeTextExtractor { suspend fun extract(document: String): CapabilityResult<String> }
interface CareerKnowledgeRepository {
    fun observe(): Flow<List<CareerKnowledge>>
    suspend fun replaceResume(candidates: List<ResumeCandidate>)
    suspend fun removeResume()
    suspend fun synchronizeProfile(profile: CareerProfile?, facts: List<SavedApplicationAnswer>)
    suspend fun chunks(): List<KnowledgeChunk>
}
object ResumeReviewMapper {
    private val headings = mapOf("skills" to KnowledgeKind.SKILLS, "technical skills" to KnowledgeKind.SKILLS, "projects" to KnowledgeKind.PROJECT, "education" to KnowledgeKind.EDUCATION, "experience" to KnowledgeKind.EXPERIENCE, "work experience" to KnowledgeKind.EXPERIENCE, "professional experience" to KnowledgeKind.EXPERIENCE, "certifications" to KnowledgeKind.CERTIFICATION, "certificates" to KnowledgeKind.CERTIFICATION, "links" to KnowledgeKind.LINKS, "summary" to KnowledgeKind.SUMMARY, "profile" to KnowledgeKind.SUMMARY)
    fun review(text: String): ResumeReview {
        require(text.isNotBlank() && text.length <= 100_000)
        val candidates = mutableListOf<ResumeCandidate>(); var kind = KnowledgeKind.SUMMARY; var label = "Resume summary / unclassified"; val lines = mutableListOf<String>()
        fun flush() { val content = lines.joinToString("\n").trim(); if (content.isNotEmpty()) candidates += ResumeCandidate(kind, label, content); lines.clear() }
        text.lines().forEach { line -> val normalized = line.trim().trimEnd(':').lowercase(); val next = headings[normalized]
            if (next != null) { flush(); kind = next; label = line.trim() } else lines += line.trimEnd()
        }; flush()
        return ResumeReview(candidates, text)
    }
}
object KnowledgeChunker {
    // Roughly 400-600 English tokens; shorter sections stay intact. No factual rewriting.
    fun chunks(source: CareerKnowledge): List<KnowledgeChunk> {
        if (source.state != EvidenceState.VERIFIED || source.text.isBlank()) return emptyList()
        val words = source.text.trim().split(Regex("\\s+")); val result = mutableListOf<KnowledgeChunk>(); var start = 0
        while (start < words.size) {
            val end = minOf(start + 350, words.size); val text = words.subList(start, end).joinToString(" "); val hash = knowledgeHash("${source.kind}|${source.label}|${source.origin}|$text")
            result += KnowledgeChunk("${source.id}:${result.size}", source.sourceId, source.kind, source.label, text, source.origin, hash, source.updatedAt)
            if (end == words.size) break
            start = end - 35
        }
        return result
    }
}
data class VectorHit(val chunk: KnowledgeChunk, val similarity: Double)
interface VectorStore {
    suspend fun put(chunk: KnowledgeChunk, modelVersion: String, vector: FloatArray)
    suspend fun contains(chunk: KnowledgeChunk, modelVersion: String): Boolean
    suspend fun search(query: FloatArray, modelVersion: String, topK: Int = 8): List<VectorHit>
    suspend fun count(modelVersion: String): Int
}
object VectorMath {
    fun encode(values: FloatArray): ByteArray { require(values.isNotEmpty() && values.size <= 4096 && values.all(Float::isFinite)); return ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putFloat) }.array() }
    fun decode(bytes: ByteArray): FloatArray { require(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16384); val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(bytes.size / 4) { buffer.float }.also { require(it.all(Float::isFinite)) } }
    fun cosine(a: FloatArray, b: FloatArray): Double { require(a.isNotEmpty() && a.size == b.size && a.all(Float::isFinite) && b.all(Float::isFinite)); var dot = 0.0; var aa = 0.0; var bb = 0.0; for (i in a.indices) { dot += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }; return if (aa == 0.0 || bb == 0.0) 0.0 else (dot / sqrt(aa * bb)).coerceIn(-1.0, 1.0) }
}
enum class ModelKind { EMBEDDING, LLM }
enum class ModelState { NOT_INSTALLED, INSTALLED, READY, ERROR, UNSUPPORTED }
data class LocalModelStatus(val kind: ModelKind, val state: ModelState, val version: String, val message: String, val installed: Boolean = false)
interface LocalModelManager {
    fun observe(): Flow<List<LocalModelStatus>>
    suspend fun importModel(kind: ModelKind, document: String, expectedSha256: String)
    suspend fun remove(kind: ModelKind)
    fun version(kind: ModelKind): String
}
data class DeviceCapability(val totalRam: Long, val availableRam: Long, val freeStorage: Long, val arm64: Boolean)
interface DeviceCapabilityChecker { fun inspect(): DeviceCapability }
class CareerRagRetriever(private val embeddings: EmbeddingEngine, private val vectors: VectorStore, private val modelVersion: () -> String): RagEngine {
    suspend fun retrieveChunks(query: String): CapabilityResult<List<VectorHit>> = when (val embedded = embeddings.embed(query)) {
        is CapabilityResult.Unavailable -> embedded
        is CapabilityResult.Available -> CapabilityResult.Available(vectors.search(embedded.value, modelVersion(), 8).filter { it.similarity > 0.2 }.distinctBy { it.chunk.hash }.take(5))
    }
    override suspend fun retrieve(query: String, limit: Int): CapabilityResult<List<RagEvidence>> = when (val result = retrieveChunks(query)) {
        is CapabilityResult.Unavailable -> result
        is CapabilityResult.Available -> CapabilityResult.Available(result.value.take(limit.coerceIn(1,5)).map { RagEvidence(it.chunk.id, it.chunk.text, it.chunk.sourceId) })
    }
}
enum class RequirementKind { SKILL, EXPERIENCE, EDUCATION, RESPONSIBILITY, LOCATION, EMPLOYMENT }
data class JobRequirement(val id: String, val text: String, val kind: RequirementKind, val explicit: Boolean)
object JobRequirementExtractor {
    fun extract(job: Opportunity): List<JobRequirement> {
        val rows = mutableListOf<Triple<String, RequirementKind, Boolean>>()
        rows += job.skills.map { Triple(it, RequirementKind.SKILL, false) }
        rows += job.requirements.map { Triple(it.text, RequirementKind.RESPONSIBILITY, it.required) }
        val vocabulary = (CareerVocabulary.families.flatMap { it.technologies } + listOf("aws", "rest", "git", "firebase")).map(CareerVocabulary::skill).distinct()
        rows += vocabulary.filter { CareerVocabulary.contains(job.description, it) && (it != "room" || CareerVocabulary.contains(job.description, "android")) }.map { Triple(it, RequirementKind.SKILL, false) }
        job.description.split(Regex("[\\n.!?]+")).map(String::trim).filter { it.length in 8..500 }.forEach { line ->
            when { Regex("(?i)\\d+\\+?\\s+years?").containsMatchIn(line) -> rows += Triple(line, RequirementKind.EXPERIENCE, true)
                Regex("(?i)bachelor|master.s|degree|diploma").containsMatchIn(line) -> rows += Triple(line, RequirementKind.EDUCATION, true) }
        }
        job.location?.let { rows += Triple(it, RequirementKind.LOCATION, true) }; job.employmentType?.let { rows += Triple(it.name, RequirementKind.EMPLOYMENT, true) }
        return rows.distinctBy { it.first.lowercase() }.take(30).map { JobRequirement(knowledgeHash("${it.second}|${it.first}"), it.first, it.second, it.third) }
    }
}
enum class EvidenceOutcome { MATCHED, PARTIAL, NOT_FOUND, UNKNOWN }
data class EvidenceReference(val chunkId: String, val label: String, val quote: String, val origin: EvidenceOrigin)
data class RequirementAlignment(val requirement: JobRequirement, val outcome: EvidenceOutcome, val evidence: List<EvidenceReference>, val inferred: Boolean = false)
data class EvidenceBasedMatchAnalysis(val opportunityId: String, val category: RelevanceBand, val deterministicSignals: List<String>, val requirements: List<RequirementAlignment>, val knowledgeHash: String, val inputHash: String, val modelVersion: String, val analyzedAt: Long, val status: String)
object EvidenceRules {
    const val VERSION = "career-evidence-v1"
    fun fingerprint(job: Opportunity, profile: CareerProfile?, chunks: List<KnowledgeChunk>, embedding: String, llm: String) = knowledgeHash(listOf(VERSION, job.contentHash, job.title, job.description, job.requirements.joinToString { it.text }, profile.toString(), chunks.sortedBy { it.id }.joinToString { "${it.id}:${it.hash}" }, embedding, llm).joinToString("|"))
    fun direct(requirement: JobRequirement, chunks: List<KnowledgeChunk>): RequirementAlignment {
        // Experience, eligibility and preference claims are never inferred from keyword hits.
        if (requirement.kind != RequirementKind.SKILL) return RequirementAlignment(requirement, EvidenceOutcome.UNKNOWN, emptyList())
        val found = chunks.filter { it.kind !in setOf(KnowledgeKind.PREFERENCES, KnowledgeKind.LINKS) && CareerVocabulary.contains(it.text, requirement.text) && !Regex("(?i)(?:no|not|without|learning|want to learn)\\s+(?:experience (?:in|with)\\s+)?${Regex.escape(requirement.text)}").containsMatchIn(it.text) }.take(3)
        return RequirementAlignment(requirement, if (found.isEmpty()) EvidenceOutcome.NOT_FOUND else EvidenceOutcome.MATCHED, found.map { EvidenceReference(it.id, it.label, it.text, it.origin) })
    }
}
interface CareerIntelligence {
    suspend fun synchronize()
    suspend fun index(): CapabilityResult<Int>
    suspend fun cached(job: Opportunity): EvidenceBasedMatchAnalysis?
    suspend fun analyze(job: Opportunity): EvidenceBasedMatchAnalysis
}

object ModelCatalog {
    const val EMBEDDING_ID = "embeddinggemma-300m-int4int8-mediapipe1"
    const val LLM_ID = "gemma-3n-E2B-it-int4-litertlm0.8.0"
    const val EMBEDDING_URL = "https://storage.googleapis.com/mediapipe-models/text_embedder/embedding_gemma/int4int8/latest/embedding_gemma.task"
    const val LLM_URL = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm"
    fun id(kind: ModelKind) = if (kind == ModelKind.EMBEDDING) EMBEDDING_ID else LLM_ID
    fun file(kind: ModelKind) = if (kind == ModelKind.EMBEDDING) "embedding.task" else "gemma.litertlm"
}

class ModelImportException(message: String, cause: Throwable): IllegalArgumentException(message, cause)
