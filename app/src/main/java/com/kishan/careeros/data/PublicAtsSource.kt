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
import java.net.URI
import java.time.Instant

fun sourcePlainText(html: String): String {
    var result = html
    repeat(2) {
        result = result.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), "")
        result = HtmlCompat.fromHtml(result, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
    }
    return result.trim()
}
class PublicBoardClient(private val client: HttpClient) {
    suspend fun get(url: String): String = client.prepareGet(url) { retry { maxRetries = 0 } }.execute { response ->
        require(response.status.value == 200) { "Source returned HTTP ${response.status.value}" }
        val channel = response.bodyAsChannel(); val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8_192)
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count == -1) break
            require(bytes.size() + count <= 12_000_000) { "Board response exceeds safe size" }
            bytes.write(buffer, 0, count)
        }
        bytes.toString("UTF-8")
    }
}
data class ParsedBoard(val opportunities: List<Opportunity>, val complete: Boolean)
class AtsParser(private val plainText: (String) -> String) {
    fun parse(body: String, company: CompanyCareerSource, now: Long): ParsedBoard {
        val json = Json.parseToJsonElement(body)
        val rows = if (company.providerType == ProviderType.GREENHOUSE) (json as? JsonObject)?.get("jobs") as? JsonArray else json as? JsonArray
        requireNotNull(rows) { "Malformed published-job response" }
        require(rows.size <= 5_000)
        var invalid = 0
        val jobs = rows.mapNotNull { value ->
            runCatching {
                val o = value.jsonObject
                fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val greenhouse = company.providerType == ProviderType.GREENHOUSE
                // Prospect/general-interest posts are not concrete open positions.
                if (greenhouse && o["internal_job_id"] == JsonNull) return@runCatching null
                val id = requireNotNull(text("id"))
                val title = requireNotNull(text(if (greenhouse) "title" else "text"))
                val url = requireNotNull(text(if (greenhouse) "absolute_url" else "hostedUrl"))
                require(Validation.httpUrl(url) && URI(url).scheme == "https")
                val host = URI(url).host.lowercase()
                require(if (greenhouse) host in setOf("boards.greenhouse.io", "job-boards.greenhouse.io", URI(company.careersUrl).host) else host == "jobs.lever.co" && URI(url).path.startsWith("/${company.providerIdentifier}/"))
                val categories = o["categories"] as? JsonObject
                val location = if (greenhouse) (o["location"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull else
                    (categories?.get("allLocations") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.joinToString(" / ")?.takeIf(String::isNotBlank) ?: categories?.get("location")?.jsonPrimitive?.contentOrNull
                val workplace = if (greenhouse) {
                    // Only explicit location markers; do not infer from company reputation.
                    when { location?.contains("hybrid", true) == true -> Workplace.HYBRID; location?.contains("remote", true) == true -> Workplace.REMOTE; else -> Workplace.UNKNOWN }
                } else when (text("workplaceType")) { "remote" -> Workplace.REMOTE; "hybrid" -> Workplace.HYBRID; "on-site", "onsite" -> Workplace.ONSITE; else -> Workplace.UNKNOWN }
                val type = categories?.get("commitment")?.jsonPrimitive?.contentOrNull?.lowercase()?.let { commitment ->
                    when { commitment.contains("intern") -> EmploymentType.INTERNSHIP; commitment.contains("full time") || commitment.contains("full-time") -> EmploymentType.FULL_TIME; commitment.contains("part time") || commitment.contains("part-time") -> EmploymentType.PART_TIME; commitment.contains("contract") -> EmploymentType.CONTRACT; commitment.contains("freelance") -> EmploymentType.FREELANCE; else -> null }
                }
                val lists = (o["lists"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    val section = entry as? JsonObject ?: return@mapNotNull null
                    val label = section["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val content = section["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    "$label\n${plainText(content)}"
                }
                val description = if (greenhouse) plainText(requireNotNull(text("content"))) else
                    (listOfNotNull(text("descriptionPlain") ?: text("description")?.let(plainText)) + lists + listOfNotNull(text("additionalPlain") ?: text("additional")?.let(plainText))).joinToString("\n\n")
                val published = if (greenhouse) text("first_published")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } else null // createdAt is not a documented publication time.
                val salary = (o["salaryRange"] as? JsonObject)?.let { salary -> runCatching {
                    val period = when (salary["interval"]?.jsonPrimitive?.content?.lowercase()) { "per-year", "year", "yearly" -> SalaryPeriod.YEAR; "per-month", "month" -> SalaryPeriod.MONTH; "per-hour", "hour" -> SalaryPeriod.HOUR; else -> return@runCatching null }
                    Salary(salary["min"]?.jsonPrimitive?.longOrNull, salary["max"]?.jsonPrimitive?.longOrNull, salary["currency"]!!.jsonPrimitive.content.uppercase(), period)
                }.getOrNull() }
                val raw = RawOpportunity(id, plainText(title), company.companyName, location, when (workplace) { Workplace.REMOTE -> true; Workplace.HYBRID, Workplace.ONSITE -> false; else -> null }, type, description, emptyList(), url, published, workplace, salary)
                requireNotNull(OpportunityNormalizer.normalize(raw, company.id, "${company.companyName} / ${company.providerType.name.lowercase().replaceFirstChar(Char::uppercase)}", now)).copy(
                    companyCareerUrl = company.careersUrl, lifecycle = OpportunityLifecycle.ACTIVE,
                    deadline = text("application_deadline")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                ).let { job ->
                    val lifecycle = if (job.deadline != null && job.deadline < now) OpportunityLifecycle.CLOSED else OpportunityLifecycle.ACTIVE
                    job.copy(lifecycle = lifecycle, provenance = listOf(OpportunityProvenance(job.sourceId, job.sourceName, id, url, job.canonicalUrl, company.careersUrl, now, published, now, lifecycle, true)))
                }
            }.onFailure { invalid++ }.getOrNull()
        }
        return ParsedBoard(jobs, invalid == 0)
    }
}
class PublicAtsSource(private val company: CompanyCareerSource, private val network: PublicBoardClient): OpportunitySource {
    override val sourceId = company.id
    override val capabilities = SourceCapabilities(sourceId, "${company.companyName} / ${company.providerType}", SourceKind.ATS, DiscoveryMode.AUTOMATIC, supportsInternships = true, supportsJobs = true,
        attributionRequirement = "${company.companyName} company careers via ${company.providerType}")
    var completeSnapshot: Boolean = false
        private set
    override suspend fun discover(config: SourceConfig): CapabilityResult<List<Opportunity>> = withContext(Dispatchers.IO) {
        completeSnapshot = false
        try {
            require(CompanyCatalog.validate(company) && config.enabled && config.url == CompanyCatalog.endpoint(company))
            val parser = AtsParser(::sourcePlainText); val now = System.currentTimeMillis()
            if (company.providerType == ProviderType.GREENHOUSE) {
                val parsed = parser.parse(network.get(config.url), company, now)
                completeSnapshot = parsed.complete
                CapabilityResult.Available(parsed.opportunities)
            } else {
                val all = mutableListOf<Opportunity>(); var complete = true
                for (page in 0 until 10) {
                    val body = network.get("${config.url}&limit=500&skip=${page * 500}")
                    val count = Json.parseToJsonElement(body).jsonArray.size
                    val parsed = parser.parse(body, company, now)
                    require(parsed.opportunities.none { row -> all.any { it.externalId == row.externalId } }) { "Board pagination repeated" }
                    all += parsed.opportunities; complete = complete && parsed.complete
                    if (count < 500) { completeSnapshot = complete; return@withContext CapabilityResult.Available(all) }
                }
                CapabilityResult.Unavailable("Board exceeds this connector's 5,000-post safety limit; cached jobs retained.")
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { CapabilityResult.Unavailable("${company.companyName}: connection or published-board response unavailable. Cached opportunities retained.") }
    }
}
