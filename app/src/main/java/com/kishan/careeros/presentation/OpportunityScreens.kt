package com.kishan.careeros.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kishan.careeros.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun date(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
private fun time(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM, HH:mm"))
private fun label(band: RelevanceBand) = band.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
private fun openExternal(context: Context, url: String): String? {
    if (!Validation.httpUrl(url)) return "Only valid HTTP/HTTPS destinations can be opened."
    return try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); null } catch (_: Exception) { "No browser could open this destination." }
}

@Composable fun OpportunityList(state: DiscoveryState, discover: () -> Unit, enable: (String, Boolean) -> Unit, retry: () -> Unit, startImport: () -> Unit, open: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var possible by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var linkError by remember { mutableStateOf<String?>(null) }
    val ranked = remember(state.jobs, state.relevance, tab, possible) {
        state.jobs.filter { job -> tab == 1 || job.lifecycle != OpportunityLifecycle.CLOSED && state.relevance[job.id]?.band in if (possible) setOf(RelevanceBand.STRONG_MATCH, RelevanceBand.GOOD_MATCH, RelevanceBand.POSSIBLE_MATCH) else setOf(RelevanceBand.STRONG_MATCH, RelevanceBand.GOOD_MATCH) }
            .sortedWith(compareByDescending<Opportunity> { if (tab == 0) state.relevance[it.id]?.score ?: 0 else 0 }.thenByDescending { it.discoveredAt })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Opportunities", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item { Text("Your profile. Real sources. One career feed.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { TabRow(selectedTabIndex = tab) { listOf("For You", "All", "Sources").forEachIndexed { index, name -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(name) }) } } }
        item {
            Button(onClick = discover, enabled = !state.discovering && !state.loading && !state.dataError && state.profile != null, modifier = Modifier.fillMaxWidth()) { Text(if (state.discovering) "Discovering across sources..." else "Discover opportunities") }
            if (state.discovering) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.profile == null && !state.loading) Text("Set up your Profile to derive search intents and rank opportunities.")
        }
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (state.loading) item { CircularProgressIndicator() }
        if (state.dataError) item { Text("Could not read local discovery data."); TextButton(onClick = retry) { Text("Retry local data") } }
        if (tab == 2) {
            item {
                Text("Search direction", style = MaterialTheme.typography.titleLarge)
                Text(state.intents.take(8).joinToString(" / ") { it.role }.ifEmpty { "Add target roles to your Profile." })
                Text("Automatic boards are fetched in full; profile intent matching happens locally. No claim is made that the whole web was searched.", style = MaterialTheme.typography.bodySmall)
            }
            item { Text("Automatic sources", style = MaterialTheme.typography.titleLarge) }
            items(state.sources.filter { it.capabilities.discoveryMode == DiscoveryMode.AUTOMATIC }, key = { "source:${it.capabilities.sourceId}" }) { source ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(source.capabilities.displayName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(source.enabled, { enable(source.capabilities.sourceId, it) }, enabled = !state.discovering)
                    }
                    Text("${source.capabilities.sourceType} / AUTOMATIC", style = MaterialTheme.typography.labelSmall)
                    Text(when { !source.enabled -> "Disabled"; source.sync?.lastError != null -> "Last attempt failed (${source.sync.failureCount} consecutive failures)"; source.sync?.lastSuccessAt != null -> "Last successful sync ${time(source.sync.lastSuccessAt)} / ${source.sync.receivedCount} source records"; else -> "Configured / never synced on this device" })
                    source.sync?.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    source.sync?.takeIf { it.nextAllowedAt > System.currentTimeMillis() }?.let { Text("Next allowed refresh ${time(it.nextAllowedAt)}", style = MaterialTheme.typography.bodySmall) }
                    Text(source.capabilities.attributionRequirement, style = MaterialTheme.typography.bodySmall)
                    Text("Refresh interval ${source.capabilities.refreshPolicy.intervalMillis / 3_600_000}h; max ${source.capabilities.refreshPolicy.maxAttemptsPerDay} refresh attempts/day", style = MaterialTheme.typography.bodySmall)
                    source.company?.let { company ->
                        Text("Published company board / endpoint checked ${company.lastVerifiedAt?.let(::date) ?: "not verified"}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { linkError = openExternal(context, company.careersUrl) }) { Text("Browse company careers") }
                    }
                } }
            }
            item { Text("Search more sources", style = MaterialTheme.typography.titleLarge); Text("USER SEARCH / SHARE. CareerOS does not automatically search these platforms. Open normally, then Share a listing to CareerOS.") }
            items(UserSearchDestinations.actions(state.profile), key = { it.id }) { action ->
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(action.displayName, modifier = Modifier.weight(1f))
                    TextButton(onClick = { linkError = openExternal(context, action.url) }) { Text("Open Search") }
                } }
            }
            items(state.sources.filter { it.capabilities.discoveryMode == DiscoveryMode.USER_SEARCH }, key = { it.capabilities.sourceId }) { source ->
                source.company?.let { company -> Column { Text(company.companyName); TextButton(onClick = { linkError = openExternal(context, company.careersUrl) }) { Text("Browse") } } }
            }
            item {
                Text("Suggested terms: ${state.intents.firstOrNull()?.role.orEmpty()}")
                TextButton(onClick = { clipboard.setText(AnnotatedString(state.intents.take(8).joinToString("\n") { it.role })) }, enabled = state.intents.isNotEmpty()) { Text("Copy profile search terms") }
                OutlinedButton(onClick = startImport) { Text("Import a shared URL or description") }
                Text("USER_IMPORT: user-reviewed information only; no restricted-page fetching.", style = MaterialTheme.typography.bodySmall)
            }
            linkError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        } else {
            if (tab == 0) item { FilterChip(selected = possible, onClick = { possible = !possible }, label = { Text("Include possible matches") }); Text("Profile relevance, not a hiring probability. Low-relevance and closed jobs remain in All.", style = MaterialTheme.typography.bodySmall) }
            if (!state.loading && !state.dataError && ranked.isEmpty()) item { Panel(if (state.jobs.isEmpty()) "No opportunities yet" else "No matches in this view yet", if (state.jobs.isEmpty()) "Discover from configured sources or import a listing using Share to CareerOS." else "Include possible matches, review All, or refine your saved Profile. No valid jobs are discarded by matching.") }
            items(ranked, key = { it.id }) { job -> OpportunityCard(job, { open(job.id) }, match = state.relevance[job.id]) }
        }
    }
}

@Composable private fun OpportunityCard(job: Opportunity, click: () -> Unit, status: String? = null, match: ProfileRelevance? = null) {
    Card(onClick = click, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(job.company, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(job.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val workplace = if (job.workplace != Workplace.UNKNOWN) job.workplace.name else if (job.remote == true) "REMOTE" else null
            Text(listOfNotNull(workplace, job.location, job.employmentType?.name?.replace('_', ' ')).joinToString(" / ").ifEmpty { "Work arrangement not specified" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(listOfNotNull(job.sourceName, job.publishedAt?.let(::date)).joinToString(" / "), style = MaterialTheme.typography.labelMedium)
            Text(when (job.lifecycle) { OpportunityLifecycle.ACTIVE -> "Listed open at last source sync"; OpportunityLifecycle.UNKNOWN -> "Open status unknown"; OpportunityLifecycle.CLOSED -> "No longer listed on source" }, style = MaterialTheme.typography.labelSmall)
            Text(match?.let { "Profile Match: ${label(it.band)}" } ?: "Profile Match: not evaluated", color = MaterialTheme.colorScheme.primary)
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable fun TrackedApplications(state: DiscoveryState, retry: () -> Unit, open: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Applications", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item { Text("Your real opportunities, from saved to submitted.") }
        if (state.loading) item { CircularProgressIndicator() }
        if (state.dataError) item { Text("Could not read applications."); TextButton(onClick = retry) { Text("Retry") } }
        if (!state.loading && !state.dataError && state.tracked.isEmpty()) item { Panel("No applications yet", "Discover or import an opportunity, then Save or Prepare Application. You always control final submission.") }
        items(state.tracked, key = { it.application.id }) { item -> OpportunityCard(item.opportunity, { open(item.opportunity.id) }, item.application.status.name.replace('_', ' '), state.relevance[item.opportunity.id]) }
    }
}

@Composable fun OpportunityDetail(id: String, state: DiscoveryState, load: (String) -> Unit, save: (Boolean) -> Unit, back: () -> Unit, applications: () -> Unit, intelligenceState: IntelligenceState, cached: (Opportunity) -> Unit, analyze: () -> Unit, setup: () -> Unit) {
    LaunchedEffect(id) { load(id) }
    LaunchedEffect(state.detail?.id, state.detail?.contentHash) { state.detail?.let(cached) }
    val context = LocalContext.current
    var linkError by remember(id) { mutableStateOf<String?>(null) }
    Page("Opportunity", "Review source evidence and your profile alignment.") {
        TextButton(onClick = back) { Text("Back") }
        when {
            state.detailLoading || state.detail == null && !state.detailError -> CircularProgressIndicator()
            state.detailError -> { Text("Could not load this opportunity."); TextButton(onClick = { load(id) }) { Text("Retry") } }
            else -> state.detail?.let { job ->
                Text(job.company, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Text(job.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(job.workplace.takeIf { it != Workplace.UNKNOWN }?.name, job.location, job.employmentType?.name?.replace('_', ' ')).joinToString(" / ").ifEmpty { "Work arrangement not specified" })
                Text("Source: ${job.sourceName}")
                Text("Source lifecycle: ${job.lifecycle}. Always confirm availability on the original site.")
                job.publishedAt?.let { Text("Published ${date(it)}") }
                job.lastSeenAt?.let { Text("Last seen ${time(it)}") }
                job.deadline?.let { Text("Deadline ${date(it)}") }
                job.salary?.let { Text("Listed salary: ${it.minimum ?: "?"} - ${it.maximum ?: "?"} ${it.currency} / ${it.period}") }
                val match = state.profile?.let { ProfileRelevanceEngine.evaluate(it, job) }
                match?.let { relevance ->
                    Panel("Profile Match: ${label(relevance.band)}", "Deterministic profile relevance, not hiring probability.")
                    if (relevance.matched.isNotEmpty()) Panel("Matched evidence", relevance.matched.joinToString("\n"))
                    if (relevance.notDetected.isNotEmpty()) Panel("Not detected in profile", relevance.notDetected.joinToString(", ") + ". These are listing mentions, not automatically hard requirements or proof of no experience.")
                    if (relevance.preferences.isNotEmpty()) Panel("Preferences", relevance.preferences.joinToString("\n"))
                    if (relevance.cautions.isNotEmpty()) Panel("Review these details", relevance.cautions.joinToString("\n"))
                }
                EvidenceAnalysisPanel(job.id, intelligenceState, analyze, setup)
                val tracked = state.tracked.find { it.opportunity.id == job.id }
                tracked?.let { Text("Application: ${it.application.status.name.replace('_', ' ')}", color = MaterialTheme.colorScheme.primary) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { save(false) }, enabled = !state.saving && tracked == null) { Text(if (tracked != null) "Saved" else "Save") }
                    Button(onClick = { save(true) }, enabled = !state.saving) { Text("Prepare Application") }
                }
                Text("Preparation updates your tracker only. Local AI drafts are not available yet.", style = MaterialTheme.typography.bodySmall)
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (tracked != null) TextButton(onClick = applications) { Text("View Applications") }
                Button(onClick = { linkError = openExternal(context, job.canonicalUrl) }, modifier = Modifier.fillMaxWidth()) { Text("Open Application") }
                Text(job.canonicalUrl, style = MaterialTheme.typography.bodySmall)
                Text("You review and submit on the external site. Opening a link never marks a job applied.", style = MaterialTheme.typography.bodySmall)
                if (job.skills.isNotEmpty()) Panel("Source tags", job.skills.joinToString(", "))
                if (job.requirements.isNotEmpty()) Panel("Source requirements", job.requirements.joinToString("\n") { it.text })
                Panel("About the role", job.description.ifBlank { "No description was shared. Review the original listing." })
                Text("Provenance", style = MaterialTheme.typography.titleLarge)
                job.provenance.forEach { p ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.sourceName); Text(if (p.official) "Company published ATS listing" else "Source/import record; not independently verified")
                        Text("${p.lifecycle} / last seen ${p.lastSeenAt?.let(::time) ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                        Text(p.originalUrl, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { linkError = openExternal(context, p.originalUrl) }) { Text("Open source record") }
                    } }
                }
                linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable fun ImportReviewScreen(state: DiscoveryState, edit: (ImportReview) -> Unit, save: () -> Unit, cancel: () -> Unit) {
    val review = state.importReview ?: return
    Page("Import review", "Confirm the real details. CareerOS does not fetch restricted pages or invent missing information.") {
        fun change(next: ImportReview) = edit(next.copy(confirmed = false))
        OutlinedTextField(review.url, { change(review.copy(url = it)) }, label = { Text("Listing URL") }, enabled = !state.saving, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(review.title, { change(review.copy(title = it.take(500))) }, label = { Text("Job title *") }, enabled = !state.saving, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(review.company, { change(review.copy(company = it.take(500))) }, label = { Text("Company *") }, enabled = !state.saving, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(review.location, { change(review.copy(location = it.take(500), locationUnknown = false)) }, label = { Text("Location") }, enabled = !state.saving, modifier = Modifier.fillMaxWidth())
        Row { Checkbox(review.locationUnknown, { change(review.copy(locationUnknown = it, location = if (it) "" else review.location)) }, enabled = !state.saving); Text("Location not provided in listing") }
        OutlinedTextField(review.description, { change(review.copy(description = it.take(SharedOpportunityImporter.MAX_TEXT))) }, label = { Text("Shared description (optional)") }, enabled = !state.saving, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 10)
        Text("Imported from: ${SharedOpportunityImporter.sourceName(review.url)}. Open status will remain UNKNOWN until there is source evidence.", style = MaterialTheme.typography.bodySmall)
        Row { Checkbox(review.confirmed, { edit(review.copy(confirmed = it)) }, enabled = !state.saving); Text("I reviewed the title, company and location above") }
        state.importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text(if (state.saving) "Saving..." else "Save reviewed opportunity") }
        TextButton(onClick = cancel, enabled = !state.saving) { Text("Cancel import") }
    }
}
