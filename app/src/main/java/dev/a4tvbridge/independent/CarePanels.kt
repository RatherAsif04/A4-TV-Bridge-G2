package dev.a4tvbridge.independent

import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Volatile
private var cachedCleanupCatalog: Map<String, List<CleanupItem>>? = null

internal fun BridgeActivity.cleanupCatalog(): Map<String, List<CleanupItem>> {
    cachedCleanupCatalog?.let { return it }
    val parsed = LinkedHashMap<String, List<CleanupItem>>()
    val bundled = runCatching {
        assets.open("profiles.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
    }.getOrNull()
    if (bundled != null) {
        val keys = bundled.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val items = cleanupEntries(bundled.optString(key, ""))
            if (items.isNotEmpty()) parsed[key] = items
        }
    } else {
        for (file in assets.list("profiles").orEmpty().filter { it.endsWith(".txt") }.sorted()) {
            val raw = assets.open("profiles/$file").bufferedReader().use { it.readText() }
            val items = cleanupEntries(raw)
            if (items.isNotEmpty()) parsed[file.removeSuffix(".txt")] = items
        }
    }
    cachedCleanupCatalog = parsed
    return parsed
}

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

internal fun BridgeActivity.syncedChanges(tv: TvConnection): Set<String> {
    val local = savedChanges(tv)
    val remote = runCatching { tv.readTvDisabledLog() }.getOrDefault(emptySet())
    val merged = local + remote
    if (merged.size > local.size) {
        preferences.edit().putStringSet("restore-${tv.serial}", merged).apply()
    }
    return merged
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
    tv.recordTvDisabled(id, true)
}

internal fun BridgeActivity.rememberEnabled(tv: TvConnection, id: String) {
    tv.togglePackage(id, true)
    updateRestore(tv, id, false)
    tv.recordTvDisabled(id, false)
}

internal fun BridgeActivity.care() {
    val tv = connection ?: return
    val changes = savedChanges(tv).size
    heading("Cleanup and restore")
    tiles(content, 1,
        Tile("Clean Up TV", Mark.CLEAN, if (changes > 0) "$changes off · Auto-scan" else "Auto-scan & remove bloatware") { show(Panel.CLEAN) },
        Tile("Check my TV", Mark.CHECK, "Verify cleaned apps, DNS & health") { show(Panel.CHECK) },
        Tile("Free up space", Mark.STORAGE) { show(Panel.SPACE) },
        Tile("Block Ads", Mark.SHIELD) { showDnsOptions() },
        Tile("Restore", Mark.RESTORE, if (changes > 0) "$changes recorded" else "Turn disabled apps back on") { show(Panel.RESTORE) }
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
    val owner = content
    heading("Restore")
    paragraph("Turn apps back on for this TV.")
    val topActions = column()
    content.addView(topActions)
    val list = column()
    content.addView(list)
    val removed = preferences.getStringSet("restore-system-${tv.serial}", emptySet()).orEmpty()
    for (id in removed.sorted()) {
        tiles(list, 1, Tile(displayName(id), Mark.RESTORE, "$id · Reinstall system app") {
            confirm("Reinstall app?", id) {
                tvTask({ reinstallSystemApp(id); recordSystemRemoval(this, id, false) }) { refresh() }
            }
        })
    }
    tvTask({
        val remembered = syncedChanges(this)
        installed().filter { !it.enabled && it.id in remembered }
    }) { disabled ->
        if (content !== owner) return@tvTask
        if (disabled.size >= 2) {
            topActions.addView(button("Turn all ${disabled.size} apps back on", emphasis = true) {
                confirm("Restore all ${disabled.size} apps?", "Turns every recorded disabled app back on for this TV.") {
                    runBatchToggle(tv, disabled.map { CleanupItem(it.id, "") }, enable = true, host = list) { done, skipped ->
                        val summary = if (skipped.isEmpty()) "Restored ${done.size} app(s)."
                        else "Restored ${done.size} app(s), ${skipped.size} could not be restored."
                        report(summary)
                        refresh()
                    }
                }
            }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })
        }
        if (disabled.isEmpty() && removed.isEmpty()) {
            list.addView(statusCard("No recorded apps are currently disabled."))
            return@tvTask
        }
        for (app in disabled) {
            list.addView(appActionRow(app.id, app.id, "Turn back on", selected = false) {
                confirm("Restore this app?", app.id) {
                    tvTask({ rememberEnabled(this, app.id) }) {
                        report("${displayName(app.id)} restored.")
                        refresh()
                    }
                }
            }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
        }
    }
}

private fun BridgeActivity.roundedProgress(): ProgressBar =
    ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        progressDrawable = LayerDrawable(arrayOf(
            card(edge, 6, outlined = false),
            ClipDrawable(card(accent, 6, outlined = false), Gravity.START, ClipDrawable.HORIZONTAL)
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

private fun BridgeActivity.appActionRow(
    pkgId: String,
    subtitle: String,
    badgeLabel: String,
    selected: Boolean,
    onClick: () -> Unit
): View = row().apply {
    background = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(ripple),
        card(if (selected) tint else field, corner, outlined = true),
        null
    )
    setPadding(px(12), px(11), px(12), px(11))
    addView(appBadge(pkgId), LinearLayout.LayoutParams(px(36), px(36)))
    val copy = column().apply {
        addView(text(displayName(pkgId), 15, bold = true))
        if (subtitle.isNotBlank()) {
            addView(text(subtitle, 12, muted).apply { setPadding(0, px(2), 0, 0) })
        }
    }
    addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(10); marginEnd = px(8) })
    if (badgeLabel.isNotBlank()) {
        addView(text(badgeLabel, 12, if (selected) accent else muted, bold = true).apply {
            background = card(if (selected) surface else field, 10, outlined = true)
            setPadding(px(8), px(4), px(8), px(4))
        })
    }
    isClickable = true
    isFocusable = true
    contentDescription = displayName(pkgId)
    setOnClickListener { onClick() }
}

private fun BridgeActivity.runBatchToggle(
    tv: TvConnection,
    items: List<CleanupItem>,
    enable: Boolean,
    host: LinearLayout,
    onComplete: (List<CleanupItem>, List<String>) -> Unit
) {
    val owner = content
    host.removeAllViews()
    val progressCard = outlinedCard()
    val title = text(if (enable) "Restoring apps…" else "Applying TV cleanup…", 18, bold = true)
    val detail = text("Preparing ${items.size} app(s)…", 14, muted).apply { setPadding(0, px(6), 0, px(10)) }
    val bar = roundedProgress()
    progressCard.addView(title)
    progressCard.addView(detail)
    progressCard.addView(bar, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(8)))
    host.addView(progressCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

    launchSheetTask {
        val succeeded = mutableListOf<CleanupItem>()
        val skipped = mutableListOf<String>()
        val protection = if (!enable) {
            withContext(Dispatchers.IO) { runCatching { tv.cleanupProtection() }.getOrDefault(CleanupProtection()) }
        } else null

        for ((index, item) in items.withIndex()) {
            if (content !== owner || connection !== tv) return@launchSheetTask
            val step = index + 1
            detail.text = "${if (enable) "Turning on" else "Turning off"} $step of ${items.size}\n${displayName(item.id)} (${item.id})"
            bar.progress = ((index * 100) / items.size.coerceAtLeast(1)).coerceIn(0, 100)

            if (!enable && protection != null && protection.isProtected(item.id)) {
                skipped += "${displayName(item.id)} — kept on (protected TV component)"
                continue
            }

            val outcome = try {
                withContext(Dispatchers.IO) {
                    if (enable) rememberEnabled(tv, item.id) else rememberDisabled(tv, item.id)
                }
                null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                "${displayName(item.id)} — wouldn't ${if (enable) "turn on" else "turn off"}, so it was left as it was."
            }

            if (outcome == null) succeeded += item else skipped += outcome
            bar.progress = ((step * 100) / items.size.coerceAtLeast(1)).coerceIn(0, 100)
        }

        if (content === owner && connection === tv) {
            onComplete(succeeded, skipped)
        }
    }
}

private data class TvCheckReport(
    val match: ProfileMatch,
    val stillOff: List<InstalledPackage>,
    val cameBack: List<InstalledPackage>,
    val remainingBloat: List<CleanupItem>,
    val activeHome: String?,
    val preferredHome: String?,
    val dnsMode: String,
    val dnsHost: String,
    val reading: DeviceReading?
) {
    val homeReverted: Boolean
        get() = !preferredHome.isNullOrBlank() &&
            !activeHome.isNullOrBlank() &&
            activeHome != preferredHome &&
            activeHome.substringBefore('/') != preferredHome.substringBefore('/')
}

internal fun BridgeActivity.checkTv() {
    val tv = connection ?: return
    val owner = content
    heading("Check my TV")
    val body = column()
    content.addView(body)

    fun runCheck() {
        val activeTv = connection ?: return
        body.removeAllViews()
        body.addView(statusCard("Checking your TV…"))

        tvTask({
            val installedList = installed()
            val installedById = installedList.associateBy(InstalledPackage::id)
            val remembered = syncedChanges(this)
            val protection = cleanupProtection()
            val catalog = cleanupCatalog()
            val savedProfile = preferences.getString("profile-$serial", null)
            val match = autoMatchCleanup(identity, installedById.keys, catalog, savedProfile)

            val (cameBack, stillOff) = remembered
                .mapNotNull { installedById[it] }
                .sortedBy(InstalledPackage::id)
                .partition(InstalledPackage::enabled)

            val cameBackIds = cameBack.mapTo(HashSet()) { it.id }
            val remaining = match.items.filter { item ->
                val state = installedById[item.id]
                state != null && state.enabled && item.id !in cameBackIds && !protection.isProtected(item.id)
            }

            val activeHome = runCatching { activeHomeActivity() }.getOrNull()
            val preferredHome = preferences.getString("preferred-home-$serial", null)
            val dns = runCatching { readDns() }.getOrDefault(listOf("", ""))
            val reading = runCatching { details() }.getOrNull()

            TvCheckReport(
                match = match,
                stillOff = stillOff,
                cameBack = cameBack,
                remainingBloat = remaining,
                activeHome = activeHome,
                preferredHome = preferredHome,
                dnsMode = dns.getOrElse(0) { "" },
                dnsHost = dns.getOrElse(1) { "" },
                reading = reading
            )
        }) { checkReport ->
            if (content !== owner || connection !== activeTv) return@tvTask
            body.removeAllViews()

            val issues = mutableListOf<String>()
            if (checkReport.cameBack.isNotEmpty()) {
                issues += "• ${checkReport.cameBack.size} cleaned app(s) turned back on"
            }
            if (checkReport.remainingBloat.isNotEmpty()) {
                val label = if (checkReport.stillOff.isNotEmpty()) "new app(s) since your last cleanup"
                else "bloatware app(s) can be turned off"
                issues += "• ${checkReport.remainingBloat.size} $label"
            }
            if (checkReport.homeReverted) {
                val currentName = displayName(checkReport.activeHome!!.substringBefore('/'))
                issues += "• Home screen reverted to $currentName"
            }

            // Overall status banner
            val summaryCard = outlinedCard(fill = if (issues.isEmpty()) tint else field)
            val headline = when {
                issues.isEmpty() && checkReport.stillOff.isNotEmpty() -> "Cleaned by A4 TV Bridge"
                issues.isEmpty() -> "Your TV looks clean"
                else -> "Items need attention"
            }
            val subtext = when {
                issues.isEmpty() && checkReport.stillOff.isNotEmpty() ->
                    "All ${checkReport.stillOff.size} cleaned app(s) are still off. No extra bloatware found."
                issues.isEmpty() ->
                    "No active bloatware from the matched profile was found on this TV."
                else -> issues.joinToString("\n")
            }
            summaryCard.addView(text(headline, 19, bold = true))
            summaryCard.addView(text(subtext, 14, if (issues.isEmpty()) ink else muted).apply {
                setPadding(0, px(6), 0, 0)
            })
            body.addView(summaryCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

            // One-tap automatic fixes
            val batchHost = column()
            body.addView(batchHost)

            if (checkReport.cameBack.isNotEmpty()) {
                body.addView(button("Turn off ${checkReport.cameBack.size} app(s) again", emphasis = true) {
                    runBatchToggle(activeTv, checkReport.cameBack.map { CleanupItem(it.id, "") }, enable = false, host = batchHost) { done, skipped ->
                        report(
                            if (skipped.isEmpty()) "Turned off ${done.size} app(s) again."
                            else "Turned off ${done.size} app(s); ${skipped.size} refused to turn off."
                        )
                        runCheck()
                    }
                }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
            }

            if (checkReport.homeReverted && checkReport.preferredHome != null) {
                val targetHome = checkReport.preferredHome
                val targetName = displayName(targetHome.substringBefore('/'))
                body.addView(button("Put my home screen back ($targetName)", emphasis = checkReport.cameBack.isEmpty()) {
                    tvTask({ setHomeActivity(targetHome) }) { applied ->
                        report(
                            if (applied) "Home screen restored to $targetName."
                            else "Opened Home Settings on TV to confirm $targetName."
                        )
                        runCheck()
                    }
                }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
            }

            if (checkReport.remainingBloat.isNotEmpty()) {
                val ctaLabel = if (checkReport.stillOff.isNotEmpty())
                    "Review ${checkReport.remainingBloat.size} new app(s)"
                else
                    "Clean Up TV (${checkReport.remainingBloat.size} apps)"
                body.addView(button(ctaLabel, emphasis = checkReport.cameBack.isEmpty() && !checkReport.homeReverted) {
                    show(Panel.CLEAN)
                }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })
            }

            // Re-enabled apps detail list
            if (checkReport.cameBack.isNotEmpty()) {
                body.addView(text("Turned back on after restart or update", 15, bold = true).apply {
                    setPadding(0, px(4), 0, px(8))
                })
                for (app in checkReport.cameBack) {
                    body.addView(appActionRow(app.id, app.id, "Turn off", selected = true) {
                        tvTask({ rememberDisabled(this, app.id) }) {
                            report("${displayName(app.id)} turned off.")
                            runCheck()
                        }
                    }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(6) })
                }
            }

            // Health & status tiles
            body.addView(text("TV status overview", 15, bold = true).apply {
                setPadding(0, px(6), 0, px(8))
            })

            val profileSummary = when (checkReport.match.kind) {
                ProfileMatchKind.EXACT_MODEL -> "Matched ${checkReport.match.profileName}"
                ProfileMatchKind.BRAND_MATCH -> "Closest ${checkReport.match.profileName}"
                ProfileMatchKind.MANUAL -> "Profile ${checkReport.match.profileName}"
                ProfileMatchKind.AUTO_DETECTED -> "Auto-detected for ${activeTv.model}"
            }
            val bloatDetail = if (checkReport.remainingBloat.isEmpty()) "$profileSummary · All clean"
            else "$profileSummary · ${checkReport.remainingBloat.size} active"

            val activeHomeName = checkReport.activeHome?.substringBefore('/')?.let(::displayName) ?: "Default launcher"
            val homeDetail = when {
                checkReport.homeReverted -> "Currently $activeHomeName · Reverted after reboot"
                checkReport.preferredHome != null -> "$activeHomeName · Matches chosen home"
                else -> activeHomeName
            }

            val dnsDetail = if (checkReport.dnsMode.equals("hostname", true) && checkReport.dnsHost.isNotBlank()) {
                "Active · ${checkReport.dnsHost}"
            } else {
                "Off · Tap to enable Private DNS"
            }

            val storageDetail = checkReport.reading?.let { r ->
                val free = formatBytes(r.diskFree)
                val warn = if (r.diskFree != null && r.diskFree < 1_000_000_000L) " · Low storage" else ""
                "$free free · RAM ${percent(r.ramUsage)}$warn"
            } ?: "Tap to check TV storage"

            tiles(
                body, 1,
                Tile("Cleaned apps", Mark.CLEAN, "${checkReport.stillOff.size} still off · ${checkReport.cameBack.size} came back") {
                    show(Panel.RESTORE)
                },
                Tile("Bloatware scan", Mark.CHECK, bloatDetail) { show(Panel.CLEAN) },
                Tile("Home screen", Mark.HOME, homeDetail) { show(Panel.HOME) },
                Tile("Block Ads (Private DNS)", Mark.SHIELD, dnsDetail) { showDnsOptions() },
                Tile("Storage & memory", Mark.STORAGE, storageDetail) { show(Panel.SPACE) }
            )

            // Action row: Check again & Restart TV to verify
            val actionRow = row()
            actionRow.addView(button("Check again") { runCheck() },
                LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
            actionRow.addView(button("Restart TV & verify") {
                confirm(
                    "Restart TV to verify?",
                    "Restarting checks that everything stays off after a reboot. It takes about 2 minutes."
                ) {
                    startRebootVerification(activeTv, body, ::runCheck)
                }
            }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(8) })
            body.addView(actionRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(4) })
        }
    }

    runCheck()
}

private fun BridgeActivity.startRebootVerification(
    tv: TvConnection,
    host: LinearLayout,
    onReconnected: () -> Unit
) {
    val owner = content
    val address = tv.address
    val autoReconnect = address.port == 5555

    tvTask({ restart() }) {
        if (!autoReconnect) {
            disconnect()
            report("TV is restarting. Reconnect once it finishes booting, then open Check my TV.")
            return@tvTask
        }
        replaceConnection(null)
        host.removeAllViews()
        val waitCard = outlinedCard()
        val title = text("Restarting TV…", 19, bold = true)
        val status = text(
            "Restarting checks that everything stays off after a reboot. Waiting for $address to come back online…",
            14,
            muted
        ).apply { setPadding(0, px(6), 0, px(12)) }
        val bar = roundedProgress().apply { isIndeterminate = true }
        waitCard.addView(title)
        waitCard.addView(status)
        waitCard.addView(bar, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(8)))
        host.addView(waitCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

        var cancelled = false
        host.addView(button("Cancel and disconnect") {
            cancelled = true
            disconnect()
        }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))

        launchSheetTask {
            for (remaining in 22 downTo 1) {
                if (cancelled || content !== owner) return@launchSheetTask
                status.text = "TV is rebooting… Reconnecting automatically in ${remaining}s."
                delay(1000)
            }
            for (attempt in 1..24) {
                if (cancelled || content !== owner) return@launchSheetTask
                status.text = "Reconnecting to $address (attempt $attempt of 24)…"
                val reconnected = try {
                    withContext(Dispatchers.IO) { TvConnection.connect(address) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
                if (reconnected != null) {
                    if (cancelled || content !== owner) {
                        withContext(Dispatchers.IO) { runCatching { reconnected.close() } }
                        return@launchSheetTask
                    }
                    replaceConnection(reconnected)
                    report("Reconnected after reboot. Verifying your TV…")
                    onReconnected()
                    return@launchSheetTask
                }
                delay(5000)
            }
            if (!cancelled && content === owner) {
                disconnect()
                report("TV took longer than expected to boot. Reconnect manually and open Check my TV.")
            }
        }
    }
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

private data class CleanupScanResult(
    val match: ProfileMatch,
    val profileNames: List<String>,
    val enabledItems: List<CleanupItem>,
    val alreadyOffItems: List<CleanupItem>,
    val protectedCount: Int
)

internal fun BridgeActivity.cleanup() {
    val tv = connection ?: return
    val owner = content
    val choiceKey = "profile-${tv.serial}"

    heading("Clean Up TV")
    paragraph("Debloat app lists are taken from CleanYourTV. Automatically scans your TV and lets you turn off selected apps in one tap.")

    val body = column()
    content.addView(body)

    fun openProfilePicker(profileNames: List<String>, currentName: String?, onPicked: () -> Unit) {
        showSheet("Choose device profile") { sheet, area ->
            area.addView(button("Auto-detect my TV", emphasis = currentName == null) {
                preferences.edit().remove(choiceKey).apply()
                sheet.dismiss()
                onPicked()
            }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

            val filter = input("Search ${profileNames.size} TV profiles…").apply {
                setSingleLine()
                setPadding(px(12), px(10), px(12), px(10))
                background = card(field, 12, outlined = true)
            }
            area.addView(filter, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

            val results = column()
            val scroller = ScrollView(this).apply { addView(results) }
            val maxHeight = px((resources.configuration.screenHeightDp - 320).coerceAtLeast(180))
            area.addView(scroller, LinearLayout.LayoutParams(BridgeActivity.MATCH, maxHeight))

            fun renderMatches(query: String) {
                results.removeAllViews()
                val q = query.trim().lowercase()
                val shown = if (q.isEmpty()) profileNames.take(60)
                else profileNames.filter { q in it.lowercase() }.take(80)
                if (shown.isEmpty()) {
                    results.addView(text("No profiles match \"$query\".", 14, muted).apply { setPadding(0, px(8), 0, px(8)) })
                    return
                }
                for (name in shown) {
                    val isCurrent = name == currentName
                    results.addView(button(name, emphasis = isCurrent) {
                        preferences.edit().putString(choiceKey, name).apply()
                        sheet.dismiss()
                        onPicked()
                    }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(6) })
                }
            }

            filter.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderMatches(s.toString())
                override fun afterTextChanged(s: Editable?) = Unit
            })
            renderMatches("")
        }
    }

    fun scan() {
        val activeTv = connection ?: return
        body.removeAllViews()
        body.addView(statusCard("Scanning ${activeTv.model} for bloatware…"))

        tvTask({
            val catalog = cleanupCatalog()
            val installedList = installed()
            val byId = installedList.associateBy(InstalledPackage::id)
            val protection = cleanupProtection()
            val savedProfile = preferences.getString(choiceKey, null)
            val match = autoMatchCleanup(identity, byId.keys, catalog, savedProfile)

            val enabled = mutableListOf<CleanupItem>()
            val alreadyOff = mutableListOf<CleanupItem>()
            var protectedCount = 0
            for (item in match.items) {
                val state = byId[item.id] ?: continue
                if (state.enabled) {
                    if (protection.isProtected(item.id)) protectedCount++
                    else enabled += item
                } else {
                    alreadyOff += item
                }
            }
            CleanupScanResult(
                match = match,
                profileNames = catalog.keys.sorted(),
                enabledItems = enabled,
                alreadyOffItems = alreadyOff,
                protectedCount = protectedCount
            )
        }) { scanResult ->
            if (content !== owner || connection !== activeTv) return@tvTask
            body.removeAllViews()

            // Summary header card
            val headerCard = outlinedCard(fill = if (scanResult.enabledItems.isEmpty()) tint else field)
            val matchTitle = when (scanResult.match.kind) {
                ProfileMatchKind.EXACT_MODEL -> "Matched profile: ${scanResult.match.profileName}"
                ProfileMatchKind.BRAND_MATCH -> "Closest profile: ${scanResult.match.profileName}"
                ProfileMatchKind.MANUAL -> "Selected profile: ${scanResult.match.profileName}"
                ProfileMatchKind.AUTO_DETECTED -> "Auto-detected for ${activeTv.model}"
            }
            val matchDetail = buildString {
                if (scanResult.enabledItems.isNotEmpty()) {
                    append("${scanResult.enabledItems.size} app(s) ready to turn off")
                } else {
                    append("Your TV looks clean")
                }
                if (scanResult.alreadyOffItems.isNotEmpty()) {
                    append(" · ${scanResult.alreadyOffItems.size} already off")
                }
                if (scanResult.protectedCount > 0) {
                    append(" · ${scanResult.protectedCount} protected kept on")
                }
            }
            headerCard.addView(text(matchTitle, 17, bold = true))
            headerCard.addView(text(matchDetail, 14, muted).apply { setPadding(0, px(4), 0, px(10)) })

            val controls = row()
            controls.addView(button("Change profile") {
                openProfilePicker(scanResult.profileNames, scanResult.match.profileName, ::scan)
            }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
            controls.addView(button("Rescan") { scan() },
                LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(8) })
            headerCard.addView(controls)
            body.addView(headerCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

            if (scanResult.enabledItems.isEmpty()) {
                val cleanActions = row()
                cleanActions.addView(button("Check my TV", emphasis = true) { show(Panel.CHECK) },
                    LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
                cleanActions.addView(button("Restore apps") { show(Panel.RESTORE) },
                    LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(8) })
                body.addView(cleanActions, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })
            } else {
                // Pre-select all detected bloatware automatically
                val selectedIds = scanResult.enabledItems.mapTo(LinkedHashSet()) { it.id }
                val primaryBtn = button("", emphasis = true) {}
                val listContainer = column()

                fun updatePrimaryButton() {
                    val count = selectedIds.size
                    primaryBtn.text = when (count) {
                        0 -> "Select apps below to turn off"
                        1 -> "Turn off 1 app"
                        else -> "Turn off $count apps"
                    }
                    primaryBtn.isEnabled = count > 0
                    primaryBtn.alpha = if (count > 0) 1f else 0.55f
                }

                fun renderSelectableRows() {
                    listContainer.removeAllViews()
                    for (item in scanResult.enabledItems) {
                        val checked = item.id in selectedIds
                        val subtitle = listOf(item.id, item.note).filter(String::isNotBlank).joinToString("\n")
                        listContainer.addView(
                            appActionRow(item.id, subtitle, if (checked) "Turn off" else "Keep", selected = checked) {
                                if (item.id in selectedIds) selectedIds.remove(item.id) else selectedIds.add(item.id)
                                updatePrimaryButton()
                                renderSelectableRows()
                            },
                            LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) }
                        )
                    }
                }

                primaryBtn.setOnClickListener {
                    val chosen = scanResult.enabledItems.filter { it.id in selectedIds }
                    if (chosen.isEmpty()) return@setOnClickListener
                    val countLabel = if (chosen.size == 1) "1 app" else "${chosen.size} apps"
                    confirm(
                        "Turn off $countLabel?",
                        "Turn off hides the app and stops it. One tap brings it back from Restore."
                    ) {
                        runBatchToggle(activeTv, chosen, enable = false, host = body) { turnedOff, skipped ->
                            body.removeAllViews()
                            val doneCard = outlinedCard(fill = tint)
                            doneCard.addView(text("Cleanup complete", 20, bold = true))
                            doneCard.addView(text(
                                "Turned off ${turnedOff.size} app(s)." +
                                    if (skipped.isNotEmpty()) "\n${skipped.size} app(s) were left on." else "",
                                15,
                                ink
                            ).apply { setPadding(0, px(6), 0, 0) })
                            body.addView(doneCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

                            if (skipped.isNotEmpty()) {
                                val skippedCard = outlinedCard()
                                skippedCard.addView(text("Left unchanged", 15, bold = true))
                                skippedCard.addView(text(skipped.joinToString("\n"), 13, muted).apply {
                                    setPadding(0, px(6), 0, 0)
                                })
                                body.addView(skippedCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })
                            }

                            body.addView(button("Check my TV", emphasis = true) { show(Panel.CHECK) },
                                LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
                            body.addView(button("Back to Clean Up TV") { scan() },
                                LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
                            report("Cleaned by A4 TV Bridge")
                        }
                    }
                }

                updatePrimaryButton()
                body.addView(primaryBtn, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

                val toggleRow = row()
                toggleRow.addView(text("Pick apps to turn off", 15, bold = true),
                    LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
                toggleRow.addView(textButton("Select all") {
                    selectedIds.clear()
                    scanResult.enabledItems.forEach { selectedIds.add(it.id) }
                    updatePrimaryButton()
                    renderSelectableRows()
                })
                toggleRow.addView(textButton("Clear") {
                    selectedIds.clear()
                    updatePrimaryButton()
                    renderSelectableRows()
                }, LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply { marginStart = px(12) })
                body.addView(toggleRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })

                renderSelectableRows()
                body.addView(listContainer)
            }

            if (scanResult.alreadyOffItems.isNotEmpty()) {
                body.addView(text("Already off (${scanResult.alreadyOffItems.size})", 15, bold = true).apply {
                    setPadding(0, px(12), 0, px(8))
                })
                for (item in scanResult.alreadyOffItems) {
                    val subtitle = listOf(item.id, item.note).filter(String::isNotBlank).joinToString("\n")
                    body.addView(
                        appActionRow(item.id, subtitle, "Off", selected = false) {
                            confirm("Turn back on?", "${item.id}\n\nTurn this app back on for the current TV user.") {
                                tvTask({ rememberEnabled(this, item.id) }) {
                                    report("${displayName(item.id)} restored.")
                                    scan()
                                }
                            }
                        },
                        LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) }
                    )
                }
            }
        }
    }

    scan()
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
