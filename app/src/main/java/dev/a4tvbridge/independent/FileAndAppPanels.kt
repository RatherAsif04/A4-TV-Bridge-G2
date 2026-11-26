package dev.a4tvbridge.independent

import android.net.Uri
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun BridgeActivity.fileManager() {
    val current = folder
    heading("File Manager")
    val title = if (current == TvPath.root) "TV storage" else "TV storage / ${current.removePrefix("/sdcard/").replace("/", " / ")}"
    val place = outlinedCard()
    place.addView(text(title, 16, bold = true))
    val links = row()
    if (current != TvPath.root) links.addView(fileLink("Parent") {
        val parent = current.substringBeforeLast('/')
        if (folders.getOrNull(folders.lastIndex - 1) == parent) onBackPressed() else visit(parent)
    })
    links.addView(fileLink("Go to") { ask("TV folder", current) { selected -> visit(selected) } })
    links.addView(fileLink("Refresh", ::refresh))
    links.addView(fileLink("New folder") { ask("Folder name") { name -> tvTask({ createFolder(current, name) }) { refresh() } } })
    place.addView(links, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(6) })
    content.addView(place, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(20) })

    val list = column()
    content.addView(list)
    floatingAction("Upload", Mark.UPLOAD) { uploadFiles() }
    val owner = content
    tvTask({ entries(current) }) { files ->
        if (content !== owner) return@tvTask
        if (files.isEmpty()) list.addView(text("This folder is empty.", 16, muted))
        val directories = files.filter { it.folder && !it.link }
        if (directories.isNotEmpty()) list.addView(sectionTitle("Folders"))
        for (pair in directories.chunked(2)) {
            val line = row()
            pair.forEachIndexed { index, child ->
                val tile = column().apply {
                    contentDescription = "Open folder ${child.name}"
                    isFocusable = true
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(ripple), card(radius = corner), null)
                    setPadding(px(12), px(10), px(10), px(12))
                    setOnClickListener { visit(TvPath.child(current, child.name)) }
                }
                val icons = row()
                icons.addView(Glyph(this, Mark.FOLDER, accent, 28), LinearLayout.LayoutParams(px(30), px(30)))
                icons.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                icons.addView(fileMore(child.name) { fileOptions(current, child) }, LinearLayout.LayoutParams(px(24), px(29)))
                tile.addView(icons)
                tile.addView(text(child.name, 16, bold = true), LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
                    topMargin = px(9)
                })
                line.addView(tile, LinearLayout.LayoutParams(0, px(94), 1f).apply {
                    if (index > 0) marginStart = px(10)
                })
            }
            if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = px(10) })
            list.addView(line, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
        }
        val documents = files.filter { !it.folder || it.link }
        if (documents.isNotEmpty()) list.addView(sectionTitle("Files"))
        for (child in documents) {
            val entry = row().apply {
                setPadding(px(12), px(4), px(10), px(4))
                background = card(radius = corner)
                isFocusable = true
                contentDescription = "File options for ${child.name}"
                setOnClickListener { fileOptions(current, child) }
            }
            entry.addView(Glyph(this, Mark.DOCUMENT, muted), LinearLayout.LayoutParams(px(23), px(23)))
            entry.addView(text(child.name, 16), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(11) })
            entry.addView(fileMore(child.name) { fileOptions(current, child) }, LinearLayout.LayoutParams(px(24), px(32)))
            list.addView(entry, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(56)).apply { bottomMargin = px(10) })
        }
    }
}

private fun BridgeActivity.sectionTitle(label: String) = text(label, 16, bold = true).apply { setPadding(0, 0, 0, px(11)) }

private fun BridgeActivity.fileLink(label: String, action: () -> Unit) = textButton(label, action = action).apply {
    textSize = 13f
    setPadding(px(10), px(4), px(10), px(4))
    minHeight = px(44)
    minimumHeight = px(44)
}

private fun BridgeActivity.fileMore(label: String, action: () -> Unit) = Glyph(this, Mark.MORE, muted).apply {
    contentDescription = "Options for $label"
    isFocusable = true
    setOnClickListener { action() }
}

private fun BridgeActivity.visit(input: String) {
    val path = runCatching { TvPath.folder(input) }.getOrElse { report(it.message.orEmpty()); return }
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
            report("Uploaded $it item(s).")
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
    heading("Apps")
    val query = input("Search apps")
    val list = column()
    val modes = arrayOf("Installed", "Built-in", "Running")
    var activeMode = 0
    var installedApps = emptyList<InstalledPackage>()
    var running = emptySet<String>()
    fun display() {
        val pattern = query.text.toString().trim()
        list.removeAllViews()
        val matching = installedApps.asSequence().filter { item ->
            item.id.contains(pattern, true) && when (activeMode) {
                0 -> !item.system
                1 -> item.system
                else -> item.id in running
            }
        }.toList()
        if (matching.isEmpty()) list.addView(text("No matching apps.", 15, muted))
        for (app in matching) {
            val status = if (!app.enabled) "Disabled" else if (app.id in running) "Running" else ""
            val nickname = displayName(app.id)
            tiles(list, 1, Tile(nickname, Mark.APPS, listOf(app.id, status).filter(String::isNotEmpty).joinToString(" · ")) {
                packageOptions(app)
            })
        }
    }
    val tabStrip = row().apply {
        background = card(field, 14, outlined = true)
        setPadding(px(4), px(4), px(4), px(4))
    }
    lateinit var styleTabs: () -> Unit
    val tabButtons = modes.mapIndexed { index, mode ->
        textButton(mode) { activeMode = index; styleTabs(); display() }
    }
    styleTabs = {
        tabButtons.forEachIndexed { idx, btn ->
            val active = idx == activeMode
            btn.isSelected = active
            btn.background = if (active) card(tint, 10, false) else null
            btn.setTextColor(if (active) accent else muted)
        }
    }
    tabButtons.forEach { tabStrip.addView(it, LinearLayout.LayoutParams(0, px(42), 1f)) }
    styleTabs()
    content.addView(tabStrip, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
    content.addView(query)
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
    val segment = id.split('.').lastOrNull { it !in setOf("tv", "androidtv", "app", "leanback") }.orEmpty()
    val cleaned = segment.replace('_', ' ').replace('-', ' ').trim()
    return cleaned.ifBlank { id }.replaceFirstChar(Char::uppercase)
}

private fun BridgeActivity.packageOptions(item: InstalledPackage) {
    val id = packageId(item.id)
    val tv = connection ?: return
    val pinned = pinnedPackages(tv).contains(id)
    choose(item.id, listOf(
        (if (pinned) "Remove shortcut" else "Add shortcut") to {
            setPinned(tv, id, !pinned)
            report(if (pinned) "Shortcut removed." else "Shortcut added to Remote.")
        },
        "Launch on TV" to { tvTask({ openApp(id) }) { report("Opened on the TV.") } },
        "Permissions" to { permissionsForPackage(id) },
        "Export APK" to { exportApks(id) },
        "Force stop" to { tvTask({ command("am force-stop --user 0 $id") }) { commandStatus(it, "Stopped.") } },
        "Clear app data" to {
            confirm("Clear app data?", "This resets the app’s settings and removes its stored data.") {
                tvTask({ command("pm clear --user 0 $id") }) { commandStatus(it, "App data cleared.") }
            }
        },
        (if (item.enabled) "Disable" else "Enable") to {
            confirm(if (item.enabled) "Disable this app?" else "Enable this app?", id) {
                tvTask({
                    if (item.enabled) {
                        check(!protectForCleanup(id)) { "This app is needed by the TV." }
                        rememberDisabled(this, id)
                    } else {
                        togglePackage(id, true)
                        updateRestore(this, id, false)
                    }
                }) { refresh() }
            }
        },
        (if (item.system) "Uninstall for this TV" else "Uninstall for good") to {
            val note = if (item.system) "Removes the built-in app for the current Android user. Restore can reinstall it when Android still has the system copy."
                else "This removes the installed app and its data. You can reinstall it from its original source."
            confirm("Uninstall app?", note) {
                tvTask({
                    check(!protectForCleanup(id)) { "This app is needed by the TV." }
                    val result = command("pm uninstall --user 0 $id")
                    if (result.status == 0 && result.text.lineSequence().any { it.trim() == "Success" } && item.system) {
                        recordSystemRemoval(this, id, removed = true)
                    }
                    result
                }) { result ->
                    if (result.status == 0 && result.text.lineSequence().any { it.trim() == "Success" }) refresh()
                    else report(result.text.ifBlank { "The TV refused to uninstall the app." })
                }
            }
        },
        "Open app settings" to {
            tvTask({ command("am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$id") }) {
                commandStatus(it, "Opened on TV.")
            }
        },
        "Open app store on TV" to {
            tvTask({ command("am start -a android.intent.action.VIEW -d 'market://details?id=$id'") }) {
                commandStatus(it, "Opened on TV.")
            }
        },
        "Open app store on this device" to { openLink("https://play.google.com/store/apps/details?id=$id") },
    ))
}

private fun BridgeActivity.permissionsForPackage(id: String) {
    val record = Regex("""^\s+([A-Za-z0-9_.]+): granted=(true|false).*""")
    tvTask({
        val dump = command("dumpsys package $id")
        val user = dump.text.substringAfter("User 0:", "").substringBefore("\n    User ")
        user.substringAfter("runtime permissions:", "").lineSequence().mapNotNull { line ->
            record.matchEntire(line)?.let { it.groupValues[1] to it.groupValues[2].toBoolean() }
        }.distinctBy { it.first }.toList()
    }) { permissions ->
        if (permissions.isEmpty()) return@tvTask report("The TV did not list runtime permissions for this app.")
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

private fun BridgeActivity.exportApks(id: String) {
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
    heading("Install Apps")
    paragraph("Choose a single APK or its matching base and split APKs. Android installs them on the connected TV.")
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
                }) { report("Installed on the TV.") }
            }
        }
    }
    floatingAction("Choose APKs", Mark.ADD) {
        filePickerSheet("Choose APKs", "Select an APK or the base APK and all matching splits.", ::pickApks)
    }
    heading("1 Click App Install", 19)
    val catalog = outlinedCard()
    for ((index, release) in ReleaseApp.entries.withIndex()) {
        if (index != 0) catalog.addView(rule())
        val item = row().apply { minimumHeight = px(60) }
        item.addView(text(release.title, 16, bold = true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        item.addView(button("Install", description = "Install ${release.title}") {
            confirm("Install ${release.title}?", "Download the verified release and send it to the TV.") {
                tvTask({
                    val abis = command("getprop ro.product.cpu.abilist").text
                        .split(',').map(String::trim).filter(String::isNotEmpty)
                    val file = downloadOfficialRelease(this@installPage, release, abis)
                    try { install(listOf(file)) } finally { file.delete() }
                }) { report("Installed ${release.title}.") }
            }
        }.apply {
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ripple), card(tint, 24, false), null
            )
            setTextColor(accent)
            textSize = 15f
            minimumHeight = px(40)
            minHeight = px(40)
        }, LinearLayout.LayoutParams(px(95), px(40)))
        catalog.addView(item, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
    }
    content.addView(catalog, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
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
                minimumHeight = px(50)
                setPadding(px(16), px(12), px(16), px(12))
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(ripple),
                    card(field, corner, outlined = true),
                    null
                )
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
    report(if (result.status == 0) message else result.text.ifBlank { "The TV rejected this command." })
}
