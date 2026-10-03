# CareerOS development

## Current Phase
Resume import, local career knowledge, embeddings/RAG and evidence-based matching. Android single app module, min SDK 28 / target 36.

## Current Task
Implementation compiled; 11/11 targeted JVM tests passed and one final assembleDebug succeeded. Verification stopped. Physical-device and actual-model inference validation are separate; no claim of tested model inference.

## Completed / Preserved
Existing profile/preferences, Remotive, Greenhouse/GitLab, Lever/Spotify/Meesho, source catalog/intents/orchestrator, normalization, cross-source dedupe, deterministic relevance, For You/All/Sources, Share-to-CareerOS, opportunities and tracker preserved. Previous live run: 349 source records -> 346 unified opportunities. Previous milestone: 13/13 targeted tests and assembleDebug passed. No new discovery sources or polling.

## Architecture
presentation -> domain; data -> domain. New domain/intelligence contracts: ResumeTextExtractor, CareerKnowledgeRepository, LocalModelManager, DeviceCapabilityChecker, VectorStore, CareerIntelligence. Existing EmbeddingEngine extended with document/batch operations; LocalLlm/RagEngine reused. Runtime specifics isolated in data/intelligence. Future application intelligence reuses these evidence/retrieval interfaces.

## Database Version
4. Explicit 1->2->3->4 migrations; no destructive fallback. New career_knowledge, knowledge_chunks, knowledge_vectors, evidence_analyses. Existing opportunity/profile tables unchanged. Vector BLOBs use little-endian Float32, model/version + chunk content hash. Chunk removal cascades vectors; changed content invalidates embeddings and analyses.

## Resume Import / PDF Extraction
Profile -> Resume / Career Intelligence -> Import Resume.pdf uses Android OpenDocument (SAF), no broad storage permission. Local PDFBox-Android 2.0.27.0 text extraction; dependency AAR measured 3,254,019 bytes before adoption. Limits: 15 MB, 30 pages, 100,000 text characters. Rejects encrypted/corrupt/unsupported/empty PDFs with safe messages. No OCR or DOCX. Scanned PDFs report no extractable text. Original PDF is not retained or uploaded.
Heading-based section candidates (skills, projects, education, experience, certifications, links, summary/unclassified) preserve extracted text without guessing structured employment/years. Review allows editing/excluding/confirming every section before replacing resume knowledge. Existing profile fields are not silently overwritten. Saved resume evidence can be edited/removed. Unsaved review is in ViewModel memory (rotation retained; process death requires reimport).

## CareerKnowledge / Chunking
Projects, skills, education, experience, preferences and links derive from existing saved profile; verified reusable factual answers included. Resume sections become RESUME_VERIFIED only after user confirmation; profile and answer evidence retain PROFILE_VERIFIED / USER_VERIFIED provenance. Inferred/unknown knowledge cannot be chunked as verified evidence. No LLM writes career facts.
Section boundaries retained; long sections split into roughly 400-600 English-token chunks (350 words, 35-word overlap). Stable chunk IDs and content hashes avoid unchanged re-embedding. Actual token counts vary by language/model; the published EmbeddingGemma task has a 512-token input limit, so unusually token-dense chunks may truncate at runtime.

## EmbeddingEngine / EmbeddingGemma
Real MediaPipe tasks-text 1.0.0 TextEmbedder adapter implemented for the officially published EmbeddingGemma 300M mixed int4/int8 .task. Uses actual binary-inspected TextEmbedder.TextFormatContext API with RETRIEVAL_DOCUMENT / RETRIEVAL_QUERY; batch documents share one native model session. CPU execution; no cloud fallback. Model weights NOT bundled/downloaded in this engineering run. On-device compatibility, quality and performance remain unverified.

## VectorStore / RAG
Room-backed exact cosine search, finite/dimension validation, deterministic ordering; skips stale hashes/other model versions. CareerRagRetriever retrieves top 8, filters low similarities/duplicates, selects up to 5 evidence chunks. Explicit Index knowledge action; unchanged chunks reused. Query uses normalized job requirements, not entire resume. Retrieval/analysis operate offline once models/data exist.

## LocalLlm / Gemma 3n
Real LiteRT-LM 0.8.0 Kotlin adapter, Gemma 3n E2B IT int4 .litertlm, CPU backend, 8,192-token context. API checked against official v0.8.0 source. Latest 0.17.1 depends on Kotlin 2.4; pinned 0.8.0 avoids changing existing Kotlin toolchain. One-shot engine/conversation closed after inference; no tools, remote requests or autonomous actions. Native LLM logging disabled. Actual multi-GB model inference not performed here.

## Model Distribution / Device Capability
Career Intelligence setup opens official model download/license pages; user downloads separately and imports through SAF with expected SHA-256. Streamed app-private no-backup storage, bounded sizes/free-space checks, checksum validation and atomic file replacement. No developer-local paths. Distinguishes NOT_INSTALLED, INSTALLED (checksum only), READY (actual runtime inference succeeded), ERROR, UNSUPPORTED. Runtime validation is explicit. Removes models locally. No built-in/resumable downloader; Gemma gated download requires user license acceptance.
Before inference checks arm64, available RAM (embedding >=650 MB, LLM >=3 GB) and >=128 MB working storage. CPU chosen; no GPU/NPU claim. Conservative guards cannot guarantee native memory stability on every device. Model inference/import/indexing serialized; no forced setup for browsing.

## Evidence-Based Matching
Existing deterministic ProfileRelevanceEngine and categories retained. Structured source requirements/skills used first; bounded deterministic JD skill/experience/education extraction. Listing mentions are labeled, not promoted to mandatory requirements. Exact skill evidence shows verified quotes. Experience, education eligibility and preference requirements remain UNKNOWN where keyword matching cannot establish satisfaction. Missing means not found in evidence, not proven absent.
Local LLM receives only bounded normalized requirements + up to five retrieved verified chunks. Output schema accepts requirement/chunk IDs and exact quotes only. Invalid IDs/quotes rejected; model relationships always PARTIAL / INFERRED and need review, never verified history or a stronger category. Raw model biographies are not displayed/stored. Structured analysis includes category, signals, alignments, citations, model/knowledge/input hashes and timestamp. Hash changes invalidate analyses; opening detail reads cache only, explicit Analyze runs inference. Failed/unavailable models preserve exact-evidence analysis.

## UI / Offline Behavior
Profile entry for resume/setup, model states/checksum/import/validation/removal, real chunk/index counts, indexing progress. Opportunity Detail adds MATCHED/PARTIAL/NOT FOUND/UNKNOWN with quotes and provenance. No fake progress percentages, embeddings, model availability or AI output. Existing discovery/tracker/deterministic matching plus resume review/knowledge/exact-evidence analysis work without AI models. No resume or embeddings sent to the network.

## Application Answer Bank / Autofill
Foundation retained; verified answers may contribute local evidence. No AutofillService, keyboard, full application assistant, application drafts or silent submission. Save/Prepare remains tracker state only.

## Gemini Decision
Gemini + Google Search Grounding remains intentionally NOT implemented because automated collection/analysis/database creation conflicts with published grounding restrictions. No Gemini/cloud embedding/cloud LLM integration in this milestone.

## Testing Status
Selected new JVM tests: resume section mapping, chunk provenance/hash, vectors/cosine, requirements/anti-hallucination, invalidation, RAG selection, PDF extraction, Room vector ordering/staleness/removal, mocked LLM/cache, v3->v4 migration. All 11 targeted tests passed (7 deterministic/domain, 4 PDF/Room/mocked-inference integration), zero skipped. No actual model required or cloud quota consumed.
Lint = NOT RUN intentionally. No emulator/AVD/system-image creation/download/launch; no connectedAndroidTest.

## Known Issues / Real Validation Gaps
Physical-device SAF/PDF/layout/long-running operations and both native model runtimes require manual validation. Model imports require trusted SHA-256 supplied by user. PDF multi-column order can need manual correction. No OCR, automatic entity-level resume parsing or inference of professional experience. Semantic relationships remain suggestions, not proof. CPU LLM may be slow; no background worker/foreground service for inference. App-private Room is not additionally encrypted; automatic backup/transfer disabled. Existing profile-draft limitation and discovery coverage limitations remain. Release hardening deferred.

## Next Milestone
Physical-device/model validation and evidence-quality tuning, then application intelligence using this same RAG/evidence layer. Do not begin automatically.

## Last Successful Test
Current milestone: 11/11 targeted JVM tests passed in 4m 27s on 2026-09-30; intelligence-tests.log. Previous discovery milestone: 13/13 passed. No full regression run.

## Last Successful Build
2026-09-30: single final :app:assembleDebug succeeded in 1m 59s. Log: intelligence-assemble.log. APK: app/build/outputs/apk/debug/app-debug.apk (207196810 bytes; previous baseline 36,656,762 bytes). Native runtime libraries increase APK size; no model weights bundled. Gradle could not strip three upstream native libraries and packaged them unchanged. No subsequent build/verification run.

## Runtime References
https://developers.google.com/edge/mediapipe/solutions/text/text_embedder/android
https://developers.google.com/edge/mediapipe/solutions/text/text_embedder
https://github.com/google-ai-edge/LiteRT-LM/tree/v0.8.0/kotlin
https://huggingface.co/google/gemma-3n-E2B-it-litert-lm
https://github.com/TomRoush/PdfBox-Android

## Build / Verification Policy
Android Studio JBR; existing gitignored .local-build/cacerts is tooling trust only. Gradle workers=2. No Gradle builds during implementation; end with targeted tests then assembleDebug ONCE; stop after success. No lint/repeated full suites/redundant builds. Never create/start/download emulator/AVD/system image. No model assets inside APK.


