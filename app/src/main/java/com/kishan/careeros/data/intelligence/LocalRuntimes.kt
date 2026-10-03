package com.kishan.careeros.data.intelligence

import android.content.Context
import android.util.Log
import com.kishan.careeros.domain.*
import com.kishan.careeros.domain.intelligence.*
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class MediaPipeEmbeddingEngine(private val context: Context, private val models: AndroidLocalModelManager): EmbeddingEngine {
    override suspend fun embed(text: String) = run(listOf(text to ""), false).let { result -> when (result) { is CapabilityResult.Unavailable -> result; is CapabilityResult.Available -> CapabilityResult.Available(result.value.single()) } }
    override suspend fun embedDocument(text: String, title: String) = run(listOf(text to title), true).let { result -> when (result) { is CapabilityResult.Unavailable -> result; is CapabilityResult.Available -> CapabilityResult.Available(result.value.single()) } }
    override suspend fun embedBatch(texts: List<String>) = run(texts.map { it to "" }, false)
    override suspend fun embedDocuments(texts: List<Pair<String, String>>) = run(texts, true)
    private suspend fun run(texts: List<Pair<String, String>>, document: Boolean): CapabilityResult<List<FloatArray>> = models.inferenceMutex.withLock { withContext(Dispatchers.Default) {
        try {
            models.requireUsable(ModelKind.EMBEDDING)
            Log.d("IntelligenceDiagnostics", "Starting TextEmbedder initialization...")
            TextEmbedder.createFromFile(context, models.file(ModelKind.EMBEDDING)).use { engine ->
                val vectors = texts.map { (text, title) ->
                    ensureActive()
                    val format = TextEmbedder.TextFormatContext.builder().setTaskType(if (document) TextEmbedder.EmbeddingType.RETRIEVAL_DOCUMENT else TextEmbedder.EmbeddingType.RETRIEVAL_QUERY).setTitle(title).build()
                    engine.embed(text, format).embeddingResult().embeddings().first().floatEmbedding().also { require(it.isNotEmpty() && it.all(Float::isFinite)) }
                }
                models.mark(ModelKind.EMBEDDING, ModelState.READY, "EmbeddingGemma runtime ready (CPU)")
                CapabilityResult.Available(vectors)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { models.failure(ModelKind.EMBEDDING, e); CapabilityResult.Unavailable("Embedding model unavailable: ${e.javaClass.simpleName}") }
        catch (e: OutOfMemoryError) { models.failure(ModelKind.EMBEDDING, e); CapabilityResult.Unavailable("Not enough memory for local inference.") }
        catch (e: LinkageError) { models.failure(ModelKind.EMBEDDING, e); CapabilityResult.Unavailable("Native embedding runtime could not load") }
    } }
}
class LiteRtLocalLlm(private val models: AndroidLocalModelManager): LocalLlm {
    override suspend fun generate(request: LlmRequest): CapabilityResult<String> = models.inferenceMutex.withLock { withContext(Dispatchers.Default) {
        try {
            models.requireUsable(ModelKind.LLM)
            val input = request.untrustedContext.joinToString("\n\n")
            require(input.length <= 18_000 && request.instruction.length < 4_000)
            val config = GemmaRuntimeConfig.create(models.file(ModelKind.LLM).absolutePath)
            Engine.setNativeMinLogServerity(LogSeverity.INFO)
            Engine(config).use { engine ->
                models.logLlmInitialization(config.backend.name, requireNotNull(config.maxNumTokens))
                // SIGSEGV in nativeCreateEngine terminates the process; Kotlin catches below cannot recover it.
                engine.initialize()
                engine.createConversation(ConversationConfig(systemMessage = Message.of(request.instruction), samplerConfig = SamplerConfig(topK = 1, topP = 0.9, temperature = 0.1))).use { conversation ->
                    ensureActive()
                    val response = conversation.sendMessage(Message.of(input)).toString()
                    models.mark(ModelKind.LLM, ModelState.READY, "Gemma 3n E2B runtime ready (CPU)")
                    CapabilityResult.Available(response.take(30_000))
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { models.failure(ModelKind.LLM, e); CapabilityResult.Unavailable("Local LLM unavailable: ${e.javaClass.simpleName}") }
        catch (e: OutOfMemoryError) { models.failure(ModelKind.LLM, e); CapabilityResult.Unavailable("Not enough memory for local inference.") }
        catch (e: LinkageError) { models.failure(ModelKind.LLM, e); CapabilityResult.Unavailable("Local LLM runtime could not load") }
    } }
}
