---
title: Semantic Search Verify & Close Gaps - Plan
type: fix
date: 2026-09-17
topic: semantic-search-audit
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
execution: code
---

# Semantic Search Verify & Close Gaps - Plan

## Goal Capsule

- **Objective:** Audit the inherited on-device semantic search implementation, close all gaps found, and mark Phase 5 of docs/plan.md done with evidence.
- **Product authority:** docs/plans/2026-09-17-001-fix-semantic-search-audit-plan.md Product Contract (below), enriched from the ce-brainstorm run of the same date.
- **Execution profile:** code; test-first for new behavior (toggle gating), characterization-first for audit verification of legacy paths.
- **Stop conditions:** a finding that invalidates the SmartScan pipeline as the canonical indexing path, or that requires a new ML model or network dependency, halts and surfaces to the user.
- **Open blockers:** none.

---

## Product Contract

Product Contract unchanged from the requirements-only artifact except: R3 annotation updated with the audit confirmation that no toggle exists today. No scope change.

### Summary

Audit the inherited on-device semantic search implementation, close all gaps found, and mark Phase 5 done with evidence.

### Problem Frame

Phase 5 of docs/plan.md was written as "build on-device semantic search" before anyone checked the codebase. The grounding scan found the feature already present: models shipped in `ml-models/src/main/assets`, indexing via the SmartScan SEARCH_INDEX phase, 512-d embeddings in the `image_embeddings` Room table, text and image-to-image query paths in `SearchViewModel`, and model-status UI in settings. The real risk is unverified inherited code: untested ranking and validation logic, offline-flavor behavior confirmed only by inspection, legacy workers still enqueued from live call sites, and no user control over semantic indexing.

### Requirements

**Offline & flavor safety**

- R1. In offline/noML builds (network permission stripped, models not bundled), the search and indexing paths must not crash, must not attempt network access, and must degrade to non-semantic search results.
- R2. The `ENABLE_INDEXING` BuildConfig guard must fully gate the indexing pipeline; no embedding work may run when it is false.

**User control**

- R3. A persisted settings toggle lets the user enable or disable semantic indexing (opt-out default ON; disabling blocks new embedding work, including manual refresh actions). Confirmed absent today: `SmartScanPreferenceDetailScreen` offers only manual one-shot refresh actions.

**Test coverage**

- R4. Unit tests cover embedding validation (`isValidEmbeddingVector`), cosine ranking (`sortByCosineDistance`), `ImageEmbeddingDao` (Robolectric), and `ClipTokenizer` edge cases, filling the four confirmed gaps per docs/testing-architecture.md.

**Code health**

- R5. The legacy `SearchIndexerUpdaterWorker` family is removed; the SmartScan pipeline is the only indexing path.
- R6. The query merge path, similarity threshold, orphan cleanup, and bitmap recycling in the indexing loop are verified correct; defects found are fixed.

### Key Decisions

- Verify-and-close-gaps rather than rebuild. (session-settled: user-directed — chosen over rebuilding or re-scoping to enhancements)
- Opt-in indexing with a settings toggle, then automatic incremental indexing. (session-settled: user-approved — agent recommendation, user assented)
- Everything best-effort media scope. (session-settled: user-directed — chosen over photos-only or photos-plus-video-frames)
- Semantic results live inside the existing search screen. (session-settled: user-approved)

### Acceptance Examples

- AE1. Covers R1, R2. **Given** an offline noML build with no downloaded models, **when** the user opens search, **then** fuzzy/metadata results still return, no exception reaches the UI, and no indexing phase runs.
- AE2. Covers R3. **Given** semantic indexing is disabled in settings, **when** a scan completes, **then** no new embeddings are written; **when** re-enabled, **then** indexing resumes from the stored revision state.
- AE3. Covers R4. **Given** an embedding vector of wrong dimension, non-finite values, or norm outside 0.9–1.1, **when** `isValidEmbeddingVector` runs, **then** it returns false and the candidate is not persisted.

### Success Criteria

- All audit findings fixed; `./gradlew testUniversalNoMLDebugUnitTest` and `./gradlew lint` green; new tests pass.
- code-reviewer verdict Approved on the final diff.
- Phase report at docs/phase-5-report.md; docs/plan.md Phase 5 marked done; deferred items listed.

### Scope Boundaries

**Deferred for later**

- Vector-search performance at scale (brute-force cosine scan over all embeddings).
- Video-frame indexing.
- Face grouping and category classification phases.
- Search UI redesign.

**Outside this phase's identity**

- Any new ML model or new dependency; any cloud/network feature (offline flavor must stay network-free).

### Dependencies / Assumptions

- Assumes the inherited SmartScan pipeline is the canonical indexing path — confirmed by repo research; the legacy worker family is the deletion candidate.
- Test tooling from Phase 3 (Robolectric, Room DAO tests) is available.

### Outstanding Questions

- Open Questions: none blocking. Device-dependent verification (offline build install, manual scan behavior) is deferred to the phase report's deferred list.

### Sources / Research

- Grounding dossier: `C:\Users\shadd\AppData\Local\Temp\ce-brainstorm\optique-semantic-search\grounding.md` (verbatim quotes with file:line pointers).
- Repo research (this planning run): settings pattern (`Settings.kt` `SmartFeatures.INCLUDE_IGNORED_ALBUMS` 357–366, `SettingsExt.kt` `rememberPreference` 23–50, `SettingsLayout.kt` `SwitchPreference` 289–371); gating points (`SmartScanPhaseProcessor.kt:437-439`, `SmartScanPlan.kt:57-78`, `GalleryApp.kt:263-284`); test conventions (`CloudUploadPrefDaoTest.kt`, `SmartScanPlanTest.kt`, `SearchIndexerStateTest.kt`); legacy-worker call sites (`DatabaseUpdaterWorker.kt:37`, `CategoryWorker.kt:254`, `GalleryApp.kt:263-272`); degradation path (`SearchViewModel.kt:495-504, 839-866`, `ModelManager.kt:175-177`).

---

## Planning Contract

### Key Technical Decisions

- KTD1. **Delete the legacy worker family.** Remove `SearchIndexerUpdaterWorker` and its live enqueuers (`DatabaseUpdaterWorker` via `WorkManager.updateDatabase()`, `CategoryWorker.startSearchIndexer()`), plus any sibling legacy workers confirmed unreferenced after the audit (`MetadataCollectionWorker`, `DatabaseUpdaterWorker` itself if only used as the legacy host). `GalleryApp`'s startup cancel list for legacy unique work names stays. (session-settled: user-directed — chosen over gating + documenting fallbacks; the SmartScan pipeline is canonical)
- KTD2. **Toggle stored in DataStore under `Settings.SmartFeatures`**, default ON, following the `INCLUDE_IGNORED_ALBUMS` pattern (preferences key + flow + setter + `SwitchPreference` in the Smart Features screen) — but note `INCLUDE_IGNORED_ALBUMS` defaults to false: the new preference flow must return `true` when the key was never written (`?: true`) and the ViewModel `StateFlow` initial value must be `true`, so the user-approved default ON holds. Disabling gates the `SearchIndexPhaseProcessor` right after the `ENABLE_INDEXING` check and disables the manual "Refresh embeddings" action. (session-settled: user-approved — default ON preserves current behavior)
- KTD3. **Accept the category-classification coupling**: disabling semantic indexing also blocks new category classification, because `CategoryClassificationPhaseProcessor` already blocks when embeddings are unavailable. Document the coupling in the toggle's summary string. (session-settled: user-approved)
- KTD4. **Test scope = the four confirmed gaps**: `isValidEmbeddingVector`, `sortByCosineDistance`, `ImageEmbeddingDao` (Robolectric, mirroring `CloudUploadPrefDaoTest`), `ClipTokenizer`. No new test infrastructure beyond existing conventions.

### Assumptions

- The offline/noML degradation path (compile-time permission stripping + `ModelManager` checks) is sufficient defense; device-run verification is deferred to the phase report.
- Any additional dead code discovered during U2 deletion follows the same deletion rule as KTD1.

---

## Implementation Units

### U1. Unit tests for embedding validation, ranking, tokenizer, and DAO

- **Goal:** Close the four confirmed test gaps so the audit's correctness claims are test-backed.
- **Requirements:** R4, R6; covers AE3.
- **Dependencies:** none.
- **Files:**
  - `app/src/test/java/com/dot/gallery/feature_node/data/data_source/ImageEmbeddingDaoTest.kt` (new)
  - `app/src/test/java/com/dot/gallery/feature_node/presentation/search/SearchEmbeddingValidationTest.kt` (new)
  - `app/src/test/java/com/dot/gallery/feature_node/presentation/search/SearchHelperImplTest.kt` (new)
  - `app/src/test/java/com/dot/gallery/feature_node/presentation/search/tokenizer/ClipTokenizerTest.kt` (new)
- **Approach:**
  1. Mirror `CloudUploadPrefDaoTest.kt` (Robolectric, in-memory `InternalDatabase`) for `ImageEmbeddingDao`: insert/get/count/deleteOrphans.
  2. Test `isValidEmbeddingVector` (internal in `SmartScanPhaseProcessor.kt`): wrong dimension, non-finite values, norm outside 0.9–1.1, valid vector passes.
  3. Test `sortByCosineDistance` ordering and tie behavior.
  4. Test `ClipTokenizer`: empty input, BOS/EOS tokens (49406/49407), 77-token truncation, unknown-word fallback.
- **Patterns to follow:** `CloudUploadPrefDaoTest.kt`, `SmartScanPlanTest.kt`, `SearchIndexerStateTest.kt`.
- **Test scenarios:**
  - Covers AE3. Vector of size ≠ 512 → false.
  - Vector containing NaN or Infinity → false.
  - Zero vector (norm 0) → false; norm 1.0 vector → true.
  - DAO: inserted embedding round-trips by id; `deleteOrphans` removes rows whose ids are absent from a provided id set.
  - Cosine ranking: higher-similarity record sorts first; identical vectors tie stably.
  - Tokenizer: BPE subword encoding on edge inputs (empty string, unknown words). Note: BOS/EOS insertion (49406/49407) and 77-token truncation live in `SearchVisionHelper.getTextEmbedding`, not `ClipTokenizer.encode` — cover that behavior in a `SearchVisionHelper`-level test, not the tokenizer test.
- **Verification:** new tests pass under `./gradlew testUniversalNoMLDebugUnitTest`; no production code changed except access modifiers if a function needs test visibility.

### U2. Offline/noML safety verification and fixes

- **Goal:** Prove R1/R2 by code-level audit plus tests; fix any gap found.
- **Requirements:** R1, R2; covers AE1.
- **Dependencies:** U1 (test conventions in place).
- **Files:** `app/src/main/kotlin/com/dot/gallery/core/ml/ModelManager.kt`, `app/src/main/kotlin/com/dot/gallery/feature_node/presentation/search/helpers/SearchVisionHelper.kt`, `app/src/main/kotlin/com/dot/gallery/feature_node/presentation/search/SearchViewModel.kt`, `app/src/main/kotlin/com/dot/gallery/core/smart/SmartScanPhaseProcessor.kt`, plus a new regression test if a gap is found.
- **Approach:**
  1. Trace every path from search UI and scan scheduling to ONNX session creation; confirm each is guarded by `ModelManager.isReady(ModelGroup.SEARCH)` / `areAiFeaturesAvailable` / `BuildConfig.ENABLE_INDEXING`.
  2. Verify no network call exists in the search/indexing path (offline flavor has INTERNET stripped — any such call is a build-breaking bug).
  3. Verify `ModelsNotAvailableException` is caught at UI boundaries (text search falls back to metadata/fuzzy; image search shows `ai_models_not_installed`).
  4. Fix gaps found; add a unit test per fixed gap.
- **Execution note:** characterization-first — write the audit findings down before changing anything; only code paths with a confirmed defect get modified.
- **Test scenarios:**
  - Covers AE1. `SearchIndexPhaseProcessor` returns `Blocked` when `ENABLE_INDEXING` is false (already covered by inspection; add test if not present).
  - Text search with models unavailable returns metadata/fuzzy results, no crash.
- **Verification:** audit findings recorded in the phase report; tests green.

### U3. Semantic indexing settings toggle

- **Goal:** User can enable/disable semantic indexing from Smart Features settings.
- **Requirements:** R3; covers AE2.
- **Dependencies:** none (logically independent of U1/U2; note U2 and U3 both edit `SmartScanPhaseProcessor.kt` — land U2's audit fixes before U3's gate, or vice versa, to avoid conflicts).
- **Files:**
  - `app/src/main/kotlin/com/dot/gallery/core/Settings.kt` (new `booleanPreferencesKey` in `Settings.SmartFeatures`)
  - `app/src/main/res/values/strings.xml` (title/summary; summary documents the category-classification coupling per KTD3)
  - `app/src/main/kotlin/com/dot/gallery/feature_node/presentation/settings/subsettings/SmartFeaturesViewModel.kt` (StateFlow + setter)
  - `app/src/main/kotlin/com/dot/gallery/feature_node/presentation/settings/subsettings/SettingsSmartFeaturesScreen.kt` (SwitchPreference in the `aiAvailable` block)
  - `app/src/main/kotlin/com/dot/gallery/core/smart/SmartScanPhaseProcessor.kt` (preference gate after the `ENABLE_INDEXING` check; requires injecting `@ApplicationContext Context` into `SearchIndexPhaseProcessor` — its constructor currently has none)
  - `app/src/main/kotlin/com/dot/gallery/feature_node/presentation/settings/subsettings/SmartScanPreferenceDetailScreen.kt` (refresh-embeddings action enabled state tied to the toggle)
  - `app/src/test/java/com/dot/gallery/core/smart/SmartScanPhaseProcessorTest.kt` (toggle gating test; mirror `SmartScanPlanTest.kt` conventions)
- **Approach:**
  1. Add the preference key + flow + setter following `INCLUDE_IGNORED_ALBUMS` (Settings.kt 357–366, SmartFeaturesViewModel.kt 92–96, 149–154), with default ON (`?: true` in the flow, `initialValue = true` in the ViewModel StateFlow).
  2. Gate `SearchIndexPhaseProcessor.process` on the preference (default ON); return `SmartScanPhaseResult.Blocked("semantic_indexing_disabled")`.
  3. Disable the manual "Refresh embeddings" action when the toggle is off (both the ViewModel method and the `ScanAction` enabled state in `SmartScanPreferenceDetailScreen`).
  4. i18n: all new strings via `strings.xml` (Crowdin-managed); no hard-coded strings.
  5. Document in the toggle summary and phase report that the gate applies to the next run — an in-progress indexing phase completes; existing embeddings are retained.
- **Patterns to follow:** `INCLUDE_IGNORED_ALBUMS` end-to-end (key → ViewModel → SwitchPreference), `SmartScanPhaseResult.Blocked` convention.
- **Test scenarios:**
  - Covers AE2. Toggle off → phase returns `Blocked("semantic_indexing_disabled")`, no embeddings written.
  - Toggle off → manual refresh action disabled in UI state.
  - Toggle on → phase proceeds to model-ready check.
  - Default value is ON when the key has never been written.
- **Verification:** tests green; toggle visible in Smart Features settings; strings externalized.

### U4. Legacy worker removal

- **Goal:** Delete the legacy indexing worker family; SmartScan is the only indexing path.
- **Requirements:** R5.
- **Dependencies:** U2 (audit confirms no other live consumers first).
- **Files:** `app/src/main/kotlin/com/dot/gallery/core/workers/SearchIndexerUpdaterWorker.kt`, `app/src/main/kotlin/com/dot/gallery/core/workers/DatabaseUpdaterWorker.kt`, `app/src/main/kotlin/com/dot/gallery/core/workers/CategoryWorker.kt`, `app/src/main/kotlin/com/dot/gallery/GalleryApp.kt` (cancel list stays). Note: the `updateDatabase()` in `StateExt.kt` is a generic lambda parameter used by live ViewModels — it is NOT a legacy WorkManager call site; do not touch it. The dead `WorkManager.updateDatabase()` extension lives in `DatabaseUpdaterWorker.kt`.
- **Approach:**
  1. Confirm each candidate worker's remaining call sites; delete worker + enqueuer together.
  2. Keep `GalleryApp`'s legacy unique-work cancellation (existing installs may have queued work).
  3. If a worker turns out to be load-bearing, keep it, gate it with `ENABLE_INDEXING`, and document it in the phase report instead of deleting.
- **Test scenarios:**
  - Test expectation: none for pure deletion — compile + full unit suite green is the proof; any surviving call site gets a gating test.
- **Verification:** `assembleUniversalNoMLDebug` green; grep shows no references to deleted classes.

### U5. Correctness spot-check, phase report, plan update

- **Goal:** Verify the query path end-to-end by reading, record the audit, close Phase 5.
- **Requirements:** R6.
- **Dependencies:** U1–U4 complete.
- **Files:**
  - `app/src/main/kotlin/com/dot/gallery/feature_node/presentation/search/SearchViewModel.kt`
  - `app/src/main/kotlin/com/dot/gallery/feature_node/presentation/search/helpers/SearchVisionHelper.kt`
  - `app/src/main/kotlin/com/dot/gallery/core/smart/SmartScanPhaseProcessor.kt`
  - `docs/phase-5-report.md` (new), `docs/plan.md`, `docs/tools-log.md`
- **Approach:**
  1. Spot-check: query merge logic, similarity threshold (`SearchVisionHelper.kt:143`), orphan cleanup, bitmap recycling in the indexing loop.
  2. Fix defects found; add regression tests per fix.
  3. Write docs/phase-5-report.md (completed, verification, deferred device items); mark Phase 5 done in docs/plan.md; log session in docs/tools-log.md.
- **Test scenarios:**
  - Any defect fixed gets a regression test naming the input and expected outcome.
- **Verification:** `./gradlew testUniversalNoMLDebugUnitTest`, `./gradlew lint`, `./gradlew assembleUniversalNoMLDebug` all green; code-reviewer verdict Approved.

---

## Verification Contract

| Gate | Command | Applies |
|---|---|---|
| Unit tests | `./gradlew testUniversalNoMLDebugUnitTest` | all units |
| Lint | `./gradlew lint` (baseline: `lint-baseline.xml`) | U3, U4, final |
| Build | `./gradlew assembleUniversalNoMLDebug` | final |
| Review | code-reviewer sub-agent on final diff | phase end (non-negotiable per AGENTS.md) |

Device-dependent checks (offline build install, real indexing run, toggle behavior on device) are deferred to the phase report's deferred list — no emulator/device in this environment.

---

## Definition of Done

- Every unit's Verification met; no dead-end or experimental code left in the diff.
- R1–R6 each satisfied with evidence (test result or audit note in the phase report).
- `testUniversalNoMLDebugUnitTest`, `lint`, and `assembleUniversalNoMLDebug` green.
- code-reviewer verdict Approved.
- docs/phase-5-report.md written; docs/plan.md Phase 5 marked done.
