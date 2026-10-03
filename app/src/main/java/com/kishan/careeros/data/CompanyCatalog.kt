package com.kishan.careeros.data

import com.kishan.careeros.domain.*
import kotlinx.serialization.json.*
import java.net.URI
import java.time.Instant

object CompanyCatalog {
    fun endpoint(company: CompanyCareerSource): String = when (company.providerType) {
        ProviderType.GREENHOUSE -> "https://boards-api.greenhouse.io/v1/boards/${company.providerIdentifier}/jobs?content=true"
        ProviderType.LEVER -> "https://api.lever.co/v0/postings/${company.providerIdentifier}?mode=json"
        else -> company.careersUrl
    }
    fun validate(company: CompanyCareerSource): Boolean = runCatching {
        require(company.id.isNotBlank() && company.companyName.isNotBlank())
        require(Validation.httpUrl(company.careersUrl) && URI(company.careersUrl).scheme == "https")
        if (company.providerType in setOf(ProviderType.GREENHOUSE, ProviderType.LEVER)) {
            require(company.providerIdentifier.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
            require(company.id == "${company.providerType.name.lowercase()}:${company.providerIdentifier}")
            require(company.verificationState == EndpointVerification.VERIFIED_ENDPOINT && company.lastVerifiedAt != null)
            require(company.verificationUrl == endpoint(company))
        } else require(company.providerType == ProviderType.EXTERNAL_SEARCH_ONLY)
        true
    }.getOrDefault(false)
    fun parse(text: String): List<CompanyCareerSource> {
        val root = Json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject
        require(root["version"]?.jsonPrimitive?.int == 1)
        val entries = root["companies"]!!.jsonArray
        require(entries.size <= 100)
        return entries.map { element ->
            val o = element.jsonObject
            fun s(key: String) = o[key]!!.jsonPrimitive.content
            fun list(key: String) = o[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            CompanyCareerSource(s("id"), s("companyName"), ProviderType.valueOf(s("providerType")), s("providerIdentifier"), s("careersUrl"), o["enabled"]!!.jsonPrimitive.boolean,
                list("tags"), list("supportedLocations"), EndpointVerification.valueOf(s("verificationState")), o["lastVerifiedAt"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it).toEpochMilli() }, s("verificationUrl"))
                .also { require(validate(it)) { "Invalid company source configuration" } }
        }.also { require(it.distinctBy(CompanyCareerSource::id).size == it.size) }
    }
}
