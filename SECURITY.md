# Security Policy

## Safe Usage Guidelines

Use **A4 TV Bridge** only with TVs you own or administer. ADB grants Android shell access on the connected television:

- Keep Wireless Debugging / Network ADB enabled only on a **trusted local network**.
- Approve only debugging prompts you recognize, and disable debugging or revoke authorizations when you finish.
- Prefer **Android 11+ Wireless Debugging with TLS pairing** over legacy unencrypted port `5555` ADB whenever your TV supports it.
- Watch for the **`ro.adb.secure=0` warning** inside **TV Care → Check my TV**: if your TV exposes unauthenticated ADB on the LAN, turn off Network ADB whenever you are not actively using it.

## Privacy & Local-Only Operation

- A4 TV Bridge stores its ADB RSA keypair, saved TV list, and preferences strictly in Android app-private storage.
- LAN discovery runs only when you ask to find TVs.
- Remote commands, screen captures, file transfers, and live telemetry travel **directly between your controller and TV** with zero cloud relays or analytics.
- **Clean Up TV Safety Guards** automatically block disabling boot-critical Android packages (`CORE_RE`), active Home launchers, HDMI/AV `TvInputService` packages, Wi-Fi setup owners (`setupwraith`), Settings, and active keyboards.
- **1-Click App Install** and the **In-App GitHub Releases Updater** contact GitHub's official HTTPS API and verify both the published **SHA-256 checksum** and the **expected Android package ID** before installing or pushing any APK.

---

## Verify Your Download

Official releases use Android package ID **`dev.a4tvbridge.app`**.

1. Download **`A4-TV-Bridge-1.4.3-release.apk`** and **`SHA256SUMS.txt`** from the same [GitHub Release](https://github.com/RatherAsif04/A4-TV-Bridge/releases/latest).
2. Verify the file checksum and inspect the APK signing certificate:

```sh
sha256sum -c SHA256SUMS.txt --ignore-missing
apksigner verify --print-certs A4-TV-Bridge-1.4.3-release.apk
```

When upgrading an existing installation, Android also enforces that the new release APK is signed by the exact same signing key before allowing an in-place update.

---

## Reporting a Vulnerability

If GitHub displays **Security → Report a vulnerability** on `RatherAsif04/A4-TV-Bridge`, please use it to submit a private disclosure. Otherwise, send a private message to the developer on X: [@ratherasif04](https://x.com/ratherasif04).

Please include:
- A4 TV Bridge version (found in **About**)
- Controller Android version and TV model / Android TV version
- Clear steps to reproduce

Before sharing logs or screenshots, redact any pairing codes, private IP addresses, Wi-Fi SSIDs, or personal screen contents.
