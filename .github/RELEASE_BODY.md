## What's new in 5.1.5

ReFra 5.1.5 is a focused reliability update that improves photo location accuracy, slideshow startup, and offline launches.

### Bug Fixes

- **More accurate photo locations** — Repair all unresolved location metadata instead of stopping after the first batch, group coordinate-only locations consistently, and center static map previews on the photo's exact GPS position (#1203)
- **Reliable slideshow startup** — Wait for media loading to finish before deciding that a slideshow is empty, while still exiting for genuinely empty or fully filtered selections (#1170)
- **Crash-free offline startup** — Avoid querying network state when an offline build lacks the network-state permission, preventing a startup crash without changing connected-build route handling (#1201)
