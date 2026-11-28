# How to Use A4 TV Bridge (v1.4.3)

This step-by-step guide walks through installing **A4 TV Bridge**, connecting your TV, running CleanYourTV automatic debloating, verifying changes after a reboot, sideloading apps, transferring files, and updating the app directly from GitHub Releases.

---

## 1. Install the Controller App

1. Download **`A4-TV-Bridge-1.4.3-release.apk`** from the [Latest GitHub Release](https://github.com/RatherAsif04/A4-TV-Bridge/releases/latest) on the Android phone or tablet (Android 8.0+) you will use as your TV controller.
2. Open the downloaded APK. If Android prompts you, grant your browser or file manager permission to install unknown apps (you can turn this back off afterward).
3. *(Recommended)* Verify the APK checksum and signature before installing — see [Verify your download](../SECURITY.md#verify-your-download).

---

## 2. Enable ADB / Wireless Debugging on Your TV

1. Connect your controller phone and your TV to the **same trusted local network** (Wi-Fi + Ethernet on the same router works fine as long as AP/client isolation is off).
2. On your TV, open **Settings → System → About** (or **Settings → Device Preferences → About**).
3. Scroll to **Android TV OS build** (or **Build**) and press **OK** 7 times until *"You are now a developer!"* appears.
4. Go back and open **Developer options**, then enable:
   - **Wireless debugging** (Android TV 11+ / Google TV), **or**
   - **Network debugging** / **USB debugging** (older Android TV and Fire TV devices).

---

## 3. Connect Your TV

Open **A4 TV Bridge** and tap **Connect your TV**, then pick the method supported by your TV:

| Method | Best For | Steps |
| --- | --- | --- |
| **Find TVs on this network** | Most modern Android TV / Google TV devices | Wait a few seconds for your TV to appear in the list, tap it, and approve **Allow debugging** on your TV screen. |
| **Pair with a code** | Android 11+ Wireless Debugging | Note the IP address and **connection port** on the main Wireless Debugging screen. On the TV, open **Pair device with pairing code** and enter the separate **pairing port** and **6-digit code** in A4 TV Bridge. |
| **Enter TV address** | Older Android TV / Fire TV / static LAN IPs | Type the TV's local IP address (e.g., `192.168.1.42`) and debugging port (commonly `5555`), then tap **Connect**. |

> **Tip:** Check **"Always allow from this computer"** on your TV's debugging prompt so A4 TV Bridge can reconnect automatically on future visits and during **Restart TV & verify**.

---

## 4. Clean Up TV (CleanYourTV Auto-Cleanup)

1. Tap **TV Care** in the bottom dock and open **Clean Up TV**.
2. A4 TV Bridge automatically reads your TV's manufacturer, brand, model, and codename and matches it against **518 bundled CleanYourTV device profiles** (you can tap **Change list** anytime to search all 518 lists manually).
3. Review the green **Safety Guards** summary — Home launchers, HDMI/AV inputs, Wi-Fi setup owners (`setupwraith`), keyboards, and core boot packages are automatically protected and cannot be disabled by mistake.
4. *(Optional)* Pick a **UI Animation Speedup** (`0.5x Snappy` or `0x Instant`) to make TV menus feel noticeably faster.
5. Review the pre-selected bloatware apps (each row shows community disable/keep counts) and tap **Turn off selected apps**. A4 TV Bridge disables them for user `0` and verifies each package live with `pm list packages -e --user 0`.

---

## 5. Check my TV & Restore

- **Check my TV** (`TV Care → Check my TV`):
  - Automatically scans your TV to verify that every cleaned app stayed off, your preferred Home launcher is still active, and `ro.adb.secure` is not exposing unauthenticated ADB.
  - If an OTA update silently re-enabled bloatware, tap **Turn off drifted apps** to clean them up in one tap.
  - Tap **Restart TV & verify** to reboot the TV, automatically wait for port `5555` reconnect, and confirm every change survives a full restart.
- **Restore** (`TV Care → Restore`):
  - Re-enable any individual disabled app or tap **Turn all apps back on**.
  - Tap **Restore animation scales** to return TV animations to their original `1.0x` setting.

---

## 6. Remote Control, Screen Mirror & Capture

Tap the raised center **Control** button in the bottom dock:

- **Remote**: Toggle between **D-Pad** and **Touchpad** modes. Use Back, Home, Menu, Volume +/-/Mute, Power, Media controls, and the **Send Text** bar to type queries or URLs straight onto your TV.
- **Screen Mirror**: Displays a refreshed view of your TV screen that responds to taps, swipes, and directional buttons.
- **Screenshot & Screen Record**: Save PNG screenshots or silent MP4 screen recordings to your controller device. *(DRM-protected video streams may appear blank due to Android platform security.)*

---

## 7. Install Apps, Split APKs & Transfer Files

Open **Apps** in the bottom dock:

- **App Manager**: Switch between **Installed**, **Built-in**, and **Running** apps to launch, force-stop, clear cache/data, disable/enable, or uninstall.
- **Install Local APKs**: Open **Install Apps → Choose APKs → Browse device**. For split APK bundles, select the base APK and all matching split APKs together.
- **1-Click App Install**: Select a supported TV app (such as SmartTube, Projectivy Launcher, Kodi, or FLauncher). A4 TV Bridge downloads the ABI-matched official GitHub release, checks its **SHA-256 hash** and **package ID**, and installs it onto your TV.
- **File Manager**: Browse `/sdcard` on the TV to upload, download, rename, move, create folders, or delete files.
- **Send Files**: Quickly push media or APKs into your TV's `Downloads`, `Movies`, `Music`, `Pictures`, or `Documents` folder (saved inside an `A4TVBridge/` subfolder).

---

## 8. Home Screen, 1080p Themes & Block Ads

- **Home & Themes** (`TV Care → Home & Themes`):
  - Switch your default TV Home launcher (e.g., Projectivy Launcher or FLauncher) in one tap — A4 TV Bridge remembers your preference for **Check my TV**.
  - Choose one of the 4 clean-room `1920×1080` themes (`Nordic Slate`, `Obsidian Gold`, `Deep Ocean`, `Emerald Dusk`) and tap **Push to TV** to generate and transfer the wallpaper on-device.
- **Block Ads (Private DNS)** (`TV Care → Block Ads`):
  - Pick an encrypted DNS-over-TLS provider (`AdGuard DNS`, `NextDNS`, `Control D`, or a custom hostname) to reduce ad and tracker requests across apps on Android 9+ TVs.

---

## 9. Monitor Device Info & Use the ADB Shell

- **Device Info**: Open from the Dashboard or TV Care to inspect **Overview**, **CPU**, **Memory**, and **Network** (live 1-second chart, Wi-Fi band/signal, and per-app traffic). Tap **Share Info** to export a clean diagnostic summary.
- **ADB Shell**: Tap **Shell** in the bottom dock, enter any non-interactive command (e.g., `getprop ro.build.version.release` or `pm list packages -3`), and tap **Run command** to view output and exit code.

---

## 10. Automatic In-App Updates

A4 TV Bridge checks `RatherAsif04/A4-TV-Bridge` GitHub Releases automatically on startup (at most once every 6 hours) and manually under **About → Check for updates**:

1. When a newer release is available, tap **Update now** (or **Install update** in **About**).
2. The app downloads the APK with a live progress bar, verifies its **SHA-256 digest** and **package ID** (`dev.a4tvbridge.app`), and opens Android's installer.
3. If Android asks for **Install unknown apps** permission for A4 TV Bridge, allow it once and press Back — the update prompt resumes automatically.

---

## Troubleshooting

- **TV does not appear in network search**: Ensure both devices are on the same subnet (disable Guest Wi-Fi / AP isolation), verify **Wireless debugging** or **Network debugging** is ON, or connect directly with **Enter TV address**.
- **Pairing code rejected**: On Android 11+, the **pairing port** shown inside the *"Pair device with pairing code"* dialog is different from the main **connection port** and changes each time you reopen the dialog.
- **Connection drops while approving prompt**: Keep the TV screen awake while tapping **Allow** on the TV prompt, and select **Always allow from this computer**.
- **Devices without ADB**: Casting-only dongles and TVs whose firmware removes ADB entirely cannot be controlled over ADB.
