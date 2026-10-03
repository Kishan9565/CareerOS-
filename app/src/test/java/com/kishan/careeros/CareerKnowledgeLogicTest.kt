package com.kishan.careeros

import com.kishan.careeros.domain.*
import com.kishan.careeros.domain.intelligence.*
import com.kishan.careeros.data.intelligence.EvidenceJson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CareerKnowledgeLogicTest {
    private fun source(text: String, state: EvidenceState = EvidenceState.VERIFIED) = CareerKnowledge("project", "profile", KnowledgeKind.PROJECT, "Actual project", text, EvidenceOrigin.PROFILE_VERIFIED, state, 1)
    private fun job(text: String) = Opportunity(title = "Android Engineer", company = "Fixture", description = text, sourceId = "test", sourceName = "Test", canonicalUrl = "https://example.com/job", contentHash = knowledgeHash(text))
    @Test fun resumeReviewPreservesExactSectionsAndNeverConfirmsOrInfersExperience() {
        val result = ResumeReviewMapper.review("Candidate\nSkills\nKotlin, Room\nExperience\nBuilt an Android app\nEducation\nBSc Example\nCertifications\nCertificate ABC")
        assertEquals(5,result.candidates.size); assertTrue(result.candidates.none { it.confirmed })
        assertEquals("Built an Android app",result.candidates.single { it.kind == KnowledgeKind.EXPERIENCE }.text)
        assertFalse(result.candidates.any { it.text.contains("years") })
    }
    @Test fun chunksAreStableBoundedAndRejectInferredKnowledge() {
        val text = (1..1000).joinToString(" ") { "word$it" }
        val chunks = KnowledgeChunker.chunks(source(text)); assertEquals(4,chunks.size)
        assertTrue(chunks.all { it.origin == EvidenceOrigin.PROFILE_VERIFIED && it.text.split(' ').size <= 350 })
        assertEquals(chunks,KnowledgeChunker.chunks(source(text)))
        assertNotEquals(chunks[0].hash,KnowledgeChunker.chunks(source("changed $text"))[0].hash)
        assertTrue(KnowledgeChunker.chunks(source(text,EvidenceState.INFERRED)).isEmpty())
    }
    @Test fun vectorSerializationAndCosineHandleZeroAndRejectInvalidNumbers() {
        val v = floatArrayOf(0.1f,-0.5f,1f); assertArrayEquals(v,VectorMath.decode(VectorMath.encode(v)),0f)
        assertEquals(1.0,VectorMath.cosine(v,v),0.000001); assertEquals(0.0,VectorMath.cosine(floatArrayOf(0f),floatArrayOf(1f)),0.0)
        assertThrows(IllegalArgumentException::class.java) { VectorMath.encode(floatArrayOf(Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { VectorMath.cosine(floatArrayOf(1f),floatArrayOf(1f,2f)) }
        assertThrows(IllegalArgumentException::class.java) { VectorMath.decode(byteArrayOf(1)) }
    }
    @Test fun requirementsAndEvidenceDoNotCreateUnknownExperienceOrSkills() {
        val requirements = JobRequirementExtractor.extract(job("Android Kotlin. Minimum 2 years professional experience. AWS required."))
        val chunks = KnowledgeChunker.chunks(source("Built Android application with Kotlin. No AWS experience."))
        assertEquals(EvidenceOutcome.MATCHED,EvidenceRules.direct(requirements.first { it.text == "kotlin" },chunks).outcome)
        assertEquals(EvidenceOutcome.NOT_FOUND,EvidenceRules.direct(requirements.first { it.text == "aws" },chunks).outcome)
        assertEquals(EvidenceOutcome.UNKNOWN,EvidenceRules.direct(requirements.first { it.kind == RequirementKind.EXPERIENCE },chunks).outcome)
    }
    @Test fun modelCanOnlyProposeCitedPartialRelationships() {
        val chunk = KnowledgeChunker.chunks(source("Built local persistence using Room in an Android application.")).single()
        val req = JobRequirement("req","Offline persistence",RequirementKind.RESPONSIBILITY,true)
        fun response(id: String, quote: String) = """{"connections":[{"requirementId":"req","chunkId":"$id","quote":"$quote"}]}"""
        val allowed = EvidenceJson.proposals(response(chunk.id,chunk.text),listOf(req),listOf(chunk)).single()
        assertEquals(EvidenceOutcome.PARTIAL,allowed.outcome); assertTrue(allowed.inferred)
        assertTrue(EvidenceJson.proposals(response("invented",chunk.text),listOf(req),listOf(chunk)).isEmpty())
        assertTrue(EvidenceJson.proposals(response(chunk.id,"Worked for Google for 5 years"),listOf(req),listOf(chunk)).isEmpty())
        assertTrue(EvidenceJson.proposals(response(chunk.id,chunk.text),listOf(req.copy(kind = RequirementKind.EXPERIENCE)),listOf(chunk)).isEmpty())
    }
    @Test fun analysisFingerprintInvalidatesOnEvidenceJobOrModels() {
        val chunks = KnowledgeChunker.chunks(source("Kotlin project")); val job = job("Kotlin")
        fun hash(j: Opportunity = job, c: List<KnowledgeChunk> = chunks, model: String = "m1") = EvidenceRules.fingerprint(j,null,c,"e1",model)
        assertEquals(hash(),hash()); assertNotEquals(hash(),hash(job.copy(description = "AWS"))); assertNotEquals(hash(),hash(c = emptyList())); assertNotEquals(hash(),hash(model = "m2"))
    }
    @Test fun ragSelectsAtMostFiveUsefulDistinctChunksAndPropagatesUnavailable() = runTest {
        val chunks = (1..10).map { KnowledgeChunker.chunks(source("Actual evidence $it").copy(id = "$it")).single() }
        val store = object: VectorStore {
            override suspend fun put(chunk: KnowledgeChunk,modelVersion: String,vector: FloatArray) = Unit
            override suspend fun contains(chunk: KnowledgeChunk,modelVersion: String) = true
            override suspend fun count(modelVersion: String) = 10
            override suspend fun search(query: FloatArray,modelVersion: String,topK: Int): List<VectorHit> { assertEquals(8,topK); return chunks.take(topK).map { VectorHit(it,0.8) } }
        }
        val engine = object: EmbeddingEngine { override suspend fun embed(text: String) = CapabilityResult.Available(floatArrayOf(1f)) }
        val rag = CareerRagRetriever(engine,store) { "v1" }
        assertEquals(5,(rag.retrieve("Kotlin",8) as CapabilityResult.Available).value.size)
        val unavailable = object: EmbeddingEngine { override suspend fun embed(text: String) = CapabilityResult.Unavailable("not installed") }
        assertTrue(CareerRagRetriever(unavailable,store) { "v1" }.retrieve("test",5) is CapabilityResult.Unavailable)
    }
}
