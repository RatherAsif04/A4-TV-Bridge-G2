package dev.a4tvbridge.independent

internal data class DeviceReading(
    val cpuTotal: Long? = null,
    val cpuIdle: Long? = null,
    val ramTotal: Long? = null,
    val ramAvailable: Long? = null,
    val diskTotal: Long? = null,
    val diskFree: Long? = null,
    val received: Long? = null,
    val transmitted: Long? = null,
    val uptimeSeconds: Long? = null,
    val ramFree: Long? = null,
    val swapTotal: Long? = null,
    val swapFree: Long? = null,
    val cpuCores: List<CpuCounter> = emptyList(),
) {
    val ramUsage get() = percentUsed(ramTotal, ramAvailable)
    val diskUsage get() = percentUsed(diskTotal, diskFree)

    fun cpuUsage(previous: DeviceReading?): Int? {
        val total = cpuTotal ?: return null
        val idle = cpuIdle ?: return null
        val oldTotal = previous?.cpuTotal ?: return null
        val oldIdle = previous.cpuIdle ?: return null
        val delta = total - oldTotal
        val idleDelta = idle - oldIdle
        return percentUsed(delta, idleDelta.takeIf { it in 0..delta })
    }

    companion object {
        fun parse(text: String): DeviceReading {
            val sections = mutableMapOf<String, MutableList<String>>()
            var name = ""
            for (line in text.lineSequence()) {
                if (line.startsWith("__")) name = line
                else if (name.isNotEmpty()) sections.getOrPut(name) { mutableListOf() }.add(line.trim())
            }
            val cpus = sections["__cpu"].orEmpty().mapNotNull { row ->
                val parts = row.split(Regex("\\s+"))
                val label = parts.firstOrNull()?.takeIf { it.matches(Regex("cpu[0-9]*")) } ?: return@mapNotNull null
                val numbers = parts.drop(1).take(8).mapNotNull(String::toLongOrNull)
                if (numbers.size < 4) return@mapNotNull null
                CpuCounter(label, numbers.sum(), numbers[3] + numbers.getOrElse(4) { 0 })
            }
            val cpu = cpus.firstOrNull { it.label == "cpu" }
            val memory = sections["__memory"].orEmpty().associate { item ->
                item.substringBefore(':') to item.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
            }
            val disk = sections["__disk"].orEmpty().firstOrNull { it.endsWith(" /data") }?.split(Regex("\\s+"))
            val diskTotalKib = disk?.let { if (it.size >= 5) it[it.size - 5].toLongOrNull() else null }
            val diskFreeKib = disk?.let { if (it.size >= 5) it[it.size - 3].toLongOrNull() else null }
            val traffic = sections["__network"].orEmpty().mapNotNull { item ->
                if (':' !in item || item.startsWith("lo:")) return@mapNotNull null
                val counters = item.substringAfter(':').trim().split(Regex("\\s+"))
                val receive = counters.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val send = counters.getOrNull(8)?.toLongOrNull() ?: return@mapNotNull null
                receive to send
            }.takeIf { it.isNotEmpty() }
            return DeviceReading(
                cpuTotal = cpu?.total,
                cpuIdle = cpu?.idle,
                ramTotal = memory["MemTotal"]?.times(1024),
                ramAvailable = (memory["MemAvailable"] ?: memory["MemFree"])?.times(1024),
                diskTotal = diskTotalKib?.times(1024),
                diskFree = diskFreeKib?.times(1024),
                received = traffic?.sumOf { it.first },
                transmitted = traffic?.sumOf { it.second },
                uptimeSeconds = sections["__uptime"]?.firstOrNull()?.substringBefore(' ')?.toDoubleOrNull()?.toLong(),
                ramFree = memory["MemFree"]?.times(1024),
                swapTotal = memory["SwapTotal"]?.times(1024),
                swapFree = memory["SwapFree"]?.times(1024),
                cpuCores = cpus.filter { it.label != "cpu" },
            )
        }
    }
}

internal data class CpuCounter(val label: String, val total: Long, val idle: Long) {
    fun change(previous: CpuCounter?): Int? = previous?.let {
        val span = total - it.total
        percentUsed(span, (idle - it.idle).takeIf { value -> value in 0..span })
    }
}

private fun percentUsed(total: Long?, free: Long?): Int? =
    if (total == null || free == null || total <= 0) null
    else ((total - free).coerceIn(0L, total) * 100.0 / total).toInt()

internal data class MemoryBreakdown(val method: String, val rows: List<ProcessMemory>)

internal data class ProcessMemory(val name: String, val kib: Long, val processes: Int)

internal fun parseProcessMemory(input: String): List<ProcessMemory> {
    val realRows = input.lineSequence().dropWhile { !it.contains("Total PSS by process:", true) }
        .drop(1).dropWhile(String::isBlank).takeWhile { it.isNotBlank() && !it.contains("Total PSS by", true) }
    val pattern = Regex("""^\s*([\d,]+)\s*[kK]\s*:\s*(\S+)\s+\(pid\s+\d+.*""")
    return groupProcessMemory(realRows.mapNotNull { line ->
        val record = pattern.matchEntire(line) ?: return@mapNotNull null
        val size = record.groupValues[1].replace(",", "").toLongOrNull() ?: return@mapNotNull null
        record.groupValues[2] to size
    })
}

internal fun parseProcessRss(input: String): List<ProcessMemory> = groupProcessMemory(
    input.lineSequence().mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"), limit = 2)
        val size = fields.first().toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val process = fields.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        process to size
    }
)

private fun groupProcessMemory(entries: Sequence<Pair<String, Long>>): List<ProcessMemory> =
    entries.groupBy { it.first.substringBefore(':') }.map { (group, rows) ->
        ProcessMemory(group, rows.sumOf { it.second }, rows.size)
    }.sortedByDescending(ProcessMemory::kib)

internal fun isKillableUserProcess(processName: String, userPackages: Set<String>): Boolean {
    val pkg = processName.substringBefore(':').trim()
    if (!pkg.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) return false
    return pkg in userPackages && !isCoreProtectedPackage(pkg)
}

internal data class WifiSnapshot(
    val transport: String,
    val linkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val rssiDbm: Int? = null
) {
    val bandLabel: String?
        get() = frequencyMhz?.let {
            when {
                it in 2400..2500 -> "2.4 GHz ($it MHz)"
                it in 4900..5900 -> "5 GHz ($it MHz)"
                it in 5925..7125 -> "6 GHz ($it MHz)"
                else -> "$it MHz"
            }
        }
}

internal data class AppTraffic(val label: String, val rxBytes: Long, val txBytes: Long) {
    val totalBytes get() = rxBytes + txBytes
}

internal fun parseWifiSnapshot(input: String): WifiSnapshot {
    val hasEth = Regex("""\beth\d+\b.*\bUP\b|\beth\d+.*LOWER_UP""").containsMatchIn(input)
    val speed = Regex("""(?:Link speed|linkSpeed|txLinkSpeedMbps)[:=\s]+(\d+)""", RegexOption.IGNORE_CASE)
        .find(input)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    val freq = Regex("""(?:Frequency|freq)[:=\s]+(\d{4})""", RegexOption.IGNORE_CASE)
        .find(input)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 2400..7200 }
    val rssi = Regex("""(?:RSSI|rssi)[:=\s]+(-\d+)""", RegexOption.IGNORE_CASE)
        .find(input)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in -120..-1 }
    val transport = when {
        freq != null || speed != null || rssi != null -> "Wi-Fi"
        hasEth -> "Ethernet"
        else -> "Local network"
    }
    return WifiSnapshot(transport, speed, freq, rssi)
}

internal fun parseUidPackages(input: String): Map<Int, List<String>> {
    val map = mutableMapOf<Int, MutableList<String>>()
    val pattern = Regex("""^package:([A-Za-z0-9_.]+)\s+uid:(\d+)$""")
    for (line in input.lineSequence()) {
        val match = pattern.matchEntire(line.trim()) ?: continue
        val pkg = match.groupValues[1]
        val uid = match.groupValues[2].toIntOrNull() ?: continue
        map.getOrPut(uid) { mutableListOf() }.add(pkg)
    }
    return map
}

internal fun parseQtaguidTraffic(input: String, uidPackages: Map<Int, List<String>>): List<AppTraffic> {
    val totals = mutableMapOf<Int, Pair<Long, Long>>()
    for (line in input.lineSequence()) {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 8 || parts[0] == "idx") continue
        if (parts[1] == "lo") continue
        val uid = parts[3].toIntOrNull() ?: continue
        val rx = parts[5].toLongOrNull() ?: continue
        val tx = parts[7].toLongOrNull() ?: continue
        val prev = totals[uid] ?: (0L to 0L)
        totals[uid] = (prev.first + rx) to (prev.second + tx)
    }
    return summarizeUidTraffic(totals, uidPackages)
}

internal fun parseNetstatsUidTraffic(input: String, uidPackages: Map<Int, List<String>>): List<AppTraffic> {
    val totals = mutableMapOf<Int, Pair<Long, Long>>()
    var currentUid: Int? = null
    val uidRegex = Regex("""\buid=(\d+)\b""")
    val rbRegex = Regex("""\brb=(\d+)\b""")
    val tbRegex = Regex("""\btb=(\d+)\b""")
    for (line in input.lineSequence()) {
        val uidMatch = uidRegex.find(line)
        if (uidMatch != null) {
            currentUid = uidMatch.groupValues[1].toIntOrNull()
        }
        val uid = currentUid ?: continue
        val rb = rbRegex.find(line)?.groupValues?.get(1)?.toLongOrNull()
        val tb = tbRegex.find(line)?.groupValues?.get(1)?.toLongOrNull()
        if (rb != null || tb != null) {
            val prev = totals[uid] ?: (0L to 0L)
            totals[uid] = (prev.first + (rb ?: 0L)) to (prev.second + (tb ?: 0L))
        }
    }
    return summarizeUidTraffic(totals, uidPackages)
}

private fun summarizeUidTraffic(
    totals: Map<Int, Pair<Long, Long>>,
    uidPackages: Map<Int, List<String>>
): List<AppTraffic> = totals.entries
    .filter { it.value.first + it.value.second > 0L }
    .map { (uid, pair) ->
        val packages = uidPackages[uid].orEmpty()
        val label = when {
            packages.size == 1 -> displayName(packages.first())
            packages.size > 1 -> "${displayName(packages.first())} (+${packages.size - 1} shared UID)"
            uid == 0 -> "Android OS (root)"
            uid == 1000 -> "Android System"
            uid == 1013 -> "Media Server"
            else -> "UID $uid"
        }
        AppTraffic(label, pair.first, pair.second)
    }
    .sortedByDescending(AppTraffic::totalBytes)

internal data class CpuBreakdown(val method: String, val rows: List<ProcessCpu>)

internal data class ProcessCpu(val name: String, val percent: Double, val processes: Int)

internal fun parseProcessCpuInfo(input: String): List<ProcessCpu> {
    val pattern = Regex("""^\s*\+?(\d+(?:\.\d+)?)%\s+\d+/(\S+?):\s+.*""")
    return groupProcessCpu(
        input.lineSequence().mapNotNull { line ->
            val match = pattern.matchEntire(line) ?: return@mapNotNull null
            val pct = match.groupValues[1].toDoubleOrNull()?.takeIf { it > 0.0 } ?: return@mapNotNull null
            val name = match.groupValues[2].trim()
            if (name.isBlank() || name.equals("TOTAL", true)) return@mapNotNull null
            name to pct
        }
    )
}

internal fun parseTopCpu(input: String): List<ProcessCpu> {
    var cpuIndex = -1
    var argsIndex = -1
    val rows = mutableListOf<Pair<String, Double>>()
    for (raw in input.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (cpuIndex < 0) {
            val cleaned = line.replace("[", " ").replace("]", " ")
            val cols = cleaned.split(Regex("\\s+"))
            val ci = cols.indexOfFirst { it.equals("%CPU", true) || it.equals("CPU%", true) }
            if (ci >= 0) {
                cpuIndex = ci
                argsIndex = cols.indexOfLast {
                    it.equals("ARGS", true) || it.equals("NAME", true) || it.equals("CMD", true) || it.equals("COMMAND", true)
                }.takeIf { it >= 0 } ?: (cols.size - 1)
            }
            continue
        }
        val parts = line.split(Regex("\\s+"))
        if (parts.size <= maxOf(cpuIndex, argsIndex)) continue
        val pct = parts[cpuIndex].removeSuffix("%").toDoubleOrNull()?.takeIf { it > 0.0 } ?: continue
        val cmd = parts[argsIndex].substringAfterLast('/').trim()
        if (cmd.isBlank() || cmd == "top" || cmd == "sh" || cmd == "dumpsys") continue
        rows += cmd to pct
    }
    return groupProcessCpu(rows.asSequence())
}

private fun groupProcessCpu(entries: Sequence<Pair<String, Double>>): List<ProcessCpu> =
    entries.groupBy { it.first.substringBefore(':') }.map { (group, rows) ->
        ProcessCpu(group, rows.sumOf { it.second }, rows.size)
    }.sortedByDescending(ProcessCpu::percent)

internal enum class BluetoothDeviceKind(val label: String) {
    REMOTE("Remote"),
    GAMEPAD("Gamepad"),
    KEYBOARD("Input"),
    AUDIO("Audio"),
    SPEAKER("Speaker"),
    STREAMING_STICK("Streaming stick"),
    TV("TV"),
    PHONE("Phone"),
    LAPTOP("Computer"),
    GENERIC("Device")
}

internal data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val transport: String,
    val connected: Boolean,
    val kind: BluetoothDeviceKind
)

internal data class ConnectedDevicesSnapshot(
    val bluetooth: List<BluetoothDeviceInfo>,
    val audioOutput: String
)

internal fun classifyBluetoothDevice(name: String): BluetoothDeviceKind {
    val n = name.lowercase()
    return when {
        listOf("remote", "rcu", "airmouse", "air mouse").any { it in n } -> BluetoothDeviceKind.REMOTE
        listOf("gamepad", "joystick", "controller", "xbox", "dualsense", "dualshock",
            "playstation", "nintendo", "joy-con", "8bitdo", "stadia")
            .any { it in n } -> BluetoothDeviceKind.GAMEPAD
        listOf("keyboard", "keypad", "mouse", "trackpad", "logitech k", "mx keys", "mx master")
            .any { it in n } -> BluetoothDeviceKind.KEYBOARD
        listOf("buds", "pods", "airpod", "headphone", "headset", "earbud", "earphone",
            "wh-", "wf-", "beats", "anc")
            .any { it in n } -> BluetoothDeviceKind.AUDIO
        listOf("soundbar", "speaker", "subwoofer", "jbl", "bose", "sonos", "homepod",
            "echo dot", "echo studio", "nest audio", "marshall", "harman")
            .any { it in n } -> BluetoothDeviceKind.SPEAKER
        listOf("fire tv", "firestick", "fire stick", "chromecast", "streaming stick", "tv stick",
            "mi stick", "mi box", "shield", "roku", "apple tv", "onn.", "onn 4k", "dongle",
            "set-top", "set top", "xstream", "jiofiber")
            .any { it in n } || Regex("""\bstb\b""").containsMatchIn(n) -> BluetoothDeviceKind.STREAMING_STICK
        listOf("smart tv", "android tv", "google tv", "bravia", "oled", "qled", "mi tv",
            "patchwall", "projector", "monitor", "display")
            .any { it in n } || Regex("""\btv\b""").containsMatchIn(n) -> BluetoothDeviceKind.TV
        listOf("macbook", "laptop", "thinkpad", "surface", "chromebook", "imac", "desktop",
            "ultrabook", "zenbook")
            .any { it in n } || Regex("""\b(pc|xps)\b""").containsMatchIn(n) -> BluetoothDeviceKind.LAPTOP
        listOf("iphone", "phone", "pixel", "galaxy", "redmi", "poco", "oneplus", "oppo", "vivo",
            "realme", "nothing", "moto", "xperia", "huawei", "honor", "ipad", "tablet", "samsung", "xiaomi")
            .any { it in n } || Regex("""\bmi[\s-]?\d+""").containsMatchIn(n) -> BluetoothDeviceKind.PHONE
        else -> BluetoothDeviceKind.GENERIC
    }
}

internal fun parseConnectedDevices(
    btBonded: String,
    btConn: String,
    audioMusic: String,
    audioOut: String
): ConnectedDevicesSnapshot {
    val macPattern = Regex("""((?:[0-9A-Fa-f]{2}|XX):(?:[0-9A-Fa-f]{2}|XX):(?:[0-9A-Fa-f]{2}|XX):(?:[0-9A-Fa-f]{2}|XX):[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2})""")
    val connectedMacs = mutableSetOf<String>()
    val connectedTails = mutableSetOf<String>()
    var inConnectedBlock = false
    for (raw in btConn.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) {
            inConnectedBlock = false
            continue
        }
        val lower = line.lowercase()
        if ("mInputDevices" in line || "connected devices" in lower) {
            inConnectedBlock = true
        }
        val mac = macPattern.find(line)?.groupValues?.get(1)?.uppercase()
        if (mac != null) {
            val isExplicitlyConnected =
                "state_connected" in lower ||
                    "state=connected" in lower ||
                    "(connected)" in lower ||
                    "connected: true" in lower ||
                    Regex("""mconnectionstate\s*[:=]\s*2\b""").containsMatchIn(lower) ||
                    (inConnectedBlock && (Regex(""":\s*2\b""").containsMatchIn(line) || "connected" in lower)) ||
                    ("active device" in lower && "null" !in lower)
            if (isExplicitlyConnected) {
                connectedMacs += mac
                connectedTails += mac.takeLast(5)
            }
        } else if (line.endsWith(":") && "mInputDevices" !in line && "connected devices" !in lower) {
            inConnectedBlock = false
        }
    }

    val seenMacs = mutableSetOf<String>()
    val devices = mutableListOf<BluetoothDeviceInfo>()
    val lines = btBonded.lines()
    val headerIdx = lines.indexOfFirst { it.contains("Bonded devices", ignoreCase = true) }
    val scanLines = if (headerIdx >= 0) lines.drop(headerIdx + 1) else lines
    for (raw in scanLines) {
        val line = raw.trim()
        if (line.isEmpty()) {
            if (devices.isNotEmpty()) break else continue
        }
        val macMatch = macPattern.find(line)
        if (macMatch == null) {
            if (devices.isNotEmpty()) break else continue
        }
        val mac = macMatch.groupValues[1].uppercase()
        if (!seenMacs.add(mac)) continue

        var rest = line.substring(macMatch.range.last + 1).trim()
        var transport = "Bluetooth"
        val transportMatch = Regex("""^[\[(]\s*([^\]\)]+?)\s*[\])]\s*(.*)$""").matchEntire(rest)
        if (transportMatch != null) {
            val rawTransport = transportMatch.groupValues[1].trim().uppercase()
            transport = when {
                rawTransport == "LE" || rawTransport == "BLE" -> "BLE"
                rawTransport == "DUAL" -> "Dual"
                rawTransport.contains("BR") || rawTransport.contains("EDR") -> "Bluetooth"
                else -> "Bluetooth"
            }
            rest = transportMatch.groupValues[2].trim()
        }
        var inlineConnected = false
        if (rest.endsWith("(connected)", ignoreCase = true)) {
            inlineConnected = true
            rest = rest.dropLast("(connected)".length).trim()
        } else if (rest.endsWith("(disconnected)", ignoreCase = true)) {
            rest = rest.dropLast("(disconnected)".length).trim()
        }
        val cleanName = rest.ifBlank { mac }
        val isConnected = inlineConnected || mac in connectedMacs ||
            (mac.startsWith("XX:") && mac.takeLast(5) in connectedTails)
        devices += BluetoothDeviceInfo(
            name = cleanName,
            address = mac,
            transport = transport,
            connected = isConnected,
            kind = classifyBluetoothDevice(cleanName)
        )
    }

    val sortedDevices = devices.sortedByDescending { it.connected }
    val audioOutput = resolveAudioOutput(audioMusic, audioOut)
    return ConnectedDevicesSnapshot(sortedDevices, audioOutput)
}

private fun resolveAudioOutput(audioMusic: String, audioOut: String): String {
    val musicDeviceLine = audioMusic.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("Devices:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    val musicCurrentLine = audioMusic.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("Current:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    val candidates = listOfNotNull(musicDeviceLine, musicCurrentLine, audioOut.takeIf { it.isNotBlank() })
    for (candidate in candidates) {
        val mapped = mapAudioRouteLabel(candidate)
        if (mapped != null) return mapped
    }
    return if (audioMusic.isNotBlank() || audioOut.isNotBlank()) "TV speakers" else "Unavailable"
}

private fun mapAudioRouteLabel(raw: String): String? {
    val s = raw.lowercase()
    return when {
        "bt_a2dp" in s || "bluetooth_a2dp" in s || "ble_headset" in s || "ble_speaker" in s || "a2dp" in s -> "Bluetooth audio"
        "hdmi_earc" in s || "earc" in s -> "HDMI eARC"
        "hdmi_arc" in s -> "HDMI ARC"
        "hdmi" in s || "aux_digital" in s -> "HDMI audio"
        "spdif" in s || "optical" in s -> "Optical / S/PDIF"
        "usb_headset" in s || "usb_device" in s || "usb_accessory" in s -> "USB audio"
        "wired_headset" in s || "wired_headphone" in s || Regex("""\bline\b""").containsMatchIn(s) -> "Wired audio"
        "speaker" in s -> "TV speakers"
        else -> null
    }
}


