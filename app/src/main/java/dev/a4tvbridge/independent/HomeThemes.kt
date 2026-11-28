package dev.a4tvbridge.independent

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.io.File

internal enum class HomeThemePreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val topColor: Int,
    val bottomColor: Int,
    val accentColor: Int,
    val cardOpacity: Double
) {
    NORDIC_SLATE(
        "nordic-slate",
        "Nordic Slate",
        "Cool cyan · Minimal",
        0xFF0F172A.toInt(),
        0xFF0A2239.toInt(),
        0xFF0284C7.toInt(),
        0.84
    ),
    OBSIDIAN_GOLD(
        "obsidian-gold",
        "Obsidian Gold",
        "True black · Amber",
        0xFF111111.toInt(),
        0xFF231808.toInt(),
        0xFFF59E0B.toInt(),
        0.86
    ),
    DEEP_OCEAN(
        "deep-ocean",
        "Deep Ocean",
        "Teal horizon · Crisp",
        0xFF042F2E.toInt(),
        0xFF0A4A48.toInt(),
        0xFF14B8A6.toInt(),
        0.80
    ),
    EMERALD_DUSK(
        "emerald-dusk",
        "Emerald Dusk",
        "Forest · Soft sage",
        0xFF064E3B.toInt(),
        0xFF082F25.toInt(),
        0xFF34D399.toInt(),
        0.82
    )
}

internal fun renderThemeWallpaper(preset: HomeThemePreset, target: File, width: Int = 1920, height: Int = 1080) {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width * 0.45f, height.toFloat(),
                preset.topColor, preset.bottomColor, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Ambient radial highlight matching clean-room theme studio
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val r = Color.red(preset.accentColor)
            val g = Color.green(preset.accentColor)
            val b = Color.blue(preset.accentColor)
            shader = RadialGradient(
                width * 0.78f, height * 0.28f, width * 0.48f,
                intArrayOf(Color.argb(140, r, g, b), Color.argb(0, r, g, b)),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)

        target.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
        }
    } finally {
        bitmap.recycle()
    }
}

internal fun themeConfigJson(preset: HomeThemePreset): String {
    val accentHex = String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and preset.accentColor)
    val opacity = String.format(java.util.Locale.US, "%.2f", preset.cardOpacity)
    return """
        {
          "id": "${preset.id}",
          "title": "${preset.title}",
          "wallpaper": "a4-theme-${preset.id}.png",
          "accentHex": "$accentHex",
          "cardOpacity": $opacity,
          "author": "RatherAsif04 / A4 TV Bridge"
        }
    """.trimIndent()
}

private class ThemePreviewCanvas(
    private val activity: BridgeActivity,
    private val preset: HomeThemePreset
) : View(activity) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = activity.px(11).toFloat()
        rect.set(0f, 0f, w, h)

        // Base dark backdrop
        paint.color = preset.topColor
        canvas.drawRoundRect(rect, r, r, paint)

        // Accent geometric orb top-right (matches 06-home-themes-light.png)
        paint.color = preset.accentColor
        canvas.drawCircle(w * 0.76f, h * 0.34f, h * 0.24f, paint)

        // 3 mini translucent TV app tiles along bottom
        val tileW = w * 0.22f
        val tileH = h * 0.21f
        val tileY = h * 0.68f
        val tileR = activity.px(5).toFloat()
        val gap = w * 0.045f
        val startX = w * 0.08f
        for (i in 0..2) {
            paint.color = if (i == 0) 0x55FFFFFF else 0x33FFFFFF
            val left = startX + i * (tileW + gap)
            rect.set(left, tileY, left + tileW, tileY + tileH)
            canvas.drawRoundRect(rect, tileR, tileR, paint)
        }
    }
}

internal fun BridgeActivity.homeScreenPage() {
    val owner = content
    var selectedPreset = HomeThemePreset.NORDIC_SLATE

    // Title & subtitle matching 06-home-themes-light.png
    pageHeader("Home & Themes", "Switch default TV launcher & push 1080p wallpapers")

    // 1. DEFAULT HOME LAUNCHER Card
    val launchersCard = outlinedCard(field, radius = 20, stroke = 1)
    launchersCard.addView(text("DEFAULT HOME LAUNCHER", 11, dim, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(0, 0, 0, px(10))
    })
    val launcherList = column()
    launcherList.addView(text("Reading installed home apps…", 13, muted))
    launchersCard.addView(launcherList)
    content.addView(launchersCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

    // 1b. RECOMMENDED TV LAUNCHER (Projectivy Launcher latest official GitHub release)
    var projectivyInstalled = false
    val projectivySubtitle = text("Latest official GitHub release · ${ReleaseApp.PROJECTIVY.repository}", 11, muted).apply {
        setPadding(0, px(2), 0, 0)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val projectivyBtn = button("Install", description = "Install Projectivy Launcher") {
        val verb = if (projectivyInstalled) "Update" else "Install"
        installOfficialRelease(ReleaseApp.PROJECTIVY, actionVerb = verb) {
            report(
                if (projectivyInstalled) "Projectivy Launcher updated to the latest release."
                else "Projectivy Launcher installed. Tap Set under Default Home Launcher to activate it."
            )
            refresh()
        }
    }.apply {
        background = interactiveBackground(card(tint, 24, false), radius = 24)
        setTextColor(accent)
        textSize = 14f
        minimumHeight = px(48)
        minHeight = px(48)
    }
    val projectivyCard = outlinedCard(field, radius = 20, stroke = 1).apply {
        addView(text("RECOMMENDED TV LAUNCHER", 11, dim, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, px(10))
        })
        val projectivyRow = row().apply {
            addView(appBadge(ReleaseApp.PROJECTIVY.packageName), LinearLayout.LayoutParams(px(36), px(36)))
            val copy = column().apply {
                addView(text(ReleaseApp.PROJECTIVY.title, 15, bold = true))
                addView(projectivySubtitle)
            }
            addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply {
                marginStart = px(12)
                marginEnd = px(10)
            })
            addView(projectivyBtn, LinearLayout.LayoutParams(px(96), px(48)))
        }
        addView(projectivyRow)
    }
    content.addView(projectivyCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(16) })

    // 2. CLEAN-ROOM 1080P THEME STUDIO (2x2 visual grid matching 06-home-themes-light.png)
    content.addView(sectionLabel("Clean-room 1080p theme studio"),
        LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

    val gridHost = column()
    content.addView(gridHost, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

    val pushBtn = button("", emphasis = true) {}
    fun updatePushButton() {
        pushBtn.text = "Push ${selectedPreset.title} (1920×1080) to TV"
    }

    fun renderThemeGrid() {
        gridHost.removeAllViews()
        for (pair in HomeThemePreset.entries.chunked(2)) {
            val line = row()
            for ((idx, preset) in pair.withIndex()) {
                val isSelected = preset == selectedPreset
                val itemCard = column().apply {
                    background = interactiveBackground(
                        card(field, 18, outlined = false).apply {
                            setStroke(px(if (isSelected) 2 else 1), if (isSelected) accent else edge)
                        },
                        radius = 18
                    )
                    setPadding(px(10), px(10), px(10), px(12))
                    addView(ThemePreviewCanvas(this@homeScreenPage, preset),
                        LinearLayout.LayoutParams(BridgeActivity.MATCH, px(96)).apply { bottomMargin = px(10) })
                    val titleRow = row()
                    titleRow.addView(text(preset.title, 14, bold = true).apply {
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
                    if (isSelected) {
                        titleRow.addView(text("Selected", 10, accentBright, bold = true).apply {
                            background = rounded(tint, 7)
                            setPadding(px(7), px(3), px(7), px(3))
                        })
                    }
                    addView(titleRow)
                    addView(text(preset.subtitle, 11, muted).apply {
                        setPadding(0, px(3), 0, 0)
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    isClickable = true
                    isFocusable = true
                    contentDescription = preset.title
                    setOnClickListener {
                        selectedPreset = preset
                        updatePushButton()
                        renderThemeGrid()
                    }
                }
                line.addView(itemCard, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply {
                    if (idx > 0) marginStart = px(10)
                })
            }
            gridHost.addView(line, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
        }
    }

    pushBtn.setOnClickListener {
        val preset = selectedPreset
        confirm(
            "Push ${preset.title} theme?",
            "Creates a 1920×1080 ${preset.title} PNG wallpaper and config in /sdcard/Pictures/A4TVBridge on your TV."
        ) {
            tvTask({
                val dest = transferDestination("Pictures")
                val imageFile = File.createTempFile("a4-theme-", ".png", cacheDir)
                val configFile = File.createTempFile("a4-theme-", ".json", cacheDir)
                try {
                    renderThemeWallpaper(preset, imageFile)
                    configFile.writeText(themeConfigJson(preset))
                    val imageName = "a4-theme-${preset.id}.png"
                    val configName = "a4-theme-${preset.id}.json"
                    uploadOverwrite(dest, imageName, imageFile)
                    uploadOverwrite(dest, configName, configFile)
                    scanMediaFile("$dest/$imageName")
                    "$dest/$imageName"
                } finally {
                    imageFile.delete()
                    configFile.delete()
                }
            }) { path ->
                report("Theme saved to $path. Select it in your TV launcher or wallpaper settings.")
            }
        }
    }
    updatePushButton()
    renderThemeGrid()

    content.addView(pushBtn, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(52)).apply { bottomMargin = px(6) })
    content.addView(text("Generates PNG on-device · Zero cloud downloads", 11, dim).apply {
        gravity = Gravity.CENTER
        setPadding(0, px(2), 0, px(14))
    }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))

    // Secondary Launcher Tools
    actions(
        "Open Home Settings" to {
            tvTask({ homeSettings() }) { report("Home settings opened on the TV.") }
        },
        "Install Launcher APK" to {
            chooseDocuments("*/*", true) { uris ->
                confirm("Install launcher on TV?", "Send ${uris.size} selected package file(s) to the connected TV.") {
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
                    }) {
                        report("Launcher installed. Choose it from Default Home Launcher or Home Settings.")
                        refresh()
                    }
                }
            }
        }
    )

    // Populate installed TV launchers with Active / Set pill buttons
    tvTask({ homeOptions() to activeHomeActivity() }) { (options, active) ->
        if (content !== owner) return@tvTask
        projectivyInstalled = options.any { it.substringBefore('/').equals(ReleaseApp.PROJECTIVY.packageName, ignoreCase = true) }
        projectivyBtn.text = if (projectivyInstalled) "Update" else "Install"
        projectivyBtn.contentDescription = "${projectivyBtn.text} Projectivy Launcher"
        projectivySubtitle.text = if (projectivyInstalled) {
            "Installed · Tap Update for latest release (${ReleaseApp.PROJECTIVY.repository})"
        } else {
            "Latest official GitHub release · ${ReleaseApp.PROJECTIVY.repository}"
        }
        launcherList.removeAllViews()
        if (options.isEmpty()) {
            launcherList.addView(text("No HOME activities were reported by this TV.", 13, muted))
            return@tvTask
        }
        options.forEachIndexed { idx, component ->
            val pkg = component.substringBefore('/')
            val isDefault = active != null && (component == active || pkg == active.substringBefore('/'))
            val rowBg = if (isDefault) {
                card(tint, 14, outlined = false).apply { setStroke(px(1), accent) }
            } else {
                card(surfaceElevated, 14, outlined = true)
            }
            val itemRow = row().apply {
                background = interactiveBackground(rowBg, radius = 14)
                minimumHeight = px(56)
                setPadding(px(14), px(12), px(12), px(12))
                val textCol = column().apply {
                    addView(text(displayName(pkg), 15, bold = true))
                    addView(text(pkg, 11, muted).apply {
                        typeface = monoTypeface
                        setPadding(0, px(2), 0, 0)
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
                addView(textCol, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(10) })
                val pill = text(if (isDefault) "Active" else "Set", 12, if (isDefault) Color.WHITE else ink, bold = true).apply {
                    background = if (isDefault) rounded(accentPill, 10) else card(field, 10, outlined = true)
                    setPadding(px(14), px(6), px(14), px(6))
                }
                addView(pill)
                isClickable = true
                isFocusable = true
                contentDescription = "${displayName(pkg)}, ${if (isDefault) "Active home launcher" else "Set as default home launcher"}"
                setOnClickListener {
                    if (isDefault) {
                        report("${displayName(pkg)} is already the active TV launcher.")
                    } else {
                        confirm("Set ${displayName(pkg)} as default Home?", "Sets $component as the TV’s Home screen.") {
                            tvTask({ setHomeActivity(component) }) { applied ->
                                connection?.let { tv ->
                                    preferences.edit().putString("preferred-home-${tv.serial}", component).apply()
                                }
                                if (applied) {
                                    report("Default Home switched to ${displayName(pkg)}.", NoticeTone.SUCCESS)
                                    refresh()
                                } else {
                                    report("Opened Home Settings on TV to confirm your launcher.")
                                }
                            }
                        }
                    }
                }
            }
            launcherList.addView(itemRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
                if (idx < options.lastIndex) bottomMargin = px(8)
            })
        }
    }
}
