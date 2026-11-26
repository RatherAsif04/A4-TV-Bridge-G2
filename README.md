# A4 TV Bridge G2 (v1.0.1 — Fixed Android APK)

A4 TV Bridge turns an Android phone or tablet into a local-network ADB controller for Android TV, Google TV, and compatible Fire TV devices.

Repository: [RatherAsif04/A4-TV-Bridge-G2](https://github.com/RatherAsif04/A4-TV-Bridge-G2)

## Automated APK Builds

Every push to `main` automatically triggers **GitHub Actions** (`.github/workflows/build-apk.yml`) on JDK 21, Android SDK Platform 37.0, and Gradle 9.3.1 to:
1. Run `:app:testDebugUnitTest`
2. Assemble `A4-TV-Bridge-1.0.1-debug.apk` and `A4-TV-Bridge-1.0.1-release.apk` (automatically aligned with `zipalign` and signed/verified with `apksigner` when the repository signing secrets `SIGNING_KEY_BASE64`, `SIGNING_KEY_ALIAS`, `SIGNING_STORE_PASSWORD`, and `SIGNING_KEY_PASSWORD` are configured; otherwise outputs `A4-TV-Bridge-1.0.1-release-unsigned.apk`)
3. Upload APK artifacts & SHA-256 checksums to the **Actions** tab and automatically publish the **v1.0.1 GitHub Release**.

## Summary of Theme & Feature Fixes in v1.0.1

- **Dark Mode & Dialog Contrast**: Updated `styles.xml` (`BridgeDialogDark`/`BridgeDialogLight`), synced `window.setBackgroundDrawable`, wrapped dialog `EditText` inputs in `ContextThemeWrapper`, and themed the `Clean Up TV` `AutoCompleteTextView` dropdown.
- **Navigation & Bottom Sheets**: Included `Panel.SHELL` in `primaryPanels` so the bottom dock stays anchored in ADB Shell, fixed bottom-sheet top-only corner radii + gesture navigation bar insets, and refined Appearance & Dashboard metric contrast.
- **Apps & Remote UI**: Added active segmented tab states for `Installed` / `Built-in` / `Running`, dimmed inactive D-pad/Touchpad labels, and adapted the Remote D-pad gradient for True Black Dark Mode.
- **Non-Blocking Remote Queue**: Replaced global `activeOperation` locking on D-pad/Mirror inputs with a dedicated coroutine `Channel` queue to eliminate dropped keys and `"Working…"` layout bounce.
- **Android 8–12 Toybox & ADB Compatibility**: Replaced `mv -n` with safe `mv --`, added `readlink -f`/`pwd -P` fallback for `realpath`, added `df -Pk` right-anchored storage parsing, added `ps` fallback for Running apps, added `screenrecord` bitrate fallback, and prevented `protectForCleanup` false positives.
- **1-Click App Install & State Preservation**: Added GitHub API `User-Agent` & `Accept` headers, trimmed ABI strings, fixed Connect sheet discovery deadlock, and added `android:configChanges` so rotation/theme switches keep the ADB connection alive.

## Build Android APK Locally

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

## Licenses

Original source under [MIT](LICENSE); complete APK distributed under [GPL-3.0](COPYING). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
