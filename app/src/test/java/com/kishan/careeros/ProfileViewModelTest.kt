package com.kishan.careeros

import com.kishan.careeros.domain.*
import com.kishan.careeros.presentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class MemoryProfile: ProfileRepository {
        val value = MutableStateFlow<CareerProfile?>(null)
        var failSave = false
        override fun observe() = value
        override suspend fun save(profile: CareerProfile) {
            if (failSave) throw java.io.IOException("test storage failure")
            value.value = profile
        }
    }
    @Test fun invalidNumbersAreNotSilentlyTreatedAsUnknown() = runTest(dispatcher) {
        val repo = MemoryProfile(); val vm = ProfileViewModel(repo); runCurrent()
        vm.accept(ProfileIntent.Edit(ProfileForm(name = "User", headline = "Engineer", roles = "Android", skills = "Kotlin", notice = "bad")))
        vm.accept(ProfileIntent.Save); runCurrent()
        assertNotNull(vm.state.value.error); assertNull(repo.value.value)
    }
    @Test fun saveFailureKeepsEditsAndSuccessfulRetryRetainsSkillIdentity() = runTest(dispatcher) {
        val repo = MemoryProfile(); val vm = ProfileViewModel(repo); runCurrent()
        val form = ProfileForm(name = "User", headline = "Engineer", roles = "Android", skills = "Kotlin, Kotlin", salary = "1000")
        vm.accept(ProfileIntent.Edit(form)); repo.failSave = true
        vm.accept(ProfileIntent.Save); runCurrent()
        assertEquals(form, vm.state.value.form); assertNotNull(vm.state.value.error); assertFalse(vm.state.value.saving)
        repo.failSave = false; vm.accept(ProfileIntent.Save); runCurrent()
        assertTrue(vm.state.value.saved); val skillId = repo.value.value!!.skills.single().id
        vm.accept(ProfileIntent.Edit(form.copy(headline = "Android engineer"))); vm.accept(ProfileIntent.Save); runCurrent()
        assertEquals(skillId, repo.value.value!!.skills.single().id)
    }
}
