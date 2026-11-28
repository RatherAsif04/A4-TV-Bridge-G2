package dev.a4tvbridge.independent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class Panel {
    DASHBOARD, LIBRARY, APPS, FILES, SHELL, CARE, CLEAN, CHECK, RESTORE, SPACE, SEND,
    REMOTE, MIRROR, INFO, INSTALL, APPEARANCE, HOME, ADB_GUIDE
}
internal val releaseRoot = "https://github.com/RatherAsif04/A4-TV-Bridge"

class BridgeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val preferences by lazy { getSharedPreferences("bridge", MODE_PRIVATE) }
    internal var connection: TvConnection? = null
        private set
    private var page = Panel.DASHBOARD
    private val screens = ArrayDeque<Panel>()
    internal val folders = mutableListOf(TvPath.root)
    internal val folder get() = folders.last()
    private var polling: Job? = null
    private val inputQueue = Channel<Pair<TvConnection, TvConnection.() -> Unit>>(32)
    private var activeOperation = false
    internal lateinit var content: LinearLayout
        private set
    internal lateinit var screenBody: FrameLayout
        private set
    private var shellCommand = ""
    private var shellOutput = ""
    private lateinit var notice: TextView
    internal var dark = false
        private set
    internal val ink get() = if (dark) 0xFFF8FAFC.toInt() else 0xFF0F172A.toInt()
    internal val muted get() = if (dark) 0xFF94A3B8.toInt() else 0xFF475569.toInt()
    internal val dim get() = 0xFF64748B.toInt()
    internal val accent get() = if (dark) 0xFF38BDF8.toInt() else 0xFF00587B.toInt()
    internal val accentStrong get() = if (dark) 0xFF0077A8.toInt() else 0xFF00587B.toInt()
    internal val accentPill get() = if (dark) 0xFF00587B.toInt() else 0xFF00587B.toInt()
    internal val field get() = if (dark) 0xFF0D1219.toInt() else 0xFFFFFFFF.toInt()
    internal val surface get() = if (dark) 0xFF05070A.toInt() else 0xFFF1F5F9.toInt()
    internal val edge get() = if (dark) 0xFF1E293B.toInt() else 0xFFCBD5E1.toInt()
    internal val tint get() = if (dark) 0xFF091D2C.toInt() else 0xFFE0F2FE.toInt()
    internal val onAccent get() = Color.WHITE
    internal val green get() = if (dark) 0xFF34D399.toInt() else 0xFF059669.toInt()
    internal val greenBg get() = if (dark) 0xFF062118.toInt() else 0xFFECFDF5.toInt()
    internal val greenStroke get() = 0xFF10B981.toInt()
    internal val purple get() = if (dark) 0xFFA78BFA.toInt() else 0xFF6D28D9.toInt()
    internal val orange get() = if (dark) 0xFFF59E0B.toInt() else 0xFFD97706.toInt()
    internal val rose get() = 0xFFF43F5E.toInt()
    internal val ripple get() = if (dark) 0x3338BDF8 else 0x2200587B
    internal val corner get() = 18
    internal val dialogTheme get() = if (dark) R.style.BridgeDialogDark else R.style.BridgeDialogLight
    private var onDocuments: ((List<Uri>) -> Unit)? = null
    private var pendingSave: File? = null
    internal var availableUpdate: AppUpdateInfo? = null
    internal var pendingUpdateApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvConnection.configureIdentity(filesDir)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        draw()
        scope.launch {
            for ((tv, action) in inputQueue) {
                if (connection !== tv) continue
                try {
                    withContext(Dispatchers.IO) { tv.action() }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    report(error.message?.take(180) ?: "Command failed.")
                }
            }
        }
        checkAppUpdateOnStartup()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        draw()
    }

    override fun onStop() {
        super.onStop()
        polling?.cancel()
        polling = null
    }

    override fun onStart() {
        super.onStart()
        val stagedApk = pendingUpdateApk
        if (stagedApk != null && stagedApk.isFile &&
            (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())
        ) {
            launchApkInstaller(stagedApk)
        }
        if (page in setOf(Panel.DASHBOARD, Panel.MIRROR, Panel.INFO)) draw()
    }

    override fun onDestroy() {
        scope.cancel()
        connection?.close()
        pendingSave?.delete()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (page == Panel.FILES && folders.size > 1) {
            folders.removeAt(folders.lastIndex)
            draw()
        } else if (screens.isNotEmpty()) {
            page = screens.removeLast()
            draw()
        } else if (page != Panel.DASHBOARD) show(Panel.DASHBOARD)
        else super.onBackPressed()
    }

    internal fun show(next: Panel, rememberPage: Boolean = true) {
        if (next != page && rememberPage) {
            if (next in primaryPanels && page in primaryPanels) screens.clear()
            if (next != Panel.DASHBOARD) screens.addLast(page)
            else screens.clear()
        }
        page = next
        draw()
    }

    internal fun refresh() = draw()

    private fun draw() {
        polling?.cancel()
        polling = null
        dark = appearanceChoice().usesDark(systemUsesDark())
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surface))
        val shell = column().apply { setBackgroundColor(surface) }
        ViewCompat.setOnApplyWindowInsetsListener(shell) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            val sides = px(if (resources.configuration.screenWidthDp < 360) 14 else 18)
            view.setPadding(bars.left + sides, bars.top + px(12), bars.right + sides, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(shell)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.requestApplyInsets(shell)

        val header = row()
        fun addHeaderAction(icon: Mark, label: String, action: () -> Unit) {
            header.addView(Glyph(this, icon, accent, 24).apply {
                contentDescription = label
                isFocusable = true
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(px(48), px(48)))
        }
        val settingsPage = page in setOf(Panel.APPEARANCE, Panel.ADB_GUIDE)
        val welcome = connection == null && page == Panel.DASHBOARD
        when {
            settingsPage -> header.addView(backButton("Back"))
            welcome -> {
                fun topLink(label: String, action: () -> Unit) = textButton(label, action = action).apply {
                    textSize = 14f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                header.addView(topLink("Feedback") { openLink("$releaseRoot/issues") },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = px(16) })
                header.addView(topLink("Appearance") { show(Panel.APPEARANCE) },
                    LinearLayout.LayoutParams(WRAP, WRAP))
                header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                addHeaderAction(Mark.INFO, "About A4 TV Bridge", ::openAbout)
            }
            page in primaryPanels -> {
                header.addView(ImageView(this).apply { setImageResource(R.drawable.a4_mark) },
                    LinearLayout.LayoutParams(px(36), px(36)).apply { marginEnd = px(10) })
                val brandCol = column().apply {
                    addView(text("A4 TV Bridge", 18, bold = true))
                    addView(text("v${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}", 11, muted).apply {
                        typeface = Typeface.MONOSPACE
                        setPadding(0, px(1), 0, 0)
                    })
                }
                header.addView(brandCol, LinearLayout.LayoutParams(0, WRAP, 1f))
                if (connection != null) {
                    val chip = row().apply {
                        background = card(greenBg, 20, outlined = false).apply { setStroke(px(1), greenStroke) }
                        setPadding(px(11), px(6), px(12), px(6))
                        val dot = View(this@BridgeActivity).apply { background = rounded(greenStroke, 8) }
                        addView(dot, LinearLayout.LayoutParams(px(7), px(7)).apply { marginEnd = px(6) })
                        addView(text("Connected", 12, if (dark) 0xFF6EE7B7.toInt() else green, bold = true))
                        isClickable = true
                        isFocusable = true
                        contentDescription = "Connected TV options"
                        setOnClickListener {
                            choose("Change TV", listOf("Choose another TV" to { connectionPicker() }, "Disconnect" to { disconnect() }))
                        }
                    }
                    header.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = px(4) })
                }
                addHeaderAction(Mark.SETTINGS, "Appearance") { show(Panel.APPEARANCE) }
                addHeaderAction(Mark.INFO, "About A4 TV Bridge", ::openAbout)
            }
            else -> {
                val label = if (page in setOf(Panel.APPS, Panel.FILES, Panel.REMOTE)) "Back" else "My TV"
                header.addView(backButton(label))
                header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                if (page != Panel.INFO) tvTitle()?.let { title ->
                    header.addView(text(title, 13, bold = true).apply {
                        background = card(tint, 30, false)
                        setPadding(px(12), px(6), px(12), px(6))
                        isSingleLine = true
                        maxWidth = px(180)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
            }
        }
        shell.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(11) })
        notice = text(if (activeOperation) "Working…" else "", 14, ink).apply {
            background = card(tint, 12, outlined = true)
            setPadding(px(14), px(10), px(14), px(10))
            setOnClickListener { if (!activeOperation) report("") }
        }
        notice.visibility = if (activeOperation) View.VISIBLE else View.GONE
        shell.addView(notice, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(10) })
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = column().apply { setPadding(0, px(6), 0, px(18)) }
        scroll.addView(content)
        screenBody = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        shell.addView(screenBody, LinearLayout.LayoutParams(MATCH, 0, 1f))
        if (connection != null && page in primaryPanels) {
            shell.addView(dock(page, ::controls), LinearLayout.LayoutParams(MATCH, px(84)).apply { bottomMargin = px(14) })
        }
        if (page !in setOf(Panel.DASHBOARD, Panel.APPEARANCE, Panel.ADB_GUIDE) && connection == null) {
            heading("Connect a TV")
            paragraph("Authorize a TV connection to use this tool.")
            addButton("Connect", emphasis = true) { connectionPicker() }
            return
        }
        when (page) {
            Panel.DASHBOARD -> dashboard()
            Panel.LIBRARY -> library()
            Panel.APPS -> apps()
            Panel.FILES -> fileManager()
            Panel.SHELL -> terminal()
            Panel.CARE -> care()
            Panel.CLEAN -> cleanup()
            Panel.CHECK -> checkTv()
            Panel.RESTORE -> restoreApps()
            Panel.SPACE -> freeSpace()
            Panel.SEND -> sendPage()
            Panel.REMOTE -> remote()
            Panel.MIRROR -> mirror()
            Panel.INFO -> deviceInfo()
            Panel.INSTALL -> installPage()
            Panel.APPEARANCE -> appearancePage()
            Panel.HOME -> homeScreenPage()
            Panel.ADB_GUIDE -> adbGuidePage()
        }
    }

    internal fun connectDialog(
        pair: Boolean,
        hostHint: String? = null,
        pairHint: Int? = null,
        portHint: Int? = null,
        after: Panel = Panel.DASHBOARD
    ) {
        val form = column().apply { setPadding(px(20), px(10), px(20), 0) }
        val host = input("TV IP address", themed = true).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(hostHint ?: preferences.getString("host", ""))
        }
        val port = input(if (pair) "Wireless debugging port" else "Debugging port", themed = true).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(portHint?.toString() ?: if (pair) "" else "5555")
        }
        form.addView(host)
        form.addView(port)
        val pairingPort = input("Pairing port", themed = true).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(pairHint?.toString().orEmpty())
        }
        val pairingCode = input("Six-digit pairing code", themed = true).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        if (pair) {
            form.addView(pairingPort)
            form.addView(pairingCode)
        }
        val helper = if (pair) "On the TV, open Wireless debugging. Use the port on that page to connect, and the separate port and code from Pair device."
            else "For most Android 9 TVs, use port 5555. Press Allow on the TV when asked."
        form.addView(text(helper, 14, muted).apply { setPadding(0, px(8), 0, px(12)) })
        val dialogue = AlertDialog.Builder(this, dialogTheme)
            .setTitle(if (pair) "Pair with a TV" else "Connect to a TV")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null).setPositiveButton(if (pair) "Pair and connect" else "Connect", null).create()
        styleDialog(dialogue)
        dialogue.show()
        dialogue.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val inputPort = port.text.toString().trim()
            val fallback = inputPort.toIntOrNull()
            if ((pair || inputPort.isNotEmpty()) && fallback !in 1..65535) {
                port.error = "Enter the debugging port shown on the TV."
                return@setOnClickListener
            }
            val address = TvAddress.parse(host.text.toString(), fallback ?: 5555)
            if (address == null) {
                host.error = "Enter the TV’s IP address."
                return@setOnClickListener
            }
            val code = pairingCode.text.toString().trim()
            val temporaryPort = pairingPort.text.toString().toIntOrNull()
            if (pair && (temporaryPort !in 1..65535 || temporaryPort == address.port)) {
                pairingPort.error = "Enter the separate port shown with the six-digit code."
                return@setOnClickListener
            }
            if (pair && !code.matches(Regex("[0-9]{6}"))) {
                pairingCode.error = "Enter the six-digit code displayed on the TV."
                return@setOnClickListener
            }
            dialogue.dismiss()
            connect(address, if (pair) temporaryPort else null, code, after)
        }
    }

    internal fun connect(address: TvAddress, pairingPort: Int? = null, code: String = "", after: Panel = Panel.DASHBOARD) {
        if (activeOperation) return report("A connection or TV command is already in progress.")
        backgroundTask({
            if (pairingPort != null) {
                try {
                    TvConnection.pair(address, pairingPort, code)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (problem: Exception) {
                    throw java.io.IOException("Pairing failed. Open a new code on the TV and confirm both ports.", problem)
                }
            }
            TvConnection.connect(address)
        }, onError = { error ->
            val explanation = when (error) {
                is java.net.UnknownHostException -> "The TV address could not be found. Check the address shown by the TV."
                is java.net.ConnectException, is java.net.NoRouteToHostException ->
                    "Could not reach $address. Check the TV’s IP address, debugging port, and Wi-Fi or Ethernet connection."
                is java.net.SocketTimeoutException ->
                    "The TV did not finish debugging authorization. Try again and press Allow on the TV."
                else -> error.message?.take(220) ?: "The TV could not authorize the connection."
            }
            report(explanation)
        }) { connected ->
            val previous = connection
            connection = connected
            if (previous?.serial != connected.serial) {
                shellCommand = ""
                shellOutput = ""
            }
            if (previous != null) scope.launch(Dispatchers.IO) { previous.close() }
            TvDirectory(this).remember(connected)
            folders.clear()
            folders.add(TvPath.root)
            screens.clear()
            show(after, rememberPage = false)
        }
        report(if (pairingPort == null) "Connecting to $address… Press Allow if the TV shows a debugging prompt."
            else "Pairing with the TV…")
    }

    internal fun disconnect() {
        polling?.cancel()
        polling = null
        val previous = connection
        connection = null
        shellCommand = ""
        shellOutput = ""
        if (previous != null) scope.launch(Dispatchers.IO) { previous.close() }
        folders.clear()
        folders.add(TvPath.root)
        screens.clear()
        report("")
        show(Panel.DASHBOARD, rememberPage = false)
    }

    internal fun replaceConnection(connected: TvConnection?) {
        polling?.cancel()
        polling = null
        val previous = connection
        connection = connected
        if (previous != null && previous !== connected) {
            scope.launch(Dispatchers.IO) { runCatching { previous.close() } }
        }
        if (connected != null) {
            TvDirectory(this).remember(connected)
        }
    }

    internal fun controls() {
        showSheet("Control and capture") { popup, area ->
            fun open(action: () -> Unit) {
                popup.dismiss()
                if (connection == null) connectionPicker() else action()
            }
            tiles(area, 2,
                Tile("Remote", Mark.REMOTE, selected = true) { open { show(Panel.REMOTE) } },
                Tile("Screen Mirror", Mark.MIRROR) { open { show(Panel.MIRROR) } },
                Tile("Screenshot", Mark.CAMERA) { open(::saveScreenshot) },
                Tile("Screen Record", Mark.VIDEO) { open(::saveRecording) }
            )
        }
    }

    internal fun tvTitle(): String? {
        val tv = connection ?: return null
        return preferences.getString("tv-model-${tv.address}", null) ?: tv.model
    }

    private fun terminal() {
        heading("ADB Shell / Terminal")
        paragraph("Runs a non-interactive command as the connected TV’s shell user. Results include stdout, stderr, and the exit code.")
        val inputArea = outlinedCard(field, radius = corner, stroke = 1)
        val label = text("Command", 12, muted, bold = true).apply { letterSpacing = .03f }
        inputArea.addView(label)
        val command = input("e.g. pm list packages -3").apply {
            setText(shellCommand)
            gravity = Gravity.TOP or Gravity.START
            minimumHeight = px(66)
            background = null
            setPadding(0, px(6), 0, 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { shellCommand = s.toString() }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        inputArea.addView(command, LinearLayout.LayoutParams(MATCH, WRAP))
        content.addView(inputArea, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(12); bottomMargin = px(14) })
        val output = text(shellOutput, 16).apply {
            background = card(radius = 22).apply { setStroke(px(2), edge) }
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(px(16), px(17), px(16), px(17))
            visibility = if (shellOutput.isEmpty()) View.GONE else View.VISIBLE
        }
        content.addView(button("Run command", emphasis = true) {
            val entered = command.text.toString().trim()
            if (entered.isEmpty()) return@button report("Enter a command.")
            tvTask({ command(entered) }) { result ->
                shellOutput = "${result.text}\n\nExit code ${result.status}"
                output.text = shellOutput
                output.visibility = View.VISIBLE
            }
        }, LinearLayout.LayoutParams(MATCH, px(54)))
        val links = row()
        links.addView(textButton("Copy output") {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TV shell output", output.text))
            report("Output copied.")
        })
        links.addView(textButton("Clear output") {
            shellOutput = ""
            output.text = ""
            output.visibility = View.GONE
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = px(16) })
        content.addView(links, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(12) })
        content.addView(output, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(5) })
    }

    internal fun sendInput(action: TvConnection.() -> Unit) {
        val tv = connection ?: return report("Connect a TV first.")
        inputQueue.trySend(tv to action)
    }

    internal fun launchSheetTask(block: suspend () -> Unit): Job = scope.launch { block() }

    internal fun <T> tvTask(work: TvConnection.() -> T, done: (T) -> Unit) {
        val tv = connection ?: return report("Connect a TV first.")
        backgroundTask({ tv.work() }) { answer ->
            if (connection === tv) done(answer)
        }
    }

    internal fun <T> backgroundTask(
        work: suspend () -> T,
        onError: (Exception) -> Unit = { report(it.message?.take(260) ?: "The operation failed.") },
        done: (T) -> Unit
    ) {
        if (activeOperation) return report("Wait for the current operation.")
        activeOperation = true
        report("Working…")
        scope.launch {
            val answer = try {
                withContext(Dispatchers.IO) { work() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                onError(error)
                return@launch
            } finally {
                activeOperation = false
            }
            report("")
            done(answer)
        }
    }

    internal fun watch(loop: suspend () -> Unit) {
        polling?.cancel()
        val outside = content
        val activeTv = connection
        polling = scope.launch {
            while (content === outside && connection === activeTv && activeTv != null) {
                try {
                    if (!activeOperation) loop()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (content === outside && connection === activeTv) {
                        report(error.message?.take(150) ?: "The TV could not be read.")
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    internal fun chooseDocuments(mime: String, multiple: Boolean, callback: (List<Uri>) -> Unit) {
        onDocuments = callback
        fun picker(action: String) = Intent(action).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        try {
            startActivityForResult(picker(Intent.ACTION_OPEN_DOCUMENT), PICK)
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                startActivityForResult(picker(Intent.ACTION_GET_CONTENT), PICK)
            } catch (_: android.content.ActivityNotFoundException) {
                onDocuments = null
                report("This Android device has no file picker.")
            }
        }
    }

    internal fun saveDocument(file: File, name: String, mime: String) {
        pendingSave?.delete()
        pendingSave = file
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, name), SAVE)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingSave = null
            file.delete()
            report("There is no document picker on this device.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK) {
            val callback = onDocuments
            onDocuments = null
            if (resultCode != RESULT_OK || data == null) return
            val clips = data.clipData
            val documents = if (clips != null) (0 until clips.itemCount).map { clips.getItemAt(it).uri }
                else listOfNotNull(data.data)
            if (documents.isNotEmpty()) callback?.invoke(documents.distinct())
        } else if (requestCode == SAVE) {
            val file = pendingSave ?: return
            pendingSave = null
            val target = data?.data
            if (resultCode != RESULT_OK || target == null) {
                file.delete()
                return
            }
            backgroundTask({
                try {
                    val stream = contentResolver.openOutputStream(target, "w") ?: error("Could not open the selected file.")
                    stream.use { output -> file.inputStream().use { it.copyTo(output) } }
                } finally {
                    file.delete()
                }
            }) { report("Saved.") }
        }
    }

    internal fun report(message: String) {
        notice.text = message
        notice.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    internal fun openLink(link: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            .onFailure { report("This device could not open the link.") }
    }

    private fun styleDialog(dialogue: AlertDialog) {
        dialogue.setOnShowListener {
            dialogue.window?.setBackgroundDrawable(
                android.graphics.drawable.InsetDrawable(card(surface, 20, outlined = dark), px(16))
            )
            dialogue.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isAllCaps = false
                setTextColor(accent)
            }
            dialogue.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                isAllCaps = false
                setTextColor(muted)
            }
        }
    }

    internal fun confirm(title: String, description: String, action: () -> Unit) {
        val dialogue = AlertDialog.Builder(this, dialogTheme).setTitle(title).setMessage(description)
            .setNegativeButton("Cancel", null).setPositiveButton("Confirm") { _, _ -> action() }.create()
        styleDialog(dialogue)
        dialogue.show()
    }

    internal fun ask(title: String, initial: String = "", action: (String) -> Unit) {
        val value = input(title, themed = true).apply { setText(initial) }
        val container = column().apply {
            setPadding(px(20), px(8), px(20), px(4))
            addView(value)
        }
        val dialogue = AlertDialog.Builder(this, dialogTheme).setTitle(title).setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> action(value.text.toString()) }.create()
        styleDialog(dialogue)
        dialogue.show()
    }

    internal fun heading(value: String, size: Int = 24) {
        content.addView(text(value, size, bold = true).apply { setPadding(0, px(9), 0, px(14)) })
    }
    internal fun paragraph(value: String) { content.addView(text(value, 16, muted).apply { setPadding(0, px(4), 0, px(10)) }) }
    internal fun statusCard(value: String): TextView = text(value, 16).apply {
        background = card(radius = corner)
        setPadding(px(15), px(15), px(15), px(15))
        content.addView(this, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(8) })
    }
    internal fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    internal fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    internal fun text(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        if (bold && size >= 22) setTypeface(Typeface.DEFAULT_BOLD)
        if (dark && size >= 22) letterSpacing = 0.01f
    }
    internal fun input(label: String, themed: Boolean = false) =
        EditText(if (themed) android.view.ContextThemeWrapper(this, dialogTheme) else this).apply {
            hint = label
            setHintTextColor(muted)
            setTextColor(ink)
            backgroundTintList = ColorStateList.valueOf(accent)
        }
    private fun backButton(label: String) = textButton(label, "Back") { onBackPressed() }.apply {
        labelIcon(this, Mark.BACK)
    }

    internal fun textButton(label: String, description: String = label, action: () -> Unit) =
        button(label, description, action = action).apply {
            setTextColor(accent)
            background = rounded(Color.TRANSPARENT, 0)
            textSize = 16f
            setPadding(px(4), px(3), px(4), px(3))
        }

    internal fun button(label: String, description: String = label, emphasis: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        includeFontPadding = false
        minHeight = px(48)
        minimumHeight = px(48)
        minimumWidth = 0
        setTextColor(if (emphasis) onAccent else ink)
        val surfaceBg = if (emphasis) {
            card(accentStrong, 15, outlined = false).apply {
                if (dark) setStroke(px(1), accent)
            }
        } else {
            card(if (dark) 0xFF131D2B.toInt() else field, 15, outlined = true)
        }
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(ripple), surfaceBg, null
        )
        setPadding(px(14), px(8), px(14), px(8))
        setOnClickListener { action() }
    }
    internal fun addButton(label: String, emphasis: Boolean = false, action: () -> Unit) {
        content.addView(button(label, emphasis = emphasis, action = action),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(7) })
    }
    internal fun actions(vararg buttons: Pair<String, () -> Unit>) {
        val line = row()
        buttons.forEachIndexed { i, (name, action) ->
            line.addView(button(name, action = action), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i > 0) marginStart = px(7)
            })
        }
        content.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(7) })
    }
    internal fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    internal fun rounded(color: Int, corner: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(corner).toFloat()
    }

    companion object {
        internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val primaryPanels = setOf(Panel.DASHBOARD, Panel.LIBRARY, Panel.SHELL, Panel.CARE)
        private const val PICK = 501
        private const val SAVE = 502
    }
}

internal fun percent(number: Int?) = number?.let { "$it%" } ?: "—"
