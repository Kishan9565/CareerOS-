package com.kishan.careeros.domain

import java.net.URI

data class ImportReview(
    val url: String = "", val originalUrl: String = "", val title: String = "", val company: String = "",
    val location: String = "", val locationUnknown: Boolean = false, val description: String = "", val confirmed: Boolean = false,
)
object SharedOpportunityImporter {
    val capabilities = SourceCapabilities("manual", "Share to CareerOS", SourceKind.MANUAL, DiscoveryMode.USER_IMPORT, supportsInternships = true, supportsJobs = true, supportsFreelance = true, attributionRequirement = "Preserve the shared original URL and user-reviewed information")
    const val MAX_TEXT = 100_000
    fun review(text: String, subject: String? = null): ImportReview {
        require(text.length <= MAX_TEXT) { "Shared text is too large. Share a URL or shorter description." }
        val url = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(text).map { it.value.trimEnd('.', ',', ')', ']', ';') }.firstOrNull(Validation::httpUrl).orEmpty()
        fun field(name: String) = Regex("(?im)^$name:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return ImportReview(url, url, field("title").ifEmpty { subject?.take(500).orEmpty() }, field("company"), field("location"),
            description = text.replace(url.takeIf { it.isNotEmpty() } ?: "\u0000", "").trim())
    }
    fun errors(review: ImportReview): List<String> = buildList {
        if (!Validation.httpUrl(review.url)) add("Enter a valid HTTP/HTTPS listing URL")
        if (review.title.isBlank() || review.title.length > 500) add("Confirm the job title (up to 500 characters)")
        if (review.company.isBlank() || review.company.length > 500) add("Confirm the company (up to 500 characters)")
        if (review.location.isBlank() && !review.locationUnknown) add("Confirm the location or mark it unknown")
        if (review.location.length > 500) add("Location must be at most 500 characters")
        if (review.description.length > MAX_TEXT) add("Description is too long")
        if (!review.confirmed) add("Confirm that you reviewed the imported information")
    }
    fun sourceName(url: String): String {
        val host = runCatching { URI(url).host.lowercase() }.getOrDefault("")
        return listOf("linkedin.com" to "LinkedIn", "indeed.com" to "Indeed", "naukri.com" to "Naukri", "internshala.com" to "Internshala", "wellfound.com" to "Wellfound").find { host == it.first || host.endsWith(".${it.first}") }?.second ?: host.ifBlank { "Shared URL" }
    }
}
