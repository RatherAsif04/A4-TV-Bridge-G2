# A4 TV Bridge — Complete Feature Reference (v1.4.4)

This document describes every screen, safety mechanism, and technical capability included in **A4 TV Bridge (`v1.4.4`)**.

---

## 1. TV Connection & Discovery

- **Automatic LAN Discovery**: Uses Android `NsdManager` to discover `_adb-tls-connect._tcp` (Android 11+ Wireless Debugging) and `_adb._tcp` (legacy network ADB) devices on the local network.
- **Wireless Debugging TLS Pairing**: Supports Android 11+ pairing with separate pairing IP:port + 6-digit code and main connection IP:port, using Conscrypt TLS and persistent RSA keypairs stored strictly inside app-private storage.
- **Manual IP:Port Connect**: Direct connection to any TV IP address and debugging port (default `5555`).
- **Device Identity Capture**: On connect, reads `ro.product.manufacturer`, `ro.product.brand`, `ro.product.model`, `ro.product.device`, `ro.product.board`, Android version, ABI, and serial number.
- **Multi-TV Profiles & Rotation Persistence**: Remembers previously connected TVs, supports quick switching, and uses `android:configChanges` so screen rotation or Dark/Light theme changes never drop an active ADB session.

---

## 2. Dashboard & Live Metrics

- **Connected TV Overview**: Displays friendly TV name, model/codename, Android TV OS version, IP:port, and connection transport badge.
- **Storage Bar**: Right-anchored `df -Pk` parser (compatible with Android 8–15 Toybox/BusyBox) displaying free and total user storage.
- **Live Telemetry Cards**: Real-time CPU %, RAM usage, and Wi-Fi/Ethernet network throughput tiles that link straight into **Device Info**.
- **5-Tab Bottom Navigation Dock**: Persistent `Home`, `Apps`, raised center `Control`, `TV Care`, and `Shell` tabs with gesture-navigation inset awareness.

---

## 3. Clean Up TV (CleanYourTV Engine)

Powered by an independent clean-room Kotlin engine (`CleanupEngine.kt`) bundled with **518 community device profiles** (`profiles.json` + `profiles_meta.json`) synced from `Gathin23/cleanyourtv`.

### Auto-Matching Pipeline
1. Exact or normalized slug match against `brand + model + device` and `tv.json` metadata (`title`, `brand`, `codename`).
2. Scored fallback match across model tokens and manufacturer.
3. Universal Android TV / Google TV community consensus fallback when an unrecognized TV model is connected.
4. Searchable manual picker allowing you to browse or switch to any of the 518 bundled lists.

### Built-In Safety Guards
Before presenting or disabling any package, `CleanupProtection` probes the live TV and locks out critical components:
- **Core Boot-Loop Protection (`CORE_RE` + `CORE_OK`)**: Never disables `android`, `com.android.systemui`, `com.android.settings`, `com.android.providers.*`, `com.android.keychain`, `com.android.packageinstaller`, `com.google.android.gms`, or other boot-critical services.
- **Active Home Launcher Guard**: Resolves real launchers (ignoring `FallbackHome`, `setupwraith`, `factorytest`) and protects the active launcher so you are never stranded without a home screen.
- **HDMI / AV & Tuner Guard**: Queries `TvInputService` bindings and protects live TV input and HDMI switching packages.
- **Wi-Fi Setup Owner Guard**: Protects `setupwraith` / OEM onboarding packages when they own network provisioning.
- **Input Method Guard**: Protects the currently active keyboard/IME.

### Execution & Speedup
- Displays community disable/keep counts for each recommended package.
- Executes 1-tap batch `pm disable-user --user 0` with live progress updates and verifies every package immediately via `pm list packages -e --user 0`.
- Records disabled packages per TV serial for drift tracking and one-tap undo.
- **Animation Speedup**: Adjusts `window_animation_scale`, `transition_animation_scale`, and `animator_duration_scale` between `1.0x`, `0.5x`, and `0x`, saving prior values so **Restore** can revert them cleanly.

---

## 4. Check my TV & Restore

### Check my TV
- **Automatic Drift Audit**: Compares your TV's live enabled packages against your recorded cleaned packages to detect apps re-enabled by OEM or Google Play OTA updates.
- **1-Tap Re-Disable**: Re-disables drifted packages immediately with readback verification.
- **Home Launcher Verification**: Confirms your preferred launcher (`preferred-home-<serial>`) is still the active default home and offers 1-tap restoration if an update reset it.
- **ADB Security Check**: Inspects `ro.adb.secure`; flags a clear warning if set to `0` (meaning network ADB accepts commands without an on-screen authorization prompt).
- **Post-Reboot Persistence Verification**: Sends `reboot`, polls until the TV comes back online on port `5555` (auto-reconnecting the ADB session), and re-runs the full audit automatically.

### Restore
- Lists all packages disabled by A4 TV Bridge (plus any disabled packages on the TV) with one-tap individual re-enable or **Turn all apps back on**.
- Includes one-tap **Restore animation speeds** back to your pre-cleanup scales (`1.0x`).

---

## 5. Remote Control, Screen Mirror & Capture

- **Dedicated Non-Blocking Key Queue**: Remote inputs run through a dedicated coroutine `Channel` so rapid navigation never blocks or triggers UI layout jumps.
- **D-Pad & Touchpad Modes**: Switch between a high-contrast circular D-pad and a gesture touchpad, paired with Back, Home, Menu, Power, Volume +/-/Mute, and Media Prev/Play-Pause/Next keys.
- **Send Text to TV**: Types ASCII text straight into any focused TV search field or login box via ADB input, with dedicated Space, Enter, and Backspace actions.
- **Interactive Screen Mirror**: Refreshed frame mirror that translates phone taps and swipe gestures into TV pointer/key events, with quick directional buttons underneath.
- **Screenshot & Screen Record**: Saves PNG screenshots (`screencap -p`) and silent MP4 recordings (`screenrecord` with automatic bitrate fallback for older TVs) into your phone's media storage.

---

## 6. Apps, Split APKs & 1-Click App Installer

- **App Manager**: Segmented `Installed`, `Built-in`, and `Running` tabs (with `ps` fallback for Android 8–12 Toybox), instant search, Launch, Force Stop, Clear Cache, Clear Data, Disable/Enable, and Uninstall.
- **Local APK & Split APK Sideload**: Select one `.apk` or multiple split APKs (base + config/density/ABI splits) from phone storage; streams them into a staged `pm install-create` / `pm install-write` / `pm install-commit` session on the TV.
- **1-Click App Install**: Curated catalog of popular open-source/community TV apps fetched directly from their official GitHub Releases with:
  - Automatic TV CPU ABI selection (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, or universal).
  - Pre-install **SHA-256 checksum** verification against the release digest.
  - Pre-install **expected Android package ID** verification via `PackageManager.getPackageArchiveInfo`.

---

## 7. File Manager & Send Files

- **TV File Manager**: Browse `/sdcard` and subfolders on the TV, inspect file sizes/timestamps, create directories, rename or move items (`mv --` safe syntax with `readlink -f`/`pwd -P` fallback), delete with confirmation, upload from controller, or download any file to your phone.
- **Send Files**: Fast media/APK transfer shortcut targeting TV `Downloads`, `Movies`, `Music`, `Pictures`, or `Documents` under an organized `A4TVBridge/` subfolder.

---

## 8. Device Info & Live Telemetry

- **Overview**: Manufacturer, model, codename, board, Android version, API level, security patch, kernel, ABI, uptime, and storage capacity.
- **CPU**: Live total utilization, per-core frequencies, architecture, and thermal zone temperature (when exposed by the TV kernel).
- **Memory**: Live RAM used/available/total, cached buffers, and swap/ZRAM usage.
- **Network**: 1-second live Download/Upload throughput chart, active interface (`wlan0`/`eth0`), IPv4 address, Wi-Fi band, RSSI signal quality, link speed, Private DNS status, and per-app / shared-UID traffic breakdown.
- **Share Device Info**: Generates a clean plain-text hardware/network diagnostic report for Android's system share sheet.

---

## 9. Home Screen Launchers, 1080p Theme Studio & Block Ads

- **Launcher Switcher**: Enumerates real TV launchers (filtering out system fallbacks), lets you set your preferred Home launcher, and saves it for **Check my TV** drift monitoring.
- **Clean-Room 1080p Theme Studio**: Procedurally renders 4 high-contrast `1920×1080` PNG wallpapers directly on your phone (`Nordic Slate`, `Obsidian Gold`, `Deep Ocean`, `Emerald Dusk`) and transfers them to `/sdcard/Pictures/A4TVBridge/` on your TV without requiring internet access.
- **Block Ads (Private DNS)**: Configures Android `private_dns_mode` and `private_dns_specifier` (DNS-over-TLS) with presets for AdGuard DNS, NextDNS, Control D, or any custom DoT hostname, plus a one-tap reset to Automatic/Off.

---

## 10. ADB Shell, Appearance & Automatic In-App Updates

- **ADB Shell Terminal**: Run any non-interactive shell command on your connected TV with live output, copyable console log, and exit code indicator.
- **Appearance System**: Supports **System Default**, **Clean Light Mode**, and **True Black Dark Mode** with properly themed dialogs, inputs, dropdowns, and edge-to-edge bottom sheets.
- **Automatic In-App GitHub Releases Updater (`AppUpdater.kt`)**:
  - Checks `https://api.github.com/repos/RatherAsif04/A4-TV-Bridge/releases/latest` silently on startup (throttled to every 6 hours) and manually via **About → Check for updates**.
  - Compares semantic version tags (`v1.4.1`, etc.) against the installed `versionName`.
  - Streams the release APK with a live progress bar in an in-app bottom sheet.
  - Verifies the downloaded APK's **SHA-256 hash** (against `SHA256SUMS.txt` or GitHub asset `digest`) and **Android package ID** (`dev.a4tvbridge.app`) before launching Android's package installer via `FileProvider`.
  - Guides users through Android 8+ **Install unknown apps** permission and resumes installation automatically upon return.
