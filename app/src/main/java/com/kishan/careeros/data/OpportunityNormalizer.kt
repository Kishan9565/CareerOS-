package com.kishan.careeros.data

import com.kishan.careeros.domain.*
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

object OpportunityNormalizer {
    private fun normalized(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun canonicalUrl(value: String): String? = runCatching {
        require(Validation.httpUrl(value.trim()))
        val uri = URI(value.trim()).normalize()
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT).let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
        val port = if (uri.port == -1 || scheme == "https" && uri.port == 443 || scheme == "http" && uri.port == 80) "" else ":${uri.port}"
        val query = uri.rawQuery?.split('&')?.filter { part ->
            val key = URLDecoder.decode(part.substringBefore('='), "UTF-8").lowercase(Locale.ROOT)
            !key.startsWith("utm_") && key !in setOf("fbclid", "gclid", "msclkid", "dclid", "mc_cid", "mc_eid", "gh_src", "lever-source", "lever-origin")
        }?.sorted()?.joinToString("&")?.takeIf { it.isNotBlank() }
        "$scheme://$host$port${uri.rawPath.ifEmpty { "/" }}${query?.let { "?$it" }.orEmpty()}"
    }.getOrNull()

    fun identity(sourceId: String, externalId: String?, company: String, title: String, location: String?, publishedAt: Long?): String {
        val external = externalId?.trim()?.takeIf { it.isNotEmpty() }
        val key = if (external != null) listOf(sourceId, external) else listOf(
            normalized(company), normalized(title), normalized(location.orEmpty()),
            publishedAt?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString() }.orEmpty(),
        )
        return hash(encodeList(key))
    }

    fun normalize(raw: RawOpportunity, sourceId: String, sourceName: String, now: Long, allowEmptyDescription: Boolean = false): Opportunity? {
        val title = raw.title.trim(); val company = raw.company.trim(); val description = raw.description.trim()
        if (title.isEmpty() || company.isEmpty() || (!allowEmptyDescription && description.isEmpty()) || title.length > 500 || company.length > 500 || description.length > 200_000) return null
        val url = canonicalUrl(raw.url) ?: return null
        val skills = raw.skills.map(String::trim).filter(String::isNotBlank).distinct().sorted()
        val externalId = raw.externalId?.trim()?.takeIf(String::isNotBlank)
        val location = raw.location?.trim()?.takeIf(String::isNotBlank)
        val hash = hash(encodeList(listOf(title, company, location.orEmpty(), raw.remote.toString(), raw.employmentType?.name.orEmpty(), description, encodeList(skills), url, raw.publishedAt.toString(), raw.workplace.name, raw.salary.toString())))
        return Opportunity(id = identity(sourceId, externalId, company, title, location, raw.publishedAt), title = title, company = company,
            location = location, remote = raw.remote, employmentType = raw.employmentType, description = description,
            skills = skills, sourceId = sourceId, sourceName = sourceName, canonicalUrl = url, externalId = externalId,
            publishedAt = raw.publishedAt, discoveredAt = now, contentHash = hash, originalUrl = raw.url, fetchedAt = now, lastSeenAt = now, workplace = raw.workplace, salary = raw.salary)
    }
}
