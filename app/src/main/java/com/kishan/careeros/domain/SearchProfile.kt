package com.kishan.careeros.domain

import java.util.Locale

data class RoleFamily(val name: String, val markers: List<String>, val aliases: List<String>, val technologies: Set<String>)
object CareerVocabulary {
    val families = listOf(
        RoleFamily("Android", listOf("android"), listOf("Android Developer", "Android Engineer", "Android Software Engineer", "Mobile Engineer Android", "Kotlin Android Developer", "Jetpack Compose Developer"), setOf("android", "kotlin", "jetpack compose", "android sdk", "room", "hilt", "mvvm", "mvi")),
        RoleFamily("iOS", listOf("ios", "iphone"), listOf("iOS Developer", "iOS Engineer", "Swift Developer"), setOf("ios", "swift", "swiftui", "objective-c")),
        RoleFamily("Frontend", listOf("frontend", "front end", "front-end"), listOf("Frontend Developer", "Frontend Engineer", "Front End Developer"), setOf("javascript", "typescript", "react", "vue", "angular", "css", "html")),
        RoleFamily("Backend", listOf("backend", "back end", "back-end"), listOf("Backend Developer", "Backend Engineer", "Server Side Engineer"), setOf("java", "kotlin", "python", "go", "node.js", "spring", "sql", "postgresql")),
        RoleFamily("Full stack", listOf("fullstack", "full stack", "full-stack"), listOf("Full Stack Developer", "Fullstack Engineer", "Full-Stack Engineer"), setOf("javascript", "typescript", "react", "node.js", "sql")),
        RoleFamily("Data", listOf("data engineer"), listOf("Data Engineer", "Data Platform Engineer", "Analytics Engineer"), setOf("python", "sql", "spark", "airflow", "dbt")),
        RoleFamily("Machine learning", listOf("machine learning", "ml engineer", "data scientist"), listOf("Machine Learning Engineer", "ML Engineer", "Data Scientist"), setOf("python", "pytorch", "tensorflow", "scikit-learn")),
        RoleFamily("Infrastructure", listOf("devops", "site reliability", "sre", "cloud engineer"), listOf("DevOps Engineer", "Site Reliability Engineer", "Cloud Engineer"), setOf("kubernetes", "docker", "terraform", "aws", "azure", "linux")),
        RoleFamily("QA", listOf("qa", "quality assurance", "test engineer", "sdet"), listOf("QA Engineer", "Test Engineer", "SDET"), setOf("selenium", "playwright", "appium", "junit")),
        RoleFamily("Design", listOf("ux", "ui designer", "product designer"), listOf("UX Designer", "Product Designer", "UI Designer"), setOf("figma", "sketch", "user research")),
        RoleFamily("Product", listOf("product manager"), listOf("Product Manager", "Product Owner"), setOf("product management", "analytics", "agile")),
        RoleFamily("Accounting", listOf("accountant", "accounting"), listOf("Accountant", "Accounting Associate"), setOf("accounting", "excel", "tally")),
        RoleFamily("Marketing", listOf("digital marketing", "seo"), listOf("Digital Marketing Specialist", "SEO Specialist"), setOf("seo", "google analytics", "content marketing")),
    )
    val aliases = mapOf("compose" to "jetpack compose", "android development" to "android", "android sdk" to "android", "reactjs" to "react", "react.js" to "react", "nodejs" to "node.js", "postgres" to "postgresql", "amazon web services" to "aws", "rest api" to "rest", "git" to "git")
    fun normalize(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun skill(value: String) = aliases[normalize(value)] ?: normalize(value)
    fun contains(text: String, term: String): Boolean = Regex("(?<![a-z0-9])${Regex.escape(normalize(term))}(?![a-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(normalize(text))
    fun families(roles: List<String>) = families.filter { family -> roles.any { role -> family.markers.any { contains(role, it) } } }
}
data class CareerSearchProfile(
    val targetRoles: List<String>, val roleAliases: List<String>, val primarySkills: Set<String>,
    val secondarySkills: Set<String>, val ecosystems: Set<String>, val locations: List<String>,
    val remote: RemotePreference, val employmentTypes: Set<EmploymentType>, val experienceYears: Double?,
    val minimumSalary: Salary?, val eligibilityFacts: Map<String, String> = emptyMap(),
)
data class SearchIntent(val role: String, val skills: Set<String>, val locations: List<String>, val employmentTypes: Set<EmploymentType>)
object SearchIntentBuilder {
    fun profile(profile: CareerProfile, answers: List<SavedApplicationAnswer> = emptyList()): CareerSearchProfile {
        val families = CareerVocabulary.families(profile.preferences.targetRoles)
        val skills = profile.skills.map { CareerVocabulary.skill(it.name) }.toSet()
        val aligned = skills.intersect(families.flatMap { it.technologies }.map(CareerVocabulary::skill).toSet())
        val primary = aligned.ifEmpty { skills }
        return CareerSearchProfile(profile.preferences.targetRoles, families.flatMap { it.aliases }.distinct(), primary, skills - primary,
            families.filter { family -> family.technologies.any { CareerVocabulary.skill(it) in skills } }.map { it.name }.toSet(),
            profile.preferences.locations, profile.preferences.remote, profile.preferences.employmentTypes, profile.experienceYears, profile.preferences.minimumSalary,
            answers.filter { it.eligibleForAutofill() }.associate { it.normalizedQuestionKey to it.answer })
    }
    fun build(profile: CareerSearchProfile): List<SearchIntent> {
        val roles = (profile.targetRoles + profile.roleAliases).toMutableList()
        if (profile.experienceYears != null && profile.experienceYears <= 2) profile.targetRoles.forEach { role ->
            if (!Regex("(?i)senior|lead|staff|principal|junior|associate|intern").containsMatchIn(role)) roles += listOf("Junior $role", "Associate $role")
        }
        if (EmploymentType.INTERNSHIP in profile.employmentTypes) roles += profile.targetRoles.map { "$it Intern" }
        return roles.distinctBy(CareerVocabulary::normalize).take(40).map { SearchIntent(it, profile.primarySkills, profile.locations, profile.employmentTypes) }
    }
}
