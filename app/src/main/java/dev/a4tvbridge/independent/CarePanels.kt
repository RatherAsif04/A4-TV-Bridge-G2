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
private var cachedCleanupCatalog: CleanupCatalog? = null

internal fun BridgeActivity.cleanupCatalog(): CleanupCatalog {
    cachedCleanupCatalog?.let { return it }
    val parsed = LinkedHashMap<String, ParsedProfile>()
    val bundled = runCatching {
        assets.open("profiles.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
    }.getOrNull()
    if (bundled != null) {
        val keys = bundled.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val list = parseProfileList(bundled.optString(key, ""))
            if (list.disabled.isNotEmpty() || list.kept.isNotEmpty()) parsed[key] = list
        }
    } else {
        for (file in assets.list("profiles").orEmpty().filter { it.endsWith(".txt") }.sorted()) {
            val raw = assets.open("profiles/$file").bufferedReader().use { it.readText() }
            val list = parseProfileList(raw)
            if (list.disabled.isNotEmpty() || list.kept.isNotEmpty()) parsed[file.removeSuffix(".txt")] = list
        }
    }

    val meta = LinkedHashMap<String, ProfileMeta>()
    val bundledMeta = runCatching {
        assets.open("profiles_meta.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
    }.getOrNull()
    if (bundledMeta != null) {
        val keys = bundledMeta.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = bundledMeta.optJSONObject(key) ?: continue
            meta[key] = ProfileMeta(
                tv = obj.optString("tv", "").trim(),
                os = obj.optString("os", "").trim(),
                brand = obj.optString("brand", "").trim()
            )
        }
    }

    val catalog = CleanupCatalog(parsed, meta)
    cachedCleanupCatalog = catalog
    return catalog
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
        Tile("Clean Up TV", Mark.CLEAN, if (changes > 0) "$changes off · CleanYourTV (518 lists)" else "CleanYourTV auto-scan & safety guards") { show(Panel.CLEAN) },
        Tile("Check my TV", Mark.CHECK, "Detect drift, re-enabled apps & safety") { show(Panel.CHECK) },
        Tile("Free up space", Mark.STORAGE) { show(Panel.SPACE) },
        Tile("Block Ads", Mark.SHIELD) { showDnsOptions() },
        Tile("Restore", Mark.RESTORE, if (changes > 0) "$changes recorded" else "Undo cleanup & animation changes") { show(Panel.RESTORE) }
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
    val animBackupKey = "anim-before-${tv.serial}"
    heading("Restore")
    paragraph("Undo Clean Up TV changes: turn disabled apps back on and restore original animation scales.")
    val topActions = column()
    content.addView(topActions)
    val list = column()
    content.addView(list)

    val savedAnim = preferences.getString(animBackupKey, null)
    if (!savedAnim.isNullOrBlank()) {
        topActions.addView(button("Restore original TV animation scales") {
            confirm("Restore animation scales?", "Puts window, transition, and animator scales back to their pre-cleanup values.") {
                val values = savedAnim.split(',')
                if (values.size == 3) {
                    tvTask({
                        writeAnimationScales(values)
                        preferences.edit().remove(animBackupKey).apply()
                    }) {
                        report("Original animation scales restored.")
                        refresh()
                    }
                }
            }
        }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
    }

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
                    runBatchToggle(tv, disabled.map { CleanupItem(it.id, "") }, enable = true, host = list) { done, skipped, _ ->
                        val summary = if (skipped.isEmpty()) "Restored ${done.size} app(s)."
                        else "Restored ${done.size} app(s), ${skipped.size} could not be restored."
                        report(summary)
                        refresh()
                    }
                }
            }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })
        }
        if (disabled.isEmpty() && removed.isEmpty() && savedAnim.isNullOrBlank()) {
            list.addView(inlineStatusCard("No recorded apps are currently disabled."),
                LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
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

private fun BridgeActivity.inlineStatusCard(value: String): TextView = text(value, 16).apply {
    background = card(radius = corner)
    setPadding(px(15), px(15), px(15), px(15))
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

private fun formatItemSubtitle(item: CleanupItem, totalLists: Int): String {
    val tally = when {
        item.disabledCount > 0 && item.keptCount > 0 && totalLists > 0 ->
            "Disabled in ${item.disabledCount}/$totalLists CleanYourTV lists · kept in ${item.keptCount}"
        item.disabledCount > 0 && totalLists > 0 ->
            "Disabled in ${item.disabledCount}/$totalLists CleanYourTV lists"
        else -> ""
    }
    return listOf(item.id, tally, item.note).filter(String::isNotBlank).joinToString("\n")
}

private fun BridgeActivity.runBatchToggle(
    tv: TvConnection,
    items: List<CleanupItem>,
    enable: Boolean,
    host: LinearLayout,
    onComplete: (List<CleanupItem>, List<String>, Boolean) -> Unit
) {
    val owner = content
    host.removeAllViews()
    val progressCard = outlinedCard()
    val title = text(if (enable) "Restoring apps…" else "Applying CleanYourTV cleanup…", 18, bold = true)
    val detail = text("Running CleanYourTV safety checks…", 14, muted).apply { setPadding(0, px(6), 0, px(10)) }
    val bar = roundedProgress()
    progressCard.addView(title)
    progressCard.addView(detail)
    progressCard.addView(bar, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(8)))
    host.addView(progressCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

    launchSheetTask {
        val attempted = mutableListOf<CleanupItem>()
        val skipped = mutableListOf<String>()
        val protection = if (!enable) {
            withContext(Dispatchers.IO) { runCatching { tv.cleanupProtection() }.getOrDefault(CleanupProtection()) }
        } else null
        val plannedIds = items.mapTo(HashSet()) { it.id }

        for ((index, item) in items.withIndex()) {
            if (content !== owner || connection !== tv) return@launchSheetTask
            val step = index + 1
            detail.text = "${if (enable) "Turning on" else "Turning off"} $step of ${items.size}\n${displayName(item.id)} (${item.id})"
            bar.progress = ((index * 100) / items.size.coerceAtLeast(1)).coerceIn(0, 100)

            val reason = if (!enable) protection?.blockReason(item.id, plannedIds) else null
            if (reason != null) {
                skipped += "${displayName(item.id)} — $reason"
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
                "${displayName(item.id)} — TV refused to ${if (enable) "turn on" else "turn off"}, left as it was."
            }

            if (outcome == null) attempted += item else skipped += outcome
            bar.progress = ((step * 100) / items.size.coerceAtLeast(1)).coerceIn(0, 100)
        }

        // CleanYourTV post-run readback verification (`pm list packages -e --user 0`)
        val verified = if (!enable && attempted.isNotEmpty() && content === owner && connection === tv) {
            detail.text = "Verifying disabled packages on the TV…"
            val stillEnabled = withContext(Dispatchers.IO) {
                runCatching { tv.enabledPackages() }.getOrDefault(emptySet())
            }
            val confirmed = mutableListOf<CleanupItem>()
            for (item in attempted) {
                if (item.id in stillEnabled) {
                    skipped += "${displayName(item.id)} — still enabled after readback (firmware did not hold change)."
                } else {
                    confirmed += item
                }
            }
            confirmed
        } else {
            attempted
        }

        if (content === owner && connection === tv) {
            onComplete(verified, skipped, protection?.adbInsecureWarning == true)
        }
    }
}

private data class TvCheckReport(
    val match: ProfileMatch,
    val protection: CleanupProtection,
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
            isRealLauncherComponent(preferredHome) &&
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
        body.addView(inlineStatusCard("Checking your TV against CleanYourTV rules…"),
            LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })

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
            val plannedIds = match.items.mapTo(HashSet()) { it.id }
            val remaining = match.items.filter { item ->
                val state = installedById[item.id]
                state != null && state.enabled && item.id !in cameBackIds &&
                    !protection.isProtected(item.id, plannedIds)
            }

            val activeHome = runCatching { activeHomeActivity() }.getOrNull()
            val preferredHome = preferences.getString("preferred-home-$serial", null)
            val dns = runCatching { readDns() }.getOrDefault(listOf("", ""))
            val reading = runCatching { details() }.getOrNull()

            TvCheckReport(
                match = match,
                protection = protection,
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
                issues += "• ${checkReport.cameBack.size} cleaned app(s) turned back on (drift detected)"
            }
            if (checkReport.remainingBloat.isNotEmpty()) {
                val label = if (checkReport.stillOff.isNotEmpty()) "new CleanYourTV app(s) since your last cleanup"
                else "CleanYourTV bloatware app(s) ready to turn off"
                issues += "• ${checkReport.remainingBloat.size} $label"
            }
            if (checkReport.homeReverted) {
                val currentName = displayName(checkReport.activeHome!!.substringBefore('/'))
                issues += "• Home screen reverted to $currentName"
            }

            // Overall status banner
            val summaryCard = outlinedCard(fill = if (issues.isEmpty()) tint else field)
            val headline = when {
                issues.isEmpty() && checkReport.stillOff.isNotEmpty() -> "In sync · Cleaned by A4 TV Bridge"
                issues.isEmpty() -> "Your TV looks clean"
                else -> "TV drift detected"
            }
            val subtext = when {
                issues.isEmpty() && checkReport.stillOff.isNotEmpty() ->
                    "All ${checkReport.stillOff.size} cleaned app(s) stayed off. Matches CleanYourTV with no drift."
                issues.isEmpty() ->
                    "No active bloatware from the matched CleanYourTV profile was found on this TV."
                else -> issues.joinToString("\n")
            }
            summaryCard.addView(text(headline, 19, bold = true))
            summaryCard.addView(text(subtext, 14, if (issues.isEmpty()) ink else muted).apply {
                setPadding(0, px(6), 0, 0)
            })
            body.addView(summaryCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

            // CleanYourTV ro.adb.secure=0 security advisory
            if (checkReport.protection.adbInsecureWarning) {
                val warnCard = outlinedCard()
                warnCard.addView(text("Security tip: open ADB port (ro.adb.secure=0)", 15, orange, bold = true))
                warnCard.addView(text(
                    "This TV accepts network ADB without an Allow prompt. When you finish cleaning up, turn off USB/Network/Wireless debugging on the TV and restart it.",
                    13,
                    muted
                ).apply { setPadding(0, px(4), 0, 0) })
                body.addView(warnCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })
            }

            // One-tap automatic fixes (`check.py --fix`)
            val batchHost = column()
            body.addView(batchHost)

            if (checkReport.cameBack.isNotEmpty()) {
                body.addView(button("Turn off ${checkReport.cameBack.size} app(s) again", emphasis = true) {
                    runBatchToggle(activeTv, checkReport.cameBack.map { CleanupItem(it.id, "") }, enable = false, host = batchHost) { done, skipped, _ ->
                        report(
                            if (skipped.isEmpty()) "Turned off ${done.size} app(s) again."
                            else "Turned off ${done.size} app(s); ${skipped.size} kept on."
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
                        tvTask({
                            val block = cleanupProtection().blockReason(app.id, setOf(app.id))
                            check(block == null) { block ?: "Protected TV component." }
                            rememberDisabled(this, app.id)
                        }) {
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

            val profileLabel = checkReport.match.profileTitle ?: checkReport.match.profileName ?: activeTv.model
            val bloatDetail = if (checkReport.remainingBloat.isEmpty()) "$profileLabel · All clean"
            else "$profileLabel · ${checkReport.remainingBloat.size} active"

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
                Tile("CleanYourTV scan", Mark.CHECK, bloatDetail) { show(Panel.CLEAN) },
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

private data class ProtectedCandidate(val item: CleanupItem, val reason: String)

private data class CleanupScanResult(
    val match: ProfileMatch,
    val catalog: CleanupCatalog,
    val protection: CleanupProtection,
    val enabledItems: List<CleanupItem>,
    val alreadyOffItems: List<CleanupItem>,
    val protectedItems: List<ProtectedCandidate>,
    val animationScales: List<String>
)

internal fun BridgeActivity.cleanup() {
    val tv = connection ?: return
    val owner = content
    val choiceKey = "profile-${tv.serial}"
    val animBackupKey = "anim-before-${tv.serial}"

    heading("Clean Up TV")
    paragraph("Powered by CleanYourTV (518 tested TV lists & safety guards). Automatically matches your TV, protects boot/Wi-Fi/HDMI/Home packages, and lets you clean in one tap.")

    val body = column()
    content.addView(body)

    fun openProfilePicker(catalog: CleanupCatalog, currentName: String?, onPicked: () -> Unit) {
        val allNames = catalog.profiles.keys.sorted()
        showSheet("Choose CleanYourTV profile (${allNames.size})") { sheet, area ->
            area.addView(button("Auto-detect my TV", emphasis = currentName == null) {
                preferences.edit().remove(choiceKey).apply()
                sheet.dismiss()
                onPicked()
            }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

            val filter = input("Search brand, model, or codename…").apply {
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
                val shown = if (q.isEmpty()) {
                    allNames.take(60)
                } else {
                    allNames.filter { slug ->
                        val info = catalog.meta[slug]
                        q in slug.lowercase() ||
                            (info != null && (q in info.tv.lowercase() || q in info.brand.lowercase() || q in info.os.lowercase()))
                    }.take(80)
                }
                if (shown.isEmpty()) {
                    results.addView(text("No CleanYourTV profiles match \"$query\".", 14, muted).apply { setPadding(0, px(8), 0, px(8)) })
                    return
                }
                for (name in shown) {
                    val info = catalog.meta[name]
                    val label = if (!info?.tv.isNullOrBlank()) "${info!!.tv}\n$name" else name
                    val isCurrent = name == currentName
                    results.addView(button(label, emphasis = isCurrent) {
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
        body.addView(inlineStatusCard("Scanning ${activeTv.model} with CleanYourTV (518 lists + safety guards)…"),
            LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })

        tvTask({
            val catalog = cleanupCatalog()
            val installedList = installed()
            val byId = installedList.associateBy(InstalledPackage::id)
            val protection = cleanupProtection()
            val savedProfile = preferences.getString(choiceKey, null)
            val match = autoMatchCleanup(identity, byId.keys, catalog, savedProfile)
            val animScales = runCatching { readAnimationScales() }.getOrDefault(listOf("1.0", "1.0", "1.0"))

            val enabled = mutableListOf<CleanupItem>()
            val alreadyOff = mutableListOf<CleanupItem>()
            val guarded = mutableListOf<ProtectedCandidate>()
            val plannedIds = match.items.mapTo(HashSet()) { it.id }

            for (item in match.items) {
                val state = byId[item.id] ?: continue
                if (state.enabled) {
                    val block = protection.blockReason(item.id, plannedIds)
                    if (block != null) guarded += ProtectedCandidate(item, block)
                    else enabled += item
                } else {
                    alreadyOff += item
                }
            }
            CleanupScanResult(
                match = match,
                catalog = catalog,
                protection = protection,
                enabledItems = enabled,
                alreadyOffItems = alreadyOff,
                protectedItems = guarded,
                animationScales = animScales
            )
        }) { scanResult ->
            if (content !== owner || connection !== activeTv) return@tvTask
            body.removeAllViews()

            // Summary header card
            val headerCard = outlinedCard(fill = if (scanResult.enabledItems.isEmpty()) tint else field)
            val tvTitle = scanResult.match.profileTitle ?: scanResult.match.profileName ?: activeTv.model
            val matchTitle = when (scanResult.match.kind) {
                ProfileMatchKind.EXACT_MODEL -> tvTitle
                ProfileMatchKind.BRAND_MATCH -> "Closest match: $tvTitle"
                ProfileMatchKind.MANUAL -> "Selected: $tvTitle"
                ProfileMatchKind.AUTO_DETECTED -> "Auto-detected via CleanYourTV consensus (${scanResult.match.totalLists} lists)"
            }
            val metaLine = listOfNotNull(
                scanResult.match.profileName?.let { "Profile: $it" },
                scanResult.match.profileOs
            ).joinToString(" · ")
            val statsLine = buildString {
                if (scanResult.enabledItems.isNotEmpty()) {
                    append("${scanResult.enabledItems.size} app(s) ready to turn off")
                } else {
                    append("Your TV looks clean")
                }
                if (scanResult.alreadyOffItems.isNotEmpty()) {
                    append(" · ${scanResult.alreadyOffItems.size} already off")
                }
                if (scanResult.protectedItems.isNotEmpty()) {
                    append(" · ${scanResult.protectedItems.size} kept safe by guards")
                }
            }
            headerCard.addView(text(matchTitle, 17, bold = true))
            if (metaLine.isNotBlank()) {
                headerCard.addView(text(metaLine, 12, accent, bold = true).apply { setPadding(0, px(3), 0, 0) })
            }
            headerCard.addView(text(statsLine, 14, muted).apply { setPadding(0, px(4), 0, px(10)) })

            val controls = row()
            controls.addView(button("Change profile") {
                openProfilePicker(scanResult.catalog, scanResult.match.profileName, ::scan)
            }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
            controls.addView(button("Rescan") { scan() },
                LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(8) })
            headerCard.addView(controls)
            body.addView(headerCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

            // CleanYourTV Animation Speedup card
            val currentAnim = scanResult.animationScales.firstOrNull() ?: "1.0"
            val animFast = currentAnim == "0.5" || currentAnim == "0" || currentAnim == "0.0"
            val animCard = outlinedCard()
            animCard.addView(text("TV animation speed", 15, bold = true))
            animCard.addView(text(
                "Current scale: ${scanResult.animationScales.joinToString(" / ")} · CleanYourTV recommends 0.5x for snappier menus (undoable in Restore).",
                13,
                muted
            ).apply { setPadding(0, px(3), 0, px(8)) })
            val animRow = row()
            animRow.addView(button("Speed up (0.5x)", emphasis = !animFast) {
                tvTask({
                    if (!preferences.contains(animBackupKey)) {
                        preferences.edit().putString(animBackupKey, readAnimationScales().joinToString(",")).apply()
                    }
                    writeAnimationScales(listOf("0.5", "0.5", "0.5"))
                }) {
                    report("TV animations set to 0.5x.")
                    scan()
                }
            }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
            animRow.addView(button("Off (0x)") {
                tvTask({
                    if (!preferences.contains(animBackupKey)) {
                        preferences.edit().putString(animBackupKey, readAnimationScales().joinToString(",")).apply()
                    }
                    writeAnimationScales(listOf("0", "0", "0"))
                }) {
                    report("TV animations turned off.")
                    scan()
                }
            }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(8) })
            animRow.addView(button("Default (1x)") {
                tvTask({
                    writeAnimationScales(listOf("1.0", "1.0", "1.0"))
                    preferences.edit().remove(animBackupKey).apply()
                }) {
                    report("TV animations reset to 1.0x.")
                    scan()
                }
            }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(8) })
            animCard.addView(animRow)
            body.addView(animCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

            if (scanResult.enabledItems.isEmpty()) {
                val cleanActions = row()
                cleanActions.addView(button("Check my TV", emphasis = true) { show(Panel.CHECK) },
                    LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
                cleanActions.addView(button("Restore apps") { show(Panel.RESTORE) },
                    LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(8) })
                body.addView(cleanActions, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })
            } else {
                // Pre-select all safe detected bloatware automatically
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
                        val subtitle = formatItemSubtitle(item, scanResult.match.totalLists)
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
                        "CleanYourTV safety guards protect boot, Wi-Fi, Home, HDMI inputs, and Settings. You can undo anytime in Restore."
                    ) {
                        runBatchToggle(activeTv, chosen, enable = false, host = body) { turnedOff, skipped, adbWarn ->
                            body.removeAllViews()
                            val doneCard = outlinedCard(fill = tint)
                            doneCard.addView(text("Cleanup complete", 20, bold = true))
                            doneCard.addView(text(
                                "Verified ${turnedOff.size} app(s) turned off." +
                                    if (skipped.isNotEmpty()) "\n${skipped.size} app(s) were kept on." else "",
                                15,
                                ink
                            ).apply { setPadding(0, px(6), 0, 0) })
                            body.addView(doneCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

                            if (adbWarn) {
                                val warnCard = outlinedCard()
                                warnCard.addView(text("Security tip: turn off ADB debugging when done", 15, orange, bold = true))
                                warnCard.addView(text(
                                    "This TV reports ro.adb.secure=0 (port 5555 accepts commands without an Allow prompt). Turn off USB/Network/Wireless debugging on the TV and restart it once finished.",
                                    13,
                                    muted
                                ).apply { setPadding(0, px(4), 0, 0) })
                                body.addView(warnCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })
                            }

                            if (skipped.isNotEmpty()) {
                                val skippedCard = outlinedCard()
                                skippedCard.addView(text("Kept on / left unchanged", 15, bold = true))
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

            if (scanResult.protectedItems.isNotEmpty()) {
                val guardCard = outlinedCard()
                guardCard.addView(text("Kept on by CleanYourTV safety guards (${scanResult.protectedItems.size})", 15, bold = true))
                val lines = scanResult.protectedItems.joinToString("\n\n") { (item, reason) ->
                    "${displayName(item.id)} (${item.id})\n$reason"
                }
                guardCard.addView(text(lines, 13, muted).apply { setPadding(0, px(6), 0, 0) })
                body.addView(guardCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
                    topMargin = px(8)
                    bottomMargin = px(8)
                })
            }

            if (scanResult.alreadyOffItems.isNotEmpty()) {
                body.addView(text("Already off (${scanResult.alreadyOffItems.size})", 15, bold = true).apply {
                    setPadding(0, px(10), 0, px(8))
                })
                for (item in scanResult.alreadyOffItems) {
                    val subtitle = formatItemSubtitle(item, scanResult.match.totalLists)
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
