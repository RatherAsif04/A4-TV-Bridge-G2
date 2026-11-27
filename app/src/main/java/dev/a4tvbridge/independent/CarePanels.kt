package dev.a4tvbridge.independent

import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout

internal data class CleanupItem(val id: String, val note: String)

internal fun cleanupEntries(input: String): List<CleanupItem> = input.lineSequence()
    .mapNotNull { source ->
        val name = source.substringBefore('#').trim()
        val id = runCatching { packageId(name) }.getOrNull() ?: return@mapNotNull null
        CleanupItem(id, source.substringAfter('#', "").trim())
    }.distinctBy(CleanupItem::id).toList()

private fun BridgeActivity.previousChanges(tv: TvConnection): Set<String> {
    val safe = tv.serial.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
    if (safe in setOf("", ".", "..")) return emptySet()
    val journal = java.io.File(filesDir, "tvs/$safe/actions.jsonl")
    if (!journal.isFile || journal.length() > 8 * 1024 * 1024) return emptySet()
    val disabled = mutableSetOf<String>()
    journal.useLines { lines ->
        for (line in lines) {
            val row = runCatching { org.json.JSONObject(line) }.getOrNull() ?: continue
            if (row.optString("phase") != "DONE") continue
            val id = runCatching { packageId(row.optString("target")) }.getOrNull() ?: continue
            when (row.optString("op")) {
                "DISABLE" -> disabled.add(id)
                "ENABLE", "UNINSTALL", "REMOVE", "REINSTALL" -> disabled.remove(id)
            }
        }
    }
    return disabled
}

internal fun BridgeActivity.savedChanges(tv: TvConnection): Set<String> {
    val key = "restore-${tv.serial}"
    if (!preferences.contains(key)) {
        val recovered = previousChanges(tv)
        preferences.edit().putStringSet(key, recovered).apply()
        return recovered
    }
    return preferences.getStringSet(key, emptySet()).orEmpty()
}

internal fun BridgeActivity.updateRestore(tv: TvConnection, id: String, disabled: Boolean) {
    val key = "restore-${tv.serial}"
    val changes = savedChanges(tv)
    val changed = if (disabled) changes + id else changes - id
    check(preferences.edit().putStringSet(key, changed).commit()) { "The TV change could not be saved locally." }
}

internal fun BridgeActivity.rememberDisabled(tv: TvConnection, id: String) {
    tv.togglePackage(id, false)
    updateRestore(tv, id, true)
}

internal fun BridgeActivity.care() {
    val tv = connection ?: return
    val changes = savedChanges(tv).size
    heading("Cleanup and restore")
    tiles(content, 1,
        Tile("Clean Up TV", Mark.CLEAN, "$changes off") { show(Panel.CLEAN) },
        Tile("Check my TV", Mark.CHECK) { show(Panel.CHECK) },
        Tile("Free up space", Mark.STORAGE) { show(Panel.SPACE) },
        Tile("Block Ads", Mark.SHIELD) { showDnsOptions() },
        Tile("Restore", Mark.RESTORE) { show(Panel.RESTORE) }
    )
    heading("Device tools")
    tiles(content, 1,
        Tile("Device Info", Mark.INFO) { show(Panel.INFO) },
        Tile("ADB Shell / Terminal", Mark.SHELL) { show(Panel.SHELL) },
        Tile("Home screen", Mark.HOME, "Launchers & themes") { show(Panel.HOME) }
    )
}

internal fun BridgeActivity.recordSystemRemoval(tv: TvConnection, id: String, removed: Boolean) {
    val key = "restore-system-${tv.serial}"
    val saved = preferences.getStringSet(key, emptySet()).orEmpty()
    check(preferences.edit().putStringSet(key, if (removed) saved + id else saved - id).commit()) {
        "The TV change could not be saved locally."
    }
}

internal fun BridgeActivity.restoreApps() {
    val tv = connection ?: return
    heading("Restore")
    paragraph("Turn apps back on for this TV.")
    val list = column()
    content.addView(list)
    val removed = preferences.getStringSet("restore-system-${tv.serial}", emptySet()).orEmpty()
    for (id in removed.sorted()) {
        tiles(list, 1, Tile(displayName(id), Mark.RESTORE, "Reinstall system app") {
            confirm("Reinstall app?", id) {
                tvTask({ reinstallSystemApp(id); recordSystemRemoval(this, id, false) }) { refresh() }
            }
        })
    }
    tvTask({
        val remembered = savedChanges(this)
        installed().filter { !it.enabled && it.id in remembered }
    }) { disabled ->
        if (disabled.isEmpty() && removed.isEmpty()) list.addView(text("No recorded apps are currently disabled.", 15, muted))
        for (app in disabled) {
            tiles(list, 1, Tile(app.id, Mark.RESTORE, "Turn back on") {
                confirm("Restore this app?", app.id) {
                    tvTask({ togglePackage(app.id, true); updateRestore(this, app.id, false) }) { refresh() }
                }
            })
        }
    }
}

internal fun BridgeActivity.checkTv() {
    heading("Check my TV")
    val status = statusCard("Checking recorded changes…")
    val changes = column()
    content.addView(changes)
    fun check() {
        tvTask({
            val remembered = savedChanges(this)
            installed().filter { it.id in remembered }.partition { it.enabled }
        }) { (backOn, off) ->
            status.text = if (backOn.isEmpty()) "Cleaned by A4 TV Bridge\n${off.size} recorded app(s) still off"
                else "${backOn.size} recorded app(s) are enabled again"
            changes.removeAllViews()
            for (app in backOn) changes.addView(text(app.id, 14, muted))
        }
    }
    addButton("Check again", action = ::check)
    addButton("Restart TV") {
        confirm("Restart the TV?", "Reconnect after it finishes restarting, then use Check again.") {
            tvTask({ restart() }) { disconnect() }
        }
    }
    check()
}

internal fun BridgeActivity.freeSpace() {
    heading("Free up space")
    val detail = statusCard("Reading TV storage…")
    fun measure() {
        tvTask({ details() }) { reading ->
            detail.text = "${formatBytes(reading.diskFree)} free · ${formatBytes(reading.diskTotal)} total"
        }
    }
    addButton("Clear app caches", emphasis = true) {
        confirm("Ask Android to trim caches?", "Android chooses which cached files to clear.") {
            tvTask({ command("pm trim-caches 999G") }) { result ->
                if (result.status == 0) measure() else commandStatus(result, "Cache request sent to Android.")
            }
        }
    }
    addButton("Manage installed apps") { show(Panel.APPS) }
    addButton("Manage files") { show(Panel.FILES) }
    measure()
}

internal fun BridgeActivity.cleanup() {
    val tv = connection ?: return
    heading("Clean Up TV")
    paragraph("Debloat app lists are taken from CleanYourTV. Review suggestions for your TV model.")
    val bundled = runCatching {
        assets.open("profiles.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
    }.getOrNull()
    val files = if (bundled != null) {
        buildList {
            val keys = bundled.keys()
            while (keys.hasNext()) add(keys.next())
        }.sorted()
    } else {
        assets.list("profiles").orEmpty().filter { it.endsWith(".txt") }.map { it.removeSuffix(".txt") }.sorted()
    }
    val choiceKey = "profile-${tv.serial}"
    val modelSlug = tv.model.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val suggested = preferences.getString(choiceKey, null)
        ?: files.firstOrNull { modelSlug.isNotBlank() && it.contains(modelSlug) }
        ?: ""
    val picker = AutoCompleteTextView(this).apply {
        threshold = 1
        hint = "Search device profiles"
        setTextColor(ink)
        setHintTextColor(muted)
        setPadding(px(14), px(10), px(14), px(10))
        background = card(field, 12, outlined = true)
        setDropDownBackgroundDrawable(card(field, 12, outlined = true))
        setAdapter(object : ArrayAdapter<String>(this@cleanup, android.R.layout.simple_dropdown_item_1line, files) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val item = super.getView(position, convertView, parent) as android.widget.TextView
                item.setTextColor(ink)
                item.setBackgroundColor(field)
                return item
            }
        })
        setText(suggested, false)
    }
    content.addView(picker, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
    val profiles = column()
    fun reportProfile() {
        val name = picker.text.toString().trim()
        if (name !in files) return report("Select an exact profile from the list.")
        preferences.edit().putString(choiceKey, name).apply()
        tvTask({
            val raw = bundled?.optString(name, "")?.takeIf { it.isNotEmpty() }
                ?: assets.open("profiles/$name.txt").bufferedReader().use { it.readText() }
            val suggestions = cleanupEntries(raw)
            val current = installed().associateBy(InstalledPackage::id)
            suggestions.mapNotNull { item -> current[item.id]?.let { item to it } }
        }) { matches ->
            profiles.removeAllViews()
            profiles.addView(text("${matches.size} suggestions found on this TV", 17, bold = true).apply {
                setPadding(0, px(8), 0, px(10))
            })
            for ((item, state) in matches) {
                val statusLabel = if (state.enabled) "" else "Disabled"
                val detail = listOf(item.id, statusLabel, item.note).filter(String::isNotBlank).joinToString(" · ")
                tiles(profiles, 1, Tile(displayName(item.id), Mark.CLEAN, detail, selected = !state.enabled) {
                    val action = if (state.enabled) "Turn off" else "Turn back on"
                    val operation = if (state.enabled) "Turning it off hides it and stops it from running. You can turn it back on from Restore."
                        else "Turn this app back on for the current TV user."
                    confirm(action, listOf(item.id, item.note, operation).filter(String::isNotBlank).joinToString("\n\n")) {
                        tvTask({
                            if (state.enabled) {
                                check(!protectForCleanup(item.id)) { "The TV’s active home, keyboard, or system support must remain enabled." }
                                rememberDisabled(this, item.id)
                            } else {
                                togglePackage(item.id, true)
                                updateRestore(this, item.id, false)
                            }
                        }) {
                            reportProfile()
                            report(if (state.enabled) "Cleaned by A4 TV Bridge" else "App restored.")
                        }
                    }
                })
            }
        }
    }
    addButton("Review this profile", emphasis = true, action = ::reportProfile)
    content.addView(profiles)
    if (picker.text.toString() in files) reportProfile()
}

internal fun BridgeActivity.showDnsOptions() {
    val tv = connection ?: return
    val key = "dns-before-${tv.serial}"
    fun applyHostname(raw: String) {
        val hostname = raw.trim().lowercase()
        val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        if (hostname.length !in 4..253 || '.' !in hostname || !hostname.split('.').all(label::matches)) {
            return report("Enter a DNS provider hostname.")
        }
        confirm("Change Private DNS?", "Set Private DNS to $hostname for the entire TV. Restore the previous settings if network access is interrupted.") {
            tvTask({
                if (!preferences.contains(key)) {
                    val backup = org.json.JSONArray(readDns()).toString()
                    check(preferences.edit().putString(key, backup).commit()) { "Could not save the existing DNS settings." }
                }
                writeDns(listOf("hostname", hostname))
            }) { report("Private DNS updated to $hostname.") }
        }
    }
    val options = mutableListOf<Pair<String, () -> Unit>>(
        "AdGuard DNS (dns.adguard-dns.com)" to { applyHostname("dns.adguard-dns.com") },
        "Mullvad Adblock DNS (adblock.dns.mullvad.net)" to { applyHostname("adblock.dns.mullvad.net") },
        "Custom private DNS hostname" to {
            ask("Private DNS hostname", "dns.adguard-dns.com", ::applyHostname)
        }
    )
    val previous = preferences.getString(key, null)
    if (previous != null) options += "Restore previous DNS settings" to {
        confirm("Restore prior DNS settings?", "The TV will use the DNS settings saved before this change.") {
            val stored = org.json.JSONArray(previous)
            tvTask({
                writeDns(listOf(stored.getString(0), stored.getString(1)))
                preferences.edit().remove(key).apply()
            }) { report("Private DNS restored.") }
        }
    } else options += "Turn off Private DNS" to {
        confirm("Turn off Private DNS?", "This disables Private DNS on the TV and returns to standard network DNS.") {
            tvTask({ writeDns(listOf("off", "")) }) { report("Private DNS turned off.") }
        }
    }
    choose("Block Ads", options)
}
