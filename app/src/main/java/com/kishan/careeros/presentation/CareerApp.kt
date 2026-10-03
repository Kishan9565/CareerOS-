package com.kishan.careeros.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.kishan.careeros.domain.*

@Composable fun CareerApp(dashboard: DashboardViewModel, profile: ProfileViewModel, discovery: DiscoveryViewModel, intelligence: IntelligenceViewModel) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFA7EBD4), onPrimary = Color(0xFF00382C), secondary = Color(0xFFB6C8EA), background = Color(0xFF0C121A), surface = Color(0xFF111C27), surfaceVariant = Color(0xFF20303C))) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val destination = entry?.destination?.route ?: "Home"
        val state by dashboard.state.collectAsStateWithLifecycle()
        val discoveryState by discovery.state.collectAsStateWithLifecycle()
        val intelligenceState by intelligence.state.collectAsStateWithLifecycle()
        LaunchedEffect(discoveryState.importReview != null) {
            if (discoveryState.importReview != null) nav.navigate("Import") { launchSingleTop = true }
        }
        LaunchedEffect(discoveryState.importedId) {
            discoveryState.importedId?.let { id -> nav.navigate("opportunity/$id") { popUpTo("Home"); launchSingleTop = true } }
        }
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Home" to Icons.Default.Home, "Opportunities" to Icons.Default.Search, "Applications" to Icons.Default.List, "Profile" to Icons.Default.Person).forEach { (name, icon) ->
                    NavigationBarItem(selected = destination == name || (destination.startsWith("opportunity/") && name == "Opportunities"), onClick = { nav.navigate(name) { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Icon(icon, contentDescription = null) }, label = { Text(name, maxLines = 1) })
                }
            }
        }) { padding ->
            NavHost(nav, "Home", Modifier.padding(padding)) {
                composable("Home") { Page("CareerOS", "Your next chapter, built on your experience.") {
                    Status(state, dashboard::load) {
                        Panel(if (state.profile == null) "Start with your story" else "Welcome, ${state.profile!!.name}", if (state.profile == null) "Build your career profile to prepare for relevant opportunities." else state.profile!!.headline) { Button(onClick = { nav.navigate("Profile") }) { Text(if (state.profile == null) "Set up profile" else "Edit profile") } }
                        Panel(if (state.opportunities.isEmpty()) "No opportunities yet" else "${state.opportunities.size} opportunities", "Discover across configured company boards and Remotive, or import a real listing.") { TextButton(onClick = { nav.navigate("Opportunities") }) { Text("Explore opportunities") } }
                        Panel(if (state.applications.isEmpty()) "Your application journey starts here" else "${state.applications.size} applications", if (state.applications.isEmpty()) "Applications you choose to track will appear here." else "Review your saved application activity.")
                        if (discoveryState.profile != null && !discoveryState.loading && !discoveryState.dataError) {
                            val active = discoveryState.jobs.filter { it.lifecycle != OpportunityLifecycle.CLOSED }
                            val relevant = active.count { discoveryState.relevance[it.id]?.band in setOf(RelevanceBand.STRONG_MATCH, RelevanceBand.GOOD_MATCH) }
                            val strong = active.count { discoveryState.relevance[it.id]?.band == RelevanceBand.STRONG_MATCH }
                            val preparing = discoveryState.tracked.count { it.application.status in setOf(ApplicationStatus.SAVED, ApplicationStatus.PREPARING) }
                            Panel("Your local career feed", "${active.count { it.status == OpportunityStatus.NEW }} new / $relevant relevant / $strong strong matches / $preparing saved or preparing")
                        }
                        Panel("Private by foundation", "Your career data stays on this device. Resume and career intelligence run locally with separately imported models. Profile editing works offline.")
                    }
                } }
                composable("Opportunities") { OpportunityList(discoveryState, discovery::discover, discovery::enabled, discovery::observe, { discovery.receiveShare("", null) }) { nav.navigate("opportunity/$it") } }
                composable("Applications") { TrackedApplications(discoveryState, discovery::observe) { nav.navigate("opportunity/$it") } }
                composable("opportunity/{id}") { route ->
                    OpportunityDetail(requireNotNull(route.arguments?.getString("id")), discoveryState, discovery::detail, discovery::save,
                        { nav.popBackStack() }, { nav.navigate("Applications") { launchSingleTop = true } },
                        intelligenceState, intelligence::cached, { discoveryState.detail?.let(intelligence::analyze) }, { nav.navigate("Intelligence") })
                }
                composable("Import") { ImportReviewScreen(discoveryState, discovery::editImport, discovery::saveImport) { discovery.cancelImport(); nav.popBackStack() } }
                composable("Intelligence") { CareerIntelligenceScreen(intelligenceState, intelligence) { nav.popBackStack() } }
                composable("Profile") { val s by profile.state.collectAsStateWithLifecycle(); ProfileScreen(s, profile::accept) { nav.navigate("Intelligence") } }
            }
        }
    }
}
@Composable internal fun Page(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("CAREER / LOCAL FIRST", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
@Composable internal fun Panel(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    } }
}
@Composable private fun Status(state: DashboardState, retry: () -> Unit, content: @Composable () -> Unit) {
    when { state.loading -> CircularProgressIndicator(); state.error -> Panel("Could not load local data", "Please try again.") { Button(onClick = retry) { Text("Retry") } }; else -> content() }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ProfileScreen(state: ProfileState, accept: (ProfileIntent) -> Unit, intelligence: () -> Unit) {
    val f = state.form
    fun edit(next: ProfileForm) = accept(ProfileIntent.Edit(next))
    Page("Career profile", "Your facts. Your preferences. Saved on this device.") {
        if (state.loading) {
            if (state.error != null) { Text(state.error); Button(onClick = { accept(ProfileIntent.Retry) }) { Text("Retry") } } else CircularProgressIndicator()
        } else {
            OutlinedButton(onClick = intelligence) { Text("Resume / Career Intelligence") }
            Text("About you", style = MaterialTheme.typography.titleLarge)
            Field("Name *", f.name, state.saving) { edit(f.copy(name = it)) }
            Field("Headline *", f.headline, state.saving) { edit(f.copy(headline = it)) }
            Field("Target roles * (comma separated)", f.roles, state.saving) { edit(f.copy(roles = it)) }
            Field("Skills * (comma separated)", f.skills, state.saving) { edit(f.copy(skills = it)) }
            Text("Work preferences", style = MaterialTheme.typography.titleLarge)
            Field("Preferred locations (comma separated)", f.locations, state.saving) { edit(f.copy(locations = it)) }
            Text("Remote preference")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RemotePreference.entries.forEach { value -> FilterChip(selected = f.remote == value, enabled = !state.saving, onClick = { edit(f.copy(remote = value)) }, label = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) }) } }
            Text("Employment types · select any")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { EmploymentType.entries.forEach { value -> FilterChip(selected = value in f.employment, enabled = !state.saving, onClick = { edit(f.copy(employment = if (value in f.employment) f.employment - value else f.employment + value)) }, label = { Text(value.name.lowercase().replace('_', ' ')) }) } }
            Field("Minimum annual salary (optional)", f.salary, state.saving, KeyboardType.Number) { edit(f.copy(salary = it)) }
            Field("Salary currency (e.g. INR, USD)", f.currency, state.saving) { edit(f.copy(currency = it)) }
            Field("Notice period in days (optional)", f.notice, state.saving, KeyboardType.Number) { edit(f.copy(notice = it)) }
            Text("Links", style = MaterialTheme.typography.titleLarge)
            Field("GitHub URL (optional)", f.github, state.saving, KeyboardType.Uri) { edit(f.copy(github = it)) }
            Field("LinkedIn URL (optional)", f.linkedin, state.saving, KeyboardType.Uri) { edit(f.copy(linkedin = it)) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.saved) Text("Profile saved on this device", color = MaterialTheme.colorScheme.primary)
            if (state.dirty) Text("Unsaved changes", style = MaterialTheme.typography.labelMedium)
            Button(onClick = { accept(ProfileIntent.Save) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (state.saving) "Saving…" else "Save profile") }
        }
    }
}
@Composable private fun Field(label: String, value: String, saving: Boolean, keyboard: KeyboardType = KeyboardType.Text, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, enabled = !saving, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = keyboard), singleLine = true)
}
