package com.kishan.careeros.data.intelligence

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kishan.careeros.data.*
import com.kishan.careeros.domain.*
import com.kishan.careeros.domain.intelligence.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

@Entity(tableName = "career_knowledge")
data class KnowledgeEntity(@PrimaryKey val id: String, val sourceId: String, val kind: String, val label: String, val text: String, val origin: String, val updatedAt: Long) {
    fun domain() = CareerKnowledge(id, sourceId, KnowledgeKind.valueOf(kind), label, text, EvidenceOrigin.valueOf(origin), updatedAt = updatedAt)
}
@Entity(tableName = "knowledge_chunks")
data class ChunkEntity(@PrimaryKey val id: String, val sourceId: String, val kind: String, val label: String, val text: String, val origin: String, val hash: String, val updatedAt: Long) {
    fun domain() = KnowledgeChunk(id, sourceId, KnowledgeKind.valueOf(kind), label, text, EvidenceOrigin.valueOf(origin), hash, updatedAt)
}
@Entity(tableName = "knowledge_vectors", primaryKeys = ["chunkId", "modelVersion"], foreignKeys = [ForeignKey(entity = ChunkEntity::class, parentColumns = ["id"], childColumns = ["chunkId"], onDelete = ForeignKey.CASCADE)])
data class VectorEntity(val chunkId: String, val modelVersion: String, val contentHash: String, val vector: ByteArray, val updatedAt: Long)
@Entity(tableName = "evidence_analyses")
data class AnalysisEntity(@PrimaryKey val opportunityId: String, val inputHash: String, val body: String)
@Dao interface KnowledgeDao {
    @Query("SELECT * FROM career_knowledge ORDER BY id") fun observe(): kotlinx.coroutines.flow.Flow<List<KnowledgeEntity>>
    @Query("SELECT * FROM career_knowledge ORDER BY id") suspend fun all(): List<KnowledgeEntity>
    @Upsert suspend fun put(rows: List<KnowledgeEntity>)
    @Query("DELETE FROM career_knowledge WHERE sourceId = :source") suspend fun removeSource(source: String)
    @Query("SELECT * FROM knowledge_chunks ORDER BY id") suspend fun chunks(): List<ChunkEntity>
    @Upsert suspend fun chunks(rows: List<ChunkEntity>)
    @Query("DELETE FROM knowledge_chunks WHERE id NOT IN (:ids)") suspend fun removeOtherChunks(ids: List<String>)
    @Query("SELECT * FROM knowledge_vectors WHERE modelVersion = :model") suspend fun vectors(model: String): List<VectorEntity>
    @Query("DELETE FROM knowledge_vectors WHERE chunkId = :id AND contentHash != :hash") suspend fun invalidate(id: String, hash: String)
    @Upsert suspend fun vector(row: VectorEntity)
    @Query("SELECT * FROM evidence_analyses WHERE opportunityId = :id") suspend fun analysis(id: String): AnalysisEntity?
    @Upsert suspend fun analysis(row: AnalysisEntity)
    @Query("DELETE FROM evidence_analyses") suspend fun invalidateAnalyses()
}
object KnowledgeMigration {
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE career_knowledge (id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, kind TEXT NOT NULL, label TEXT NOT NULL, text TEXT NOT NULL, origin TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE knowledge_chunks (id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, kind TEXT NOT NULL, label TEXT NOT NULL, text TEXT NOT NULL, origin TEXT NOT NULL, hash TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE knowledge_vectors (chunkId TEXT NOT NULL, modelVersion TEXT NOT NULL, contentHash TEXT NOT NULL, vector BLOB NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(chunkId,modelVersion), FOREIGN KEY(chunkId) REFERENCES knowledge_chunks(id) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE evidence_analyses (opportunityId TEXT NOT NULL PRIMARY KEY, inputHash TEXT NOT NULL, body TEXT NOT NULL)")
        }
    }
}
class RoomCareerKnowledge(private val db: CareerDatabase): CareerKnowledgeRepository {
    override fun observe() = db.knowledge().observe().map { it.map(KnowledgeEntity::domain) }
    override suspend fun replaceResume(candidates: List<ResumeCandidate>) {
        require(candidates.isNotEmpty() && candidates.all { it.confirmed && it.text.isNotBlank() } && candidates.sumOf { it.text.length } <= 100_000)
        replace("resume", candidates.mapIndexed { index, c -> CareerKnowledge("resume:$index", "resume", c.kind, c.label, c.text, EvidenceOrigin.RESUME_VERIFIED) })
    }
    override suspend fun removeResume() = replace("resume", emptyList())
    override suspend fun synchronizeProfile(profile: CareerProfile?, facts: List<SavedApplicationAnswer>) {
        val rows = buildList {
            profile?.let { p ->
                fun add(id: String, kind: KnowledgeKind, label: String, text: String) { if (text.isNotBlank()) add(CareerKnowledge("profile:$id", "profile", kind, label, text, EvidenceOrigin.PROFILE_VERIFIED, updatedAt = p.updatedAt)) }
                add("summary", KnowledgeKind.SUMMARY, "Profile", p.headline)
                add("skills", KnowledgeKind.SKILLS, "Profile skills", p.skills.joinToString(", ") { it.name })
                p.projects.forEach { add("project:${it.id}", KnowledgeKind.PROJECT, it.name, "${it.name}\n${it.description}") }
                p.education.forEach { add("education:${it.id}", KnowledgeKind.EDUCATION, it.institution, "${it.qualification}, ${it.institution}, ${it.startYear ?: ""} - ${it.endYear ?: ""}") }
                p.experienceYears?.let { add("experience", KnowledgeKind.EXPERIENCE, "Verified profile experience", "$it years of experience") }
                add("preferences", KnowledgeKind.PREFERENCES, "Career preferences (not experience)", "Target roles: ${p.preferences.targetRoles.joinToString()}; locations: ${p.preferences.locations.joinToString()}; ${p.preferences.remote}; ${p.preferences.employmentTypes.joinToString()}")
                add("links", KnowledgeKind.LINKS, "Profile links", listOfNotNull(p.githubUrl, p.linkedinUrl).joinToString("\n"))
            }
            facts.filter { it.eligibleForAutofill() }.forEach { add(CareerKnowledge("profile:fact:${it.id}", "profile", KnowledgeKind.FACT, it.originalQuestion, it.answer, EvidenceOrigin.USER_VERIFIED, updatedAt = it.updatedAt)) }
        }
        replace("profile", rows)
    }
    private suspend fun replace(source: String, rows: List<CareerKnowledge>) = db.withTransaction {
        val dao = db.knowledge(); val old = dao.all().filter { it.sourceId == source }.map { it.domain() }
        if (old.sortedBy { it.id } == rows.sortedBy { it.id }) return@withTransaction
        dao.removeSource(source); dao.put(rows.map { KnowledgeEntity(it.id, it.sourceId, it.kind.name, it.label, it.text, it.origin.name, it.updatedAt) })
        val chunks = dao.all().flatMap { KnowledgeChunker.chunks(it.domain()) }
        dao.removeOtherChunks(chunks.map { it.id })
        dao.chunks(chunks.map { ChunkEntity(it.id, it.sourceId, it.kind.name, it.label, it.text, it.origin.name, it.hash, it.updatedAt) })
        chunks.forEach { dao.invalidate(it.id, it.hash) }; dao.invalidateAnalyses()
    }
    override suspend fun chunks() = db.knowledge().chunks().map(ChunkEntity::domain)
}
class RoomVectorStore(private val db: CareerDatabase): VectorStore {
    override suspend fun put(chunk: KnowledgeChunk, modelVersion: String, vector: FloatArray) { db.knowledge().vector(VectorEntity(chunk.id, modelVersion, chunk.hash, VectorMath.encode(vector), System.currentTimeMillis())) }
    override suspend fun contains(chunk: KnowledgeChunk, modelVersion: String) = db.knowledge().vectors(modelVersion).any { it.chunkId == chunk.id && it.contentHash == chunk.hash }
    override suspend fun search(query: FloatArray, modelVersion: String, topK: Int): List<VectorHit> {
        val chunks = db.knowledge().chunks().associateBy { it.id }
        return db.knowledge().vectors(modelVersion).mapNotNull { v -> val c = chunks[v.chunkId]?.takeIf { it.hash == v.contentHash } ?: return@mapNotNull null
            runCatching { val vector = VectorMath.decode(v.vector); if (vector.size != query.size) null else VectorHit(c.domain(), VectorMath.cosine(query, vector)) }.getOrNull()
        }.sortedWith(compareByDescending<VectorHit> { it.similarity }.thenBy { it.chunk.id }).take(topK.coerceIn(1, 50))
    }
    override suspend fun count(modelVersion: String): Int { val chunks = db.knowledge().chunks().associateBy { it.id }; return db.knowledge().vectors(modelVersion).count { chunks[it.chunkId]?.hash == it.contentHash } }
}
