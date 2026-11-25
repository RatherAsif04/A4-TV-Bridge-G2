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
}
