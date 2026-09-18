# Optique — FOSS gallery app for Android

<div align="center">

[![Platform](https://img.shields.io/badge/platform-Android%2011%2B-3DDC84?logo=android&logoColor=white)](https://github.com/alzimerahmed/Optique/releases/latest)
[![Language](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![License](https://img.shields.io/github/license/alzimerahmed/Optique?color=247EE0)](LICENSE)
[![CI](https://github.com/alzimerahmed/Optique/actions/workflows/checks.yml/badge.svg?branch=main)](https://github.com/alzimerahmed/Optique/actions/workflows/checks.yml)
[![Crowdin](https://badges.crowdin.net/gallery-compose/localized.svg)](https://crowdin.com/project/gallery-compose)

*The gallery app everyone wants, with the features everyone needs — private, offline-capable, and FOSS.*

[Download](#download) • [Features](#features) • [Building](#building)

</div>

---

## Features

- **Timeline & albums** — grouped by day/month, favorites, trash with restore, secure mode
- **On-device semantic search** — CLIP-style embeddings index your library locally; search by description, no cloud
- **Subject Cutout** — MobileSAM-powered one-tap subject isolation with interactive point refinement
- **Memories & People** — "On this day" recaps, storycards, on-device face grouping
- **Editor** — crop, markup, filters, EXIF view/edit, develop tools
- **RAW & HEIC support** — native codecs (Libraw, libheif, JPEG 2000) via NDK, no server round-trip
- **Vault** — encrypted private folder with backup support
- **Cloud sync** — Immich, ownCloud, Nextcloud, WebDAV, SMB, NFS
- **Location maps** — photo clusters on an interactive map
- **Widgets, casting, wallpapers** — home-screen media widgets, Chromecast, set wallpaper from any photo
- **`offline` variant** — network and location permissions stripped at build time; fully self-contained

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin (JDK 17) |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| Persistence | Room + KSP |
| Native codecs | C++17 / NDK r29 / CMake 3.31.x — libheif, libde265, Libraw, JPEG 2000 |
| ML | ONNX Runtime — MobileSAM, arcface, face detection, CLIP-style encoders |
| Performance | Baseline Profiles, Compose stability config |
| i18n | Crowdin (~40 locales) |

## Project Structure

```
app/src/main/kotlin/com/dot/gallery/
├── core/            # decoders, thumbnails, encryption, ML, workers, backup
├── feature_node/    # MVVM features: data / domain / presentation (~35 screens)
├── cloud/           # cloud sync framework (auth, sync, Room DAOs, UI)
├── ui/              # theme + custom icons
└── injection/       # Hilt modules
app/src/main/cpp/    # native codec stacks (libheif, Libraw, jp2, imgcodec)
ml-models/           # ONNX models (WithML flavor)
baselineprofile/     # macrobenchmarks + profile generation
```

## Download

[<img
    alt='Get it on GitHub'
    src='./screenshots/items/get-it-on-github.png'
    height="80" />](https://github.com/alzimerahmed/Optique/releases/latest)

GitHub Releases is the only distribution channel — grab the latest APK from the [Releases page](https://github.com/alzimerahmed/Optique/releases). APK checksums are provided in the release notes.

## Building

```bash
git clone https://github.com/alzimerahmed/Optique.git
cd Optique
./gradlew assembleDebug
```

Requires JDK 17, Android SDK 37, NDK (version pinned via `refra.ndkVersion` in `gradle.properties`), CMake 3.31.x. Run `powershell -File scripts/verify-env.ps1` to check the toolchain.

<details>
<summary>Advanced build options</summary>

- **Offline flavor**: `./gradlew assembleOfflineDebug` — strips network/location permissions
- **Unit tests**: `./gradlew testDebugUnitTest`
- **Release bundle**: needs `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` env vars and `app/release_key.jks`
- **Native codecs**: rebuild per-ABI with `scripts/native/*.sh` (bash + NDK r29); prebuilt outputs live in `app/src/main/cpp/<stack>/<abi>`
- **ML models**: `:ml-models:assembleModels` reassembles split models; `checkModelSizes` guards the 100 MB asset limit

</details>

## Usage

Install the APK, grant photo access, and the timeline builds itself. Everything works on-device: semantic search, subject cutout, face grouping, and editing require no account and no network (use the `offline` release to guarantee it).

## Frequent Questions

- **Why 'Optique'?** From 'optics' — the science of light and vision. The app is about how you see and manage your media.
- **What is the `offline` variant?** A build with all online features removed — maps, cloud providers, anything needing internet, even local network. For a fully self-contained gallery with no network permissions.
- **Why Android 11 minimum?** Trash and several media APIs require it.
- **Will you support lower Android versions?** Not a priority right now; PRs welcome.
- **Can I verify the downloaded APK?** Checksums are provided in the release notes.
- **Can you remove permission X?** Some permissions (internet, location) back optional features like map previews; the `offline` release already strips them.
- **Will you add feature X?** Open a feature request under Issues.

## Contributing

Fork the repo, create a feature branch, and open a pull request — CI runs ktlint, detekt, and unit tests on every PR. Translations are community-managed on [Crowdin](https://crowdin.com/project/gallery-compose); questions and discussion happen on Telegram.

## Roadmap

- [x] On-device semantic search
- [x] Memories & recaps
- [ ] Face grouping UX: name, merge, ignore
- [ ] Batch media power tools (convert, resize, tags)
- [ ] Security & vault hardening audit

## Changelog

See [GitHub Releases](https://github.com/alzimerahmed/Optique/releases) — per-version notes also live in `fastlane/metadata/android/en-US/changelogs/`.

## License

Apache-2.0 — see [LICENSE](LICENSE). Project lead: Alzimer Ahmed.
