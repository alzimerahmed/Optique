# Optique Gallery — Project Rules for AI Agents

## Project

Optique (fork of IacobIonut01/ReFra, itself renamed from `IacobIonut01/Gallery`; upstream: https://github.com/IacobIonut01/ReFra) — a FOSS Android gallery app built with Jetpack Compose. Goal: the gallery app everyone wants, with the features everyone needs. Repo: https://github.com/alzimerahmed/Optique — applicationId `com.dot.gallery`. Fully independent from upstream (no sync). Distributed via GitHub Releases ONLY (no Play Store, F-Droid, or other channels; the `gplay` flavor exists in code but is not shipped).

**Stack:** Kotlin, Jetpack Compose + Material 3, Hilt (DI), Room + KSP, Kotlin Serialization, Kotlin Parcelize, Compose Compiler Gradle plugin, Baseline Profiles (`baselineprofile/`). Min SDK 29 (Android 11 — required by Trash/media APIs), target/compile SDK 37. JDK 17.

**Key subsystems:**
- **Native media codecs (C++/NDK + CMake 3.31.x, `app/src/main/cpp/`):** HEIC tiled decoding via libheif + libde265, plus imgcodec, heifenc, Libraw (RAW), and JPEG 2000 (jp2) stacks — prebuilt static libs built by `scripts/native/*.sh` per ABI (arm64-v8a, armeabi-v7a, x86_64, x86). NDK version pinned via `refra.ndkVersion` in `gradle.properties`. CMake 4.x intentionally avoided (breaks libde265/libheif `cmake_minimum_required`).
- **On-device ML (`ml-models/`, WithML flavor):** MobileSAM ONNX "Cutout Engine" (subject segmentation + interactive point-prompt refinement), mask refinement pipeline (BFS hole-fill, island removal, box blur, sigmoid LUT), arcface (split model, reassembled by `:ml-models:assembleModels`). Ubiquitous language in `CONTEXT.md` — use those exact terms (Subject Cutout, Cutout Engine, Mask Refinement, Additive/Subtractive Point Mode, Cached Cutout).
- **Build variants:** `offline` flavor strips network/location permissions via `manifestConfig`; feature flags as BuildConfig booleans (MAPS_ENABLED, IMMICH_ENABLED, OWNCLOUD_ENABLED, NEXTCLOUD_ENABLED, WEBDAV_ENABLED, SMB_ENABLED, NFS_ENABLED, OFFLINE_MODE, ENABLE_INDEXING). Cloud provider support: Immich, ownCloud, Nextcloud, WebDAV, SMB, NFS.
- **Media provider:** ContentProvider `com.dot.gallery.media_provider` (`.debug` suffix in debug builds), CONTENT_AUTHORITY per build type.
- **i18n via Crowdin** (project: gallery-compose). Community on Telegram.

**Build/verify:** `./gradlew assembleDebug` · `./gradlew testDebugUnitTest` · `./gradlew bundleRelease` (needs `SIGNING_STORE_PASSWORD`/`SIGNING_KEY_ALIAS`/`SIGNING_KEY_PASSWORD` env vars + `release_key.jks`). Native codec rebuilds need NDK r29 + bash scripts (`scripts/native/`); prebuilt outputs live in `app/src/main/cpp/<stack>/<abi>`. `:ml-models:checkModelSizes` fails the build if unmanaged assets exceed GitHub's 100 MB limit. Lint baseline: `lint-baseline.xml`. Requires JDK 17 + Android SDK 37.

## Entry Point

This file is auto-loaded by Devin at every session start. It is the entry point to the full prompt system in `.devin/prompt/`. Read `.devin/prompt/map.md` before starting any task — it is the system map.

## Resource Discipline (mandatory for non-trivial tasks)

Before starting any non-trivial task:
1. Read `docs/toolset.md` intent-map (one table, task type → resources)
2. Identify the task type row; invoke every skill and sub-agent listed there
3. Read every rule listed for that task type (from `.devin/rules/`)
4. At task end: `code-reviewer` sub-agent on the final diff (non-negotiable)
5. Append learnings via `/ce-compound` if a durable lesson was learned

For phase implementations (any task completing a row in docs/plan.md), /ce-work is mandatory.

Skip this for single-line edits, pure Q&A, or reading files.

## Project-Type Filter (Android native app)

This is a native Android app, not a website. Per the intent-map in `docs/toolset.md`:
- **Skip web-only sub-agents/skills:** frontend-designer, css-architect, pwa-engineer, seo-specialist, search-optimization, playwright-design-clone (except when auditing Compose UI against design references — use tastemaker/pixel-analyst instead).
- **Keep universal ones:** code-reviewer, debugger, test-engineer, security-auditor, performance-engineer, git-master, migration-specialist, docs-writer, i18n-specialist (Crowdin-managed translations via Compose string resources), build-optimizer, caveman-compressor, pixel-analyst, vibe-coding-auditor, type-safety-engineer (Kotlin), database-engineer (Room).
- **Optique-specific quality gates:** `./gradlew lint` (Android Lint, baseline in `lint-baseline.xml`), unit tests via JUnit/Robolectric, Compose UI tests, a11y via Compose semantics — not axe-core/browser tooling. Baseline Profile generation matters for gallery scroll/jank performance. Respect the `offline` flavor: never add network calls that would break permission-stripped builds; guard online features behind the BuildConfig flags.
- **ML work:** keep models within the `:ml-models` size guard; use the CONTEXT.md ubiquitous language for cutout features.

## Communication Style

Default to **caveman-lite** compression (lightly compressed, still readable, full technical accuracy). Use the `/caveman` skill for full / ultra / wenyan modes when tighter compression is needed.

## Quick Task Flow

For quick tasks, follow `.devin/prompt/quick.md` (commandments) and `.devin/prompt/rules.md` (scoping, verification, escalation). For phased work, follow `.devin/prompt/phase.md`.

## Key References

- `docs/toolset.md` — intent map (task type → skills, sub-agents, rules) — create per phase.md §4 if absent
- `docs/plan.md` — phased plan and current status — create per phase.md §8 if absent
- `docs/project.md` — project state and structure — create per phase.md §3 if absent
- `docs/agent.md` — past implementations and decisions — create if absent
- `docs/research.md` — technical research, ADRs, gotchas — create per phase.md §7 if absent
- `CONTEXT.md` — upstream ubiquitous language for media/AI features (Subject Cutout, Cutout Engine, etc.)
- `docs/adr/` — upstream architecture decision records
- Upstream repo — README (features, variants, FAQ, signing fingerprints) and `.github/` for CI workflows (nightly builds)
