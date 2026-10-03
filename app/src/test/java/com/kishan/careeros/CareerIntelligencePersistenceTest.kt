package com.kishan.careeros

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kishan.careeros.data.*
import com.kishan.careeros.data.intelligence.*
import com.kishan.careeros.domain.*
import com.kishan.careeros.domain.intelligence.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CareerIntelligencePersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun pdfTextExtractionHandlesTextEmptyAndCorruptDocumentsLocally() = runTest {
        PDFBoxResourceLoader.init(context)
        fun pdf(text: String?): ByteArray = PDDocument().use { document ->
            val page = PDPage(); document.addPage(page)
            if (text != null) PDPageContentStream(document,page).use { stream -> stream.beginText(); stream.setFont(PDType1Font.HELVETICA,12f); stream.newLineAtOffset(30f,700f); stream.showText(text); stream.endText() }
            ByteArrayOutputStream().also(document::save).toByteArray()
        }
        suspend fun extract(bytes: ByteArray): CapabilityResult<String> { val uri = Uri.parse("content://test/resume.pdf"); shadowOf(context.contentResolver).registerInputStream(uri,ByteArrayInputStream(bytes)); return PdfResumeTextExtractor(context).extract(uri.toString()) }
        val result = extract(pdf("Built an Android project using Kotlin and Room"))
        assertTrue(result is CapabilityResult.Available); assertTrue((result as CapabilityResult.Available).value.contains("Kotlin and Room"))
        assertTrue(extract(pdf(null)) is CapabilityResult.Unavailable)
        assertTrue(extract("%PDF-corrupted".toByteArray()) is CapabilityResult.Unavailable)
    }
    @Test fun vectorsRespectHashesAndOrderAndResumeDeletionRemovesEvidence() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context,CareerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val knowledge = RoomCareerKnowledge(db); val store = RoomVectorStore(db)
            assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { knowledge.replaceResume(listOf(ResumeCandidate(KnowledgeKind.SKILLS,"Skills","Kotlin"))) } }
            knowledge.replaceResume(listOf(ResumeCandidate(KnowledgeKind.SKILLS,"Skills","Kotlin",true),ResumeCandidate(KnowledgeKind.PROJECT,"Project","Built a website",true)))
            val chunks = knowledge.chunks(); store.put(chunks[0],"v1",floatArrayOf(1f,0f)); store.put(chunks[1],"v1",floatArrayOf(0f,1f))
            assertEquals(chunks[1].id,store.search(floatArrayOf(0f,1f),"v1").first().chunk.id)
            assertTrue(store.search(floatArrayOf(1f,0f),"v2").isEmpty())
            knowledge.replaceResume(listOf(ResumeCandidate(KnowledgeKind.SKILLS,"Skills","Kotlin and Hilt",true)))
            assertEquals(0,store.count("v1")); knowledge.removeResume(); assertTrue(knowledge.chunks().isEmpty())
        } finally { db.close() }
    }
    @Test fun fakeModelCannotPromoteInventedEvidenceAndRepeatedAnalysisUsesCache() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context,CareerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val profiles = RoomProfileRepository(db); profiles.save(CareerProfile(name="User",headline="Android",skills=listOf(Skill(name="Kotlin")),preferences=CareerPreferences(targetRoles=listOf("Android Engineer"))))
            val knowledge = RoomCareerKnowledge(db); val vectors = RoomVectorStore(db); var calls = 0
            val embedding = object: EmbeddingEngine { override suspend fun embed(text: String) = CapabilityResult.Available(floatArrayOf(1f,0f)) }
            val models = object: LocalModelManager {
                override fun observe() = flowOf(emptyList<LocalModelStatus>())
                override suspend fun importModel(kind: ModelKind,document: String,expectedSha256: String) = Unit
                override suspend fun remove(kind: ModelKind) = Unit
                override fun version(kind: ModelKind) = kind.name + "v1"
            }
            val llm = object: LocalLlm { override suspend fun generate(request: LlmRequest): CapabilityResult<String> { calls++; assertFalse(request.untrustedContext.joinToString().contains("User")); return CapabilityResult.Available("""{"connections":[{"requirementId":"invented","chunkId":"invented","quote":"Invented job at Google"}]}""") } }
            val service = LocalCareerIntelligence(db,knowledge,profiles,RoomAnswerBankRepository(db),embedding,vectors,llm,models)
            service.index()
            val job = Opportunity(title="Android Engineer",company="Fixture",description="Kotlin AWS",sourceId="test",sourceName="Test",canonicalUrl="https://example.com/job",contentHash="job")
            val result = service.analyze(job)
            assertEquals(EvidenceOutcome.NOT_FOUND,result.requirements.first { it.requirement.text == "aws" }.outcome)
            assertNotNull(service.cached(job)); service.analyze(job); assertEquals(1,calls)
            assertNull(service.cached(job.copy(contentHash="changed")))
        } finally { db.close() }
    }
    @Test fun migrationFromThreePreservesExistingOpportunitiesAndValidatesSchema() = runTest {
        val name = "knowledge-migration.db"; context.deleteDatabase(name)
        val schema = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/com.kishan.careeros.data.CareerDatabase/3.json")).bufferedReader().use { it.readText() }).jsonObject["database"]!!.jsonObject
        context.openOrCreateDatabase(name,0,null).use { old ->
            schema["entities"]!!.jsonArray.forEach { element -> val entity = element.jsonObject; val table = entity["tableName"]!!.jsonPrimitive.content
                old.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",table))
                entity["indices"]?.jsonArray?.forEach { old.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",table)) }
            }
            old.execSQL("INSERT INTO opportunities(id,title,company,description,skills,sourceId,sourceName,canonicalUrl,discoveredAt,contentHash,status) VALUES('job','Engineer','Company','Description','[]','source','Source','https://example.com/job',1,'hash','NEW')")
            old.version = 3
        }
        val db = Room.databaseBuilder(context,CareerDatabase::class.java,name).addMigrations(KnowledgeMigration.MIGRATION_3_4).allowMainThreadQueries().build()
        try { assertEquals("Engineer",RoomOpportunityRepository(db).get("job")!!.title); assertTrue(db.knowledge().all().isEmpty()); assertEquals(4,db.openHelper.writableDatabase.version) }
        finally { db.close(); context.deleteDatabase(name) }
    }
}
