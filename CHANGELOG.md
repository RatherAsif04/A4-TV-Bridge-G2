# Changelog

All notable changes to **A4 TV Bridge G2** (`dev.a4tvbridge.app`) are documented in this file.

---

## [v1.4.2] — Build 13

### Changed & Redesigned
- **Unified High-Contrast Dark & Light Design System**: True-black obsidian dark mode (`#05070A` canvas, `#0D1219` elevated cards, `#1E293B` borders, `#38BDF8` cyan / `#10B981` emerald / `#F59E0B` amber accents), clean light mode (`#F1F5F9` / `#FFFFFF` / `#00587B`), and a floating 5-tab bottom dock (`Home`, `Apps`, raised circular cyan `Control`, `TV Care`, `Shell`).
- **Redesigned Dashboard**: Connected TV hero card with `Switch TV` pill and storage bar, 3 colored-dot live metric tiles (`CPU`, `Memory`, `Network`), 2×2 Quick Actions cards with tinted icon badges, and a grouped `More Tools` card.
- **Redesigned Remote Control**: Full-width `D-Pad` / `Touchpad` segmented toggle, 4-button `Mirror / Screenshot / Record / Power` bar, sculpted circular D-Pad with central cyan `OK` button, split `Volume` & `Media` cards, and an inline `Send Text to TV Keyboard` bar.
- **Redesigned Clean Up TV & Check my TV**: Auto-matched CleanYourTV profile banner (`AUTO-MATCHED` pill + `Change list`), 3-step `1.0x / 0.5x / 0x` Animation Speedup selector, emerald `Safety Guards Active` card, custom checkbox rows with community disable percentages, emerald Verdict Hero card, 2×2 Status Overview grid, `System Health Checks` with `PASS / WARN / SAFE` pills, and `Post-Reboot Persistence Test` card.
- **Redesigned Device Info (Network) & Home Theme Studio**: Always-available `Share Info` pill, area-shaded live Download/Upload chart, structured `Wi-Fi Link Details` card, proportional colored per-app traffic bars, `Active / Set` launcher pills, and a 2×2 visual preview grid for `Nordic Slate`, `Obsidian Gold`, `Deep Ocean`, and `Emerald Dusk` 1080p wallpapers.
- **Documentation & Screenshots**: Added 6 high-DPI screenshots in `docs/screenshots/`, `docs/FEATURES.md`, updated `README.md`, `docs/USAGE.md`, `SECURITY.md`, and `CHANGELOG.md`.

---

## [v1.4.1] — Build 12

### Added
- **Automatic In-App GitHub Releases Updater (`AppUpdater.kt`)**:
  - Checks `RatherAsif04/A4-TV-Bridge-G2` latest GitHub Release silently on startup (6-hour throttle) and manually via **About → Check for updates**.
  - Compares semantic version tags, streams the release APK with a live progress bar, verifies **SHA-256** (`SHA256SUMS.txt` or GitHub `digest`) and **Android package ID** (`dev.a4tvbridge.app`), handles Android 8+ `REQUEST_INSTALL_PACKAGES` permission resume, and installs via `FileProvider`.
- **CleanYourTV Auto-Cleanup Engine (`CleanupEngine.kt`)**:
  - Synced all **518 device profiles** (`profiles.json` + `profiles_meta.json`, commit `be60870`) from `Gathin23/cleanyourtv` (MIT).
  - Automatic TV model matching (`manufacturer`, `brand`, `model`, `device`, `board`, `tv.json` metadata, plus universal consensus fallback) and searchable 518-list picker.
  - Built-in boot-loop & lock-out Safety Guards (`CORE_RE`/`CORE_OK`, real launcher filter, `TvInputService`/HDMI guard, `setupwraith` Wi-Fi owner guard, Settings & IME protection).
  - Community disable/keep tallies, 1-tap batch toggle with live progress and `pm list packages -e --user 0` readback verification, and undoable `1.0x`/`0.5x`/`0x` UI animation speedup.
- **Automatic `Check my TV` & Upgraded `Restore`**:
  - Live drift detection for OTA-re-enabled bloatware with 1-tap re-disable.
  - Preferred Home launcher verification & restoration, `ro.adb.secure=0` security warning, and automated **Restart TV & verify** (reboots TV, polls port `5555` auto-reconnect, and re-runs audit).
  - Upgraded **Restore** with 1-tap **Turn all apps back on** and animation scale restore.
- **Complete GitHub Documentation & Screenshots**:
  - Added 6 high-DPI UI screenshots in `docs/screenshots/`, full feature guide (`docs/FEATURES.md`), updated `README.md`, `docs/USAGE.md`, `SECURITY.md`, and `CHANGELOG.md`.

### Changed
- Bumped Android app version to **`1.4.1` (`versionCode = 12`)**.
- Updated `.github/workflows/build-apk.yml` to build, test, sign/align, checksum (`SHA256SUMS.txt`), and publish `v1.4.1` releases automatically.
- Updated `THIRD_PARTY_NOTICES.md` with CleanYourTV MIT attribution and snapshot reference.

---

## [v1.4.0] — Build 11

### Added
- Full clean-room feature parity across **Home Screen Launchers & 1080p Theme Studio** (4 procedural on-device wallpapers), **Block Ads (Private DNS)**, **Device Info** (`Overview`, `CPU`, `Memory`, `Network` live 1s chart + per-app UID attribution + **Share Device Info**), **1-Click Verified App Installer**, **Split APK Sideload**, **File Manager & Send Files**, **Screen Mirror & Capture**, and **ADB Shell Terminal** — all authored cleanly under the MIT License.

---

## [v1.0.1] — Fixed Android APK Baseline

### Fixed
- **Dark Mode & Dialog Contrast**: Dedicated `BridgeDialogDark` / `BridgeDialogLight` themes, window background sync, themed dialog inputs, and readable `Clean Up TV` dropdowns.
- **Navigation & Bottom Sheets**: Anchored bottom dock in ADB Shell, top-only bottom sheet corners, and gesture navigation inset handling.
- **Non-Blocking Remote Queue**: Dedicated coroutine `Channel` queue preventing dropped D-pad presses and layout bounce.
- **Android 8–12 Toybox & ADB Compatibility**: Safe `mv --`, `readlink -f`/`pwd -P` fallback, right-anchored `df -Pk` storage parser, `ps` fallback for Running apps, `screenrecord` bitrate fallback, and `android:configChanges` session preservation.
- **Build Toolchain**: Upgraded to `kadb:2.1.4`, `compileSdk = 37`, `AGP 9.1.1`, `Kotlin 2.4.0`, `Gradle 9.3.1`, and `JDK 21`.

---

## [v1.0.0]

- Initial public release of A4 TV Bridge.
