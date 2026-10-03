package com.kishan.careeros.presentation

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kishan.careeros.domain.intelligence.*


@Composable fun CareerIntelligenceScreen(state: IntelligenceState, vm: IntelligenceViewModel, back: () -> Unit) {
    val context = LocalContext.current
    var embeddingChecksum by rememberSaveable { mutableStateOf("") }
    var llmChecksum by rememberSaveable { mutableStateOf("") }
    var pendingKind by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingChecksum by rememberSaveable { mutableStateOf("") }
    var confirmRemoval by remember { mutableStateOf(false) }
    val resumePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importResume(it.toString()) } }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val selectedKind = pendingKind?.let(ModelKind::valueOf)
        val selectedChecksum = pendingChecksum
        pendingKind = null
        pendingChecksum = ""
        if (uri != null && selectedKind != null) vm.importModel(selectedKind, uri.toString(), selectedChecksum)
    }
    Page("Career Intelligence", "Resume, evidence and local models. Nothing is uploaded.") {
        TextButton(onClick = back) { Text("Back") }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it) }
        Text("Resume", style = MaterialTheme.typography.titleLarge)
        Text(if (state.knowledge.any { it.sourceId == "resume" }) "Reviewed resume evidence imported" else "No resume imported")
        Button(onClick = { resumePicker.launch(arrayOf("application/pdf")) }, enabled = !state.busy) { Text("Import Resume.pdf") }
        if (state.knowledge.any { it.sourceId == "resume" }) {
            TextButton(onClick = vm::reviewSaved, enabled = !state.busy) { Text("Review / edit saved resume evidence") }
            TextButton(onClick = { confirmRemoval = true }, enabled = !state.busy) { Text("Remove resume evidence") }
        }
        state.review?.let { review ->
            Text("Review imported career facts", style = MaterialTheme.typography.titleLarge)
            Text("Sections are detected from headings, not AI guesses. Edit PDF layout errors, remove unwanted details, and confirm each section. Experience is retained as text; no years are inferred. This replaces previous resume evidence when saved.")
            review.candidates.forEachIndexed { index, c ->
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${c.kind} / ${c.label}")
                    OutlinedTextField(c.text, { vm.editReview(index,c.copy(text = it,confirmed = false)) }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy, minLines = 3, maxLines = 12)
                    Row { Checkbox(c.confirmed, { vm.editReview(index,c.copy(confirmed = it)) }, enabled = !state.busy); Text("I confirm this is accurate career evidence") }
                    TextButton(onClick = { vm.removeReview(index) }, enabled = !state.busy) { Text("Exclude section") }
                } }
            }
            Button(onClick = vm::saveResume, enabled = !state.busy && review.candidates.isNotEmpty() && review.candidates.all { it.confirmed && it.text.isNotBlank() }) { Text("Save verified career knowledge") }
            TextButton(onClick = vm::cancelReview, enabled = !state.busy) { Text("Discard review") }
        }
        Panel("Knowledge base", "${state.knowledge.size} verified sections / ${state.chunks} chunks / ${state.indexed} indexed for the current embedding model")
        Button(onClick = vm::index, enabled = !state.busy && state.chunks > 0) { Text("Index knowledge locally") }
        Text("Local model setup", style = MaterialTheme.typography.titleLarge)
        Text("Download the supported model from its official page, accept its license, then import the file. No model weights are bundled. SHA-256 must match your trusted download. File verification does not replace runtime validation.")
        state.models.forEach { model ->
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (model.kind == ModelKind.EMBEDDING) "EmbeddingGemma 300M (.task)" else "Gemma 3n E2B IT (.litertlm)", style = MaterialTheme.typography.titleMedium)
                Text("${model.state}: ${model.message}")
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (model.kind == ModelKind.EMBEDDING) ModelCatalog.EMBEDDING_URL else ModelCatalog.LLM_URL))) } }) { Text("Official model download / license") }
                val checksum = if (model.kind == ModelKind.EMBEDDING) embeddingChecksum else llmChecksum
                OutlinedTextField(checksum, {
                    if (model.kind == ModelKind.EMBEDDING) embeddingChecksum = it.trim() else llmChecksum = it.trim()
                }, label = { Text("Trusted expected SHA-256 (64 hex characters)") }, enabled = !state.busy && pendingKind == null, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    pendingKind = model.kind.name
                    pendingChecksum = checksum
                    modelPicker.launch(arrayOf("*/*"))
                }, enabled = !state.busy && pendingKind == null && checksum.matches(Regex("[0-9a-fA-F]{64}"))) { Text("Import model file") }
                TextButton(onClick = { vm.validate(model.kind) }, enabled = !state.busy && model.installed) { Text("Validate runtime") }
                TextButton(onClick = { vm.removeModel(model.kind) }, enabled = !state.busy && model.installed) { Text("Remove model") }
            } }
        }
        Text("CPU inference requires arm64, free RAM and storage. Gemma needs at least 3 GB available RAM under this app's conservative guard. Validation on your physical device is required. Browsing and deterministic matching work without models.")
    }
    if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false }, title = { Text("Remove resume evidence?") }, text = { Text("This removes saved resume sections, their vectors and cached analyses. Your profile and opportunities remain.") }, confirmButton = { TextButton(onClick = { confirmRemoval = false; vm.removeResume() }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Cancel") } })
}
@Composable fun EvidenceAnalysisPanel(jobId: String, state: IntelligenceState, analyze: () -> Unit, setup: () -> Unit) {
    Text("Career evidence", style = MaterialTheme.typography.titleLarge)
    Text("Exact verified text and optional local semantic suggestions. Profile Match remains the deterministic category.")
    Button(onClick = analyze, enabled = !state.busy) { Text(if (state.busy) "Working locally..." else "Analyze career evidence") }
    TextButton(onClick = setup) { Text("Resume / local AI setup") }
    val analysis = state.analysis?.takeIf { it.opportunityId == jobId }
    if (analysis == null) Text("No current evidence analysis. Models are optional for exact evidence matching.")
    analysis?.let { a ->
        Text(a.status)
        a.requirements.forEach { row ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${row.outcome.name.replace('_',' ')}: ${row.requirement.text}")
                if (!row.requirement.explicit) Text("Listing mention; not necessarily a hard requirement", style = MaterialTheme.typography.bodySmall)
                if (row.inferred) Text("INFERRED relationship / needs review. This does not add a verified fact.")
                row.evidence.forEach { e -> Text("${e.label} / ${e.origin}", color = MaterialTheme.colorScheme.primary); Text(e.quote, style = MaterialTheme.typography.bodySmall) }
                if (row.outcome == EvidenceOutcome.NOT_FOUND) Text("Not found in verified evidence; this does not prove absence of skill.")
                if (row.outcome == EvidenceOutcome.UNKNOWN) Text("Requires factual review; keyword matching cannot establish this requirement.")
            } }
        }
    }
}
