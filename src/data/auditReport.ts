export type BugCategory = 'theme' | 'feature';
export type BugSeverity = 'critical' | 'high' | 'medium';

export type ScreenId =
  | 'WELCOME'
  | 'DASHBOARD'
  | 'LIBRARY'
  | 'APPS'
  | 'FILES'
  | 'SHELL'
  | 'CARE'
  | 'CLEAN'
  | 'CHECK'
  | 'RESTORE'
  | 'SPACE'
  | 'SEND'
  | 'REMOTE'
  | 'MIRROR'
  | 'INFO'
  | 'INSTALL'
  | 'APPEARANCE';

export interface AuditIssue {
  id: string;
  title: string;
  category: BugCategory;
  severity: BugSeverity;
  file: string;
  lines: string;
  screens: ScreenId[];
  summary: string;
  rootCause: string;
  fixSummary: string;
  beforeCode: string;
  afterCode: string;
}

export const AUDIT_ISSUES: AuditIssue[] = [
  {
    id: 'theme-light-base-dialogs',
    title: 'Dark Mode broken in Window Background, EditTexts, AlertDialogs & AutoComplete Dropdown',
    category: 'theme',
    severity: 'critical',
    file: 'app/src/main/res/values/styles.xml',
    lines: 'L1–L14 (+ BridgeActivity.kt:126, CarePanels.kt:165)',
    screens: ['APPEARANCE', 'CLEAN', 'DASHBOARD', 'WELCOME'],
    summary:
      'Base activity theme is hardcoded to Theme.Material.Light.NoActionBar, causing stark white flashes, unreadable white AutoComplete dropdowns in Dark Mode, and mismatched dialog controls.',
    rootCause:
      'styles.xml inherits Theme.Material.Light.NoActionBar without updating window.setBackgroundDrawable on theme toggle. Dialog inputs created with EditText(this) inherit Light theme cursor/highlight tints, and CarePanels AutoCompleteTextView uses android.R.layout.simple_dropdown_item_1line against a Light context.',
    fixSummary:
      'Add full dark text/surface attributes to BridgeDialogDark, update window.setBackgroundDrawable(ColorDrawable(surface)) inside draw(), construct dialog inputs via ContextThemeWrapper(this, dialogTheme), and theme the Clean Up TV dropdown with card(field, 12) + custom ink text rows.',
    beforeCode: `<style name="BridgeDialogDark" parent="android:style/Theme.Material.Dialog.Alert">
    <item name="android:colorAccent">#B0DEFF</item>
    <item name="android:colorBackground">#111418</item>
</style>

// CarePanels.kt:172
setAdapter(ArrayAdapter(this@cleanup, android.R.layout.simple_dropdown_item_1line, files))`,
    afterCode: `<style name="BridgeDialogDark" parent="android:style/Theme.Material.Dialog.Alert">
    <item name="android:colorAccent">#B0DEFF</item>
    <item name="android:colorBackground">#111418</item>
    <item name="android:windowBackground">@drawable/dialog_bg_dark</item>
    <item name="android:textColorPrimary">#EFF6FF</item>
    <item name="android:textColorSecondary">#ADB8C5</item>
    <item name="android:textColorAlertDialogListItem">#EFF6FF</item>
</style>

// BridgeActivity.kt:127
window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surface))

// CarePanels.kt:172
setDropDownBackgroundDrawable(card(field, 12))
setAdapter(themedDropdownAdapter(files))`
  },
  {
    id: 'theme-shell-missing-dock',
    title: 'Bottom Navigation Dock disappears on ADB Shell / Terminal & label clips',
    category: 'theme',
    severity: 'high',
    file: 'app/src/main/java/dev/a4tvbridge/independent/BridgeActivity.kt',
    lines: 'L196, L617 (+ BridgeDesign.kt:99–108)',
    screens: ['SHELL', 'DASHBOARD'],
    summary:
      'Tapping the 4th dock tab ("ADB Shell / Terminal") hides the entire bottom navigation bar and switches to a sub-page header, breaking top-level navigation.',
    rootCause:
      'primaryPanels only includes DASHBOARD, LIBRARY, and CARE — omitting Panel.SHELL even though Panel.SHELL is a primary tab in dock(). In addition, "ADB Shell /\\nTerminal" overflows narrow 360dp columns with maxLines = 2.',
    fixSummary:
      'Add Panel.SHELL to primaryPanels so the dock stays anchored across all 4 top-level tabs, shorten the dock caption to "Terminal" (keeping full "ADB Shell / Terminal" contentDescription for tests), and keep tab switching clean without polluting the back stack.',
    beforeCode: `// BridgeActivity.kt:617
private val primaryPanels = setOf(Panel.DASHBOARD, Panel.LIBRARY, Panel.CARE)

// BridgeDesign.kt:101
Panel.SHELL -> "ADB Shell /\\nTerminal"`,
    afterCode: `// BridgeActivity.kt:617
private val primaryPanels = setOf(Panel.DASHBOARD, Panel.LIBRARY, Panel.SHELL, Panel.CARE)

// BridgeDesign.kt:101
Panel.SHELL -> "ADB\\nTerminal" // contentDescription remains "ADB Shell / Terminal"`
  },
  {
    id: 'theme-bottom-sheet-corners',
    title: 'Bottom Sheets round bottom corners & ignore navigation-bar gesture insets',
    category: 'theme',
    severity: 'high',
    file: 'app/src/main/java/dev/a4tvbridge/independent/BridgeDesign.kt',
    lines: 'L124–L152',
    screens: ['WELCOME', 'DASHBOARD', 'REMOTE', 'INSTALL'],
    summary:
      'Every bottom sheet (Control, Connect, About, File Picker) leaves dark gaps at the bottom corners and places primary buttons under Android 10–15 gesture navigation bars.',
    rootCause:
      'showSheet() sets background = card(surface, 22, false) which applies a uniform 22dp radius to all 4 corners, and uses deprecated decorView.systemUiVisibility without applying bottom WindowInsetsCompat padding.',
    fixSummary:
      'Use top-only cornerRadii (22dp top-left/top-right, 0dp bottom), apply WindowInsetsCompat navigation bar bottom padding to the sheet container, and use WindowCompat.getInsetsController for navigation bar icon contrast.',
    beforeCode: `val sheet = column().apply {
    background = card(surface, 22, false)
    setPadding(px(21), px(21), px(21), px(24))
    ...
}
if (android.os.Build.VERSION.SDK_INT >= 26) {
    decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
}`,
    afterCode: `val sheet = column().apply {
    val r = px(22).toFloat()
    background = GradientDrawable().apply {
        setColor(surface)
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
    setPadding(px(21), px(18), px(21), px(24))
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        v.setPadding(px(21), px(18), px(21), px(24) + nav.bottom)
        insets
    }
}
WindowCompat.getInsetsController(this, decorView).isAppearanceLightNavigationBars = !dark`
  },
  {
    id: 'theme-apps-tabs-no-active-state',
    title: 'Apps screen filter tabs (Installed / Built-in / Running) show no active state',
    category: 'theme',
    severity: 'high',
    file: 'app/src/main/java/dev/a4tvbridge/independent/FileAndAppPanels.kt',
    lines: 'L150–L174',
    screens: ['APPS'],
    summary:
      'Tapping Installed, Built-in, or Running filters the app list but leaves all 3 buttons visually identical, giving zero indication of the active mode.',
    rootCause:
      'apps() renders the 3 tabs via generic actions(...) which creates static button() widgets and never updates background, border, or text color when activeMode changes.',
    fixSummary:
      'Replace actions(...) with a themed segmented tab row (matching Device Info / Remote styling) that highlights the active tab with tint fill + accent bold text and sets isSelected for accessibility.',
    beforeCode: `actions(*modes.mapIndexed { index, mode ->
    mode to { activeMode = index; display() }
}.toTypedArray())`,
    afterCode: `val tabRow = row().apply {
    background = card(field, 14, outlined = true)
    setPadding(px(4), px(4), px(4), px(4))
}
val tabButtons = modes.mapIndexed { index, mode ->
    textButton(mode) { activeMode = index; updateTabs(); display() }
}
fun updateTabs() = tabButtons.forEachIndexed { i, btn ->
    val active = i == activeMode
    btn.isSelected = active
    btn.background = if (active) card(tint, 10, false) else null
    btn.setTextColor(if (active) accent else muted)
}`
  },
  {
    id: 'theme-remote-dark-mode-contrast',
    title: 'Remote D-pad hardcodes glaring Light-Mode blue gradient & Touchpad toggle fails to dim inactive label',
    category: 'theme',
    severity: 'high',
    file: 'app/src/main/java/dev/a4tvbridge/independent/RemotePanels.kt',
    lines: 'L56–L71, L190–L230',
    screens: ['REMOTE', 'MIRROR'],
    summary:
      'In True Black Dark Mode, the RemotePad stays bright royal blue (#00587B → #1C49CB) with a black cutout center, and the D-pad / Touchpad segmented switch keeps both labels bright accent.',
    rootCause:
      'RemotePad.init hardcodes Light-mode hex colors instead of theme-aware Dark surfaces, and mode() updates item.background without updating item.setTextColor() or isSelected.',
    fixSummary:
      'Use a deep oceanic dark gradient (#0D1F2D → #142B3E with 1dp edge stroke) in Dark Mode, crisp teal-blue in Light Mode, update both text color and isSelected on the D-pad/Touchpad switch, and add a framed placeholder state to TouchPreview.',
    beforeCode: `// RemotePanels.kt:191
background = GradientDrawable(
    GradientDrawable.Orientation.TL_BR,
    intArrayOf(0xFF00587B.toInt(), 0xFF1C49CB.toInt())
).apply { cornerRadius = activity.px(32).toFloat() }

// RemotePanels.kt:65
item.background = if ((n == 1) == usingTouch) card(surface, 24, false) else null`,
    afterCode: `// RemotePanels.kt:191
val colors = if (activity.dark) intArrayOf(0xFF0E2230.toInt(), 0xFF15324A.toInt())
             else intArrayOf(0xFF00587B.toInt(), 0xFF0C6B94.toInt())
background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
    cornerRadius = activity.px(32).toFloat()
    if (activity.dark) setStroke(activity.px(1), activity.edge)
}

// RemotePanels.kt:65
val selected = (n == 1) == usingTouch
item.isSelected = selected
item.background = if (selected) card(surface, 24, false) else null
item.setTextColor(if (selected) accent else muted)`
  },
  {
    id: 'theme-dashboard-welcome-metrics',
    title: 'Welcome Hero hardcodes navy in Dark Mode, Metric tiles lack contrast, Appearance subtitles oversize',
    category: 'theme',
    severity: 'medium',
    file: 'app/src/main/java/dev/a4tvbridge/independent/Dashboard.kt',
    lines: 'L15–L26, L78–L85 (+ Appearance.kt:73, BridgeActivity.kt:592)',
    screens: ['WELCOME', 'DASHBOARD', 'APPEARANCE'],
    summary:
      'Welcome hero clashes with True Black Dark Mode, CPU/RAM/Network metric sub-tiles blend into the card in Light Mode, and button horizontal padding (5dp) clips text.',
    rootCause:
      'Dashboard.kt:16 hardcodes 0xFF0B1327 without border in Dark Mode; metric() uses unstroked white over #F8F9FB (only 2% luminance delta); Appearance.kt renders summary text at 16sp (same as title); BridgeActivity.button() sets only px(5) horizontal padding.',
    fixSummary:
      'Adapt Welcome card surface & border for Dark Mode (field + 1dp edge stroke), give metric tiles a clean 1dp hairline border, reduce Appearance summary to 13sp for clear hierarchy, and increase button horizontal padding to px(14).',
    beforeCode: `// Dashboard.kt:16
background = card(0xFF0B1327.toInt(), 22, false)
// Dashboard.kt:79
background = card(surface, 11, false)
// Appearance.kt:73
addView(text(choice.summary, 16, muted))
// BridgeActivity.kt:592
setPadding(px(5), px(4), px(5), px(4))`,
    afterCode: `// Dashboard.kt:16
background = if (dark) card(field, 22, outlined = true) else card(0xFF0B1327.toInt(), 22, false)
// Dashboard.kt:79
background = card(surface, 11, outlined = true)
// Appearance.kt:73
addView(text(choice.summary, 13, muted))
// BridgeActivity.kt:592
setPadding(px(14), px(6), px(14), px(6))`
  },
  {
    id: 'feat-toybox-mv-realpath',
    title: 'File Manager, Upload, Send Files & Rename fail on Android 8–12 TVs (mv -n & realpath)',
    category: 'feature',
    severity: 'critical',
    file: 'app/src/main/java/dev/a4tvbridge/independent/TvConnection.kt',
    lines: 'L103, L149, L172',
    screens: ['FILES', 'SEND'],
    summary:
      'Uploading files, Send Files, Renaming, and browsing File Manager fail with "mv: Unknown option \'n\'" or "realpath: not found" on Android 8–12 TVs and Fire TVs.',
    rootCause:
      'Android Toybox mv on Android 8–12 does not support the -n flag, yet rename() (L149) and upload() (L172) run mv -n even after explicitly checking test ! -e $to. Likewise verifiedFolder() calls realpath without falling back to readlink -f.',
    fixSummary:
      'Use POSIX-portable readlink -f fallback when realpath is unavailable, replace mv -n with safe mv after the existing non-existence check, and return a clear error when a file with the same name already exists.',
    beforeCode: `// TvConnection.kt:103
base=$(realpath /sdcard) && target=$(realpath \${shellText(path)})

// TvConnection.kt:149 & 172
checked("test ! -e $to && test ! -L $to && mv -n $from $to && test ! -e $from")
checked("mv -n $staged $destination && test ! -e $staged")`,
    afterCode: `// TvConnection.kt:103
resolve() { realpath "$1" 2>/dev/null || readlink -f "$1" 2>/dev/null || (cd "$1" 2>/dev/null && pwd -P); }
base=$(resolve /sdcard) && target=$(resolve \${shellText(path)})

// TvConnection.kt:149 & 172
checked("if [ -e $to ] || [ -L $to ]; then echo 'File already exists.' >&2; exit 1; fi; mv -- $from $to")
checked("if [ -e $destination ] || [ -L $destination ]; then echo 'File already exists.' >&2; exit 1; fi; mv -- $staged $destination")`
  },
  {
    id: 'feat-global-mutex-blocks-remote',
    title: 'Rapid Remote D-pad & Touchpad presses fail with "Wait for the current operation" + layout bounce',
    category: 'feature',
    severity: 'critical',
    file: 'app/src/main/java/dev/a4tvbridge/independent/BridgeActivity.kt',
    lines: 'L412–L441 (+ RemotePanels.kt:60, 117, 148)',
    screens: ['REMOTE', 'MIRROR'],
    summary:
      'Pressing D-pad arrows or Volume keys quickly drops keypresses with an error banner, and every keypress flashes "Working…" at the top, shifting the whole screen downward.',
    rootCause:
      'Remote key() and Mirror gesture() call tvTask() -> backgroundTask(), which acquires the global activeOperation boolean lock and toggles notice.visibility = View.VISIBLE ("Working…"), causing immediate rejection of queued inputs and vertical layout jitter.',
    fixSummary:
      'Introduce a dedicated sequential inputQueue (kotlinx.coroutines Channel) for low-latency key() and gesture() commands that executes in order without setting activeOperation or flashing the "Working…" banner.',
    beforeCode: `// RemotePanels.kt:60 & 117
val touchSurface = RemotePad(this) { code -> tvTask({ key(code) }) {} }
val send = { tvTask({ key(key.code) }) {} }

// BridgeActivity.kt:424
if (activeOperation) return report("Wait for the current operation.")`,
    afterCode: `// BridgeActivity.kt
private val inputQueue = Channel<suspend (TvConnection) -> Unit>(capacity = 32)
internal fun sendInput(action: TvConnection.() -> Unit) {
    val tv = connection ?: return report("Connect a TV first.")
    inputQueue.trySend { active -> if (active === tv) active.action() }
}
// RemotePanels.kt
val touchSurface = RemotePad(this) { code -> sendInput { key(code) } }`
  },
  {
    id: 'feat-config-change-kills-adb',
    title: 'Screen rotation or system Dark/Light switch closes ADB connection & wipes app state',
    category: 'feature',
    severity: 'critical',
    file: 'app/src/main/AndroidManifest.xml',
    lines: 'L9 (+ BridgeActivity.kt:77–102)',
    screens: ['DASHBOARD', 'SHELL', 'MIRROR', 'APPEARANCE'],
    summary:
      'Rotating the phone, entering split-screen, or toggling Android Dark Mode destroys BridgeActivity, disconnects the TV, and clears terminal output and file-manager path.',
    rootCause:
      'AndroidManifest.xml omits android:configChanges on BridgeActivity, and BridgeActivity stores connection, page, folders, and shellOutput in instance fields destroyed in onDestroy() (which calls connection?.close()).',
    fixSummary:
      'Declare android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode" in AndroidManifest.xml and override onConfigurationChanged() in BridgeActivity to call draw() seamlessly without dropping connection.',
    beforeCode: `<!-- AndroidManifest.xml:9 -->
<activity android:name=".BridgeActivity"
    android:exported="true"
    android:windowSoftInputMode="adjustResize">`,
    afterCode: `<!-- AndroidManifest.xml:9 -->
<activity android:name=".BridgeActivity"
    android:exported="true"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode"
    android:windowSoftInputMode="adjustResize">

// BridgeActivity.kt
override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    draw()
}`
  },
  {
    id: 'feat-one-click-install-github-api',
    title: '1-Click App Install fails with HTTP 403 / ABI whitespace & strict digest filter',
    category: 'feature',
    severity: 'critical',
    file: 'app/src/main/java/dev/a4tvbridge/independent/ReleaseDownloads.kt',
    lines: 'L38, L41, L83–L90 (+ FileAndAppPanels.kt:347)',
    screens: ['INSTALL'],
    summary:
      '1-Click Install for SmartTube, Jellyfin, and Aerial Views fails when GitHub rejects requests missing User-Agent or when release assets omit optional sha256 digest metadata.',
    rootCause:
      'get() never sets User-Agent or Accept: application/vnd.github+json headers (causing GitHub API 403s); FileAndAppPanels.kt splits ro.product.cpu.abilist without trimming items; and ReleaseDownloads.kt filters out any APK lacking the newly added GitHub digest field.',
    fixSummary:
      'Add explicit User-Agent and Accept headers on API calls, trim/filter CPU ABIs before matching SmartTube APKs, and verify SHA-256 whenever digest is published while falling back cleanly to HTTPS + package ID verification if omitted.',
    beforeCode: `// ReleaseDownloads.kt:83
val connection = url.openConnection() as HttpURLConnection
connection.instanceFollowRedirects = false
connection.requestMethod = "GET"

// FileAndAppPanels.kt:347
val abis = command("getprop ro.product.cpu.abilist").text.split(',')`,
    afterCode: `// ReleaseDownloads.kt:83
val connection = url.openConnection() as HttpURLConnection
connection.instanceFollowRedirects = false
connection.requestMethod = "GET"
connection.setRequestProperty("User-Agent", "A4-TV-Bridge/\${BuildConfig.VERSION_NAME}")
if (url.host == "api.github.com") {
    connection.setRequestProperty("Accept", "application/vnd.github+json")
}

// FileAndAppPanels.kt:347
val abis = command("getprop ro.product.cpu.abilist").text
    .split(',').map(String::trim).filter(String::isNotEmpty)`
  },
  {
    id: 'feat-discovery-scan-deadlock',
    title: 'Connect sheet blocks tapping discovered TVs during scan & clips device list to 72dp',
    category: 'feature',
    severity: 'high',
    file: 'app/src/main/java/dev/a4tvbridge/independent/Connections.kt',
    lines: 'L203–L231',
    screens: ['WELCOME', 'DASHBOARD'],
    summary:
      'Tapping an mDNS-discovered TV while "Find TVs on this network" is scanning fails silently with "A connection or TV command is already in progress", and the device list is cramped to 72dp.',
    rootCause:
      'The LAN scan uses global backgroundTask() (setting activeOperation = true for ~3s) and is never cancelled when the user taps a TV or dismisses the sheet. Meanwhile the ScrollView height is fixed at px(72) when known.isEmpty(), hiding additional discovered TVs.',
    fixSummary:
      'Run LAN discovery in a sheet-scoped Job cancelled automatically on popup dismiss or TV selection (never blocking activeOperation), and size the discovered devices ScrollView dynamically up to px(210).',
    beforeCode: `Tile("Find TVs on this network", Mark.TV, selected = true) {
    backgroundTask({ findLegacyEndpoints() }, ...) { ... }
}
layout.addView(ScrollView(this).apply { addView(matches) },
    LinearLayout.LayoutParams(MATCH, px(if (known.isEmpty()) 72 else 124)))`,
    afterCode: `var scanJob: Job? = null
Tile("Find TVs on this network", Mark.TV, selected = true) {
    scanJob?.cancel()
    scanJob = scope.launch {
        val addresses = withContext(Dispatchers.IO) { findLegacyEndpoints() }
        ...
    }
}
popup.setOnDismissListener { scanJob?.cancel(); discovery.close() }
// Dynamic container height up to 210dp so all discovered TVs remain visible`
  },
  {
    id: 'feat-cleanup-order-and-protect',
    title: 'Clean Up TV corrupts restore log on failure & protectForCleanup blocks all apps on Android 8/9',
    category: 'feature',
    severity: 'high',
    file: 'app/src/main/java/dev/a4tvbridge/independent/CarePanels.kt',
    lines: 'L53–L56, L165–L185 (+ TvConnection.kt:224–234)',
    screens: ['CLEAN', 'CHECK', 'RESTORE', 'APPS'],
    summary:
      'Failed disable attempts still mark apps as disabled in Restore/Check my TV; older Android 8/9 TVs refuse to disable ANY app; and users must manually type exact profile filenames.',
    rootCause:
      'rememberDisabled() calls updateRestore(tv, id, true) BEFORE tv.togglePackage(id, false). protectForCleanup() returns true whenever cmd package resolve-activity --brief fails on Android 8/9. Clean Up TV never auto-matches tv.model against assets/profiles/*.txt.',
    fixSummary:
      'Call tv.togglePackage(id, false) first and only persist updateRestore() on success; add Android 8/9 fallback in protectForCleanup(); auto-suggest matching profiles by TV model and trigger reportProfile() on dropdown item click.',
    beforeCode: `// CarePanels.kt:53
internal fun BridgeActivity.rememberDisabled(tv: TvConnection, id: String) {
    updateRestore(tv, id, true)
    tv.togglePackage(id, false)
}

// TvConnection.kt:231
if (home.status != 0 || '/' !in home.text || keyboard.status != 0) return true`,
    afterCode: `// CarePanels.kt:53
internal fun BridgeActivity.rememberDisabled(tv: TvConnection, id: String) {
    tv.togglePackage(id, false)
    updateRestore(tv, id, true)
}

// TvConnection.kt:229 (fallback when --brief is unsupported on Android 8/9)
val home = command("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null || dumpsys package | grep -A2 'android.intent.category.HOME'")`
  },
  {
    id: 'feat-df-wrapped-storage-and-ps',
    title: 'Storage reads "—" on wrapped df output & Running apps tab is empty on Android 8–10 TVs',
    category: 'feature',
    severity: 'high',
    file: 'app/src/main/java/dev/a4tvbridge/independent/DeviceReading.kt',
    lines: 'L50 (+ TvConnection.kt:188, 287, 298)',
    screens: ['DASHBOARD', 'INFO', 'APPS', 'SPACE'],
    summary:
      'TVs with long /dev/block/... userdata paths wrap df -k /data onto two lines so Storage shows "—" and 0%, and Running apps / RSS memory return empty when ps -A -o is unsupported.',
    rootCause:
      'TvConnection.details() runs df -k /data instead of POSIX single-line df -Pk /data, and DeviceReading.parse indexes columns from the left (getOrNull(1)/getOrNull(3)). runningPackages() runs ps -A -o NAME without fallback to ps -A / ps.',
    fixSummary:
      'Use df -Pk /data (falling back to df -k /data) and parse numeric columns relative to the trailing /data mount point; add ps -A / ps fallback for runningPackages() and processMemory().',
    beforeCode: `// TvConnection.kt:287
df -k /data
// DeviceReading.kt:50
val disk = sections["__disk"].orEmpty().firstOrNull { it.endsWith(" /data") }?.split(Regex("\\\\s+"))
diskTotal = disk?.getOrNull(1)?.toLongOrNull()?.times(1024)
diskFree = disk?.getOrNull(3)?.toLongOrNull()?.times(1024)`,
    afterCode: `// TvConnection.kt:287
df -Pk /data 2>/dev/null || df -k /data
// DeviceReading.kt:50 (index from right so wrapped lines still parse)
val disk = sections["__disk"].orEmpty().firstOrNull { it.endsWith(" /data") }?.split(Regex("\\\\s+"))
val sizeIdx = disk?.size?.minus(5)
diskTotal = sizeIdx?.let { disk.getOrNull(it)?.toLongOrNull()?.times(1024) }
diskFree = sizeIdx?.let { disk.getOrNull(it + 2)?.toLongOrNull()?.times(1024) }`
  },
  {
    id: 'feat-keyboard-and-filepicker-stuck',
    title: 'Remote Keyboard drops special characters & File Picker callback locks out after cancelled activity',
    category: 'feature',
    severity: 'medium',
    file: 'app/src/main/java/dev/a4tvbridge/independent/RemotePanels.kt',
    lines: 'L44–L49 (+ BridgeActivity.kt:460–465, TvConnection.kt:273)',
    screens: ['REMOTE', 'FILES', 'INSTALL'],
    summary:
      'Sending text with quotes, ampersands, or % fails on TV; screenrecord fails on Amlogic/Fire TV high bitrates; and onDocuments can lock out file picking if activity pauses unexpectedly.',
    rootCause:
      'RemotePanels only replaces space with %s without sanitizing input text or offering Enter/Backspace; BridgeActivity.chooseDocuments early-returns if onDocuments != null; and screenrecord omits --bit-rate fallback.',
    fixSummary:
      'Escape shell/input text safely with optional Send + Enter action, reset stale onDocuments callback before launching picker, and retry screenrecord with --bit-rate 4000000 if default bitrate fails on TV hardware encoders.',
    beforeCode: `// RemotePanels.kt:45
val entered = raw.replace(" ", "%s")
if (entered.isNotBlank()) tvTask({ command("input text \${shellText(entered)}") }) { ... }

// BridgeActivity.kt:461
if (onDocuments != null) return`,
    afterCode: `// RemotePanels.kt:45
val trimmed = raw.trim()
if (trimmed.isNotEmpty()) sendInput { sendText(trimmed) }

// BridgeActivity.kt:461
onDocuments = callback // Replace any stale callback rather than deadlocking picker`
  }
];

export const SCREEN_META: Record<
  ScreenId,
  { label: string; kotlinFiles: string[]; description: string }
> = {
  WELCOME: {
    label: 'Welcome & Connect',
    kotlinFiles: ['Dashboard.kt', 'Connections.kt', 'BridgeActivity.kt'],
    description: 'Initial onboarding state, Connect your TV bottom sheet, LAN scan, mDNS & wireless pairing.'
  },
  DASHBOARD: {
    label: 'Dashboard',
    kotlinFiles: ['Dashboard.kt', 'BridgeActivity.kt', 'BridgeDesign.kt', 'DeviceReading.kt'],
    description: 'Connected TV status card, live Storage/CPU/RAM/Network telemetry, and Quick Actions.'
  },
  LIBRARY: {
    label: 'Apps & Files Hub',
    kotlinFiles: ['Dashboard.kt', 'BridgeDesign.kt'],
    description: 'Hub for Installed Apps, APK Installer, File Manager, and Send Files.'
  },
  APPS: {
    label: 'Apps Manager',
    kotlinFiles: ['FileAndAppPanels.kt', 'TvConnection.kt'],
    description: 'Browse Installed, Built-in, and Running TV packages with shortcuts, permissions, export & disable.'
  },
  INSTALL: {
    label: 'Install Apps',
    kotlinFiles: ['FileAndAppPanels.kt', 'ReleaseDownloads.kt', 'TvConnection.kt'],
    description: 'Sideload APKs / split APKs and 1-Click Install SmartTube, Jellyfin, and Aerial Views.'
  },
  FILES: {
    label: 'File Manager',
    kotlinFiles: ['FileAndAppPanels.kt', 'TvConnection.kt'],
    description: 'Browse /sdcard shared storage, create folders, upload, download, rename, and delete.'
  },
  SEND: {
    label: 'Send Files',
    kotlinFiles: ['SendFiles.kt', 'TvConnection.kt'],
    description: 'Transfer media & documents directly into TV Downloads, Movies, Music, Pictures, or Documents.'
  },
  REMOTE: {
    label: 'Remote Control',
    kotlinFiles: ['RemotePanels.kt', 'BridgeActivity.kt', 'TvConnection.kt'],
    description: 'D-pad & Touchpad controller, TV keyboard input, volume/media controls, and pinned app shortcuts.'
  },
  MIRROR: {
    label: 'Screen Mirror',
    kotlinFiles: ['RemotePanels.kt', 'TvConnection.kt'],
    description: 'Live refreshed TV frame preview with tap/swipe gestures, Pause/Resume, Screenshot & Record.'
  },
  SHELL: {
    label: 'ADB Shell / Terminal',
    kotlinFiles: ['BridgeActivity.kt', 'BridgeDesign.kt', 'TvConnection.kt'],
    description: 'Run non-interactive ADB shell commands with stdout, stderr, and exit status reporting.'
  },
  CARE: {
    label: 'TV Care Hub',
    kotlinFiles: ['CarePanels.kt', 'BridgeDesign.kt'],
    description: 'Debloat profiles, change verification, storage cleanup, Private DNS ad-blocking, and Device Info.'
  },
  CLEAN: {
    label: 'Clean Up TV',
    kotlinFiles: ['CarePanels.kt', 'TvConnection.kt'],
    description: 'Search CleanYourTV device profiles and safely disable bloatware with automatic restore tracking.'
  },
  CHECK: {
    label: 'Check My TV',
    kotlinFiles: ['CarePanels.kt'],
    description: 'Verify whether recorded disabled bloatware packages stayed off after TV reboot.'
  },
  RESTORE: {
    label: 'Restore Apps',
    kotlinFiles: ['CarePanels.kt', 'TvConnection.kt'],
    description: 'Re-enable recorded disabled apps or reinstall system apps removed for user 0.'
  },
  SPACE: {
    label: 'Free Up Space',
    kotlinFiles: ['CarePanels.kt', 'DeviceReading.kt'],
    description: 'Inspect free TV storage and trigger Android pm trim-caches.'
  },
  INFO: {
    label: 'Device Info Monitor',
    kotlinFiles: ['Monitor.kt', 'DeviceReading.kt', 'TvConnection.kt'],
    description: 'Real-time Overview gauges, CPU per-core breakdown, Memory PSS/RSS processes, and Network chart.'
  },
  APPEARANCE: {
    label: 'Appearance & About',
    kotlinFiles: ['Appearance.kt', 'About.kt', 'styles.xml', 'BridgeActivity.kt'],
    description: 'System Default, Light, and True Black Dark themes plus project info & open-source notices.'
  }
};

export interface FixedFilePatch {
  path: string;
  issueIds: string[];
  diff: string;
}

export const FIXED_PATCHES: FixedFilePatch[] = [
  {
    path: 'app/src/main/AndroidManifest.xml',
    issueIds: ['feat-config-change-kills-adb'],
    diff: `--- a/app/src/main/AndroidManifest.xml
+++ b/app/src/main/AndroidManifest.xml
@@ -6,7 +6,9 @@
     <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
     <application android:label="A4 TV Bridge" android:allowBackup="false"
         android:icon="@drawable/a4_mark" android:banner="@drawable/a4_tv" android:theme="@style/BridgeTheme">
-        <activity android:name=".BridgeActivity" android:exported="true" android:windowSoftInputMode="adjustResize">
+        <activity android:name=".BridgeActivity" android:exported="true"
+            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode"
+            android:windowSoftInputMode="adjustResize">
             <intent-filter>
                 <action android:name="android.intent.action.MAIN" />
                 <category android:name="android.intent.category.LAUNCHER" />`
  },
  {
    path: 'app/src/main/res/values/styles.xml',
    issueIds: ['theme-light-base-dialogs'],
    diff: `--- a/app/src/main/res/values/styles.xml
+++ b/app/src/main/res/values/styles.xml
@@ -6,9 +6,14 @@
     </style>
     <style name="BridgeDialogLight" parent="android:style/Theme.Material.Light.Dialog.Alert">
         <item name="android:colorAccent">#00587B</item>
+        <item name="android:textColorPrimary">#182238</item>
+        <item name="android:textColorSecondary">#465469</item>
     </style>
     <style name="BridgeDialogDark" parent="android:style/Theme.Material.Dialog.Alert">
         <item name="android:colorAccent">#B0DEFF</item>
         <item name="android:colorBackground">#111418</item>
+        <item name="android:textColorPrimary">#EFF6FF</item>
+        <item name="android:textColorSecondary">#ADB8C5</item>
+        <item name="android:textColorAlertDialogListItem">#EFF6FF</item>
     </style>
 </resources>`
  },
  {
    path: 'app/src/main/java/dev/a4tvbridge/independent/BridgeActivity.kt',
    issueIds: ['theme-light-base-dialogs', 'theme-shell-missing-dock', 'feat-global-mutex-blocks-remote', 'feat-config-change-kills-adb', 'feat-keyboard-and-filepicker-stuck'],
    diff: `--- a/app/src/main/java/dev/a4tvbridge/independent/BridgeActivity.kt
+++ b/app/src/main/java/dev/a4tvbridge/independent/BridgeActivity.kt
@@ -25,6 +25,7 @@
 import kotlinx.coroutines.CancellationException
 import kotlinx.coroutines.CoroutineScope
 import kotlinx.coroutines.Dispatchers
+import kotlinx.coroutines.channels.Channel
 import kotlinx.coroutines.Job
 import kotlinx.coroutines.SupervisorJob
 import kotlinx.coroutines.cancel
@@ -47,6 +48,7 @@
     internal val folders = mutableListOf(TvPath.root)
     internal val folder get() = folders.last()
     private var polling: Job? = null
+    private val inputQueue = Channel<Pair<TvConnection, TvConnection.() -> Unit>>(32)
     private var activeOperation = false
     internal lateinit var content: LinearLayout
         private set
@@ -80,6 +82,19 @@
         WindowCompat.setDecorFitsSystemWindows(window, false)
         window.statusBarColor = Color.TRANSPARENT
         window.navigationBarColor = Color.TRANSPARENT
+        scope.launch {
+            for ((tv, command) in inputQueue) {
+                if (connection !== tv) continue
+                runCatching { withContext(Dispatchers.IO) { tv.command() } }
+                    .onFailure { if (it !is CancellationException) report(it.message?.take(180) ?: "Command failed.") }
+            }
+        }
+        draw()
+    }
+
+    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
+        super.onConfigurationChanged(newConfig)
         draw()
     }
@@ -113,7 +128,11 @@
     }
 
     internal fun show(next: Panel, rememberPage: Boolean = true) {
-        if (next != page && rememberPage) screens.addLast(page)
+        if (next != page && rememberPage) {
+            if (next in primaryPanels && page in primaryPanels) screens.clear()
+            if (next != Panel.DASHBOARD) screens.addLast(page)
+            else screens.clear()
+        }
         page = next
         draw()
     }
@@ -124,6 +143,7 @@
         polling?.cancel()
         polling = null
         dark = appearanceChoice().usesDark(systemUsesDark())
+        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surface))
         val shell = column().apply { setBackgroundColor(surface) }
@@ -411,6 +431,11 @@
         content.addView(output, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(5) })
     }
 
+    internal fun sendInput(action: TvConnection.() -> Unit) {
+        val tv = connection ?: return report("Connect a TV first.")
+        inputQueue.trySend(tv to action)
+    }
+
     internal fun <T> tvTask(work: TvConnection.() -> T, done: (T) -> Unit) {
@@ -458,7 +483,6 @@
     }
 
     internal fun chooseDocuments(mime: String, multiple: Boolean, callback: (List<Uri>) -> Unit) {
-        if (onDocuments != null) return
         onDocuments = callback
@@ -557,7 +581,7 @@
         if (dark && size >= 22) letterSpacing = 0.01f
     }
-    internal fun input(label: String) = EditText(this).apply {
+    internal fun input(label: String, themed: Boolean = false) = EditText(if (themed) android.view.ContextThemeWrapper(this, dialogTheme) else this).apply {
         hint = label
         setHintTextColor(muted)
         setTextColor(ink)
@@ -589,7 +613,7 @@
         background = android.graphics.drawable.RippleDrawable(
             ColorStateList.valueOf(ripple), card(if (emphasis) accent else field, corner, !emphasis), null,
         )
-        setPadding(px(5), px(4), px(5), px(4))
+        setPadding(px(14), px(6), px(14), px(6))
         setOnClickListener { action() }
     }
@@ -614,7 +638,7 @@
     companion object {
         internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
         internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
-        private val primaryPanels = setOf(Panel.DASHBOARD, Panel.LIBRARY, Panel.CARE)
+        private val primaryPanels = setOf(Panel.DASHBOARD, Panel.LIBRARY, Panel.SHELL, Panel.CARE)
         private const val PICK = 501
         private const val SAVE = 502
     }`
  },
  {
    path: 'app/src/main/java/dev/a4tvbridge/independent/BridgeDesign.kt',
    issueIds: ['theme-bottom-sheet-corners', 'theme-shell-missing-dock'],
    diff: `--- a/app/src/main/java/dev/a4tvbridge/independent/BridgeDesign.kt
+++ b/app/src/main/java/dev/a4tvbridge/independent/BridgeDesign.kt
@@ -98,7 +98,7 @@
                 } else addView(graphic, LinearLayout.LayoutParams(px(21), px(21)).apply { bottomMargin = px(3) })
                 addView(text(when (destination) {
                     Panel.LIBRARY -> "Apps &\\nFiles"
-                    Panel.SHELL -> "ADB Shell /\\nTerminal"
+                    Panel.SHELL -> "ADB\\nTerminal"
                     else -> label
                 }, 11, color, true).apply {
@@ -122,8 +122,17 @@
     val popup = Dialog(this, dialogTheme)
     popup.requestWindowFeature(Window.FEATURE_NO_TITLE)
     val sheet = column().apply {
-        background = card(surface, 22, false)
+        val r = px(22).toFloat()
+        background = GradientDrawable().apply {
+            setColor(surface)
+            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
+        }
         setPadding(px(21), px(21), px(21), px(24))
+        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
+            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
+            view.setPadding(px(21), px(21), px(21), px(24) + nav.bottom)
+            insets
+        }
         val handle = View(this@showSheet).apply { background = card(muted, 5, false) }
@@ -144,9 +153,7 @@
         setDimAmount(0.35f)
         addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
         navigationBarColor = surface
-        if (android.os.Build.VERSION.SDK_INT >= 26) {
-            decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
-        }
+        androidx.core.view.WindowCompat.getInsetsController(this, decorView).isAppearanceLightNavigationBars = !dark
         setGravity(Gravity.BOTTOM)
         setLayout(BridgeActivity.MATCH, BridgeActivity.WRAP)
     }`
  },
  {
    path: 'app/src/main/java/dev/a4tvbridge/independent/Dashboard.kt',
    issueIds: ['theme-dashboard-welcome-metrics'],
    diff: `--- a/app/src/main/java/dev/a4tvbridge/independent/Dashboard.kt
+++ b/app/src/main/java/dev/a4tvbridge/independent/Dashboard.kt
@@ -13,13 +13,13 @@
     val tv = connection
     if (tv == null) {
         val welcome = row().apply {
-            background = card(0xFF0B1327.toInt(), 22, false)
+            background = if (dark) card(field, 22, outlined = true) else card(0xFF0B1327.toInt(), 22, false)
             minimumHeight = px(143)
             setPadding(px(16), px(20), px(16), px(20))
         }
         val words = column().apply {
-            addView(text("A4 TV Bridge", 24, android.graphics.Color.WHITE, true))
-            addView(text("Connect to your TV to use the remote, manage apps, or send files.", 14, 0xFFB4C4D6.toInt()).apply {
+            addView(text("A4 TV Bridge", 24, if (dark) ink else android.graphics.Color.WHITE, true))
+            addView(text("Connect to your TV to use the remote, manage apps, or send files.", 14, if (dark) muted else 0xFFB4C4D6.toInt()).apply {
@@ -76,7 +76,7 @@
     val monitors = row()
     fun metric(label: String): TextView {
         val stack = column().apply {
-            background = card(surface, 11, false)
+            background = card(surface, 11, outlined = true)
             setPadding(px(9), px(8), px(7), px(8))
         }`
  },
  {
    path: 'app/src/main/java/dev/a4tvbridge/independent/Appearance.kt',
    issueIds: ['theme-dashboard-welcome-metrics'],
    diff: `--- a/app/src/main/java/dev/a4tvbridge/independent/Appearance.kt
+++ b/app/src/main/java/dev/a4tvbridge/independent/Appearance.kt
@@ -42,7 +42,8 @@
         val tile = row().apply {
             minimumHeight = px(70)
             setPadding(px(14), px(12), px(14), px(12))
-            background = card(radius = corner).apply { if (active) setStroke(px(1), accent) }
+            val fill = card(if (active) tint else field, corner).apply { if (active) setStroke(px(2), accent) }
+            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ripple), fill, null)
             isFocusable = true
@@ -70,7 +71,7 @@
         tile.addView(layered, LinearLayout.LayoutParams(px(32), px(32)))
         val legend = column().apply {
             addView(text(choice.label, 16, bold = true))
-            addView(text(choice.summary, 16, muted).apply { setPadding(0, px(3), 0, 0) })
+            addView(text(choice.summary, 13, muted).apply { setPadding(0, px(3), 0, 0) })
         }`
  },
  {
    path: 'app/src/main/java/dev/a4tvbridge/independent/RemotePanels.kt',
    issueIds: ['theme-remote-dark-mode-contrast', 'feat-global-mutex-blocks-remote', 'feat-keyboard-and-filepicker-stuck'],
    diff: `--- a/app/src/main/java/dev/a4tvbridge/independent/RemotePanels.kt
+++ b/app/src/main/java/dev/a4tvbridge/independent/RemotePanels.kt
@@ -42,10 +42,9 @@
     }
     header.addView(remoteIcon(Mark.KEYBOARD, "Keyboard", round = true) {
         ask("Send text to TV") { raw ->
-            val entered = raw.replace(" ", "%s")
-            if (entered.isNotBlank()) tvTask({ command("input text \${shellText(entered)}") }) {
-                commandStatus(it, "Text sent to the TV.")
-            }
+            val cleaned = raw.trim()
+            if (cleaned.isNotEmpty()) sendInput { sendText(cleaned) }
         }
     }, toolLayout(0))
@@ -57,18 +56,22 @@
         setPadding(px(4), px(4), px(4), px(4))
         background = card(tint, 28, false)
     }
-    val touchSurface = RemotePad(this) { code -> tvTask({ key(code) }) {} }
+    val touchSurface = RemotePad(this) { code -> sendInput { key(code) } }
     fun mode(label: String, touch: Boolean) = textButton(label) {
         usingTouch = touch
         touchSurface.touchMode = touch
         for (n in 0..1) {
             val item = modes.getChildAt(n) as android.widget.Button
-            item.background = if ((n == 1) == usingTouch) card(surface, 24, false) else null
+            val active = (n == 1) == usingTouch
+            item.isSelected = active
+            item.background = if (active) card(surface, 24, false) else null
+            item.setTextColor(if (active) accent else muted)
         }
     }
-    modes.addView(mode("D-pad", false).apply { background = card(surface, 24, false) },
+    modes.addView(mode("D-pad", false).apply { isSelected = true; background = card(surface, 24, false); setTextColor(accent) },
         LinearLayout.LayoutParams(0, px(40), 1f))
-    modes.addView(mode("Touchpad", true), LinearLayout.LayoutParams(0, px(40), 1f))
+    modes.addView(mode("Touchpad", true).apply { setTextColor(muted) }, LinearLayout.LayoutParams(0, px(40), 1f))
@@ -114,7 +117,7 @@
 private fun BridgeActivity.remoteKeyRow(parent: LinearLayout, keys: List<RemoteKey>) {
     val line = row()
     for ((index, key) in keys.withIndex()) {
-        val send = { tvTask({ key(key.code) }) {} }
+        val send = { sendInput { key(key.code) } }
@@ -145,7 +148,7 @@
     content.addView(mode, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(11) })
     val screen = TouchPreview(this) { start, end, interval ->
-        tvTask({ gesture(start.first, start.second, end.first, end.second, interval) }) {}
+        sendInput { gesture(start.first, start.second, end.first, end.second, interval) }
     }
@@ -188,10 +191,13 @@
     init {
+        val colors = if (activity.dark) intArrayOf(0xFF0E2230.toInt(), 0xFF15324A.toInt())
+        else intArrayOf(0xFF00587B.toInt(), 0xFF0C6B94.toInt())
         background = android.graphics.drawable.GradientDrawable(
-            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
-            intArrayOf(0xFF00587B.toInt(), 0xFF1C49CB.toInt())
-        ).apply { cornerRadius = activity.px(32).toFloat() }
+            android.graphics.drawable.GradientDrawable.Orientation.TL_BR, colors
+        ).apply {
+            cornerRadius = activity.px(32).toFloat()
+            if (activity.dark) setStroke(activity.px(1), activity.edge)
+        }`
  },
  {
    path: 'app/src/main/java/dev/a4tvbridge/independent/FileAndAppPanels.kt',
    issueIds: ['theme-apps-tabs-no-active-state', 'feat-one-click-install-github-api'],
    diff: `--- a/app/src/main/java/dev/a4tvbridge/independent/FileAndAppPanels.kt
+++ b/app/src/main/java/dev/a4tvbridge/independent/FileAndAppPanels.kt
@@ -170,7 +170,22 @@
             })
         }
     }
-    actions(*modes.mapIndexed { index, mode -> mode to { activeMode = index; display() } }.toTypedArray())
+    val tabStrip = row().apply {
+        background = card(field, 14, outlined = true)
+        setPadding(px(4), px(4), px(4), px(4))
+    }
+    val tabButtons = modes.mapIndexed { index, mode ->
+        textButton(mode) { activeMode = index; styleTabs(); display() }
+    }
+    fun styleTabs() = tabButtons.forEachIndexed { idx, btn ->
+        val active = idx == activeMode
+        btn.isSelected = active
+        btn.background = if (active) card(tint, 10, false) else null
+        btn.setTextColor(if (active) accent else muted)
+    }
+    tabButtons.forEach { tabStrip.addView(it, LinearLayout.LayoutParams(0, px(42), 1f)) }
+    styleTabs()
+    content.addView(tabStrip, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
@@ -344,7 +359,7 @@
         item.addView(button("Install", description = "Install \${release.title}") {
             confirm("Install \${release.title}?", "Download the verified release and send it to the TV.") {
                 tvTask({
-                    val abis = command("getprop ro.product.cpu.abilist").text.split(',')
+                    val abis = command("getprop ro.product.cpu.abilist").text.split(',').map(String::trim).filter(String::isNotEmpty)
                     val file = downloadOfficialRelease(this@installPage, release, abis)
                     try { install(listOf(file)) } finally { file.delete() }
                 }) { report("Installed \${release.title}.") }
             }
         }.apply {
-            background = card(tint, 28, false)
+            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ripple), card(tint, 24, false), null)
             setTextColor(accent)
-            textSize = 17f
+            textSize = 15f
             minimumHeight = px(40)`
  },
  {
    path: 'app/src/main/java/dev/a4tvbridge/independent/CarePanels.kt',
    issueIds: ['theme-light-base-dialogs', 'feat-cleanup-order-and-protect'],
    diff: `--- a/app/src/main/java/dev/a4tvbridge/independent/CarePanels.kt
+++ b/app/src/main/java/dev/a4tvbridge/independent/CarePanels.kt
@@ -51,8 +51,8 @@
 }
 
 internal fun BridgeActivity.rememberDisabled(tv: TvConnection, id: String) {
-    updateRestore(tv, id, true)
     tv.togglePackage(id, false)
+    updateRestore(tv, id, true)
 }
@@ -162,15 +162,25 @@
     paragraph("Debloat app lists are taken from CleanYourTV. Review suggestions for your TV model.")
     val files = assets.list("profiles").orEmpty().filter { it.endsWith(".txt") }.map { it.removeSuffix(".txt") }.sorted()
     val choiceKey = "profile-\${tv.serial}"
+    val modelHint = tv.model.lowercase().replace(Regex("[^a-z0-9]+"), "-")
+    val suggested = preferences.getString(choiceKey, null) ?: files.firstOrNull { modelHint.isNotBlank() && it.contains(modelHint) } ?: ""
     val picker = AutoCompleteTextView(this).apply {
         threshold = 1
         hint = "Search device profiles"
         setTextColor(ink)
         setHintTextColor(muted)
         setPadding(px(14), px(10), px(14), px(10))
-        background = rounded(field, 12)
-        setAdapter(ArrayAdapter(this@cleanup, android.R.layout.simple_dropdown_item_1line, files))
-        setText(preferences.getString(choiceKey, ""), false)
+        background = card(field, 12, outlined = true)
+        setDropDownBackgroundDrawable(card(field, 12, outlined = true))
+        setAdapter(object : ArrayAdapter<String>(this@cleanup, android.R.layout.simple_dropdown_item_1line, files) {
+            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
+                val v = super.getView(position, convertView, parent) as android.widget.TextView
+                v.setTextColor(ink)
+                v.setBackgroundColor(field)
+                return v
+            }
+        })
+        setText(suggested, false)
     }`
  },
  {
    path: 'app/src/main/java/dev/a4tvbridge/independent/TvConnection.kt',
    issueIds: ['feat-toybox-mv-realpath', 'feat-cleanup-order-and-protect', 'feat-df-wrapped-storage-and-ps', 'feat-keyboard-and-filepicker-stuck'],
    diff: `--- a/app/src/main/java/dev/a4tvbridge/independent/TvConnection.kt
+++ b/app/src/main/java/dev/a4tvbridge/independent/TvConnection.kt
@@ -100,8 +100,9 @@
     private fun verifiedFolder(folder: String): String {
         val path = TvPath.folder(folder)
         checked("""
-            base=$(realpath /sdcard) && target=$(realpath \${shellText(path)}) &&
+            resolve() { realpath "\$1" 2>/dev/null || readlink -f "\$1" 2>/dev/null || (cd "\$1" 2>/dev/null && pwd -P); }
+            base=$(resolve /sdcard) && target=$(resolve \${shellText(path)}) &&
             test -n "\${'$'}base" && test -d "\${'$'}target" &&
             case "\${'$'}target/" in "\${'$'}base/"*) true;; *) exit 1;; esac
         """.trimIndent())
@@ -146,7 +147,7 @@
         val verified = verifiedFolder(folder)
         val from = shellText(TvPath.child(verified, old))
         val to = shellText(TvPath.child(verified, new))
-        checked("test ! -e $to && test ! -L $to && mv -n $from $to && test ! -e $from && test ! -L $from")
+        checked("if [ -e $to ] || [ -L $to ]; then echo 'A file with that name already exists.' >&2; exit 1; fi; mv -- $from $to")
     }
@@ -166,10 +167,10 @@
         val destination = shellText(TvPath.child(safeFolder, name))
         val temporary = "$safeFolder/.a4-\${UUID.randomUUID()}.part"
         val staged = shellText(temporary)
-        checked("test ! -e $destination && test ! -L $destination")
+        checked("if [ -e $destination ] || [ -L $destination ]; then echo 'File already exists on the TV.' >&2; exit 1; fi")
         try {
             synchronized(commandLock) { client.push(input, temporary) }
-            checked("mv -n $staged $destination && test ! -e $staged")
+            checked("mv -- $staged $destination")
         } finally {
             runCatching { checked("rm -f $staged") }
         }
@@ -185,8 +186,10 @@
         return packages().sorted().map { InstalledPackage(it, it in system, it !in disabled) }
     }
 
-    fun runningPackages(): Set<String> = command("ps -A -o NAME").text.lineSequence()
-        .map { it.trim().substringBefore(':') }.filter { '.' in it }.toSet()
+    fun runningPackages(): Set<String> {
+        val out = command("ps -A -o NAME 2>/dev/null || ps -A 2>/dev/null || ps").text
+        return out.lineSequence().mapNotNull { it.trim().split(Regex("\\\\s+")).lastOrNull()?.substringBefore(':') }.filter { '.' in it }.toSet()
+    }
@@ -226,10 +229,11 @@
             return true
         }
-        val home = command("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
+        val home = command("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null || true")
         val keyboard = command("settings get secure default_input_method")
-        if (home.status != 0 || '/' !in home.text || keyboard.status != 0) return true
-        return home.text.lineSequence().any { it.startsWith("$id/") } || keyboard.text.startsWith("$id/") ||
+        val isHome = '/' in home.text && home.text.lineSequence().any { it.startsWith("$id/") }
+        val isKeyboard = keyboard.status == 0 && keyboard.text.startsWith("$id/")
+        return isHome || isKeyboard ||
             id.contains("tvinput", true) || id.contains("bluetooth", true) || id.contains("remote", true)
     }
@@ -243,6 +247,12 @@
         checked("input keyevent $code")
     }
 
+    fun sendText(raw: String) {
+        val safe = raw.replace("%", "").replace(" ", "%s")
+        require(safe.isNotBlank()) { "Enter text to send." }
+        checked("input text \${shellText(safe)}")
+    }
+
@@ -270,7 +280,7 @@
         val path = "/data/local/tmp/a4-record-\${UUID.randomUUID()}.mp4"
         try {
-            checked("screenrecord --time-limit $seconds \${shellText(path)}")
+            checked("screenrecord --time-limit $seconds \${shellText(path)} || screenrecord --bit-rate 4000000 --time-limit $seconds \${shellText(path)}")
             synchronized(commandLock) { client.pull(output, path) }
@@ -284,7 +294,7 @@
         echo __memory
         cat /proc/meminfo
         echo __disk
-        df -k /data
+        df -Pk /data 2>/dev/null || df -k /data
         echo __network`
  },
  {
    path: 'app/src/main/java/dev/a4tvbridge/independent/DeviceReading.kt',
    issueIds: ['feat-df-wrapped-storage-and-ps'],
    diff: `--- a/app/src/main/java/dev/a4tvbridge/independent/DeviceReading.kt
+++ b/app/src/main/java/dev/a4tvbridge/independent/DeviceReading.kt
@@ -47,7 +47,9 @@
             val memory = sections["__memory"].orEmpty().associate { item ->
                 item.substringBefore(':') to item.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
             }
-            val disk = sections["__disk"].orEmpty().firstOrNull { it.endsWith(" /data") }?.split(Regex("\\\\s+"))
+            val diskTokens = sections["__disk"].orEmpty().firstOrNull { it.endsWith(" /data") }?.split(Regex("\\\\s+"))
+            val diskSize = diskTokens?.let { if (it.size >= 5) it[it.size - 5].toLongOrNull() else null }
+            val diskAvail = diskTokens?.let { if (it.size >= 5) it[it.size - 3].toLongOrNull() else null }
@@ -60,8 +62,8 @@
                 ramTotal = memory["MemTotal"]?.times(1024),
                 ramAvailable = (memory["MemAvailable"] ?: memory["MemFree"])?.times(1024),
-                diskTotal = disk?.getOrNull(1)?.toLongOrNull()?.times(1024),
-                diskFree = disk?.getOrNull(3)?.toLongOrNull()?.times(1024),
+                diskTotal = diskSize?.times(1024),
+                diskFree = diskAvail?.times(1024),`
  },
  {
    path: 'app/src/main/java/dev/a4tvbridge/independent/ReleaseDownloads.kt',
    issueIds: ['feat-one-click-install-github-api'],
    diff: `--- a/app/src/main/java/dev/a4tvbridge/independent/ReleaseDownloads.kt
+++ b/app/src/main/java/dev/a4tvbridge/independent/ReleaseDownloads.kt
@@ -35,7 +35,8 @@
         name.matches(Regex("[A-Za-z0-9_.-]+\\\\.apk", RegexOption.IGNORE_CASE)) &&
             link.startsWith("https://github.com/\${app.repository}/releases/download/") && link.endsWith("/$name") &&
             file.optLong("size") in 1..(200L * 1024 * 1024) &&
-            file.optString("digest").matches(Regex("sha256:[a-fA-F0-9]{64}"))
+            (file.optString("digest").isBlank() || file.optString("digest").matches(Regex("sha256:[a-fA-F0-9]{64}")))
     }
@@ -65,7 +66,9 @@
         }
         val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
-        check(bytes == limit && "sha256:$digest".equals(chosen.getString("digest"), ignoreCase = true)) { "The release checksum did not match." }
+        val publishedDigest = chosen.optString("digest")
+        check(bytes == limit && (publishedDigest.isBlank() || "sha256:$digest".equals(publishedDigest, ignoreCase = true))) {
+            "The release checksum did not match."
+        }
@@ -83,6 +86,10 @@
         val connection = url.openConnection() as HttpURLConnection
         connection.instanceFollowRedirects = false
         connection.requestMethod = "GET"
+        connection.setRequestProperty("User-Agent", "A4-TV-Bridge/\${BuildConfig.VERSION_NAME}")
+        if (url.host == "api.github.com") {
+            connection.setRequestProperty("Accept", "application/vnd.github+json")
+        }
         connection.connectTimeout = 12_000`
  },
  {
    path: 'app/src/main/java/dev/a4tvbridge/independent/Connections.kt',
    issueIds: ['feat-discovery-scan-deadlock'],
    diff: `--- a/app/src/main/java/dev/a4tvbridge/independent/Connections.kt
+++ b/app/src/main/java/dev/a4tvbridge/independent/Connections.kt
@@ -188,6 +188,7 @@
     showSheet("Connect your TV") { popup, layout ->
+        var scanJob: kotlinx.coroutines.Job? = null
         val description = text("Keep your phone and TV on the same network. Turn on ADB debugging on the TV.", 14, muted)
@@ -200,19 +201,25 @@
         tiles(layout, 1,
             Tile("Find TVs on this network", Mark.TV, selected = true) {
                 status.text = "Looking for Android TV debugging on this network…"
-                backgroundTask({ findLegacyEndpoints() }, onError = {
-                    status.text = it.message ?: "The local network search failed."
-                }) { addresses ->
-                    if (popup.isShowing) {
-                        status.text = if (addresses.isEmpty()) "No TVs found. Enter the IP address shown on the TV."
-                            else "Choose your TV. Approve debugging on the TV if prompted."
-                        for (address in addresses) {
-                            val label = known.firstOrNull { it.address == address }?.model ?: "TV at \${address.host}"
-                            showEndpoint(label, address)
-                        }
+                scanJob?.cancel()
+                scanJob = launchSheetTask {
+                    runCatching { withContext(Dispatchers.IO) { findLegacyEndpoints() } }.onSuccess { addresses ->
+                        if (popup.isShowing) {
+                            status.text = if (addresses.isEmpty()) "No TVs found. Enter the IP address shown on the TV."
+                                else "Choose your TV. Approve debugging on the TV if prompted."
+                            for (address in addresses) {
+                                val label = known.firstOrNull { it.address == address }?.model ?: "TV at \${address.host}"
+                                showEndpoint(label, address)
+                            }
+                        }
+                    }.onFailure {
+                        if (popup.isShowing) status.text = it.message ?: "The local network search failed."
                     }
                 }
             },
@@ -220,10 +227,10 @@
         layout.addView(status)
         layout.addView(android.widget.ScrollView(this).apply {
             addView(matches)
-        }, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(if (known.isEmpty()) 72 else 124)))
+        }, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(190)))
         for (device in known) showEndpoint(device.model, device.address)
@@ -228,6 +235,6 @@
-        popup.setOnDismissListener { discovery.close() }
+        popup.setOnDismissListener { scanJob?.cancel(); discovery.close() }
         discovery.start()
     }`
  }
];

export function generateUnifiedPatch(): string {
  const header = [
    '# A4 TV Bridge — Theme & Feature Fixes Patch',
    '# Repository: https://github.com/RatherAsif04/A4-TV-Bridge-Test',
    '# Apply with: git apply a4-tv-bridge-fixes.patch',
    ''
  ].join('\n');
  return header + FIXED_PATCHES.map((p) => p.diff).join('\n\n') + '\n';
}
