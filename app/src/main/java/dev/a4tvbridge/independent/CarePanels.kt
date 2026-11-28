package dev.a4tvbridge.independent

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
    pageHeader("TV Care", "Clean bloatware, verify drift, block ads & restore changes")
    content.addView(sectionLabel("Cleanup and restore"),
        LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(2); bottomMargin = px(8) })
    tiles(content, 1,
        Tile("Clean Up TV", Mark.CLEAN, if (changes > 0) "$changes off · CleanYourTV (518 lists)" else "CleanYourTV auto-scan & safety guards") { show(Panel.CLEAN) },
        Tile("Check my TV", Mark.CHECK, "Detect drift, re-enabled apps & safety") { show(Panel.CHECK) },
        Tile("Free up space", Mark.STORAGE, "Clear app caches & check storage") { show(Panel.SPACE) },
        Tile("Block Ads", Mark.SHIELD, "Private DNS ad blocking") { showDnsOptions() },
        Tile("Restore", Mark.RESTORE, if (changes > 0) "$changes recorded" else "Undo cleanup & animation changes") { show(Panel.RESTORE) }
    )
    content.addView(sectionLabel("Device tools"),
        LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(6); bottomMargin = px(8) })
    tiles(content, 1,
        Tile("Device Info", Mark.INFO, "Live CPU, memory & network telemetry") { show(Panel.INFO) },
        Tile("Home & Themes", Mark.HOME, "Launchers & 4 clean-room 1080p themes") { show(Panel.HOME) }
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
    pageHeader("Restore", "Undo Clean Up TV changes: turn disabled apps back on and restore animation scales")
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

private fun BridgeActivity.inlineStatusCard(value: String): TextView = text(value, 15).apply {
    background = card(field, corner, outlined = true)
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
    val bg = card(if (selected) tint else field, 16, outlined = true).apply {
        if (selected) setStroke(px(1), if (dark) 0xFF1E3A5F.toInt() else accent)
    }
    background = RippleDrawable(ColorStateList.valueOf(ripple), bg, null)
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
        addView(text(badgeLabel, 11, if (selected) accent else muted, bold = true).apply {
            background = card(if (selected) surface else field, 9, outlined = true)
            setPadding(px(9), px(4), px(9), px(4))
        })
    }
    isClickable = true
    isFocusable = true
    contentDescription = displayName(pkgId)
    setOnClickListener { onClick() }
}

private fun BridgeActivity.cleanupCheckRow(
    item: CleanupItem,
    totalLists: Int,
    checked: Boolean,
    onClick: () -> Unit
): View = row().apply {
    val rowBg = card(field, 16, outlined = false).apply {
        setStroke(px(1), if (checked) accent else edge)
    }
    background = interactiveBackground(rowBg, radius = 16)
    minimumHeight = px(56)
    setPadding(px(13), px(12), px(13), px(12))

    // Rounded checkbox matching 03-cleanup-tv.png
    val checkbox = FrameLayout(this@cleanupCheckRow).apply {
        background = if (checked) {
            card(accentStrong, 7, outlined = false).apply { setStroke(px(1), accent) }
        } else {
            card(Color.TRANSPARENT, 7, outlined = false).apply { setStroke(px(2), dim) }
        }
        if (checked) {
            addView(text("✓", 14, Color.WHITE, bold = true), FrameLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP, Gravity.CENTER))
        }
    }
    addView(checkbox, LinearLayout.LayoutParams(px(24), px(24)))

    val tallyText = when {
        item.disabledCount > 0 && totalLists > 0 -> {
            val totalVotes = (item.disabledCount + item.keptCount).coerceAtLeast(item.disabledCount)
            val pct = ((item.disabledCount * 100) / totalVotes.coerceAtLeast(1)).coerceIn(1, 100)
            "$pct% off · ${item.disabledCount} TVs" + if (item.note.isNotBlank()) " · ${item.note}" else ""
        }
        item.note.isNotBlank() -> item.note
        else -> "CleanYourTV recommended"
    }

    val copy = column().apply {
        addView(text(displayName(item.id), 15, bold = true))
        addView(text(item.id, 11, dim).apply {
            typeface = monoTypeface
            setPadding(0, px(2), 0, 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(text(tallyText, 11, if (checked) accent else dim, bold = true).apply {
            setPadding(0, px(3), 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }
    addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(12) })
    isClickable = true
    isFocusable = true
    contentDescription = "${displayName(item.id)}, ${if (checked) "selected" else "not selected"}"
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
    val animationScales: List<String>,
    val reading: DeviceReading?
) {
    val homeReverted: Boolean
        get() = !preferredHome.isNullOrBlank() &&
            !activeHome.isNullOrBlank() &&
            isRealLauncherComponent(preferredHome) &&
            activeHome != preferredHome &&
            activeHome.substringBefore('/') != preferredHome.substringBefore('/')
}

private data class StatTile(
    val label: String,
    val value: String,
    val sub: String,
    val subColor: Int,
    val onClick: () -> Unit
)

internal fun BridgeActivity.checkTv() {
    val tv = connection ?: return
    val owner = content

    // Header matching 04-check-my-tv.png
    pageHeader("Check my TV", "Automatic drift check, launcher guard & reboot verify")

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
            val anims = runCatching { readAnimationScales() }.getOrDefault(listOf("1.0", "1.0", "1.0"))
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
                animationScales = anims,
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
                val label = if (checkReport.stillOff.isNotEmpty()) "new CleanYourTV app(s) available"
                else "CleanYourTV bloatware app(s) ready to turn off"
                issues += "• ${checkReport.remainingBloat.size} $label"
            }
            if (checkReport.homeReverted) {
                val currentName = displayName(checkReport.activeHome!!.substringBefore('/'))
                issues += "• Home screen reverted to $currentName"
            }
            val clean = issues.isEmpty()

            // 1. Emerald Verdict Hero Card (matches 04-check-my-tv.png)
            val verdictFill = if (clean) greenBg else warningBg
            val verdictStroke = if (clean) greenStroke else orange
            val verdictCard = column().apply {
                background = card(verdictFill, 20, outlined = false).apply { setStroke(px(2), verdictStroke) }
                setPadding(px(16), px(16), px(16), px(14))
            }
            val heroTop = row()
            val circleIcon = FrameLayout(this).apply {
                background = rounded(if (clean) greenStroke else orange, 50)
                addView(text(if (clean) "✓" else "!", 22, Color.WHITE, bold = true),
                    FrameLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP, Gravity.CENTER))
            }
            heroTop.addView(circleIcon, LinearLayout.LayoutParams(px(46), px(46)).apply { marginEnd = px(14) })
            val heroCopy = column()
            val headline = if (clean) "Your TV looks clean" else "TV drift detected"
            val subtext = when {
                clean && checkReport.stillOff.isNotEmpty() -> "All ${checkReport.stillOff.size} cleaned apps stayed disabled"
                clean -> "No active bloatware from your matched CleanYourTV list"
                else -> issues.joinToString("\n")
            }
            heroCopy.addView(text(headline, 20, if (clean && dark) 0xFFECFDF5.toInt() else ink, bold = true))
            heroCopy.addView(text(subtext, 13, if (clean && dark) 0xFFA7F3D0.toInt() else muted).apply {
                setPadding(0, px(3), 0, 0)
            })
            heroTop.addView(heroCopy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
            verdictCard.addView(heroTop)

            val heroDiv = View(this).apply {
                setBackgroundColor(if (clean) (if (dark) 0xFF065F46.toInt() else 0xFFA7F3D0.toInt()) else edge)
            }
            verdictCard.addView(heroDiv, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(1)).apply { topMargin = px(14); bottomMargin = px(10) })
            verdictCard.addView(text("Last verified: Just now · Live pm readback passed", 11, if (clean) successInk else orange).apply {
                typeface = monoTypeface
            })
            body.addView(verdictCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })

            // One-tap fixes when drift exists
            val batchHost = column()
            body.addView(batchHost)

            if (checkReport.cameBack.isNotEmpty()) {
                body.addView(button("Turn off ${checkReport.cameBack.size} drifted app(s) again", emphasis = true) {
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

            // 2. 2x2 Status Overview Metric Grid (matches 04-check-my-tv.png)
            val totalCleaned = checkReport.stillOff.size + checkReport.cameBack.size
            val activeHomeName = checkReport.activeHome?.substringBefore('/')?.let(::displayName) ?: "Default"
            val animVal = checkReport.animationScales.firstOrNull()?.ifBlank { "1.0" } ?: "1.0"
            val adbSafe = !checkReport.protection.adbInsecureWarning
            val adbRaw = checkReport.protection.adbSecureRaw?.takeIf { it.isNotBlank() } ?: if (adbSafe) "1" else "0"

            val statTiles = listOf(
                StatTile(
                    "Cleaned Apps",
                    if (totalCleaned > 0) "${checkReport.stillOff.size} / $totalCleaned Off" else "0 Recorded",
                    if (checkReport.cameBack.isEmpty()) "0 re-enabled by OTA" else "${checkReport.cameBack.size} came back",
                    if (checkReport.cameBack.isEmpty()) green else orange
                ) { show(Panel.RESTORE) },
                StatTile(
                    "Home Launcher",
                    activeHomeName,
                    if (checkReport.homeReverted) "Reverted after reboot" else "Active default home",
                    if (checkReport.homeReverted) orange else accent
                ) { show(Panel.HOME) },
                StatTile(
                    "Animation Speed",
                    "${animVal}x Scale",
                    "Window & transition",
                    purple
                ) { show(Panel.CLEAN) },
                StatTile(
                    "ADB Security",
                    if (adbSafe) "Authorized" else "Open ADB",
                    "ro.adb.secure = $adbRaw",
                    if (adbSafe) green else orange
                ) { show(Panel.INFO) }
            )
            for (pair in statTiles.chunked(2)) {
                val r = row()
                pair.forEachIndexed { idx, st ->
                    val box = outlinedCard(field, radius = 16, stroke = 1).apply {
                        setPadding(px(14), px(12), px(12), px(12))
                        addView(text(st.label, 12, muted, true))
                        addView(text(st.value, 18, bold = true).apply {
                            setPadding(0, px(6), 0, 0)
                            isSingleLine = true
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        addView(text(st.sub, 11, st.subColor).apply {
                            setPadding(0, px(5), 0, 0)
                            isSingleLine = true
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { st.onClick() }
                    }
                    r.addView(box, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply {
                        if (idx > 0) marginStart = px(10)
                    })
                }
                body.addView(r, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
            }

            // 3. SYSTEM HEALTH CHECKS grouped card with PASS / WARN / SAFE badges (matches 04-check-my-tv.png)
            body.addView(sectionLabel("System health checks"),
                LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(4); bottomMargin = px(8) })

            val checksCard = outlinedCard(field, radius = 18, stroke = 1).apply {
                setPadding(px(16), px(6), px(16), px(6))
            }
            val dnsOn = checkReport.dnsMode.equals("hostname", true) && checkReport.dnsHost.isNotBlank()
            val checkRows = listOf(
                Triple(
                    "CleanYourTV disabled packages",
                    if (checkReport.cameBack.isEmpty()) "${checkReport.stillOff.size} verified disabled for user 0" else "${checkReport.cameBack.size} package(s) re-enabled",
                    (if (checkReport.cameBack.isEmpty()) "PASS" to greenStroke else "DRIFT" to orange)
                ),
                Triple(
                    "Preferred Home Launcher lock",
                    checkReport.activeHome?.substringBefore('/')?.let { "$it active" } ?: "Default TV home active",
                    (if (!checkReport.homeReverted) "PASS" to greenStroke else "WARN" to orange)
                ),
                Triple(
                    "Private DNS Ad Blocking",
                    if (dnsOn) "${checkReport.dnsHost} active" else "Not configured · Tap Block Ads",
                    (if (dnsOn) "PASS" to greenStroke else "OFF" to dim)
                ),
                Triple(
                    "Unauthenticated ADB Check",
                    if (adbSafe) "ro.adb.secure=$adbRaw (safe)" else "ro.adb.secure=0 · Turn off ADB when done",
                    (if (adbSafe) "SAFE" to accent else "WARN" to orange)
                )
            )
            checkRows.forEachIndexed { idx, (title, sub, badge) ->
                val (badgeLabel, badgeCol) = badge
                val itemRow = row().apply { setPadding(0, px(10), 0, px(10)) }
                val col = column().apply {
                    addView(text(title, 14, bold = true))
                    addView(text(sub, 12, muted).apply { setPadding(0, px(2), 0, 0) })
                }
                itemRow.addView(col, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(8) })
                val badgeBgColor = when (badgeCol) {
                    greenStroke -> greenBg
                    accent -> tint
                    orange -> warningBg
                    else -> surface
                }
                itemRow.addView(text(badgeLabel, 10, badgeCol, bold = true).apply {
                    background = card(badgeBgColor, 8, outlined = false).apply { setStroke(px(1), badgeCol) }
                    setPadding(px(10), px(5), px(10), px(5))
                })
                checksCard.addView(itemRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
                if (idx < checkRows.lastIndex) checksCard.addView(rule())
            }
            body.addView(checksCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

            // Re-enabled apps detail list if any drifted
            if (checkReport.cameBack.isNotEmpty()) {
                body.addView(sectionLabel("Turned back on after restart or update"),
                    LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
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
                    }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
                }
            }

            // 4. Post-Reboot Persistence Test Card (matches 04-check-my-tv.png)
            val rebootCard = outlinedCard(field, radius = 18, stroke = 1)
            rebootCard.addView(text("Post-Reboot Persistence Test", 15, bold = true))
            rebootCard.addView(text("Reboots TV, waits for port 5555 reconnect & re-checks apps.", 12, muted).apply {
                setPadding(0, px(4), 0, px(12))
            })
            val actionRow = row()
            actionRow.addView(button("Check again") { runCheck() },
                LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
            actionRow.addView(button("Restart TV & verify", emphasis = true) {
                confirm(
                    "Restart TV to verify?",
                    "Restarting checks that everything stays off after a reboot. It takes about 2 minutes."
                ) {
                    startRebootVerification(activeTv, body, ::runCheck)
                }
            }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(10) })
            rebootCard.addView(actionRow)
            body.addView(rebootCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
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
    val owner = content
    pageHeader("Free up space", "Inspect TV storage and trim system app caches")

    val pctPill = text("—%", 11, accent, bold = true).apply {
        background = rounded(tint, 10)
        setPadding(px(10), px(4), px(10), px(4))
    }
    val headline = text("Reading TV storage…", 20, bold = true)
    val subline = text("Checking /data partition capacity", 12, muted).apply {
        typeface = monoTypeface
        setPadding(0, px(3), 0, 0)
    }
    val storageBar = roundedProgress()

    val storageCard = outlinedCard(field, radius = 20, stroke = 1).apply {
        val topRow = row()
        topRow.addView(text("TV STORAGE OVERVIEW", 11, dim, bold = true).apply { letterSpacing = 0.08f },
            LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        topRow.addView(pctPill)
        addView(topRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
        addView(headline)
        addView(subline)
        addView(storageBar, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(9)).apply { topMargin = px(12) })
    }
    content.addView(storageCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

    fun measure() {
        tvTask({ details() }) { reading ->
            if (content !== owner) return@tvTask
            val free = reading.diskFree
            val total = reading.diskTotal
            val used = if (free != null && total != null) (total - free).coerceAtLeast(0L) else null
            val pct = reading.diskUsage ?: 0
            headline.text = "${formatBytes(free)} available"
            subline.text = "${formatBytes(used)} used  ·  ${formatBytes(total)} total"
            pctPill.text = "$pct% used"
            storageBar.progress = pct.coerceIn(0, 100)
        }
    }

    addButton("Clear app caches", emphasis = true) {
        confirm("Ask Android to trim caches?", "Android chooses which cached files to clear.") {
            tvTask({ command("pm trim-caches 999G") }) { result ->
                if (result.status == 0) {
                    report("Cache trim completed.", NoticeTone.SUCCESS)
                    measure()
                } else {
                    commandStatus(result, "Cache request sent to Android.")
                }
            }
        }
    }

    content.addView(sectionLabel("Reclaim more space"),
        LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(6); bottomMargin = px(8) })
    tiles(content, 1,
        Tile("Manage installed apps", Mark.APPS, "Uninstall or disable large TV apps") { show(Panel.APPS) },
        Tile("Browse TV files", Mark.FOLDER, "Remove old APKs, recordings & downloads") { show(Panel.FILES) }
    )
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

    // Header matching 03-cleanup-tv.png
    pageHeader("Clean Up TV", "Powered by CleanYourTV · 518 community device lists")

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
                backgroundTintList = null
                background = card(surface, 12, outlined = true)
            }
            area.addView(filter, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

            val results = column()
            val scroller = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                addView(results)
            }
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
                    val label = info?.tv?.takeIf { it.isNotBlank() }?.let { "$it\n$name" } ?: name
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

            // 1. Auto-Matched Profile Banner (matches 03-cleanup-tv.png)
            val profileBanner = column().apply {
                background = card(tint, 20, outlined = false).apply { setStroke(px(1), accent) }
                setPadding(px(16), px(12), px(16), px(14))
            }
            val bannerTop = row()
            val badgeLabel = when (scanResult.match.kind) {
                ProfileMatchKind.EXACT_MODEL -> "AUTO-MATCHED"
                ProfileMatchKind.BRAND_MATCH -> "CLOSEST MATCH"
                ProfileMatchKind.MANUAL -> "SELECTED LIST"
                ProfileMatchKind.AUTO_DETECTED -> "AUTO-DETECTED"
            }
            bannerTop.addView(text(badgeLabel, 10, accentBright, bold = true).apply {
                background = rounded(if (dark) 0xFF0C4A6E.toInt() else Color.WHITE, 9)
                setPadding(px(10), px(4), px(10), px(4))
                letterSpacing = 0.05f
            })
            bannerTop.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            bannerTop.addView(textButton("Change list  ›", "Change CleanYourTV list") {
                openProfilePicker(scanResult.catalog, scanResult.match.profileName, ::scan)
            }.apply {
                textSize = 12f
                minHeight = px(48)
                minimumHeight = px(48)
                setPadding(px(10), 0, px(4), 0)
            })
            profileBanner.addView(bannerTop)

            val tvTitle = scanResult.match.profileTitle ?: scanResult.match.profileName ?: activeTv.model
            profileBanner.addView(text(tvTitle, 17, bold = true).apply { setPadding(0, px(4), 0, 0) })
            val summarySub = buildString {
                append(scanResult.match.profileName?.let { "Matched via tv.json · " } ?: "CleanYourTV consensus · ")
                if (scanResult.enabledItems.isNotEmpty()) append("${scanResult.enabledItems.size} active bloatware apps found")
                else append("Your TV looks clean")
                if (scanResult.alreadyOffItems.isNotEmpty()) append(" · ${scanResult.alreadyOffItems.size} off")
            }
            profileBanner.addView(text(summarySub, 12, if (dark) 0xFFCBD5E1.toInt() else muted).apply {
                setPadding(0, px(4), 0, 0)
            })
            body.addView(profileBanner, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

            // 2. UI Animation Speedup Card (matches 03-cleanup-tv.png)
            val currentAnim = scanResult.animationScales.firstOrNull()?.trim() ?: "1.0"
            val isZero = currentAnim == "0" || currentAnim == "0.0"
            val isHalf = currentAnim == "0.5"
            val isNormal = !isZero && !isHalf

            val animCard = outlinedCard(field, radius = 18, stroke = 1)
            val animHeader = row()
            animHeader.addView(text("UI Animation Speedup", 15, bold = true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
            animHeader.addView(text("Undoable in Restore", 11, muted))
            animCard.addView(animHeader, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

            val animRow = row()
            fun animChip(label: String, active: Boolean, marginStartDp: Int, onPick: () -> Unit) {
                val chip = button(label) { onPick() }.apply {
                    textSize = 12f
                    minHeight = px(48)
                    minimumHeight = px(48)
                    setPadding(px(4), px(8), px(4), px(8))
                    setTextColor(if (active) Color.WHITE else muted)
                    background = interactiveBackground(
                        if (active) card(accentPill, 12, outlined = false).apply {
                            if (dark) setStroke(px(1), accent)
                        } else card(surfaceElevated, 12, outlined = true),
                        radius = 12
                    )
                }
                animRow.addView(chip, LinearLayout.LayoutParams(0, px(48), 1f).apply {
                    if (marginStartDp > 0) marginStart = px(marginStartDp)
                })
            }
            animChip("1.0x Normal", isNormal, 0) {
                tvTask({
                    writeAnimationScales(listOf("1.0", "1.0", "1.0"))
                    preferences.edit().remove(animBackupKey).apply()
                }) { report("TV animations reset to 1.0x.", NoticeTone.SUCCESS); scan() }
            }
            animChip("0.5x Snappy", isHalf, 8) {
                tvTask({
                    if (!preferences.contains(animBackupKey)) {
                        preferences.edit().putString(animBackupKey, readAnimationScales().joinToString(",")).apply()
                    }
                    writeAnimationScales(listOf("0.5", "0.5", "0.5"))
                }) { report("TV animations set to 0.5x.", NoticeTone.SUCCESS); scan() }
            }
            animChip("0x Instant", isZero, 8) {
                tvTask({
                    if (!preferences.contains(animBackupKey)) {
                        preferences.edit().putString(animBackupKey, readAnimationScales().joinToString(",")).apply()
                    }
                    writeAnimationScales(listOf("0", "0", "0"))
                }) { report("TV animations turned off.", NoticeTone.SUCCESS); scan() }
            }
            animCard.addView(animRow)
            body.addView(animCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

            // 3. SAFETY GUARDS ACTIVE Emerald Card (matches 03-cleanup-tv.png)
            val guardBanner = column().apply {
                background = card(greenBg, 18, outlined = false).apply { setStroke(px(1), greenStroke) }
                setPadding(px(16), px(13), px(16), px(13))
            }
            val guardedSuffix = if (scanResult.protectedItems.isNotEmpty()) " (${scanResult.protectedItems.size} PROTECTED)" else ""
            guardBanner.addView(text("SAFETY GUARDS ACTIVE$guardedSuffix", 11, successInk, bold = true).apply {
                letterSpacing = 0.06f
            })
            guardBanner.addView(text("Boot-loop & lock-out packages automatically skipped:", 12, if (dark) 0xFFD1FAE5.toInt() else ink).apply {
                setPadding(0, px(4), 0, 0)
            })
            guardBanner.addView(text("Home launcher · HDMI/AV inputs · setupwraith · SystemUI", 11, successInk).apply {
                typeface = monoTypeface
                setPadding(0, px(4), 0, 0)
            })
            if (scanResult.protectedItems.isNotEmpty()) {
                val guardedList = scanResult.protectedItems.joinToString("\n") { (item, reason) ->
                    "• ${displayName(item.id)}: $reason"
                }
                guardBanner.addView(text(guardedList, 11, muted).apply { setPadding(0, px(6), 0, 0) })
            }
            body.addView(guardBanner, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })

            // 4. RECOMMENDED TO TURN OFF checkbox list + 1-Tap Verify CTA (matches 03-cleanup-tv.png)
            if (scanResult.enabledItems.isEmpty()) {
                val cleanActions = row()
                cleanActions.addView(button("Check my TV", emphasis = true) { show(Panel.CHECK) },
                    LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
                cleanActions.addView(button("Restore apps") { show(Panel.RESTORE) },
                    LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(8) })
                body.addView(cleanActions, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })
            } else {
                val selectedIds = scanResult.enabledItems.mapTo(LinkedHashSet()) { it.id }
                val headerText = text("", 11, dim, bold = true).apply { letterSpacing = 0.07f }
                val primaryBtn = button("", emphasis = true) {}
                val listContainer = column()

                fun updateCounts() {
                    val count = selectedIds.size
                    headerText.text = "RECOMMENDED TO TURN OFF ($count SELECTED)"
                    primaryBtn.text = when (count) {
                        0 -> "Select apps above to turn off"
                        1 -> "Turn off 1 selected app & verify"
                        else -> "Turn off $count selected apps & verify"
                    }
                    primaryBtn.isEnabled = count > 0
                    primaryBtn.alpha = if (count > 0) 1f else 0.55f
                }

                fun renderSelectableRows() {
                    listContainer.removeAllViews()
                    for (item in scanResult.enabledItems) {
                        val checked = item.id in selectedIds
                        listContainer.addView(
                            cleanupCheckRow(item, scanResult.match.totalLists, checked) {
                                if (item.id in selectedIds) selectedIds.remove(item.id) else selectedIds.add(item.id)
                                updateCounts()
                                renderSelectableRows()
                            },
                            LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) }
                        )
                    }
                }

                val listHeader = row()
                listHeader.addView(headerText, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
                listHeader.addView(textButton("All") {
                    selectedIds.clear()
                    scanResult.enabledItems.forEach { selectedIds.add(it.id) }
                    updateCounts()
                    renderSelectableRows()
                }.apply {
                    textSize = 12f
                    minHeight = px(48)
                    minimumHeight = px(48)
                    setPadding(px(10), 0, px(10), 0)
                })
                listHeader.addView(textButton("None") {
                    selectedIds.clear()
                    updateCounts()
                    renderSelectableRows()
                }.apply {
                    textSize = 12f
                    minHeight = px(48)
                    minimumHeight = px(48)
                    setPadding(px(10), 0, px(6), 0)
                }, LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply { marginStart = px(4) })
                body.addView(listHeader, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(4) })

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
                            val doneCard = column().apply {
                                background = card(greenBg, 20, outlined = false).apply { setStroke(px(1), greenStroke) }
                                setPadding(px(16), px(15), px(16), px(15))
                            }
                            doneCard.addView(text("Cleanup complete", 20, bold = true))
                            doneCard.addView(text(
                                "Verified ${turnedOff.size} app(s) turned off." +
                                    if (skipped.isNotEmpty()) "\n${skipped.size} app(s) were kept on." else "",
                                14,
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

                updateCounts()
                renderSelectableRows()
                body.addView(listContainer)
                body.addView(primaryBtn, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(54)).apply { topMargin = px(6); bottomMargin = px(12) })
            }

            if (scanResult.alreadyOffItems.isNotEmpty()) {
                body.addView(sectionLabel("Already off (${scanResult.alreadyOffItems.size})"),
                    LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(6); bottomMargin = px(8) })
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
