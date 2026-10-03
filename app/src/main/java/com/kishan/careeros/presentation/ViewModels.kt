package com.kishan.careeros.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kishan.careeros.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardState(val loading: Boolean = true, val profile: CareerProfile? = null, val opportunities: List<Opportunity> = emptyList(), val applications: List<Application> = emptyList(), val error: Boolean = false)
@HiltViewModel class DashboardViewModel @Inject constructor(private val profiles: ProfileRepository, private val opportunities: OpportunityRepository, private val applications: ApplicationRepository): ViewModel() {
    private val mutable = MutableStateFlow(DashboardState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    init { load() }
    fun load() { job?.cancel(); job = viewModelScope.launch {
        mutable.value = DashboardState()
        combine(profiles.observe(), opportunities.observeAll(), applications.observeAll()) { p, o, a -> DashboardState(false, p, o, a) }
            .catch { emit(DashboardState(loading = false, error = true)) }.collect { mutable.value = it }
    } }
}

data class ProfileForm(val name: String = "", val headline: String = "", val roles: String = "", val skills: String = "", val locations: String = "", val remote: RemotePreference = RemotePreference.UNSPECIFIED, val employment: Set<EmploymentType> = emptySet(), val salary: String = "", val currency: String = "INR", val notice: String = "", val github: String = "", val linkedin: String = "")
data class ProfileState(val loading: Boolean = true, val form: ProfileForm = ProfileForm(), val saving: Boolean = false, val dirty: Boolean = false, val error: String? = null, val saved: Boolean = false)
sealed interface ProfileIntent { data class Edit(val form: ProfileForm): ProfileIntent; data object Save: ProfileIntent; data object Retry: ProfileIntent }
@HiltViewModel class ProfileViewModel @Inject constructor(private val repository: ProfileRepository): ViewModel() {
    private val mutable = MutableStateFlow(ProfileState())
    val state = mutable.asStateFlow()
    private var original: CareerProfile? = null
    init { load() }
    private fun load() { viewModelScope.launch {
        mutable.value = ProfileState()
        try {
            original = repository.observe().first()
            val p = original
            mutable.value = ProfileState(loading = false, form = p?.let { ProfileForm(it.name, it.headline, it.preferences.targetRoles.joinToString(", "), it.skills.joinToString(", ") { s -> s.name }, it.preferences.locations.joinToString(", "), it.preferences.remote, it.preferences.employmentTypes, it.preferences.minimumSalary?.minimum?.toString().orEmpty(), it.preferences.minimumSalary?.currency ?: "INR", it.noticePeriodDays?.toString().orEmpty(), it.githubUrl.orEmpty(), it.linkedinUrl.orEmpty()) } ?: ProfileForm())
        } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.value = ProfileState(error = "Could not load your profile. Retry to protect existing data.") }
    } }
    fun accept(intent: ProfileIntent) { when (intent) {
        is ProfileIntent.Edit -> if (!state.value.saving) mutable.update { it.copy(form = intent.form, dirty = true, error = null, saved = false) }
        ProfileIntent.Retry -> load()
        ProfileIntent.Save -> save()
    } }
    private fun save() {
        val current = state.value
        if (current.loading || current.saving) return
        val f = current.form
        fun parts(s: String) = s.split(',').map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase() }
        val salary = f.salary.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
        val notice = f.notice.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        val numericError = when {
            f.salary.isNotBlank() && (salary == null || salary < 0) -> "Enter a valid non-negative annual salary"
            f.notice.isNotBlank() && (notice == null || notice < 0) -> "Enter a valid non-negative notice period"
            salary != null && !f.currency.trim().uppercase().matches(Regex("[A-Z]{3}")) -> "Use a three-letter currency code"
            else -> null
        }
        if (numericError != null) { mutable.update { it.copy(error = numericError) }; return }
        val p = (original ?: CareerProfile(name = "", headline = "")).copy(name = f.name.trim(), headline = f.headline.trim(), preferences = CareerPreferences(parts(f.roles), parts(f.locations), f.remote, f.employment, salary?.let { Salary(it, currency = f.currency.trim().uppercase()) }), skills = parts(f.skills).map { name -> original?.skills?.find { it.name.equals(name, true) } ?: Skill(name = name) }, noticePeriodDays = notice, githubUrl = f.github.trim().ifEmpty { null }, linkedinUrl = f.linkedin.trim().ifEmpty { null }, updatedAt = System.currentTimeMillis())
        val errors = Validation.profile(p)
        if (errors.isNotEmpty()) { mutable.update { it.copy(error = errors.joinToString("\n")) }; return }
        mutable.update { it.copy(saving = true, error = null, saved = false) }
        viewModelScope.launch {
            try { repository.save(p); original = p; mutable.update { it.copy(saving = false, dirty = false, saved = true) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(saving = false, error = "Could not save. Your edits are still here; please retry.") } }
        }
    }
}
