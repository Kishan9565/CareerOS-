package com.kishan.careeros.data

import androidx.core.text.HtmlCompat
import com.kishan.careeros.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject

/** Tolerant at record boundaries, strict about the response envelope. */
class RemotiveParser(private val allCategories: Boolean = false, private val plainText: (String) -> String) {
    fun parse(body: String): List<RawOpportunity> {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid response")
        val jobs = root["jobs"] as? JsonArray ?: error("Missing jobs")
        require(jobs.size <= 2_000) { "Response too large" }
        return jobs.mapNotNull { element ->
            runCatching {
                val job = element as? JsonObject ?: return@runCatching null
                fun text(key: String) = (job[key] as? JsonPrimitive)?.takeIf { it.isString || key == "id" && it.longOrNull != null }?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                // The live endpoint may ignore query filters. Enforce its own category locally.
                if (!allCategories && text("category") !in setOf("Software Development", "software-dev")) return@runCatching null
                val url = text("url") ?: return@runCatching null
                val uri = java.net.URI(url)
                if (uri.host?.lowercase() !in setOf("remotive.com", "www.remotive.com") || !Validation.httpUrl(url)) return@runCatching null
                val description = text("description") ?: return@runCatching null
                if (description.length > 300_000) return@runCatching null
                val published = text("publication_date")?.let { value ->
                    runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
                        runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
                    }
                }
                RawOpportunity(text("id"), plainText(text("title") ?: return@runCatching null), plainText(text("company_name") ?: return@runCatching null),
                    text("candidate_required_location")?.let(plainText), true,
                    when (text("job_type")) { "full_time" -> EmploymentType.FULL_TIME; "part_time" -> EmploymentType.PART_TIME; "contract" -> EmploymentType.CONTRACT; "internship" -> EmploymentType.INTERNSHIP; "freelance" -> EmploymentType.FREELANCE; else -> null },
                    plainText(description), (job["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull } ?: emptyList(), url, published)
            }.getOrNull()
        }
    }
}

class RemotiveSource @Inject constructor(private val client: HttpClient): OpportunitySource {
    override val sourceId = ID
    override val capabilities = SourceCapabilities(ID, "Remotive", SourceKind.AGGREGATOR, DiscoveryMode.AUTOMATIC,
        supportsInternships = true, supportsJobs = true, supportsFreelance = true,
        attributionRequirement = "Remotive: remote-only public listings, delayed 24 hours; link back required")
    override suspend fun discover(config: SourceConfig): CapabilityResult<List<Opportunity>> = withContext(Dispatchers.IO) {
        try {
            require(config.id == ID && config.url == URL && config.enabled)
            val body = client.prepareGet(URL) {
                // Fetch all published categories; query filters were unreliable in live verification.
                // Each HTTP attempt is explicitly rate-limited by the discovery repository.
                retry { maxRetries = 0 }
            }.execute { response ->
                require(response.status.value == 200)
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(8_192)
                val output = ByteArrayOutputStream()
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count == -1) break
                    require(output.size() + count <= 6_000_000) { "Response too large" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            val parser = RemotiveParser(allCategories = true) { html ->
                val safe = html.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), "")
                HtmlCompat.fromHtml(safe, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
            }
            val now = System.currentTimeMillis()
            val rows = parser.parse(body).mapNotNull { OpportunityNormalizer.normalize(it, ID, "Remotive", now)?.copy(lifecycle = OpportunityLifecycle.ACTIVE, workplace = Workplace.REMOTE) }.distinctBy { it.id }
            CapabilityResult.Available(rows)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { CapabilityResult.Unavailable("Could not refresh Remotive. Check your connection or try again later. Your saved results are still available.") }
    }
    companion object {
        const val ID = "remotive"
        const val URL = "https://remotive.com/api/remote-jobs"
        fun config() = SourceConfig(ID, "Remotive", SourceType.PUBLIC_API, URL, enabled = true)
    }
}
