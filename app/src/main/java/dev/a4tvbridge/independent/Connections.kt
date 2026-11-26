package dev.a4tvbridge.independent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException

internal data class SavedTv(val address: TvAddress, val model: String)

internal class TvDirectory(private val context: Context) {
    private val preferences get() = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    private fun previous() = File(context.filesDir, "tvs").listFiles().orEmpty().mapNotNull { device ->
        val profile = File(device, "tv.json")
        if (!profile.isFile || profile.length() !in 1..65_536) return@mapNotNull null
        runCatching {
            val record = JSONObject(profile.readText())
            val address = TvAddress.parse(record.optString("host")) ?: return@runCatching null
            SavedTv(address, record.optString("title").ifBlank { record.optString("model", "Android TV") })
        }.getOrNull()
    }

    fun saved(): List<SavedTv> {
        val newer = preferences.getStringSet("tv-addresses", emptySet()).orEmpty().mapNotNull { text ->
            TvAddress.parse(text)?.let { SavedTv(it, preferences.getString("tv-model-$text", null) ?: "Android TV") }
        }
        return (newer + previous()).distinctBy(SavedTv::address).sortedBy(SavedTv::model)
    }

    fun remember(tv: TvConnection) {
        val address = tv.address.toString()
        val addresses = preferences.getStringSet("tv-addresses", emptySet()).orEmpty() + address
        val title = previous().firstOrNull { it.address.host == tv.address.host }?.model ?: tv.model
        preferences.edit().putStringSet("tv-addresses", addresses)
            .putString("tv-model-$address", title).putString("host", tv.address.host)
            .putString("port", tv.address.port.toString()).apply()
    }
}

private enum class AdvertisedService(val protocol: String) {
    LEGACY("_adb._tcp."), CONNECT("_adb-tls-connect._tcp."), PAIR("_adb-tls-pairing._tcp.")
}

private class TvDiscovery(context: Context, private val result: (String, AdvertisedService, TvAddress) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val multicast = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("a4-bridge-discovery")
    }.getOrNull()
    private val main = Handler(Looper.getMainLooper())
    private val registry = mutableListOf<NsdManager.DiscoveryListener>()
    private val resolvingQueue = ArrayDeque<Pair<AdvertisedService, NsdServiceInfo>>()
    private var resolving = false
    private var stopped = false

    fun start() {
        runCatching { multicast?.acquire() }
        for (station in AdvertisedService.entries) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post {
                        if (!stopped) {
                            resolvingQueue.addLast(station to info)
                            advance()
                        }
                    }
                }
            }
            runCatching { manager.discoverServices(station.protocol, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onSuccess { registry.add(listener) }
        }
    }

    private fun advance() {
        if (stopped || resolving || resolvingQueue.isEmpty()) return
        val (station, candidate) = resolvingQueue.removeFirst()
        resolving = true
        val request = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                main.post(::finishResolution)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post {
                    if (!stopped) {
                        val addresses = if (android.os.Build.VERSION.SDK_INT >= 34) info.hostAddresses
                            else listOfNotNull(info.host)
                        val preferred = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
                        val address = runCatching { TvAddress(preferred?.hostAddress.orEmpty(), info.port) }.getOrNull()
                        if (address != null) result(info.serviceName, station, address)
                    }
                    finishResolution()
                }
            }
        }
        runCatching { manager.resolveService(candidate, request) }.onFailure { finishResolution() }
    }

    private fun finishResolution() {
        resolving = false
        advance()
    }

    override fun close() {
        stopped = true
        resolvingQueue.clear()
        for (listener in registry) runCatching { manager.stopServiceDiscovery(listener) }
        registry.clear()
        runCatching { if (multicast?.isHeld == true) multicast.release() }
    }
}

internal fun localTvCandidates(address: Inet4Address, prefix: Int): List<String> {
    val octets = address.address.map { it.toInt() and 255 }
    val privateNetwork = octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 ||
        octets[0] == 192 && octets[1] == 168
    if (!privateNetwork || prefix !in 1..30) return emptyList()
    val block = 1 shl (32 - prefix.coerceAtLeast(24))
    val start = octets[3] / block * block
    val stem = octets.take(3).joinToString(".")
    return (start + 1 until start + block - 1).filterNot { it == octets[3] }.map { "$stem.$it" }
}

private data class LocalLink(val network: Network, val address: Inet4Address, val prefix: Int)

private fun Context.tvLocalLink(): LocalLink? {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val networks = listOfNotNull(connectivity.activeNetwork) + connectivity.allNetworks.asList()
    return networks.distinct().firstNotNullOfOrNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
        val onLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!onLan) return@firstNotNullOfOrNull null
        connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().firstNotNullOfOrNull { assigned ->
            val address = assigned.address as? Inet4Address ?: return@firstNotNullOfOrNull null
            if (localTvCandidates(address, assigned.prefixLength).isEmpty()) return@firstNotNullOfOrNull null
            LocalLink(network, address, assigned.prefixLength)
        }
    }
}

private suspend fun Context.findLegacyEndpoints(): List<TvAddress> {
    val local = tvLocalLink() ?: throw IllegalStateException("Connect to the TV’s Wi-Fi or Ethernet network, or enter its address.")
    val candidates = localTvCandidates(local.address, local.prefix)
    return coroutineScope {
        candidates.chunked(32).flatMap { group ->
            group.map { host ->
                async(Dispatchers.IO) {
                    try {
                        local.network.socketFactory.createSocket().use {
                            it.connect(InetSocketAddress(host, 5555), 450)
                            TvAddress(host, 5555)
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: java.io.IOException) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}

internal fun BridgeActivity.connectionPicker(after: Panel = Panel.DASHBOARD) {
    val known = TvDirectory(this).saved()
    val devices = linkedSetOf<Pair<TvAddress, Boolean>>()
    val wirelessPorts = mutableMapOf<String, Int>()
    showSheet("Connect your TV") { popup, layout ->
        var scanJob: kotlinx.coroutines.Job? = null
        val description = text("Keep your phone and TV on the same network. Turn on ADB debugging on the TV.", 14, muted)
        layout.addView(description, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
        val matches = column()
        val status = text("", 13, muted)
        fun showEndpoint(name: String, address: TvAddress, pairing: Boolean = false) {
            if (!devices.add(address to pairing)) return
            if (status.text.startsWith("No TVs found")) {
                status.text = "Choose your TV. Approve debugging on the TV if prompted."
            }
            tiles(matches, 1, Tile(name, Mark.TV, address.toString()) {
                popup.dismiss()
                if (pairing) connectDialog(
                    pair = true, hostHint = address.host, pairHint = address.port, portHint = wirelessPorts[address.host], after = after
                )
                else connect(address, after = after)
            })
        }
        tiles(layout, 1,
            Tile("Find TVs on this network", Mark.TV, selected = true) {
                status.text = "Looking for Android TV debugging on this network…"
                scanJob?.cancel()
                scanJob = launchSheetTask {
                    try {
                        val addresses = kotlinx.coroutines.withContext(Dispatchers.IO) { findLegacyEndpoints() }
                        if (popup.isShowing) {
                            for (address in addresses) {
                                val label = known.firstOrNull { it.address == address }?.model ?: "TV at ${address.host}"
                                showEndpoint(label, address)
                            }
                            status.text = if (devices.isEmpty()) "No TVs found. Enter the IP address shown on the TV."
                                else "Choose your TV. Approve debugging on the TV if prompted."
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        if (popup.isShowing) status.text = error.message ?: "The local network search failed."
                    }
                }
            },
            Tile("Enter TV address", Mark.SHELL) { popup.dismiss(); connectDialog(pair = false, after = after) },
            Tile("Pair with a code", Mark.REMOTE) { popup.dismiss(); connectDialog(pair = true, after = after) }
        )
        layout.addView(status)
        layout.addView(android.widget.ScrollView(this).apply {
            addView(matches)
        }, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(190)))
        for (device in known) showEndpoint(device.model, device.address)
        val discovery = TvDiscovery(this) { name, station, address ->
            if (station == AdvertisedService.CONNECT) wirelessPorts[address.host] = address.port
            showEndpoint(if (station == AdvertisedService.PAIR) "Pair TV at ${address.host}" else name,
                address, station == AdvertisedService.PAIR)
        }
        popup.setOnDismissListener {
            scanJob?.cancel()
            discovery.close()
        }
        discovery.start()
    }
}
