package dev.a4tvbridge.independent

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import okio.Path.Companion.toOkioPath

internal data class TvAddress(val host: String, val port: Int) {
    init {
        require(host.matches(Regex("[0-9A-Za-z.:%_-]+")) && port in 1..65535) { "Enter a TV address and port." }
    }

    override fun toString() = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        fun parse(input: String, fallbackPort: Int = 5555): TvAddress? {
            val text = input.trim()
            val address = Regex("""^\[([^\]]+)](?::([0-9]+))?$""").matchEntire(text)
            val (host, port) = when {
                address != null -> address.groupValues[1] to address.groupValues[2]
                text.count { it == ':' } == 1 -> text.substringBefore(':') to text.substringAfter(':')
                else -> text to ""
            }
            return runCatching {
                TvAddress(host, if (port.isEmpty()) fallbackPort else port.toInt())
            }.getOrNull()
        }
    }
}

internal data class RemoteFile(val name: String, val kind: Char) {
    val folder get() = kind == 'd'
    val link get() = kind == 'l'
}

internal data class InstalledPackage(val id: String, val system: Boolean, val enabled: Boolean)
internal data class TerminalResult(val status: Int, val text: String)

internal object TvPath {
    const val root = "/sdcard"

    fun validName(input: String): Boolean =
        input.isNotBlank() && input != "." && input != ".." &&
            input.toByteArray().size <= 255 && input.none { it == '/' || it.isISOControl() }

    fun folder(input: String): String {
        val path = input.trim().trimEnd('/')
        require(path == root || path.startsWith("$root/") && path.substring(root.length + 1).split('/').all(::validName)) {
            "Choose a folder in TV shared storage."
        }
        return path
    }

    fun child(folder: String, name: String): String {
        require(validName(name)) { "Enter a valid file name." }
        return "${folder(folder)}/$name"
    }
}

internal fun shellText(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

internal fun packageId(input: String): String {
    require(input.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*"))) { "Invalid package name." }
    return input
}

internal class TvConnection private constructor(
    private val client: Kadb,
    val address: TvAddress,
    val model: String,
    val androidVersion: String,
    val serial: String,
    val manufacturer: String = "",
    val brand: String = "",
    val device: String = "",
    val board: String = "",
) : AutoCloseable {
    val identity: TvIdentity
        get() = TvIdentity(manufacturer, brand, model, device, board, androidVersion)
    private val commandLock = Any()

    fun command(line: String): TerminalResult {
        require(line.isNotBlank() && '\u0000' !in line && line.length <= 16_384) { "Enter a valid command." }
        return synchronized(commandLock) {
            // Some TVs expose only the legacy shell service, which has no exit-status channel.
            val marker = "a4-status-${UUID.randomUUID()}:"
            val result = client.shell("(\n$line\n)\nprintf '\n%s%d\n' '$marker' \"\$?\"")
            val combined = result.allOutput.trimEnd()
            val code = combined.lineSequence().lastOrNull { it.startsWith(marker) }
                ?.removePrefix(marker)?.toIntOrNull()
                ?: throw IOException("The TV connection ended before reporting the command status.")
            TerminalResult(code, combined.lineSequence().filterNot { it.startsWith(marker) }.joinToString("\n").trimEnd())
        }
    }

    private fun checked(line: String): String {
        val result = command(line)
        if (result.status != 0) throw IOException(result.text.ifEmpty { "The TV rejected this operation." })
        return result.text
    }

    private fun verifiedFolder(folder: String): String {
        val path = TvPath.folder(folder)
        checked("""
            resolve() { realpath "${'$'}1" 2>/dev/null || readlink -f "${'$'}1" 2>/dev/null || (cd "${'$'}1" 2>/dev/null && pwd -P); }
            base=${'$'}(resolve /sdcard) && target=${'$'}(resolve ${shellText(path)}) &&
            test -n "${'$'}base" && test -d "${'$'}target" &&
            case "${'$'}target/" in "${'$'}base/"*) true;; *) exit 1;; esac
        """.trimIndent())
        return path
    }

    fun entries(folder: String): List<RemoteFile> {
        val discovered = checked("""
            cd ${shellText(verifiedFolder(folder))} || exit 1
            for entry in * .[!.]* ..?*; do
                if [ -L "${'$'}entry" ]; then kind=l
                elif [ -d "${'$'}entry" ]; then kind=d
                elif [ -f "${'$'}entry" ]; then kind=f
                else continue
                fi
                printf '%s\0%s\0' "${'$'}kind" "${'$'}entry"
            done
        """.trimIndent()).split('\u0000')
        return discovered.chunked(2).mapNotNull { pair ->
            val kind = pair.first().singleOrNull()?.takeIf { it in "dfl" }
            if (pair.size == 2 && kind != null && TvPath.validName(pair[1])) RemoteFile(pair[1], kind) else null
        }.sortedWith(compareBy<RemoteFile> { !it.folder }.thenBy { it.name.lowercase() })
    }

    fun prepareDownloads() { checked("mkdir -p /sdcard/Download") }

    fun transferDestination(volume: String): String {
        require(volume in setOf("Download", "Movies", "Music", "Pictures", "Documents"))
        val base = "/sdcard/$volume"
        checked("mkdir -p ${shellText(base)}")
        verifiedFolder(base)
        val destination = "$base/A4TVBridge"
        checked("mkdir -p ${shellText(destination)}")
        return verifiedFolder(destination)
    }

    fun createFolder(folder: String, name: String) {
        val target = TvPath.child(verifiedFolder(folder), name)
        checked("mkdir ${shellText(target)}")
    }

    fun rename(folder: String, old: String, new: String) {
        val verified = verifiedFolder(folder)
        val from = shellText(TvPath.child(verified, old))
        val to = shellText(TvPath.child(verified, new))
        checked("if [ -e $to ] || [ -L $to ]; then echo 'A file with that name already exists.' >&2; exit 1; fi; mv -- $from $to")
    }

    fun delete(folder: String, file: RemoteFile) {
        val option = if (file.folder) "rmdir" else "rm"
        checked("$option ${shellText(TvPath.child(verifiedFolder(folder), file.name))}")
    }

    fun download(folder: String, name: String, output: File) {
        val path = TvPath.child(verifiedFolder(folder), name)
        val quoted = shellText(path)
        checked("test -f $quoted && test ! -L $quoted")
        synchronized(commandLock) { client.pull(output, path) }
    }

    fun upload(folder: String, name: String, input: File) {
        val safeFolder = verifiedFolder(folder)
        val destination = shellText(TvPath.child(safeFolder, name))
        val temporary = "$safeFolder/.a4-${UUID.randomUUID()}.part"
        val staged = shellText(temporary)
        checked("if [ -e $destination ] || [ -L $destination ]; then echo 'File already exists on the TV.' >&2; exit 1; fi")
        try {
            synchronized(commandLock) { client.push(input, temporary) }
            checked("mv -- $staged $destination")
        } finally {
            runCatching { checked("rm -f $staged") }
        }
    }

    fun uploadOverwrite(folder: String, name: String, input: File) {
        val safeFolder = verifiedFolder(folder)
        val destination = shellText(TvPath.child(safeFolder, name))
        val temporary = "$safeFolder/.a4-${UUID.randomUUID()}.part"
        val staged = shellText(temporary)
        try {
            synchronized(commandLock) { client.push(input, temporary) }
            checked("mv -f -- $staged $destination")
        } finally {
            runCatching { checked("rm -f $staged") }
        }
    }

    fun scanMediaFile(path: String) {
        val safe = TvPath.child(path.substringBeforeLast('/'), path.substringAfterLast('/'))
        val target = shellText("file://$safe")
        runCatching {
            command("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d $target >/dev/null 2>&1 || true")
        }
    }

    private fun packages(flag: String = "") = checked("pm list packages --user 0 $flag")
        .lineSequence().mapNotNull { it.trim().takeIf { text -> text.startsWith("package:") }?.substringAfter(':') }
        .toSet()

    fun installed(): List<InstalledPackage> {
        val system = packages("-s")
        val disabled = packages("-d")
        return packages().sorted().map { InstalledPackage(it, it in system, it !in disabled) }
    }

    fun runningPackages(): Set<String> {
        val out = command("ps -A -o NAME 2>/dev/null || ps -A 2>/dev/null || ps").text
        return out.lineSequence()
            .mapNotNull { it.trim().split(Regex("\\s+")).lastOrNull()?.substringBefore(':') }
            .filter { '.' in it }.toSet()
    }

    fun reinstallSystemApp(id: String) {
        val result = checked("cmd package install-existing --user 0 ${packageId(id)}")
        check(id in packages()) { result.ifBlank { "The TV could not restore this system app." } }
    }

    fun install(apks: List<File>) {
        require(apks.isNotEmpty() && apks.size <= 32)
        synchronized(commandLock) {
            if (apks.size == 1) client.install(apks.single(), "-r")
            else client.installMultiple(apks, "-r")
        }
    }

    fun exportPackage(name: String, write: (String, (File) -> Unit) -> Unit) {
        val paths = checked("pm path ${packageId(name)}").lines()
            .filter { it.startsWith("package:/") && it.endsWith(".apk") }
            .map { it.removePrefix("package:").trim() }.distinct()
        check(paths.isNotEmpty()) { "The TV did not provide any APK files." }
        paths.forEach { path ->
            write(File(path).name) { target -> synchronized(commandLock) { client.pull(target, path) } }
        }
    }

    fun openApp(name: String) {
        val id = packageId(name)
        val target = listOf("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER")
            .firstNotNullOfOrNull { category ->
                command("cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c $category $id")
                    .text.lineSequence().lastOrNull { it.startsWith("$id/") }
            } ?: throw IOException("This package has no launchable activity on the TV.")
        checked("am start --user 0 -n ${shellText(target)}")
    }

    fun cleanupProtection(): CleanupProtection {
        val raw = command("""
            echo __home
            cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null || true
            echo __claims
            cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null || true
            echo __ime
            settings get secure default_input_method 2>/dev/null || true
            echo __wifi
            dumpsys wifi 2>/dev/null | grep -o 'cname=[^ ]*' | head -n 40 || true
            echo __channel
            cmd package query-activities --brief -a android.intent.action.VIEW -t vnd.android.cursor.item/channel 2>/dev/null || true
            echo __settings
            cmd package query-activities --brief -a android.settings.SETTINGS 2>/dev/null || true
            echo __tvinput
            pm query-services --components -a android.media.tv.TvInputService 2>/dev/null || true
            echo __adbsecure
            getprop ro.adb.secure 2>/dev/null || true
        """.trimIndent()).text

        val sections = mutableMapOf<String, MutableList<String>>()
        var current = ""
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("__")) current = t
            else if (current.isNotEmpty() && t.isNotEmpty()) sections.getOrPut(current) { mutableListOf() }.add(t)
        }

        val componentRegex = Regex("""^([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+$""")
        fun componentPackages(lines: List<String>): Set<String> = lines.mapNotNull { line ->
            componentRegex.matchEntire(line)?.groupValues?.get(1)
        }.toSet()

        val homeComponent = sections["__home"].orEmpty().lastOrNull { componentRegex.matches(it) }
        val homePkg = homeComponent?.substringBefore('/')?.takeIf { it != "android" }
        val realLaunchers = sections["__claims"].orEmpty()
            .filter { componentRegex.matches(it) && isRealLauncherComponent(it) }
            .map { it.substringBefore('/') }
            .toSet()

        val imePkg = sections["__ime"].orEmpty().firstOrNull()?.takeIf { '/' in it }?.substringBefore('/')

        val wifiOwners = sections["__wifi"].orEmpty()
        val wifiChecked = wifiOwners.isNotEmpty()
        val setupwraithOwnsWifi = wifiOwners.any { it.contains("setupwraith", ignoreCase = true) }

        val channelPkgs = componentPackages(sections["__channel"].orEmpty())
        val settingsPkgs = componentPackages(sections["__settings"].orEmpty())
        val tvInputPkgs = componentPackages(sections["__tvinput"].orEmpty())
        val adbSecureRaw = sections["__adbsecure"].orEmpty().firstOrNull() ?: ""

        return CleanupProtection(
            activeHomePackage = homePkg,
            realHomeLaunchers = realLaunchers,
            activeKeyboardPackage = imePkg,
            setupwraithOwnsWifi = setupwraithOwnsWifi,
            wifiOwnersChecked = wifiChecked,
            inputHardwarePackages = tvInputPkgs,
            channelHandlerPackages = channelPkgs,
            settingsHandlerPackages = settingsPkgs,
            adbSecureRaw = adbSecureRaw
        )
    }

    fun protectForCleanup(name: String): Boolean = cleanupProtection().isProtected(name)

    fun enabledPackages(): Set<String> = packages("-e")

    fun readAnimationScales(): List<String> = listOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale"
    ).map { key ->
        command("settings get global $key 2>/dev/null || true").text.trim().takeUnless { it == "null" || it.isBlank() } ?: "1.0"
    }

    fun writeAnimationScales(scales: List<String>) {
        require(scales.size == 3)
        val keys = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        val valid = Regex("""^-?\d+(\.\d+)?$""")
        keys.zip(scales).forEach { (key, value) ->
            require(valid.matches(value.trim())) { "Invalid animation scale." }
            checked("settings put global $key ${shellText(value.trim())}")
        }
    }

    fun readTvDisabledLog(): Set<String> {
        val out = command("if [ -f /data/local/tmp/a4tvbridge/disabled.txt ]; then cat /data/local/tmp/a4tvbridge/disabled.txt; fi").text
        return out.lineSequence()
            .map(String::trim)
            .mapNotNull { runCatching { packageId(it) }.getOrNull() }
            .toSet()
    }

    fun recordTvDisabled(name: String, disabled: Boolean) {
        val id = shellText(packageId(name))
        val file = "/data/local/tmp/a4tvbridge/disabled.txt"
        runCatching {
            if (disabled) {
                command(
                    "mkdir -p /data/local/tmp/a4tvbridge && " +
                        "(grep -qxF $id $file 2>/dev/null || printf '%s\n' $id >> $file)"
                )
            } else {
                command(
                    "if [ -f $file ]; then grep -vxF $id $file > $file.tmp || true; mv -f $file.tmp $file; fi"
                )
            }
        }
    }

    fun togglePackage(name: String, enabled: Boolean) {
        checked("pm ${if (enabled) "enable" else "disable-user"} --user 0 ${packageId(name)}")
        check((name !in packages("-d")) == enabled) { "Android did not apply the requested package state." }
    }

    fun key(code: Int) {
        require(code in 1..300)
        checked("input keyevent $code")
    }

    fun sendText(raw: String) {
        val safe = raw.replace("%", "").replace(" ", "%s")
        require(safe.isNotBlank()) { "Enter text to send." }
        checked("input text ${shellText(safe)}")
    }

    fun gesture(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long) {
        require(listOf(startX, startY, endX, endY).all { it in 0..16_384 })
        val movement = kotlin.math.abs(endX - startX) + kotlin.math.abs(endY - startY)
        if (movement < 12 && duration < 450) checked("input tap $startX $startY")
        else checked("input swipe $startX $startY $endX $endY ${duration.coerceIn(100L, 2_000L)}")
    }

    fun screenshot(): ByteArray = synchronized(commandLock) {
        client.open("exec:screencap -p").use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.source.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 24 * 1024 * 1024) { "The screenshot is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray().also { bytes ->
                check(bytes.size >= 8 && bytes[0] == 0x89.toByte()) { "The TV did not return a PNG screenshot." }
            }
        }
    }

    fun record(seconds: Int, output: File) {
        require(seconds in 5..30)
        val path = "/data/local/tmp/a4-record-${UUID.randomUUID()}.mp4"
        try {
            checked("screenrecord --time-limit $seconds ${shellText(path)} || screenrecord --bit-rate 4000000 --time-limit $seconds ${shellText(path)}")
            synchronized(commandLock) { client.pull(output, path) }
            check(output.length() > 0) { "The TV did not create a recording." }
        } finally {
            runCatching { checked("rm -f ${shellText(path)}") }
        }
    }

    fun details() = checked("""
        echo __cpu
        grep '^cpu' /proc/stat
        echo __memory
        cat /proc/meminfo
        echo __disk
        df -Pk /data 2>/dev/null || df -k /data
        echo __network
        cat /proc/net/dev 2>/dev/null
        echo __uptime
        cat /proc/uptime
        echo __end
    """.trimIndent()).let(DeviceReading::parse)

    fun processMemory(): MemoryBreakdown {
        val pss = parseProcessMemory(command("dumpsys meminfo").text)
        if (pss.isNotEmpty()) return MemoryBreakdown("PSS", pss)
        return MemoryBreakdown("RSS estimate", parseProcessRss(command("ps -A -o RSS,NAME").text))
    }

    fun wifiSummary(): WifiSnapshot {
        val raw = command("(dumpsys wifi 2>/dev/null | grep -iE 'mWifiInfo|Link speed|linkSpeed|Frequency|freq|RSSI' | head -n 12; ip link show eth0 2>/dev/null || true)").text
        return parseWifiSnapshot(raw)
    }

    fun networkByApp(): List<AppTraffic> {
        val uidMap = parseUidPackages(command("pm list packages -U --user 0 2>/dev/null || true").text)
        val qtaguid = command("cat /proc/net/xt_qtaguid/stats 2>/dev/null || true").text
        val legacy = parseQtaguidTraffic(qtaguid, uidMap)
        if (legacy.isNotEmpty()) return legacy
        val netstats = command("dumpsys netstats --uid 2>/dev/null || dumpsys netstats 2>/dev/null || true").text
        return parseNetstatsUidTraffic(netstats, uidMap)
    }

    fun readDns() = listOf("private_dns_mode", "private_dns_specifier").map {
        checked("settings get global $it").takeUnless { value -> value == "null" }.orEmpty()
    }

    fun writeDns(values: List<String>) {
        require(values.size == 2)
        val previous = readDns()
        fun apply(settings: List<String>) {
            listOf("private_dns_mode", "private_dns_specifier").zip(settings).reversed().forEach { (key, value) ->
                if (value.isBlank()) checked("settings delete global $key")
                else checked("settings put global $key ${shellText(value)}")
            }
        }
        try {
            apply(values)
            check(readDns() == values) { "The TV did not apply the DNS settings." }
        } catch (error: Exception) {
            runCatching { apply(previous) }
            throw error
        }
    }

    fun homeOptions(): List<String> {
        return command("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .text.lineSequence().map(String::trim).filter { it.matches(Regex("[.a-zA-Z0-9_]+/[.a-zA-Z0-9_]+")) }.distinct().toList()
    }

    fun activeHomeActivity(): String? {
        return command("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null || true")
            .text.lineSequence().map(String::trim).lastOrNull { it.matches(Regex("[.a-zA-Z0-9_]+/[.a-zA-Z0-9_]+")) }
    }

    fun setHomeActivity(component: String): Boolean {
        require(component.matches(Regex("[.a-zA-Z0-9_]+/[.a-zA-Z0-9_]+"))) { "Invalid launcher component." }
        val result = command("cmd package set-home-activity --user 0 ${shellText(component)} 2>&1")
        if (result.status == 0 && !result.text.contains("Error", ignoreCase = true)) return true
        homeSettings()
        return false
    }

    fun restart() {
        synchronized(commandLock) { client.open("reboot:").close() }
    }

    fun homeSettings() {
        checked("am start -a android.settings.HOME_SETTINGS")
    }

    override fun close() = client.close()

    companion object {
        private var identityReady = false

        @Synchronized
        fun configureIdentity(directory: File) {
            if (identityReady) return
            val retainedKey = File(directory, "keys/adbkey.pem")
            val key = retainedKey.takeIf { it.isFile && it.length() > 0L }
                ?: File(directory, "a4-identity.pem").also { if (it.isFile && it.length() == 0L) it.delete() }
            KadbCert.configure(OkioFilePrivateKeyStore(key.toOkioPath()))
            identityReady = true
        }

        suspend fun pair(address: TvAddress, pairingPort: Int, code: String) {
            require(pairingPort in 1..65535 && code.matches(Regex("\\d{6}"))) { "Enter the pairing port and six-digit code shown on the TV." }
            KadbCert.ensureReady()
            Kadb.pair(address.host, pairingPort, code)
        }

        fun connect(address: TvAddress): TvConnection {
            KadbCert.ensureReady()
            val adb = Kadb.create(address.host, address.port, connectTimeout = 6500, socketTimeout = 95_000)
            try {
                val marker = "a4-link-${UUID.randomUUID()}"
                val reply = adb.shell(
                    "echo $marker; getprop ro.product.model; getprop ro.build.version.release; getprop ro.serialno; " +
                        "getprop ro.product.manufacturer; getprop ro.product.brand; getprop ro.product.device; getprop ro.product.board"
                )
                val rows = reply.allOutput.lineSequence().map(String::trim).toList()
                val markerIndex = rows.indexOf(marker)
                check(markerIndex >= 0) { "The TV opened a connection but did not return an ADB shell response." }
                val model = rows.getOrNull(markerIndex + 1).orEmpty().ifBlank { "Android TV" }
                val version = rows.getOrNull(markerIndex + 2).orEmpty()
                val hardware = rows.getOrNull(markerIndex + 3).orEmpty()
                val manufacturer = rows.getOrNull(markerIndex + 4).orEmpty()
                val brand = rows.getOrNull(markerIndex + 5).orEmpty()
                val device = rows.getOrNull(markerIndex + 6).orEmpty()
                val board = rows.getOrNull(markerIndex + 7).orEmpty()
                return TvConnection(
                    adb, address, model, version, hardware.ifBlank { address.host },
                    manufacturer, brand, device, board
                )
            } catch (error: Exception) {
                adb.close()
                throw error
            }
        }
    }
}
