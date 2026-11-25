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
