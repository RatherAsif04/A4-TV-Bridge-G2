package dev.a4tvbridge.independent

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val APPS_INITIAL_RENDER_LIMIT = 60

internal fun BridgeActivity.fileManager() {
    val current = folder
    pageHeader("File Manager", "Browse TV storage, create folders, download, or upload files")
    val title = if (current == TvPath.root) "TV storage (/sdcard)" else "TV storage / ${current.removePrefix("/sdcard/").replace("/", " / ")}"
    val place = outlinedCard(field, radius = 18, stroke = 1)
    place.addView(text("CURRENT PATH", 10, dim, bold = true).apply { letterSpacing = 0.08f })
    place.addView(text(title, 14, ink, bold = true).apply {
        typeface = monoTypeface
        setPadding(0, px(4), 0, px(10))
    })

    val actionChips = row()
    fun addChip(label: String, accentStyle: Boolean = false, action: () -> Unit) {
        val chip = text(label, 12, if (accentStyle) accent else ink, bold = true).apply {
            background = interactiveBackground(
                card(if (accentStyle) tint else surfaceElevated, 12, outlined = !accentStyle).apply {
                    if (accentStyle) setStroke(px(1), if (dark) accentBright else edge)
                },
                radius = 12
            )
            minHeight = px(48)
            minimumHeight = px(48)
            gravity = Gravity.CENTER
            setPadding(px(13), 0, px(13), 0)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { action() }
        }
        actionChips.addView(chip, LinearLayout.LayoutParams(BridgeActivity.WRAP, px(48)).apply {
            if (actionChips.childCount > 0) marginStart = px(8)
        })
    }
    if (current != TvPath.root) addChip("Up", accentStyle = true) {
        val parent = current.substringBeforeLast('/')
        if (folders.getOrNull(folders.lastIndex - 1) == parent) handleBackNavigation() else visit(parent)
    }
    addChip("Send Files", accentStyle = true) { show(Panel.SEND) }
    addChip("New folder") { ask("Folder name") { name -> tvTask({ createFolder(current, name) }) { refresh() } } }
    addChip("Go to") { ask("TV folder", current) { selected -> visit(selected) } }
    addChip("Refresh", action = ::refresh)

    place.addView(HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(actionChips)
    }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
    content.addView(place, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(16) })

    val list = column()
    content.addView(list)
    floatingAction("Upload", Mark.UPLOAD) { uploadFiles() }
    val owner = content
    tvTask({ entries(current) }) { files ->
        if (content !== owner) return@tvTask
        if (files.isEmpty()) {
            list.addView(outlinedCard(field, radius = 16, stroke = 1).apply {
                addView(text("This folder is empty.", 14, muted))
            })
        }
        val directories = files.filter { it.folder && !it.link }
        if (directories.isNotEmpty()) {
            list.addView(sectionLabel("Folders (${directories.size})"),
                LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
        }
        for (pair in directories.chunked(2)) {
            val line = row()
            pair.forEachIndexed { index, child ->
                val tile = column().apply {
                    contentDescription = "Open folder ${child.name}"
                    isClickable = true
                    isFocusable = true
                    background = interactiveBackground(card(field, corner, outlined = true), radius = corner)
                    setPadding(px(12), px(6), px(4), px(12))
                    setOnClickListener { visit(TvPath.child(current, child.name)) }
                }
                val icons = row()
                icons.addView(Glyph(this, Mark.FOLDER, orange, 24), LinearLayout.LayoutParams(px(28), px(28)))
                icons.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                icons.addView(fileMore(child.name) { fileOptions(current, child) }, LinearLayout.LayoutParams(px(48), px(48)))
                tile.addView(icons)
                tile.addView(text(child.name, 14, bold = true).apply {
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, 0, px(8), 0)
                }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
                    topMargin = px(2)
                })
                line.addView(tile, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply {
                    if (index > 0) marginStart = px(10)
                })
            }
            if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = px(10) })
            list.addView(line, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
        }
        val documents = files.filter { !it.folder || it.link }
        if (documents.isNotEmpty()) {
            list.addView(sectionLabel("Files (${documents.size})"),
                LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(4); bottomMargin = px(8) })
        }
        for (child in documents) {
            val ext = child.name.substringAfterLast('.', "").uppercase().take(4)
            val entry = row().apply {
                setPadding(px(12), px(4), px(4), px(4))
                minimumHeight = px(58)
                background = interactiveBackground(card(field, corner, outlined = true), radius = corner)
                isClickable = true
                isFocusable = true
                contentDescription = "File options for ${child.name}"
                setOnClickListener { fileOptions(current, child) }
            }
            entry.addView(Glyph(this, Mark.DOCUMENT, accent, 20), LinearLayout.LayoutParams(px(24), px(24)))
            val nameCol = column().apply {
                addView(text(child.name, 14, ink, bold = true).apply {
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (ext.isNotBlank() && ext.length <= 4) {
                    addView(text(if (child.link) "LINK · $ext" else "$ext file", 11, muted).apply {
                        typeface = monoTypeface
                        setPadding(0, px(2), 0, 0)
                    })
                }
            }
            entry.addView(nameCol, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(11) })
            entry.addView(fileMore(child.name) { fileOptions(current, child) }, LinearLayout.LayoutParams(px(48), px(48)))
            list.addView(entry, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
        }
    }
}

private fun BridgeActivity.fileMore(label: String, action: () -> Unit) = Glyph(this, Mark.MORE, muted, 18).apply {
    contentDescription = "Options for $label"
    background = RippleDrawable(ColorStateList.valueOf(ripple), null, card(radius = 24))
    isClickable = true
    isFocusable = true
    setOnClickListener { action() }
}

private fun BridgeActivity.visit(input: String) {
    val path = runCatching { TvPath.folder(input) }.getOrElse { report(it.message.orEmpty(), NoticeTone.ERROR); return }
    if (path == folder) return
    tvTask({ entries(path) }) {
        folders.add(path)
        refresh()
    }
}

internal fun BridgeActivity.uploadFiles(target: String = folder) {
    chooseDocuments("*/*", true) { selected ->
        tvTask({
            selected.forEach { uri ->
                val (name, temporary) = stage(uri)
                try {
                    if (target == "/sdcard/Download") prepareDownloads()
                    upload(target, name, temporary)
                } finally { temporary.delete() }
            }
            selected.size
        }) {
            refresh()
            report("Uploaded $it item(s).", NoticeTone.SUCCESS)
        }
    }
}

private fun BridgeActivity.fileOptions(current: String, item: RemoteFile) {
    val actions = mutableListOf<Pair<String, () -> Unit>>()
    if (!item.folder && !item.link) actions += "Download" to {
        tvTask({
            val temp = File.createTempFile("download", ".part", cacheDir)
            try { download(current, item.name, temp); temp }
            catch (error: Exception) { temp.delete(); throw error }
        }) { saveDocument(it, item.name, "application/octet-stream") }
    }
    actions += "Rename" to {
        ask("New name", item.name) { name -> tvTask({ rename(current, item.name, name) }) { refresh() } }
    }
    actions += "Delete" to {
        confirm("Delete ${item.name}?", if (item.folder) "Only an empty folder can be removed." else "This removes the file from the TV.") {
            tvTask({ delete(current, item) }) { refresh() }
        }
    }
    choose(item.name, actions)
}

internal fun BridgeActivity.apps() {
    pageHeader("Apps", "Tap an app to inspect, disable, export APK, or pin to Remote")

    // Quick-access A4 pill strip (replaces old intermediate Apps & Files hub)
    val quickStrip = row()
    val quickItems = listOf(
        Triple("1-Click Install", Mark.DOWNLOAD) { show(Panel.INSTALL) },
        Triple("File Manager", Mark.FOLDER) { show(Panel.FILES) },
        Triple("Send Files", Mark.UPLOAD) { show(Panel.SEND) },
        Triple("Refresh", Mark.RESTORE) { refresh() }
    )
    quickItems.forEachIndexed { idx, (label, icon, onTap) ->
        val isAccent = idx == 0
        val chip = row().apply {
            background = interactiveBackground(
                card(if (isAccent) tint else field, 14, outlined = !isAccent).apply {
                    if (isAccent) setStroke(px(1), if (dark) accentBright else edge)
                },
                radius = 14
            )
            minimumHeight = px(48)
            setPadding(px(12), 0, px(13), 0)
            isClickable = true
            isFocusable = true
            contentDescription = label
            addView(Glyph(this@apps, icon, if (isAccent) accent else muted, 16),
                LinearLayout.LayoutParams(px(18), px(18)).apply { marginEnd = px(6) })
            addView(text(label, 12, if (isAccent) accent else ink, bold = true))
            setOnClickListener { onTap() }
        }
        quickStrip.addView(chip, LinearLayout.LayoutParams(BridgeActivity.WRAP, px(48)).apply {
            if (idx > 0) marginStart = px(8)
        })
    }
    content.addView(HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(quickStrip)
    }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

    val query = input("Search apps or package name").apply {
        setSingleLine()
        setText(appsSearchQuery)
        setSelection(text.length)
        background = card(field, 14, outlined = true)
        setPadding(px(14), px(12), px(14), px(12))
    }
    val countLabel = text("", 12, dim, bold = true).apply {
        letterSpacing = 0.07f
        setPadding(0, px(12), 0, px(8))
    }
    val list = column()
    val modes = arrayOf("Installed", "Built-in", "Running")
    var activeMode = appsFilterTab.coerceIn(0, 2)
    var showAllEntries = false
    var installedApps = emptyList<InstalledPackage>()
    var running = emptySet<String>()

    fun display() {
        val pattern = query.text.toString().trim()
        appsSearchQuery = query.text.toString()
        list.removeAllViews()
        val matching = installedApps.asSequence().filter { item ->
            (item.id.contains(pattern, true) || displayName(item.id).contains(pattern, true)) && when (activeMode) {
                0 -> !item.system
                1 -> item.system
                else -> item.id in running
            }
        }.toList()
        countLabel.text = when (activeMode) {
            0 -> "APPS YOU INSTALLED (${matching.size})"
            1 -> "BUILT-IN TV APPS (${matching.size})"
            else -> "RUNNING APPS (${matching.size})"
        }
        if (matching.isEmpty()) {
            list.addView(outlinedCard(field, radius = 16, stroke = 1).apply {
                addView(text(if (pattern.isNotBlank()) "No apps match \"$pattern\"." else "No apps in this category.", 14, muted))
            })
            return
        }
        val visible = if (showAllEntries || pattern.isNotBlank() || matching.size <= APPS_INITIAL_RENDER_LIMIT) {
            matching
        } else {
            matching.take(APPS_INITIAL_RENDER_LIMIT)
        }
        for (app in visible) {
            val status = if (!app.enabled) "Off" else if (app.id in running) "Running" else ""
            val nickname = displayName(app.id)
            val detail = listOf(app.id, status).filter(String::isNotEmpty).joinToString(" · ")
            val entry = row().apply {
                background = interactiveBackground(card(field, corner, outlined = true), radius = corner)
                minimumHeight = px(64)
                setPadding(px(12), px(10), px(12), px(10))
                isFocusable = true
                isClickable = true
                contentDescription = nickname
                setOnClickListener { packageOptions(app) }
            }
            entry.addView(appBadge(app.id), LinearLayout.LayoutParams(px(36), px(36)))
            val copy = column().apply {
                addView(text(nickname, 15, bold = true))
                addView(text(detail, 11, muted).apply {
                    typeface = monoTypeface
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, px(3), 0, 0)
                })
            }
            entry.addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(12) })
            entry.addView(text("›", 20, muted), LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply { marginStart = px(6) })
            list.addView(entry, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
        }
        if (visible.size < matching.size) {
            list.addView(button("Show all ${matching.size} apps") {
                showAllEntries = true
                display()
            }, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(48)).apply { topMargin = px(4) })
        }
    }

    val tabStrip = row().apply {
        background = card(field, 14, outlined = true)
        setPadding(px(4), px(4), px(4), px(4))
    }
    lateinit var styleTabs: () -> Unit
    val tabButtons = modes.mapIndexed { index, mode ->
        textButton(mode) {
            activeMode = index
            appsFilterTab = index
            showAllEntries = false
            styleTabs()
            display()
        }.apply {
            textSize = 13f
            minHeight = px(40)
            minimumHeight = px(40)
        }
    }
    styleTabs = {
        tabButtons.forEachIndexed { idx, btn ->
            val active = idx == activeMode
            btn.isSelected = active
            btn.background = if (active) {
                card(accentPill, 10, outlined = false).apply {
                    if (dark) setStroke(px(1), accent)
                }
            } else null
            btn.setTextColor(if (active) Color.WHITE else muted)
        }
    }
    tabButtons.forEach { tabStrip.addView(it, LinearLayout.LayoutParams(0, px(40), 1f)) }
    styleTabs()
    content.addView(tabStrip, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(48)).apply { bottomMargin = px(10) })
    content.addView(query, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
    content.addView(countLabel)
    content.addView(list)
    val owner = content
    query.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { display() }
        override fun afterTextChanged(s: Editable?) = Unit
    })
    tvTask({ installed() to runningPackages() }) { (all, current) ->
        if (content !== owner) return@tvTask
        installedApps = all
        running = current
        display()
    }
}

internal fun displayName(id: String): String {
    if (id.equals(ReleaseApp.PROJECTIVY.packageName, ignoreCase = true)) return ReleaseApp.PROJECTIVY.title
    val segment = id.split('.').lastOrNull { it !in setOf("tv", "androidtv", "app", "leanback") }.orEmpty()
    val cleaned = segment.replace('_', ' ').replace('-', ' ').trim()
    return cleaned.ifBlank { id }.replaceFirstChar(Char::uppercase)
}

internal fun BridgeActivity.permissionsForPackage(id: String) {
    val record = Regex("""^\s+([A-Za-z0-9_.]+): granted=(true|false).*""")
    tvTask({
        val dump = command("dumpsys package $id")
        val user = dump.text.substringAfter("User 0:", "").substringBefore("\n    User ")
        user.substringAfter("runtime permissions:", "").lineSequence().mapNotNull { line ->
            record.matchEntire(line)?.let { it.groupValues[1] to it.groupValues[2].toBoolean() }
        }.distinctBy { it.first }.toList()
    }) { permissions ->
        if (permissions.isEmpty()) return@tvTask report("The TV did not list runtime permissions for this app.", NoticeTone.INFO)
        choose("Runtime permissions", permissions.map { (permission, granted) ->
            val verb = if (granted) "revoke" else "grant"
            "${permission.substringAfterLast('.')} · ${if (granted) "Allowed" else "Denied"}" to {
                confirm("${verb.replaceFirstChar(Char::uppercase)} permission?", permission) {
                    tvTask({ command("pm $verb --user 0 $id ${shellText(permission)}") }) { commandStatus(it, "Permission updated.") }
                }
            }
        })
    }
}

internal fun BridgeActivity.exportApks(id: String) {
    tvTask({
        val parts = mutableListOf<Pair<String, File>>()
        var bundle: File? = null
        try {
            exportPackage(id) { name, write ->
                val output = File.createTempFile("export", ".apk", cacheDir)
                parts.add(name to output)
                write(output)
            }
            if (parts.size == 1) parts.removeAt(0).second to "$id.apk"
            else {
                val zip = File.createTempFile("export", ".zip", cacheDir)
                bundle = zip
                ZipOutputStream(zip.outputStream()).use { out ->
                    for ((name, file) in parts) {
                        out.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
                zip to "$id.zip"
            }
        } catch (error: Exception) {
            bundle?.delete()
            throw error
        } finally {
            parts.forEach { it.second.delete() }
        }
    }) { (file, name) ->
        saveDocument(file, name, if (name.endsWith(".zip")) "application/zip" else "application/vnd.android.package-archive")
    }
}

internal fun BridgeActivity.installPage() {
    val owner = content
    pageHeader("Install Apps", "1-tap verified GitHub releases or sideload local APK / split APKs")

    fun pickApks() {
        chooseDocuments("*/*", true) { uris ->
            confirm("Install on TV?", "Send ${uris.size} selected package file(s) to the connected TV.") {
                tvTask({
                    val apks = mutableListOf<File>()
                    try {
                        for (uri in uris) {
                            val (name, file) = stage(uri)
                            apks.add(file)
                            require(name.endsWith(".apk", true)) { "Select APK files only." }
                        }
                        install(apks)
                    } finally {
                        apks.forEach(File::delete)
                    }
                }) { report("Installed on the TV.", NoticeTone.SUCCESS) }
            }
        }
    }
    floatingAction("Choose APKs", Mark.ADD, ::pickApks)

    content.addView(sectionLabel("1-Click Verified TV Apps"),
        LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

    val actionButtons = mutableMapOf<ReleaseApp, TextView>()
    val statusSubtitles = mutableMapOf<ReleaseApp, TextView>()
    var installedSet = emptySet<String>()

    for (release in ReleaseApp.entries) {
        val rowCard = row().apply {
            background = interactiveBackground(card(field, 18, outlined = true), radius = 18)
            setPadding(px(14), px(12), px(12), px(12))
            isClickable = true
            isFocusable = true
            contentDescription = "Install ${release.title}"
        }
        rowCard.addView(appBadge(release.packageName), LinearLayout.LayoutParams(px(40), px(40)).apply { marginEnd = px(12) })
        val copy = column()
        copy.addView(text(release.title, 15, bold = true))
        copy.addView(text(release.summary, 12, muted).apply {
            setPadding(0, px(2), 0, 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val repoLine = text(release.repository, 11, dim).apply {
            typeface = monoTypeface
            setPadding(0, px(3), 0, 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        copy.addView(repoLine)
        statusSubtitles[release] = repoLine
        rowCard.addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(10) })

        val pill = text("Install", 12, accent, bold = true).apply {
            background = card(tint, 14, outlined = false).apply {
                setStroke(px(1), if (dark) accentBright else edge)
            }
            minHeight = px(48)
            minimumHeight = px(48)
            gravity = Gravity.CENTER
            setPadding(px(16), 0, px(16), 0)
        }
        actionButtons[release] = pill
        rowCard.addView(pill, LinearLayout.LayoutParams(BridgeActivity.WRAP, px(48)))

        val triggerInstall = {
            val already = release.packageName.lowercase() in installedSet
            val verb = if (already) "Update" else "Install"
            installOfficialRelease(release, actionVerb = verb) {
                report(if (already) "Updated ${release.title}." else "Installed ${release.title}.", NoticeTone.SUCCESS)
                refresh()
            }
        }
        rowCard.setOnClickListener { triggerInstall() }
        content.addView(rowCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
    }

    // Check which 1-Click apps are already installed on the TV
    tvTask({ installed().mapTo(HashSet()) { it.id.lowercase() } }) { present ->
        if (content !== owner) return@tvTask
        installedSet = present
        for (release in ReleaseApp.entries) {
            val isInstalled = release.packageName.lowercase() in present
            val pill = actionButtons[release] ?: continue
            val repoLine = statusSubtitles[release]
            if (isInstalled) {
                pill.text = "Update"
                pill.setTextColor(successInk)
                pill.background = card(greenBg, 14, outlined = false).apply { setStroke(px(1), greenStroke) }
                repoLine?.text = "Installed · ${release.repository}"
                repoLine?.setTextColor(successInk)
            }
        }
    }
}

internal fun BridgeActivity.installOfficialRelease(
    release: ReleaseApp,
    actionVerb: String = "Install",
    onInstalled: () -> Unit
) {
    confirm(
        "$actionVerb ${release.title}?",
        "Download the latest verified release from ${release.repository} and send it to the TV."
    ) {
        tvTask({
            val abis = command("getprop ro.product.cpu.abilist").text
                .split(',').map(String::trim).filter(String::isNotEmpty)
            val file = downloadOfficialRelease(this@installOfficialRelease, release, abis)
            try { install(listOf(file)) } finally { file.delete() }
        }) { onInstalled() }
    }
}

internal fun BridgeActivity.stage(uri: Uri): Pair<String, File> {
    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { result ->
        if (result.moveToFirst()) result.getString(0) else null
    } ?: "file"
    val temp = File.createTempFile("document", ".data", cacheDir)
    try {
        val source = contentResolver.openInputStream(uri) ?: error("Could not read the selected file.")
        source.use { input -> temp.outputStream().use { input.copyTo(it) } }
        return name to temp
    } catch (error: Exception) {
        temp.delete()
        throw error
    }
}

internal fun BridgeActivity.choose(title: String, actions: List<Pair<String, () -> Unit>>) {
    showSheet(title) { popup, sheet ->
        val list = column()
        for ((index, entry) in actions.withIndex()) {
            val (label, action) = entry
            val item = row().apply {
                minimumHeight = px(52)
                setPadding(px(16), px(12), px(16), px(12))
                background = interactiveBackground(card(field, corner, outlined = true), radius = corner)
                contentDescription = label
                isFocusable = true
                isClickable = true
                addView(text(label, 15, ink, bold = true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
                addView(text("›", 21, muted), LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply { marginStart = px(8) })
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
            }
            list.addView(item, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
                if (index < actions.lastIndex) bottomMargin = px(8)
            })
        }
        attachSheetBody(sheet, list)
    }
}

internal fun BridgeActivity.commandStatus(result: TerminalResult, message: String) {
    if (result.status == 0) report(message, NoticeTone.SUCCESS)
    else report(result.text.ifBlank { "The TV rejected this command." }, NoticeTone.ERROR)
}
