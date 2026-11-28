package dev.a4tvbridge.independent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ParsersTest {
    @Test fun localSearchStaysOnItsPrivateNetworkAndSkipsLocalAndBroadcastAddresses() {
        fun ip(value: String) = java.net.InetAddress.getByName(value) as java.net.Inet4Address
        val smallNetwork = localTvCandidates(ip("192.168.24.6"), 29)
        assertEquals(listOf("192.168.24.1", "192.168.24.2", "192.168.24.3", "192.168.24.4", "192.168.24.5"), smallNetwork)
        assertEquals(253, localTvCandidates(ip("10.1.31.2"), 16).size)
        assertTrue(localTvCandidates(ip("203.0.113.10"), 24).isEmpty())
        assertTrue(localTvCandidates(ip("127.0.0.1"), 24).isEmpty())
    }

    @Test fun acceptsPortInAddressAndIpv6() {
        assertEquals(TvAddress("192.0.2.31", 5555), TvAddress.parse(" 192.0.2.31 "))
        assertEquals(TvAddress("192.0.2.31", 8844), TvAddress.parse("192.0.2.31:8844"))
        assertEquals(TvAddress("2001:db8::1", 8844), TvAddress.parse("[2001:db8::1]:8844"))
        assertEquals(TvAddress("2001:db8::1", 5555), TvAddress.parse("2001:db8::1"))
        assertNull(TvAddress.parse("192.0.2.31:99999"))
        assertNull(TvAddress.parse("http://192.0.2.31"))
    }
    @Test fun readsDeviceCountersAndComputesDelta() {
        val sample = DeviceReading.parse("""
            __cpu
            cpu 25 1 10 60 4 0 0 0
            __memory
            MemTotal:       2048 kB
            MemAvailable:   1024 kB
            __disk
            /dev/block/dm-0 10000 7000 3000 70% /data
            __network
                lo: 9999 0 0 0 0 0 0 0 9999 0
              eth0: 120 0 0 0 0 0 0 0 340 0
             wlan0: 20 0 0 0 0 0 0 0 60 0
            __uptime
            5412.42 50.00
        """.trimIndent())
        assertEquals(50, sample.ramUsage)
        assertEquals(70, sample.diskUsage)
        assertEquals(140L, sample.received)
        assertEquals(400L, sample.transmitted)
        assertEquals(5412L, sample.uptimeSeconds)
        assertEquals(50, sample.cpuUsage(DeviceReading(cpuTotal = 80, cpuIdle = 54)))
        assertNull(sample.cpuUsage(null))
        assertNull(sample.cpuUsage(DeviceReading(cpuTotal = 900, cpuIdle = 500)))
    }

    @Test fun distinguishesUnavailableCountersFromZero() {
        val sample = DeviceReading.parse("__cpu\ncpu broken\n__network\nPermission denied")
        assertNull(sample.ramUsage)
        assertNull(sample.diskUsage)
        assertNull(sample.received)
        assertNull(sample.cpuUsage(sample))
    }

    @Test fun groupsMemoryForAndroidPssAndRssFormats() {
        val pss = parseProcessMemory("""
            Total PSS by process:
               124,078K: com.example.tv (pid 1111 / activities)
                25,000K: com.example.tv:player (pid 2222)
                12,340K: system (pid 3333)

            Total PSS by OOM adjustment:
                999,999K: example.not_a_process (pid 4444)
        """.trimIndent())
        assertEquals(listOf(ProcessMemory("com.example.tv", 149078, 2),
            ProcessMemory("system", 12340, 1)), pss)
        assertEquals(listOf(ProcessMemory("com.example.tv", 111, 2)),
            parseProcessRss("RSS NAME\n0 kernel_thread\n100 com.example.tv\n11 com.example.tv:player"))
    }

    @Test fun readsPackageRowsAndCorrespondingRemarks() {
        assertEquals(listOf(CleanupItem("com.example.suggested", "Vendor recommendations")),
            cleanupEntries("# supported model\ncom.example.suggested # Vendor recommendations\n" +
                "com.example.suggested\nthis is not a package"))
    }

    @Test fun confinesFilesToSharedStorage() {
        assertEquals("/sdcard/Movies/holiday.mkv", TvPath.child("/sdcard/Movies", "holiday.mkv"))
        assertThrows(IllegalArgumentException::class.java) { TvPath.folder("/sdcard/../data") }
        assertThrows(IllegalArgumentException::class.java) { TvPath.folder("/sdcard-other") }
        assertFalse(TvPath.validName("side/path"))
        assertEquals("'name'\"'\"'s video'", shellText("name's video"))
    }

    @Test fun parsesWifiSnapshotAndAppTrafficFormats() {
        val wifi = parseWifiSnapshot("mWifiInfo SSID: \"HomeNet\", RSSI: -54, Link speed: 866Mbps, Frequency: 5180MHz")
        assertEquals("Wi-Fi", wifi.transport)
        assertEquals(866, wifi.linkSpeedMbps)
        assertEquals(5180, wifi.frequencyMhz)
        assertEquals(-54, wifi.rssiDbm)
        assertEquals("5 GHz (5180 MHz)", wifi.bandLabel)

        val eth = parseWifiSnapshot("2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500")
        assertEquals("Ethernet", eth.transport)

        val uids = parseUidPackages("package:org.smarttube.stable uid:10123\npackage:com.google.android.youtube.tv uid:10124")
        val qtaguid = parseQtaguidTraffic(
            "idx iface acct_tag_hex uid_tag_int cnt_set rx_bytes rx_packets tx_bytes tx_packets\n" +
                "1 lo 0x0 10123 0 99999 10 99999 10\n" +
                "2 wlan0 0x0 10123 0 5000 5 1000 2\n" +
                "3 wlan0 0x0 10124 0 2000 2 500 1",
            uids
        )
        assertEquals(2, qtaguid.size)
        assertEquals("Stable", qtaguid[0].label)
        assertEquals(6000L, qtaguid[0].totalBytes)

        val netstats = parseNetstatsUidTraffic(
            "ident=[{type=WIFI}] uid=10124 set=DEFAULT\n  st=1700000 rb=8000 rp=4 tb=2000 tp=2",
            uids
        )
        assertEquals(1, netstats.size)
        assertEquals("Youtube", netstats[0].label)
        assertEquals(10000L, netstats[0].totalBytes)
    }

    @Test fun resolvesAppBadgesAndThemeConfigs() {
        assertEquals("ST", AppBrandRegistry.resolve("org.smarttube.stable").monogram)
        assertEquals("JF", AppBrandRegistry.resolve("org.jellyfin.androidtv").monogram)
        assertEquals("YT", AppBrandRegistry.resolve("com.google.android.youtube.tv").monogram)
        val json = themeConfigJson(HomeThemePreset.NORDIC_SLATE)
        assertTrue(json.contains("\"nordic-slate\""))
        assertTrue(json.contains("RatherAsif04"))
    }

    @Test fun autoMatchesProfilesAndCleanYourTvSafetyGuards() {
        val profiles = mapOf(
            "xiaomi-mitv-mooq0" to parseProfileList("""
                # Xiaomi Mi TV header sentence
                com.miui.tv.analytics # Xiaomi telemetry
                com.google.android.feedback # Crash feedback
                # com.mitv.tvhome.atv # Keep PatchWall if no replacement launcher
            """.trimIndent()),
            "tcl-c755-google-tv" to parseProfileList("""
                com.google.android.feedback # Crash feedback
                com.tcl.esticker # Store demo overlay
            """.trimIndent()),
            "sony-bravia-4k-vh2" to parseProfileList("""
                com.google.android.feedback # Crash feedback
                com.sony.dtv.demosystem # Retail demo
            """.trimIndent())
        )
        val meta = mapOf(
            "xiaomi-mitv-mooq0" to ProfileMeta(
                tv = "Xiaomi Mi TV (MiTV-MOOQ0, croods; includes P1 43\" EU variant)",
                os = "Android TV 10 and 11",
                brand = "Xiaomi"
            )
        )
        val catalog = CleanupCatalog(profiles, meta)

        // Verify parseProfileList separates active vs commented packages
        assertEquals(listOf("com.miui.tv.analytics", "com.google.android.feedback"), profiles["xiaomi-mitv-mooq0"]!!.disabled.map { it.id })
        assertEquals(listOf("com.mitv.tvhome.atv"), profiles["xiaomi-mitv-mooq0"]!!.kept.map { it.id })

        // Verify tv.json codename matching ("croods") + community tally enrichment
        val exact = autoMatchCleanup(
            TvIdentity(manufacturer = "Xiaomi", brand = "Xiaomi", model = "MiTV-MOOQ0", device = "croods"),
            setOf("com.miui.tv.analytics", "com.google.android.feedback", "com.android.settings"),
            catalog
        )
        assertEquals(ProfileMatchKind.EXACT_MODEL, exact.kind)
        assertEquals("xiaomi-mitv-mooq0", exact.profileName)
        assertEquals("Android TV 10 and 11", exact.profileOs)
        assertEquals(2, exact.items.size)
        assertEquals(3, exact.items.first { it.id == "com.google.android.feedback" }.disabledCount)

        // Verify CleanYourTV match.py consensus fallback on unlisted TV
        val unknownTv = autoMatchCleanup(
            TvIdentity(manufacturer = "NewBrand", brand = "NewBrand", model = "SmartTV-9000"),
            setOf("com.google.android.feedback", "com.android.settings"),
            catalog
        )
        assertEquals(ProfileMatchKind.AUTO_DETECTED, unknownTv.kind)
        assertEquals(listOf("com.google.android.feedback"), unknownTv.items.map { it.id })

        // Verify manual profile override
        val manual = autoMatchCleanup(
            TvIdentity(manufacturer = "Xiaomi", brand = "Xiaomi", model = "MiTV-MOOQ0"),
            setOf("com.google.android.feedback", "com.tcl.esticker"),
            catalog,
            savedProfile = "tcl-c755-google-tv"
        )
        assertEquals(ProfileMatchKind.MANUAL, manual.kind)
        assertEquals("tcl-c755-google-tv", manual.profileName)
        assertEquals(2, manual.items.size)

        // Verify CleanYourTV CORE_RE + CORE_OK boot-loop guard
        assertTrue(isCoreProtectedPackage("android"))
        assertTrue(isCoreProtectedPackage("com.android.location.fused"))
        assertTrue(isCoreProtectedPackage("com.cltv.mal"))
        assertTrue(isCoreProtectedPackage("com.mediatek.tis"))
        assertTrue(isCoreProtectedPackage("com.android.providers.settings"))
        assertFalse(isCoreProtectedPackage("com.android.providers.calendar"))
        assertFalse(isCoreProtectedPackage("com.google.android.feedback"))

        // Verify CleanYourTV launcher filter
        assertFalse(isRealLauncherComponent("com.tcl.keycustomfunctionservice/.FallbackHome"))
        assertFalse(isRealLauncherComponent("com.duokan.factorytest/.FactoryLauncher"))
        assertFalse(isRealLauncherComponent("com.google.android.tungsten.setupwraith/.RecoveryActivity"))
        assertTrue(isRealLauncherComponent("com.google.android.tvlauncher/.MainActivity"))

        // Verify full CleanupProtection guards (Wi-Fi setupwraith, HDMI, Settings, Home, ro.adb.secure)
        val protection = CleanupProtection(
            activeHomePackage = "com.google.android.tvlauncher",
            realHomeLaunchers = setOf("com.google.android.tvlauncher"),
            activeKeyboardPackage = "com.google.android.inputmethod.latin",
            setupwraithOwnsWifi = true,
            wifiOwnersChecked = true,
            inputHardwarePackages = setOf("com.vendor.hdmiinput"),
            settingsHandlerPackages = setOf("com.android.tv.settings"),
            adbSecureRaw = "0"
        )
        assertTrue(protection.adbInsecureWarning)
        assertTrue(protection.isProtected(SETUPWRAITH_PACKAGE))
        assertTrue(protection.isProtected("com.google.android.tvlauncher"))
        assertTrue(protection.isProtected("com.vendor.hdmiinput"))
        assertTrue(protection.isProtected("com.android.tv.settings"))
        assertTrue(protection.isProtected("com.google.android.inputmethod.latin"))
        assertFalse(protection.isProtected("com.google.android.feedback"))

        // If a second real launcher is installed and staying enabled, disabling stock home is allowed
        val withAltLauncher = protection.copy(
            realHomeLaunchers = setOf("com.google.android.tvlauncher", "com.custom.launcher")
        )
        assertFalse(withAltLauncher.isProtected("com.google.android.tvlauncher", setOf("com.google.android.tvlauncher")))
    }

    @Test fun checksAppUpdateVersionsChecksumsAndAssetSelection() {
        assertTrue(isNewerVersion("v1.4.2", "1.4.1"))
        assertTrue(isNewerVersion("1.10.0", "1.4.1"))
        assertTrue(isNewerVersion("v2.0", "1.9.9"))
        assertFalse(isNewerVersion("v1.4.1", "1.4.1"))
        assertFalse(isNewerVersion("1.4.0", "1.4.1"))

        val sums = """
            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  A4-TV-Bridge-1.4.2-debug.apk
            bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  A4-TV-Bridge-1.4.2-release.apk
        """.trimIndent()
        assertEquals(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            parseSha256Sums(sums, "A4-TV-Bridge-1.4.2-release.apk")
        )
        assertNull(parseSha256Sums(sums, "missing.apk"))

        val base = "https://github.com/$APP_GITHUB_REPO/releases/download/v1.4.2"
        val picked = pickReleaseApkAsset(listOf(
            ReleaseAssetInfo("A4-TV-Bridge-1.4.2-release-unsigned.apk", "$base/A4-TV-Bridge-1.4.2-release-unsigned.apk", 5_000_000),
            ReleaseAssetInfo("A4-TV-Bridge-1.4.2-debug.apk", "$base/A4-TV-Bridge-1.4.2-debug.apk", 6_000_000),
            ReleaseAssetInfo("A4-TV-Bridge-1.4.2-release.apk", "$base/A4-TV-Bridge-1.4.2-release.apk", 5_200_000),
            ReleaseAssetInfo("evil.apk", "https://evil.example.com/evil.apk", 1_000_000)
        ))
        assertEquals("A4-TV-Bridge-1.4.2-release.apk", picked?.name)
    }

    @Test fun selectsOfficialProjectivyLauncherReleaseAndFormatsBranding() {
        assertEquals("spocky/miproja1", ReleaseApp.PROJECTIVY.repository)
        assertEquals("com.spocky.projengmenu", ReleaseApp.PROJECTIVY.packageName)
        assertEquals("Projectivy Launcher", displayName("com.spocky.projengmenu"))
        assertEquals("PL", AppBrandRegistry.resolve("com.spocky.projengmenu").monogram)

        val base = "https://github.com/spocky/miproja1/releases/download/4.63"
        val xdaRelease = pickOfficialReleaseAsset(
            ReleaseApp.PROJECTIVY,
            listOf(
                OfficialReleaseAsset("ProjectivyLauncher-4.63-c463-debug.apk", "$base/ProjectivyLauncher-4.63-c463-debug.apk", 12_000_000),
                OfficialReleaseAsset("ProjectivyLauncher-4.63-c463-xda-release.apk", "$base/ProjectivyLauncher-4.63-c463-xda-release.apk", 11_500_000)
            ),
            listOf("arm64-v8a", "armeabi-v7a")
        )
        assertEquals("ProjectivyLauncher-4.63-c463-xda-release.apk", xdaRelease?.name)

        val githubPreferred = pickOfficialReleaseAsset(
            ReleaseApp.PROJECTIVY,
            listOf(
                OfficialReleaseAsset("ProjectivyLauncher-4.68-c468-xda-release.apk", "$base/ProjectivyLauncher-4.68-c468-xda-release.apk", 11_500_000),
                OfficialReleaseAsset("ProjectivyLauncher-4.68-c468-github-release.apk", "$base/ProjectivyLauncher-4.68-c468-github-release.apk", 11_600_000)
            ),
            listOf("arm64-v8a")
        )
        assertEquals("ProjectivyLauncher-4.68-c468-github-release.apk", githubPreferred?.name)
    }

    @Test fun providesFourOrderedAdbGuideStepsForEveryTvBrand() {
        assertEquals(4, TvGuideBrand.entries.size)
        for (brand in TvGuideBrand.entries) {
            assertEquals(listOf(1, 2, 3, 4), brand.steps.map(AdbGuideStep::number))
            assertTrue(brand.steps.first().pathChip?.isNotBlank() == true)
            assertTrue(brand.steps[2].showToggle)
            assertTrue(brand.steps.last().finalStep)
        }
    }

    @Test fun mapsConnectionPhasesToOrderedScannerSteps() {
        assertEquals(1, activeConnectionStep(ConnectionPhase.IDLE))
        assertEquals(1, activeConnectionStep(ConnectionPhase.SCANNING))
        assertEquals(2, activeConnectionStep(ConnectionPhase.CONNECTING))
        assertEquals(3, activeConnectionStep(ConnectionPhase.AWAITING_APPROVAL))
        assertEquals(4, activeConnectionStep(ConnectionPhase.CONNECTED))
        assertEquals(2, activeConnectionStep(ConnectionPhase.ERROR))
    }

    @Test fun buildsSafetyGuidanceForBuiltInAndUserInstalledApps() {
        val voice = describeAppForSheet(InstalledPackage("com.google.android.speech.pumpkin", system = true, enabled = true))
        assertFalse(voice.riskyUninstall)
        assertTrue(voice.summary.contains("voice", ignoreCase = true))
        assertTrue(voice.warning.contains("Think first", ignoreCase = true))

        val kodi = describeAppForSheet(InstalledPackage("org.xbmc.kodi", system = false, enabled = true))
        assertTrue(kodi.riskyUninstall)
        assertTrue(kodi.summary.contains("permanent", ignoreCase = true))
        assertTrue(kodi.warning.contains("You installed this app", ignoreCase = true))

        val noted = describeAppForSheet(
            InstalledPackage("com.vendor.demo", system = true, enabled = true),
            catalogNote = "Store demo overlay"
        )
        assertEquals("Store demo overlay.", noted.summary)
    }

    @Test fun parsesProcessCpuInfoAndTopFormats() {
        val cpuInfo = parseProcessCpuInfo("""
            Load: 1.45 / 1.32 / 1.18
            CPU usage from 301234ms to 1234ms ago:
              14% 1234/com.google.android.youtube.tv: 9.2% user + 4.8% kernel
              1.5% 2345/com.google.android.youtube.tv:media: 1.0% user + 0.5% kernel
              +5.2% 567/system_server: 3.1% user + 2.1% kernel
              0% 456/adbd: 0% user + 0% kernel
            21% TOTAL: 13% user + 7.4% kernel + 0.6% iowait
        """.trimIndent())
        assertEquals(2, cpuInfo.size)
        assertEquals("com.google.android.youtube.tv", cpuInfo[0].name)
        assertEquals(15.5, cpuInfo[0].percent, 0.01)
        assertEquals(2, cpuInfo[0].processes)
        assertEquals("system_server", cpuInfo[1].name)
        assertEquals(5.2, cpuInfo[1].percent, 0.01)

        val topRows = parseTopCpu("""
            Tasks: 184 total, 1 running, 183 sleeping
             PID USER   PR NI  VIRT  RES  SHR S[%CPU] %MEM   TIME+ ARGS
            1234 u0_a55 20  0  1.2G 120M  45M S 12.5   5.8 1:23.45 com.google.android.youtube.tv
             567 system 18 -2  1.8G 180M  60M S  3.2   8.7 5:12.10 system_server
             999 shell  20  0   12M   4M   2M R  4.0   0.2 0:00.01 top
        """.trimIndent())
        assertEquals(2, topRows.size)
        assertEquals("com.google.android.youtube.tv", topRows[0].name)
        assertEquals(12.5, topRows[0].percent, 0.01)
        assertEquals("system_server", topRows[1].name)
    }

    @Test fun parsesConnectedBluetoothDevicesAndAudioOutputAcrossAndroidVersions() {
        val snapshot = parseConnectedDevices(
            btBonded = """
                Bonded devices:
                  AA:BB:CC:DD:EE:01 [ LE ] JIO BLE Remote
                  11:22:33:44:55:02 [BR/EDR] Mi 10
                  77:88:99:AA:BB:03 [ DUAL ] Galaxy Buds Pro (E803)

                Scan Mode: SCAN_MODE_CONNECTABLE
            """.trimIndent(),
            btConn = """
                mInputDevices:
                  AA:BB:CC:DD:EE:01: 2
                mConnectionState: 0
            """.trimIndent(),
            audioMusic = """
                - STREAM_MUSIC:
                   Muted: false
                   Devices: speaker
            """.trimIndent(),
            audioOut = ""
        )
        assertEquals("TV speakers", snapshot.audioOutput)
        assertEquals(3, snapshot.bluetooth.size)
        assertEquals("JIO BLE Remote", snapshot.bluetooth[0].name)
        assertTrue(snapshot.bluetooth[0].connected)
        assertEquals("BLE", snapshot.bluetooth[0].transport)
        assertEquals(BluetoothDeviceKind.REMOTE, snapshot.bluetooth[0].kind)

        val mi10 = snapshot.bluetooth.first { it.name == "Mi 10" }
        assertFalse(mi10.connected)
        assertEquals(BluetoothDeviceKind.PHONE, mi10.kind)

        val buds = snapshot.bluetooth.first { it.name.contains("Galaxy Buds") }
        assertFalse(buds.connected)
        assertEquals(BluetoothDeviceKind.AUDIO, buds.kind)

        val arcAndA14 = parseConnectedDevices(
            btBonded = """
                Bonded devices:
                  XX:XX:XX:XX:EE:FF [ LE ] Sony TV Remote (connected)
            """.trimIndent(),
            btConn = "",
            audioMusic = "- STREAM_MUSIC:\n  Devices: hdmi_arc",
            audioOut = ""
        )
        assertEquals("HDMI ARC", arcAndA14.audioOutput)
        assertEquals(1, arcAndA14.bluetooth.size)
        assertTrue(arcAndA14.bluetooth[0].connected)
        assertEquals("Sony TV Remote", arcAndA14.bluetooth[0].name)

        val btAudio = parseConnectedDevices(
            btBonded = "",
            btConn = "",
            audioMusic = "- STREAM_MUSIC:\n  Devices: bt_a2dp, speaker",
            audioOut = ""
        )
        assertEquals("Bluetooth audio", btAudio.audioOutput)
    }

    @Test fun classifiesConnectedDeviceCategoriesForDistinctIcons() {
        assertEquals(BluetoothDeviceKind.TV, classifyBluetoothDevice("Sony BRAVIA 4K TV"))
        assertEquals(BluetoothDeviceKind.TV, classifyBluetoothDevice("LG OLED C3"))
        assertEquals(BluetoothDeviceKind.STREAMING_STICK, classifyBluetoothDevice("Fire TV Stick 4K"))
        assertEquals(BluetoothDeviceKind.STREAMING_STICK, classifyBluetoothDevice("Chromecast with Google TV"))
        assertEquals(BluetoothDeviceKind.STREAMING_STICK, classifyBluetoothDevice("Mi TV Stick"))
        assertEquals(BluetoothDeviceKind.PHONE, classifyBluetoothDevice("Mi 10"))
        assertEquals(BluetoothDeviceKind.PHONE, classifyBluetoothDevice("Pixel 9 Pro"))
        assertEquals(BluetoothDeviceKind.PHONE, classifyBluetoothDevice("iPhone 16"))
        assertEquals(BluetoothDeviceKind.REMOTE, classifyBluetoothDevice("Fire TV Remote"))
        assertEquals(BluetoothDeviceKind.REMOTE, classifyBluetoothDevice("JIO BLE Remote"))
        assertEquals(BluetoothDeviceKind.GAMEPAD, classifyBluetoothDevice("Xbox Wireless Controller"))
        assertEquals(BluetoothDeviceKind.AUDIO, classifyBluetoothDevice("Galaxy Buds Pro (E803)"))
        assertEquals(BluetoothDeviceKind.SPEAKER, classifyBluetoothDevice("JBL Bar 500 Soundbar"))
        assertEquals(BluetoothDeviceKind.KEYBOARD, classifyBluetoothDevice("Logitech K400 Keyboard"))
        assertEquals(BluetoothDeviceKind.LAPTOP, classifyBluetoothDevice("MacBook Pro"))
        assertEquals(BluetoothDeviceKind.GENERIC, classifyBluetoothDevice("HC-05 Module"))
    }

    @Test fun identifiesKillableNonSystemUserProcessesOnly() {
        val userApps = setOf(
            "org.smarttube.stable",
            "org.jellyfin.androidtv",
            "com.android.providers.settings" // even if spoofed, core guard blocks it
        )
        assertTrue(isKillableUserProcess("org.smarttube.stable", userApps))
        assertTrue(isKillableUserProcess("org.smarttube.stable:player", userApps))
        assertTrue(isKillableUserProcess("org.jellyfin.androidtv", userApps))
        assertFalse(isKillableUserProcess("com.android.systemui", userApps))
        assertFalse(isKillableUserProcess("com.google.android.tvlauncher", userApps))
        assertFalse(isKillableUserProcess("system_server", userApps))
        assertFalse(isKillableUserProcess("surfaceflinger", userApps))
        assertFalse(isKillableUserProcess("com.android.providers.settings", userApps))
    }

    @Test fun parsesApkPathsAndExtractsOriginalIconFromRemoteZipApk() {
        val paths = parsePackageApkPaths("""
            package:/data/app/~~abc==/app.netmirror.newtv-xyz==/base.apk=app.netmirror.newtv
            package:/data/app/~~def==/com.amazon.amazonvideo.livingroom-123==/base.apk=com.amazon.amazonvideo.livingroom
            package:/system/priv-app/TvSettings/TvSettings.apk=com.android.tv.settings
        """.trimIndent())
        assertEquals(3, paths.size)
        assertEquals("/data/app/~~abc==/app.netmirror.newtv-xyz==/base.apk", paths["app.netmirror.newtv"])
        assertEquals("/system/priv-app/TvSettings/TvSettings.apk", paths["com.android.tv.settings"])

        // Build a genuine in-memory ZIP archive mimicking an Android TV APK
        val fakeIconPayload = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(640) { (it % 251).toByte() }
        val fakeSmallNotif = ByteArray(400) { 0x11 }
        val fakeBanner = ByteArray(500) { 0x22 }
        val zipBytes = java.io.ByteArrayOutputStream().apply {
            java.util.zip.ZipOutputStream(this).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("res/drawable-xhdpi-v4/ic_stat_notification.png"))
                zos.write(fakeSmallNotif)
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("res/drawable-xhdpi-v4/tv_banner.png"))
                zos.write(fakeBanner)
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("res/mipmap-xxhdpi-v4/ic_launcher.png"))
                zos.write(fakeIconPayload)
                zos.closeEntry()
            }
        }.toByteArray()

        val eocd = parseZipEocd(zipBytes)!!
        assertEquals(3, eocd.entryCount)
        val cdOffset = findEocdOffset(zipBytes) - eocd.centralDirSize.toInt()
        val entries = parseZipCentralDirectory(zipBytes, cdOffset, eocd.centralDirSize)
        assertEquals(3, entries.size)

        val picked = pickIconEntryByName(entries)!!
        assertEquals("res/mipmap-xxhdpi-v4/ic_launcher.png", picked.name)

        val slice = zipBytes.copyOfRange(picked.localHeaderOffset.toInt(), zipBytes.size)
        val extracted = extractZipEntryPayload(slice, picked)!!
        assertTrue(fakeIconPayload.contentEquals(extracted))
    }
}
