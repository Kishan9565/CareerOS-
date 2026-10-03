package com.kishan.careeros.domain

enum class RelevanceBand { STRONG_MATCH, GOOD_MATCH, POSSIBLE_MATCH, LOW_RELEVANCE }
data class ProfileRelevance(val band: RelevanceBand, val score: Int, val matched: List<String>, val notDetected: List<String>, val preferences: List<String>, val cautions: List<String>)
object ProfileRelevanceEngine {
    fun evaluate(profile: CareerSearchProfile, job: Opportunity): ProfileRelevance {
        val matched = mutableListOf<String>(); val preferences = mutableListOf<String>(); val cautions = mutableListOf<String>()
        val text = "${job.title}\n${job.description}\n${job.skills.joinToString(" ")}\n${job.requirements.joinToString(" ") { it.text }}"
        val skills = profile.primarySkills + profile.secondarySkills
        fun mentioned(skill: String): Boolean {
            if (skill == "room" && !CareerVocabulary.contains(text, "android")) return false
            return CareerVocabulary.contains(text, skill) || CareerVocabulary.aliases.any { (alias, canonical) -> canonical == skill && CareerVocabulary.contains(text, alias) }
        }
        val overlaps = skills.filter(::mentioned)
        matched += overlaps.map { "$it: in your profile and mentioned in this listing" }
        val known = (CareerVocabulary.families.flatMap { it.technologies } + listOf("aws", "azure", "git", "rest", "firebase")).map(CareerVocabulary::skill).toSet()
        val notDetected = (job.skills.map(CareerVocabulary::skill) + known.filter(::mentioned)).distinct().filter { it !in skills }
        val families = CareerVocabulary.families(profile.targetRoles)
        val directRole = (profile.targetRoles + profile.roleAliases).any { CareerVocabulary.contains(job.title, it) }
        val familyTitle = families.any { it.markers.any { marker -> CareerVocabulary.contains(job.title, marker) } }
        val contextual = families.any { family -> family.markers.any { CareerVocabulary.contains(text, it) } } && overlaps.size >= 2
        var score = when { directRole || familyTitle -> 45; contextual -> 25; else -> 0 }
        if (directRole || familyTitle) matched += "Role: '${job.title}' aligns with ${profile.targetRoles.joinToString()}"
        else if (contextual) matched += "Role context and profile skills overlap; title alignment is uncertain"
        score += (overlaps.size * 8).coerceAtMost(30)
        val ecosystem = profile.ecosystems.filter { name -> CareerVocabulary.families.find { it.name == name }?.technologies?.count { CareerVocabulary.skill(it) in skills && mentioned(CareerVocabulary.skill(it)) }?.let { it >= 2 } == true }
        if (ecosystem.isNotEmpty()) { score += 10; matched += "Technology overlap: ${ecosystem.joinToString()} (profile skills mentioned in listing)" }
        if (job.employmentType != null && profile.employmentTypes.isNotEmpty()) {
            if (job.employmentType in profile.employmentTypes) { score += 5; preferences += "Employment type matches: ${job.employmentType}" }
            else { score -= 15; cautions += "Employment type ${job.employmentType} differs from your preferences" }
        }
        val workplace = if (job.workplace == Workplace.UNKNOWN && job.remote == true) Workplace.REMOTE else job.workplace
        if (profile.remote !in setOf(RemotePreference.UNSPECIFIED, RemotePreference.FLEXIBLE) && workplace != Workplace.UNKNOWN) {
            if (profile.remote.name == workplace.name) { score += 5; preferences += "Workplace preference matches: $workplace" }
            else { score -= 15; cautions += "Workplace $workplace differs from your ${profile.remote} preference" }
        }
        if (profile.locations.isNotEmpty() && job.location != null) {
            if (profile.locations.any { CareerVocabulary.contains(job.location, it) }) { score += 5; preferences += "Listing location matches: ${job.location}" }
            else { cautions += "Listing location '${job.location}' does not match your saved locations; remote eligibility is not assumed"; score -= 10 }
        }
        val years = Regex("(?i)(?:at least |minimum(?: of)? )?(\\d{1,2})(?:\\s*[-–]\\s*\\d{1,2})?\\+?\\s+years?\\s+(?:of\\s+)?(?:relevant |professional |software development |industry )?experience").findAll(job.description).toList()
        val minimum = years.maxByOrNull { it.groupValues[1].toInt() }
        if (minimum != null) {
            val required = minimum.groupValues[1].toDouble()
            if (profile.experienceYears == null) cautions += "Experience unknown in profile; listing says '${minimum.value}'"
            else if (profile.experienceYears < required) { score -= 25; cautions += "Profile ${profile.experienceYears} years vs listing '${minimum.value}'" }
            else preferences += "Profile experience meets listing '${minimum.value}'"
        }
        val desired = profile.minimumSalary; val offered = job.salary
        if (desired != null && offered != null && desired.minimum != null && offered.maximum != null && desired.currency == offered.currency && desired.period == offered.period) {
            if (offered.maximum < desired.minimum) { score -= 20; cautions += "Listed maximum ${offered.maximum} ${offered.currency}/${offered.period} is below your minimum ${desired.minimum}" }
            else preferences += "Listed salary range can meet your configured minimum"
        }
        val sponsorship = profile.eligibilityFacts["requires_visa_sponsorship"]?.uppercase()
        val noSponsorship = Regex("(?i)(?:no visa sponsorship|cannot (?:provide|offer|sponsor) (?:visa|sponsorship)|do not (?:provide|offer) visa sponsorship)").find(job.description)
        if (sponsorship == "YES" && noSponsorship != null) { score -= 50; cautions += "Verified sponsorship preference conflicts with '${noSponsorship.value}'" }
        if (job.lifecycle == OpportunityLifecycle.CLOSED) { score = 0; cautions += "No longer listed on its configured source" }
        if (!directRole && !familyTitle && !contextual) score = score.coerceAtMost(34)
        val finalScore = score.coerceIn(0, 100)
        return ProfileRelevance(when { finalScore >= 75 -> RelevanceBand.STRONG_MATCH; finalScore >= 55 -> RelevanceBand.GOOD_MATCH; finalScore >= 25 -> RelevanceBand.POSSIBLE_MATCH; else -> RelevanceBand.LOW_RELEVANCE }, finalScore, matched, notDetected, preferences, cautions)
    }
}
