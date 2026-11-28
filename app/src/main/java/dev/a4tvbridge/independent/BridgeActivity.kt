package dev.a4tvbridge.independent

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class Panel {
    DASHBOARD, LIBRARY, APPS, FILES, SHELL, CARE, CLEAN, CHECK, RESTORE, SPACE, SEND,
    REMOTE, MIRROR, INFO, INSTALL, APPEARANCE, HOME, ADB_GUIDE, SCANNER
}

internal enum class NoticeTone { INFO, WORKING, SUCCESS, ERROR }

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
    private var connectJob: Job? = null
    private var noticeJob: Job? = null
    private val inputQueue = Channel<Pair<TvConnection, TvConnection.() -> Unit>>(32)
    private var activeOperation = false
    internal var scannerAfter: Panel = Panel.DASHBOARD
    internal var connectionStatus: ConnectionStatusState = ConnectionStatusState()
        private set
    internal var onConnectionStatusChanged: ((ConnectionStatusState) -> Unit)? = null
    internal lateinit var content: LinearLayout
        private set
    internal lateinit var screenBody: FrameLayout
        private set

    // Preserved screen state across tab switches & rotations
    private var shellCommand = ""
    private var shellOutput = ""
    private val shellHistory = ArrayDeque<String>()
    internal var appsSearchQuery = ""
    internal var appsFilterTab = 0
    internal var remoteDraftText = ""

    private lateinit var notice: TextView
    private var backCallback: Any? = null

    // Bundled local Google Fonts with graceful fallback
    private val uiRegularTypeface: Typeface by lazy {
        runCatching { ResourcesCompat.getFont(this, R.font.plus_jakarta_sans) }.getOrNull() ?: Typeface.SANS_SERIF
    }
    private val uiBoldTypeface: Typeface by lazy {
        Typeface.create(uiRegularTypeface, Typeface.BOLD)
    }
    internal val monoTypeface: Typeface by lazy {
        runCatching { ResourcesCompat.getFont(this, R.font.jetbrains_mono) }.getOrNull() ?: Typeface.MONOSPACE
    }

    internal var dark = false
        private set
    private var useDynamicAccent = false

    private fun dynamicAccentOr(fallback: Int): Int {
        if (!useDynamicAccent || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fallback
        val resId = if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
        return runCatching { getColor(resId) }.getOrDefault(fallback)
    }

    private fun dynamicAccentStrongOr(fallback: Int): Int {
        if (!useDynamicAccent || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fallback
        val resId = if (dark) android.R.color.system_accent1_500 else android.R.color.system_accent1_600
        return runCatching { getColor(resId) }.getOrDefault(fallback)
    }

    internal val ink get() = if (dark) 0xFFF8FAFC.toInt() else 0xFF0F172A.toInt()
    internal val muted get() = if (dark) 0xFF94A3B8.toInt() else 0xFF475569.toInt()
    internal val dim get() = 0xFF64748B.toInt()
    internal val accent get() = dynamicAccentOr(if (dark) 0xFF38BDF8.toInt() else 0xFF00587B.toInt())
    internal val accentBright get() = dynamicAccentOr(if (dark) 0xFF38BDF8.toInt() else 0xFF0284C7.toInt())
    internal val accentStrong get() = dynamicAccentStrongOr(if (dark) 0xFF0077A8.toInt() else 0xFF00587B.toInt())
    internal val accentPill get() = dynamicAccentStrongOr(0xFF00587B.toInt())
    internal val field get() = if (dark) 0xFF0D1219.toInt() else 0xFFFFFFFF.toInt()
    internal val surface get() = if (dark) 0xFF05070A.toInt() else 0xFFF1F5F9.toInt()
    internal val surfaceElevated get() = if (dark) 0xFF131D2B.toInt() else 0xFFF8FAFC.toInt()
    internal val surfaceSubtle get() = if (dark) 0xFF0A0F16.toInt() else surface
    internal val edge get() = if (dark) 0xFF1E293B.toInt() else 0xFFCBD5E1.toInt()
    internal val tint get() = if (dark) 0xFF091D2C.toInt() else 0xFFE0F2FE.toInt()
    internal val onAccent get() = Color.WHITE
    internal val green get() = if (dark) 0xFF34D399.toInt() else 0xFF059669.toInt()
    internal val successInk get() = if (dark) 0xFF6EE7B7.toInt() else green
    internal val greenBg get() = if (dark) 0xFF062118.toInt() else 0xFFECFDF5.toInt()
    internal val greenStroke get() = 0xFF10B981.toInt()
    internal val purple get() = if (dark) 0xFFA78BFA.toInt() else 0xFF6D28D9.toInt()
    internal val orange get() = if (dark) 0xFFF59E0B.toInt() else 0xFFD97706.toInt()
    internal val warningBg get() = if (dark) 0xFF261A08.toInt() else 0xFFFFFBEB.toInt()
    internal val rose get() = 0xFFF43F5E.toInt()
    internal val dangerBg get() = if (dark) 0xFF281018.toInt() else 0xFFFFF1F2.toInt()
    internal val dangerInk get() = if (dark) 0xFFFB7185.toInt() else 0xFFE11D48.toInt()
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
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        registerPredictiveBack()
        draw()
        scope.launch {
            for ((tv, action) in inputQueue) {
                if (connection !== tv) continue
                try {
                    withContext(Dispatchers.IO) { tv.action() }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    report(error.message?.take(180) ?: "Command failed.", NoticeTone.ERROR)
                }
            }
        }
        checkAppUpdateOnStartup()
    }

    private fun registerPredictiveBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cb = OnBackInvokedCallback {
                if (!handleBackNavigation()) {
                    finish()
                }
            }
            backCallback = cb
            runCatching {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    cb
                )
            }
        }
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
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())
        ) {
            launchApkInstaller(stagedApk)
        }
        if (page in setOf(Panel.DASHBOARD, Panel.MIRROR, Panel.INFO)) draw()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? OnBackInvokedCallback)?.let { cb ->
                runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb) }
            }
        }
        scope.cancel()
        connection?.close()
        pendingSave?.delete()
        super.onDestroy()
    }

    internal fun handleBackNavigation(): Boolean {
        if (page == Panel.FILES && folders.size > 1) {
            folders.removeAt(folders.lastIndex)
            draw()
            return true
        }
        if (screens.isNotEmpty()) {
            page = screens.removeLast()
            draw()
            return true
        }
        if (page != Panel.DASHBOARD) {
            show(Panel.DASHBOARD)
            return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!handleBackNavigation()) {
            super.onBackPressed()
        }
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

    internal fun openScanner(after: Panel = Panel.DASHBOARD) {
        scannerAfter = after
        if (connectionStatus.phase == ConnectionPhase.CONNECTED || connectionStatus.phase == ConnectionPhase.ERROR) {
            updateConnectionStatus(ConnectionStatusState(ConnectionPhase.IDLE))
        }
        show(Panel.SCANNER)
    }

    internal fun updateConnectionStatus(state: ConnectionStatusState) {
        connectionStatus = state
        onConnectionStatusChanged?.invoke(state)
    }

    internal fun cancelActiveConnection() {
        connectJob?.cancel()
        connectJob = null
        activeOperation = false
        report("")
        updateConnectionStatus(ConnectionStatusState(ConnectionPhase.IDLE))
    }

    internal fun refresh() = draw()

    private fun draw() {
        polling?.cancel()
        polling = null
        val choice = appearanceChoice()
        dark = choice.usesDark(systemUsesDark())
        useDynamicAccent = dynamicColorEnabled()

        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surface))
        val root = FrameLayout(this).apply { setBackgroundColor(surface) }
        val isWide = resources.configuration.screenWidthDp >= 680
        val shell = column().apply { setBackgroundColor(surface) }
        val shellParams = FrameLayout.LayoutParams(
            if (isWide) px(640) else MATCH,
            MATCH,
            Gravity.CENTER_HORIZONTAL
        )
        root.addView(shell, shellParams)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            val sides = px(if (resources.configuration.screenWidthDp < 360) 14 else 18)
            view.setPadding(bars.left + sides, bars.top + px(12), bars.right + sides, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.requestApplyInsets(root)

        val header = row()
        fun addHeaderAction(icon: Mark, label: String, action: () -> Unit) {
            header.addView(Glyph(this, icon, accent, 24).apply {
                contentDescription = label
                background = RippleDrawable(ColorStateList.valueOf(ripple), null, card(radius = 24))
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(px(48), px(48)))
        }
        val settingsPage = page in setOf(Panel.APPEARANCE, Panel.ADB_GUIDE)
        val scannerPage = page == Panel.SCANNER
        val welcome = connection == null && page == Panel.DASHBOARD
        when {
            settingsPage -> header.addView(backButton(getString(R.string.action_back)))
            scannerPage -> {
                header.addView(backButton("Find & Connect TV"))
                header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                val lan = tvLocalLink()
                val lanReady = lan != null
                val pillFill = if (lanReady) greenBg else warningBg
                val pillStroke = if (lanReady) greenStroke else orange
                val pillText = if (lanReady) (if (lan.ethernet) "Ethernet" else "Wi-Fi Ready") else "Check Wi-Fi"
                val pillInk = if (lanReady) successInk else orange
                val lanChip = row().apply {
                    background = card(pillFill, 20, outlined = false).apply { setStroke(px(1), pillStroke) }
                    setPadding(px(10), px(6), px(11), px(6))
                    addView(View(this@BridgeActivity).apply { background = rounded(pillStroke, 8) },
                        LinearLayout.LayoutParams(px(7), px(7)).apply { marginEnd = px(6) })
                    addView(text(pillText, 12, pillInk, bold = true))
                }
                header.addView(lanChip)
            }
            welcome -> {
                fun topLink(label: String, action: () -> Unit) = textButton(label, action = action).apply {
                    textSize = 14f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                header.addView(topLink("Feedback") { openLink("$releaseRoot/issues") },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = px(12) })
                header.addView(topLink("Appearance") { show(Panel.APPEARANCE) },
                    LinearLayout.LayoutParams(WRAP, WRAP))
                header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                addHeaderAction(Mark.INFO, "About A4 TV Bridge", ::openAbout)
            }
            page in primaryPanels -> {
                header.addView(ImageView(this).apply {
                    setImageResource(R.drawable.a4_mark)
                    contentDescription = getString(R.string.app_name)
                }, LinearLayout.LayoutParams(px(36), px(36)).apply { marginEnd = px(10) })
                val brandCol = column().apply {
                    addView(text(getString(R.string.app_name), 18, bold = true))
                    addView(text("v${BuildConfig.VERSION_NAME}", 11, muted).apply {
                        typeface = monoTypeface
                        setPadding(0, px(1), 0, 0)
                    })
                }
                header.addView(brandCol, LinearLayout.LayoutParams(0, WRAP, 1f))
                if (connection != null) {
                    val chip = row().apply {
                        background = RippleDrawable(
                            ColorStateList.valueOf(ripple),
                            card(greenBg, 20, outlined = false).apply { setStroke(px(1), greenStroke) },
                            null
                        )
                        minimumHeight = px(38)
                        setPadding(px(11), px(6), px(12), px(6))
                        val dot = View(this@BridgeActivity).apply { background = rounded(greenStroke, 8) }
                        addView(dot, LinearLayout.LayoutParams(px(7), px(7)).apply { marginEnd = px(6) })
                        addView(text(getString(R.string.status_connected), 12, successInk, bold = true))
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
                val label = if (page in setOf(Panel.FILES, Panel.REMOTE, Panel.INSTALL, Panel.SEND)) getString(R.string.action_back) else "My TV"
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
        shell.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(10) })
        notice = text(if (activeOperation) getString(R.string.status_working) else "", 13, ink, bold = true).apply {
            background = card(tint, 14, outlined = true)
            setPadding(px(14), px(11), px(14), px(11))
            isClickable = true
            setOnClickListener { if (!activeOperation) report("") }
        }
        notice.visibility = if (activeOperation) View.VISIBLE else View.GONE
        shell.addView(notice, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(10) })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        content = column().apply { setPadding(0, px(6), 0, px(18)) }
        scroll.addView(content)
        screenBody = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        shell.addView(screenBody, LinearLayout.LayoutParams(MATCH, 0, 1f))
        if (connection != null && page in primaryPanels) {
            shell.addView(dock(page, ::controls), LinearLayout.LayoutParams(MATCH, px(84)).apply { bottomMargin = px(14) })
        }
        if (page !in setOf(Panel.DASHBOARD, Panel.APPEARANCE, Panel.ADB_GUIDE, Panel.SCANNER) && connection == null) {
            pageHeader("Connect a TV", "Authorize a TV connection to use this tool.")
            addButton("Connect", emphasis = true) { connectionPicker() }
            return
        }
        when (page) {
            Panel.DASHBOARD -> dashboard()
            Panel.LIBRARY -> apps()
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
            Panel.SCANNER -> tvScannerPage()
        }
    }

    internal fun connectDialog(
        pair: Boolean,
        hostHint: String? = null,
        pairHint: Int? = null,
        portHint: Int? = null,
        after: Panel = Panel.DASHBOARD
    ) {
        fun styledField(label: String, initial: String, inputTypeFlags: Int): Pair<LinearLayout, EditText> {
            val box = column().apply {
                addView(text(label.uppercase(), 10, dim, bold = true).apply {
                    letterSpacing = 0.08f
                    setPadding(px(2), 0, 0, px(5))
                })
            }
            val edit = input(label).apply {
                inputType = inputTypeFlags
                setSingleLine()
                setText(initial)
                typeface = monoTypeface
                background = card(surfaceElevated, 14, outlined = true)
                setPadding(px(14), px(12), px(14), px(12))
            }
            box.addView(edit, LinearLayout.LayoutParams(MATCH, WRAP))
            return box to edit
        }

        showSheet(if (pair) "Pair with a TV" else "Connect to a TV") { popup, frame ->
            val body = column()
            val (hostBox, host) = styledField(
                "TV IP address",
                hostHint ?: preferences.getString("host", "").orEmpty(),
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            )
            val (portBox, port) = styledField(
                if (pair) "Wireless debugging port" else "Debugging port",
                portHint?.toString() ?: if (pair) "" else "5555",
                android.text.InputType.TYPE_CLASS_NUMBER
            )
            body.addView(hostBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(10) })
            body.addView(portBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(10) })

            val (pairPortBox, pairingPort) = styledField(
                "Pairing port",
                pairHint?.toString().orEmpty(),
                android.text.InputType.TYPE_CLASS_NUMBER
            )
            val (pairCodeBox, pairingCode) = styledField(
                "Six-digit pairing code",
                "",
                android.text.InputType.TYPE_CLASS_NUMBER
            )
            if (pair) {
                body.addView(pairPortBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(10) })
                body.addView(pairCodeBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(10) })
            }

            val helper = if (pair) {
                "On your TV, open Wireless debugging. Enter the main port to connect, plus the separate port and 6-digit code from Pair device."
            } else {
                "Most Android TV and Fire TV devices use port 5555. Press Allow on your TV when prompted."
            }
            val tipCard = text(helper, 12, muted).apply {
                background = card(tint, 14, outlined = false).apply {
                    setStroke(px(1), if (dark) accentBright else edge)
                }
                setLineSpacing(px(3).toFloat(), 1f)
                setPadding(px(13), px(10), px(13), px(10))
            }
            body.addView(tipCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(14) })
            attachSheetBody(frame, body)

            val btnRow = row()
            btnRow.addView(button(getString(R.string.action_cancel)) { popup.dismiss() }, LinearLayout.LayoutParams(0, px(50), 1f))
            btnRow.addView(button(if (pair) "Pair & Connect" else "Connect", emphasis = true) {
                val inputPort = port.text.toString().trim()
                val fallback = inputPort.toIntOrNull()
                if ((pair || inputPort.isNotEmpty()) && fallback !in 1..65535) {
                    port.error = "Enter the debugging port shown on the TV."
                    return@button
                }
                val address = TvAddress.parse(host.text.toString(), fallback ?: 5555)
                if (address == null) {
                    host.error = "Enter the TV’s IP address."
                    return@button
                }
                val code = pairingCode.text.toString().trim()
                val temporaryPort = pairingPort.text.toString().toIntOrNull()
                if (pair && (temporaryPort !in 1..65535 || temporaryPort == address.port)) {
                    pairingPort.error = "Enter the separate port shown with the six-digit code."
                    return@button
                }
                if (pair && !code.matches(Regex("[0-9]{6}"))) {
                    pairingCode.error = "Enter the six-digit code displayed on the TV."
                    return@button
                }
                popup.dismiss()
                connect(address, if (pair) temporaryPort else null, code, after)
            }, LinearLayout.LayoutParams(0, px(50), 1.35f).apply { marginStart = px(10) })
            frame.addView(btnRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(4) })
        }
    }

    internal fun connect(address: TvAddress, pairingPort: Int? = null, code: String = "", after: Panel = Panel.DASHBOARD) {
        if (activeOperation) return report("A connection or TV command is already in progress.", NoticeTone.INFO)
        activeOperation = true
        val targetLabel = address.toString()
        updateConnectionStatus(
            ConnectionStatusState(
                phase = ConnectionPhase.CONNECTING,
                targetLabel = targetLabel,
                detail = if (pairingPort == null) "Opening ADB session with $address…" else "Pairing with $address…",
                targetAddress = address
            )
        )
        if (page != Panel.SCANNER) {
            report(
                if (pairingPort == null) "Connecting to $address… Press Allow if the TV shows a debugging prompt."
                else "Pairing with the TV…",
                NoticeTone.WORKING
            )
        }
        connectJob?.cancel()
        connectJob = scope.launch {
            val approvalPrompt = launch {
                delay(1200)
                if (connectionStatus.phase == ConnectionPhase.CONNECTING) {
                    updateConnectionStatus(
                        ConnectionStatusState(
                            phase = ConnectionPhase.AWAITING_APPROVAL,
                            targetLabel = targetLabel,
                            detail = "Select 'Always allow' and press OK on your TV",
                            targetAddress = address
                        )
                    )
                }
            }
            val connected = try {
                withContext(Dispatchers.IO) {
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
                }
            } catch (cancel: CancellationException) {
                approvalPrompt.cancel()
                activeOperation = false
                throw cancel
            } catch (error: Exception) {
                approvalPrompt.cancel()
                activeOperation = false
                val explanation = when (error) {
                    is java.net.UnknownHostException -> "The TV address could not be found. Check the address shown by the TV."
                    is java.net.ConnectException, is java.net.NoRouteToHostException ->
                        "Could not reach $address. Check the TV’s IP address, debugging port, and Wi-Fi or Ethernet connection."
                    is java.net.SocketTimeoutException ->
                        "The TV did not finish debugging authorization. Try again and press Allow on the TV."
                    else -> error.message?.take(220) ?: "The TV could not authorize the connection."
                }
                updateConnectionStatus(
                    ConnectionStatusState(
                        phase = ConnectionPhase.ERROR,
                        targetLabel = targetLabel,
                        detail = explanation,
                        targetAddress = address
                    )
                )
                if (page != Panel.SCANNER) report(explanation, NoticeTone.ERROR)
                return@launch
            } finally {
                approvalPrompt.cancel()
            }
            activeOperation = false
            connectJob = null
            report("")
            val previous = connection
            connection = connected
            if (previous?.serial != connected.serial) {
                shellCommand = ""
                shellOutput = ""
                shellHistory.clear()
                appsSearchQuery = ""
                remoteDraftText = ""
            }
            if (previous != null) scope.launch(Dispatchers.IO) { previous.close() }
            TvDirectory(this@BridgeActivity).remember(connected)
            updateConnectionStatus(
                ConnectionStatusState(
                    phase = ConnectionPhase.CONNECTED,
                    targetLabel = connected.model,
                    detail = "${connected.address} · Connected",
                    targetAddress = connected.address
                )
            )
            folders.clear()
            folders.add(TvPath.root)
            screens.clear()
            show(after, rememberPage = false)
        }
    }

    internal fun disconnect() {
        polling?.cancel()
        polling = null
        val previous = connection
        connection = null
        shellCommand = ""
        shellOutput = ""
        shellHistory.clear()
        appsSearchQuery = ""
        remoteDraftText = ""
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
        return preferences.getString("tv-model-${tv.address}", null)?.takeIf { it.isNotBlank() } ?: tv.model
    }

    internal fun renameConnectedTv() {
        val tv = connection ?: return
        ask("Rename TV", tvTitle().orEmpty()) { entered ->
            val trimmed = entered.trim().take(48)
            val key = "tv-model-${tv.address}"
            if (trimmed.isEmpty()) {
                preferences.edit().remove(key).apply()
                report("TV name reset to ${tv.model}.", NoticeTone.SUCCESS)
            } else {
                preferences.edit().putString(key, trimmed).apply()
                report("Renamed TV to $trimmed.", NoticeTone.SUCCESS)
            }
            refresh()
        }
    }

    private fun terminal() {
        pageHeader("ADB Shell / Terminal", "Run non-interactive commands as the TV’s shell user")

        // Quick Command Chips
        val presets = listOf(
            "User Apps" to "pm list packages -3",
            "TV Model" to "getprop ro.product.model",
            "Storage" to "df -h /data",
            "IP Info" to "ip -4 addr show",
            "Android Ver" to "getprop ro.build.version.release"
        )
        content.addView(sectionLabel("Quick commands"),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(8) })
        val presetStrip = row()
        val inputArea = outlinedCard(field, radius = corner, stroke = 1)
        val command = input("e.g. pm list packages -3").apply {
            setText(shellCommand)
            typeface = monoTypeface
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            minimumHeight = px(64)
            background = null
            setPadding(0, px(6), 0, 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { shellCommand = s.toString() }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        presets.forEachIndexed { idx, (label, cmd) ->
            val chip = text(label, 12, accent, bold = true).apply {
                background = RippleDrawable(ColorStateList.valueOf(ripple), card(tint, 14, outlined = false).apply {
                    setStroke(px(1), if (dark) accentBright else edge)
                }, null)
                minHeight = px(48)
                minimumHeight = px(48)
                gravity = Gravity.CENTER
                setPadding(px(14), 0, px(14), 0)
                isClickable = true
                isFocusable = true
                contentDescription = "Insert $label command"
                setOnClickListener {
                    shellCommand = cmd
                    command.setText(cmd)
                    command.setSelection(cmd.length)
                }
            }
            presetStrip.addView(chip, LinearLayout.LayoutParams(WRAP, px(48)).apply {
                if (idx > 0) marginStart = px(8)
            })
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(presetStrip)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(12) })

        val label = text("COMMAND", 10, dim, bold = true).apply { letterSpacing = .08f }
        inputArea.addView(label)
        inputArea.addView(command, LinearLayout.LayoutParams(MATCH, WRAP))
        content.addView(inputArea, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(12) })

        val historyHost = column()
        fun renderHistory() {
            historyHost.removeAllViews()
            if (shellHistory.isEmpty()) return
            historyHost.addView(sectionLabel("Recent commands"),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(4); bottomMargin = px(6) })
            val strip = row()
            shellHistory.forEachIndexed { idx, past ->
                val chip = text(past, 11, muted, bold = false).apply {
                    typeface = monoTypeface
                    background = RippleDrawable(ColorStateList.valueOf(ripple), card(surfaceElevated, 12, outlined = true), null)
                    minHeight = px(48)
                    minimumHeight = px(48)
                    gravity = Gravity.CENTER
                    maxWidth = px(220)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(px(12), 0, px(12), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        shellCommand = past
                        command.setText(past)
                        command.setSelection(past.length)
                    }
                }
                strip.addView(chip, LinearLayout.LayoutParams(WRAP, px(48)).apply {
                    if (idx > 0) marginStart = px(8)
                })
            }
            historyHost.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(10) })
        }

        val output = text(shellOutput, 13).apply {
            background = card(surfaceSubtle, radius = corner, outlined = true)
            typeface = monoTypeface
            setLineSpacing(px(3).toFloat(), 1f)
            setTextIsSelectable(true)
            setPadding(px(15), px(14), px(15), px(14))
            visibility = if (shellOutput.isEmpty()) View.GONE else View.VISIBLE
        }
        content.addView(button("Run command", emphasis = true) {
            val entered = command.text.toString().trim()
            if (entered.isEmpty()) return@button report("Enter a command.", NoticeTone.ERROR)
            shellHistory.remove(entered)
            shellHistory.addFirst(entered)
            while (shellHistory.size > 6) shellHistory.removeLast()
            renderHistory()
            tvTask({ command(entered) }) { result ->
                shellOutput = "${result.text.ifBlank { "(no output)" }}\n\nExit code ${result.status}"
                output.text = shellOutput
                output.visibility = View.VISIBLE
            }
        }, LinearLayout.LayoutParams(MATCH, px(52)).apply { bottomMargin = px(10) })

        renderHistory()
        content.addView(historyHost, LinearLayout.LayoutParams(MATCH, WRAP))

        val links = row()
        links.addView(button("Copy output") {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TV shell output", output.text))
            report("Output copied.", NoticeTone.SUCCESS)
        }, LinearLayout.LayoutParams(0, px(48), 1f))
        links.addView(button("Clear output") {
            shellOutput = ""
            output.text = ""
            output.visibility = View.GONE
        }, LinearLayout.LayoutParams(0, px(48), 1f).apply { marginStart = px(10) })
        content.addView(links, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(10) })
        content.addView(output, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    internal fun sendInput(action: TvConnection.() -> Unit) {
        val tv = connection ?: return report("Connect a TV first.", NoticeTone.ERROR)
        inputQueue.trySend(tv to action)
    }

    internal fun launchSheetTask(block: suspend () -> Unit): Job = scope.launch { block() }

    internal fun <T> tvTask(work: TvConnection.() -> T, done: (T) -> Unit) {
        val tv = connection ?: return report("Connect a TV first.", NoticeTone.ERROR)
        backgroundTask({ tv.work() }) { answer ->
            if (connection === tv) done(answer)
        }
    }

    internal fun <T> backgroundTask(
        work: suspend () -> T,
        onError: (Exception) -> Unit = { report(it.message?.take(260) ?: "The operation failed.", NoticeTone.ERROR) },
        done: (T) -> Unit
    ) {
        if (activeOperation) return report("Wait for the current operation.", NoticeTone.INFO)
        activeOperation = true
        report(getString(R.string.status_working), NoticeTone.WORKING)
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
                        report(error.message?.take(150) ?: "The TV could not be read.", NoticeTone.ERROR)
                    }
                }
                delay(1000)
            }
        }
    }

    @Suppress("DEPRECATION")
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
                report("This Android device has no file picker.", NoticeTone.ERROR)
            }
        }
    }

    @Suppress("DEPRECATION")
    internal fun saveDocument(file: File, name: String, mime: String) {
        pendingSave?.delete()
        pendingSave = file
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, name), SAVE)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingSave = null
            file.delete()
            report("There is no document picker on this device.", NoticeTone.ERROR)
        }
    }

    @Suppress("DEPRECATION")
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
            }) { report("Saved.", NoticeTone.SUCCESS) }
        }
    }

    private fun inferNoticeTone(message: String): NoticeTone {
        val low = message.lowercase()
        return when {
            low.endsWith("…") || "working" in low || "connecting" in low || "checking" in low || "pairing" in low -> NoticeTone.WORKING
            "failed" in low || "could not" in low || "refused" in low || "rejected" in low ||
                "invalid" in low || "error" in low || "no file picker" in low || "enter a" in low ||
                "connect a tv" in low || "did not" in low -> NoticeTone.ERROR
            "saved" in low || "restored" in low || "installed" in low || "updated" in low ||
                "copied" in low || "turned off" in low || "turned on" in low || "sent" in low ||
                "up to date" in low || "opened" in low || "cleared" in low || "cleaned" in low ||
                "uploaded" in low || "uninstalled" in low || "renamed" in low || "switched" in low -> NoticeTone.SUCCESS
            else -> NoticeTone.INFO
        }
    }

    internal fun report(message: String, tone: NoticeTone? = null) {
        noticeJob?.cancel()
        noticeJob = null
        if (message.isBlank()) {
            notice.text = ""
            notice.visibility = View.GONE
            return
        }
        val resolved = tone ?: inferNoticeTone(message)
        val bgFill: Int
        val strokeColor: Int
        val textColor: Int
        val prefix: String
        when (resolved) {
            NoticeTone.SUCCESS -> {
                bgFill = greenBg; strokeColor = greenStroke; textColor = successInk; prefix = "✓  "
            }
            NoticeTone.ERROR -> {
                bgFill = dangerBg; strokeColor = rose; textColor = dangerInk; prefix = "!  "
            }
            NoticeTone.WORKING -> {
                bgFill = tint; strokeColor = if (dark) accentBright else edge; textColor = ink; prefix = "●  "
            }
            NoticeTone.INFO -> {
                bgFill = tint; strokeColor = if (dark) accentBright else edge; textColor = ink; prefix = ""
            }
        }
        notice.text = "$prefix$message"
        notice.setTextColor(textColor)
        notice.background = card(bgFill, 14, outlined = false).apply {
            setStroke(px(1), strokeColor)
        }
        notice.visibility = View.VISIBLE
        if (resolved != NoticeTone.WORKING) {
            noticeJob = scope.launch {
                delay(4500)
                if (!activeOperation) {
                    notice.visibility = View.GONE
                }
            }
        }
    }

    internal fun openLink(link: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            .onFailure { report("This device could not open the link.", NoticeTone.ERROR) }
    }

    internal fun confirm(title: String, description: String, action: () -> Unit) {
        showSheet(title, closeIcon = false) { popup, frame ->
            val noteBox = text(description, 14, muted).apply {
                background = card(surfaceElevated, 16, outlined = true)
                setLineSpacing(px(3).toFloat(), 1f)
                setPadding(px(14), px(13), px(14), px(13))
            }
            frame.addView(noteBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(16) })
            val btnRow = row()
            btnRow.addView(button(getString(R.string.action_cancel)) { popup.dismiss() }, LinearLayout.LayoutParams(0, px(50), 1f))
            btnRow.addView(button(getString(R.string.action_confirm), emphasis = true) {
                popup.dismiss()
                action()
            }, LinearLayout.LayoutParams(0, px(50), 1.25f).apply { marginStart = px(10) })
            frame.addView(btnRow, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    internal fun ask(title: String, initial: String = "", action: (String) -> Unit) {
        showSheet(title, closeIcon = false) { popup, frame ->
            val value = input(title).apply {
                setSingleLine()
                setText(initial)
                setSelection(text.length)
                background = card(surfaceElevated, 14, outlined = true)
                setPadding(px(14), px(13), px(14), px(13))
            }
            frame.addView(value, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(16) })
            val btnRow = row()
            btnRow.addView(button(getString(R.string.action_cancel)) { popup.dismiss() }, LinearLayout.LayoutParams(0, px(50), 1f))
            btnRow.addView(button(getString(R.string.action_apply), emphasis = true) {
                popup.dismiss()
                action(value.text.toString())
            }, LinearLayout.LayoutParams(0, px(50), 1.25f).apply { marginStart = px(10) })
            frame.addView(btnRow, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    internal fun heading(value: String, size: Int = 24) {
        content.addView(text(value, size, bold = true).apply { setPadding(0, px(9), 0, px(14)) })
    }
    internal fun paragraph(value: String) { content.addView(text(value, 15, muted).apply {
        setLineSpacing(px(3).toFloat(), 1f)
        setPadding(0, px(2), 0, px(12))
    }) }
    internal fun statusCard(value: String): TextView = text(value, 15).apply {
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
        typeface = if (bold) uiBoldTypeface else uiRegularTypeface
        if (dark && size >= 22) letterSpacing = 0.01f
    }
    internal fun input(label: String, themed: Boolean = false) =
        EditText(if (themed) android.view.ContextThemeWrapper(this, dialogTheme) else this).apply {
            hint = label
            typeface = uiRegularTypeface
            setHintTextColor(muted)
            setTextColor(ink)
            backgroundTintList = ColorStateList.valueOf(accent)
        }
    private fun backButton(label: String) = textButton(label, getString(R.string.action_back)) { handleBackNavigation() }.apply {
        labelIcon(this, Mark.BACK)
    }

    internal fun textButton(label: String, description: String = label, action: () -> Unit) =
        button(label, description, action = action).apply {
            setTextColor(accent)
            background = RippleDrawable(ColorStateList.valueOf(ripple), null, card(radius = 12))
            textSize = 15f
            setPadding(px(6), px(4), px(6), px(4))
        }

    internal fun button(label: String, description: String = label, emphasis: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        textSize = 15f
        typeface = uiBoldTypeface
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
            card(if (dark) surfaceElevated else field, 15, outlined = true)
        }
        background = interactiveBackground(surfaceBg, focusFill = if (emphasis) accentStrong else tint, radius = 15)
        setPadding(px(14), px(8), px(14), px(8))
        setOnClickListener { action() }
    }
    internal fun addButton(label: String, emphasis: Boolean = false, action: () -> Unit) {
        content.addView(button(label, emphasis = emphasis, action = action),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(8) })
    }
    internal fun actions(vararg buttons: Pair<String, () -> Unit>) {
        val line = row()
        buttons.forEachIndexed { i, (name, action) ->
            line.addView(button(name, action = action), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i > 0) marginStart = px(8)
            })
        }
        content.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(8) })
    }
    internal fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    internal fun rounded(color: Int, corner: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(corner).toFloat()
    }

    companion object {
        internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val primaryPanels = setOf(Panel.DASHBOARD, Panel.APPS, Panel.SHELL, Panel.CARE)
        private const val PICK = 501
        private const val SAVE = 502
    }
}

internal fun percent(number: Int?) = number?.let { "$it%" } ?: "—"
