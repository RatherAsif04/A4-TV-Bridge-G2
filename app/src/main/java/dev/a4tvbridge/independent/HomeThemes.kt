package dev.a4tvbridge.independent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
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
    MIDNIGHT(
        "midnight",
        "Default Midnight",
        "Deep slate backdrop with calm ocean accent glow",
        0xFF091224.toInt(),
        0xFF0E2940.toInt(),
        0xFF00587B.toInt(),
        0.86
    ),
    AURORA(
        "aurora",
        "Aurora Borealis",
        "Emerald and teal polar ribbon over true night sky",
        0xFF06131C.toInt(),
        0xFF0C3B44.toInt(),
        0xFF2DD4BF.toInt(),
        0.80
    ),
    GLASS(
        "glass",
        "Frosted Glass",
        "Cool slate gradient tuned for translucent launcher tiles",
        0xFF101928.toInt(),
        0xFF1E3A5F.toInt(),
        0xFFB0DEFF.toInt(),
        0.65
    ),
    DUSK_PEAKS(
        "dusk-peaks",
        "Dusk Peaks",
        "Layered mountain silhouettes under twilight amber sky",
        0xFF141B34.toInt(),
        0xFF4C2A4F.toInt(),
        0xFFF59E0B.toInt(),
        0.82
    )
}

internal fun renderThemeWallpaper(preset: HomeThemePreset, target: File, width: Int = 1920, height: Int = 1080) {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width * 0.4f, height.toFloat(),
                preset.topColor, preset.bottomColor, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Ambient radial highlight
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val r = Color.red(preset.accentColor)
            val g = Color.green(preset.accentColor)
            val b = Color.blue(preset.accentColor)
            shader = RadialGradient(
                width * 0.72f, height * 0.28f, width * 0.55f,
                intArrayOf(Color.argb(95, r, g, b), Color.argb(0, r, g, b)),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)

        if (preset == HomeThemePreset.DUSK_PEAKS) {
            val ridge = Paint(Paint.ANTI_ALIAS_FLAG)
            ridge.color = 0x550B1021
            canvas.drawPath(Path().apply {
                moveTo(0f, height * 0.78f)
                lineTo(width * 0.22f, height * 0.58f)
                lineTo(width * 0.44f, height * 0.72f)
                lineTo(width * 0.71f, height * 0.52f)
                lineTo(width.toFloat(), height * 0.70f)
                lineTo(width.toFloat(), height.toFloat())
                lineTo(0f, height.toFloat())
                close()
            }, ridge)
            ridge.color = 0x99060913.toInt()
            canvas.drawPath(Path().apply {
                moveTo(0f, height * 0.88f)
                lineTo(width * 0.30f, height * 0.70f)
                lineTo(width * 0.58f, height * 0.83f)
                lineTo(width * 0.84f, height * 0.66f)
                lineTo(width.toFloat(), height * 0.79f)
                lineTo(width.toFloat(), height.toFloat())
                lineTo(0f, height.toFloat())
                close()
            }, ridge)
        } else if (preset == HomeThemePreset.AURORA) {
            val ribbon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, height * 0.2f, width.toFloat(), height * 0.65f,
                    intArrayOf(0x002DD4BF, 0x662DD4BF, 0x558B5CF6, 0x008B5CF6),
                    null, Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(Path().apply {
                moveTo(0f, height * 0.48f)
                quadTo(width * 0.33f, height * 0.14f, width * 0.66f, height * 0.42f)
                quadTo(width * 0.85f, height * 0.56f, width.toFloat(), height * 0.25f)
                lineTo(width.toFloat(), height * 0.55f)
                quadTo(width * 0.78f, height * 0.78f, width * 0.46f, height * 0.50f)
                quadTo(width * 0.22f, height * 0.32f, 0f, height * 0.68f)
                close()
            }, ribbon)
        }

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

internal fun BridgeActivity.homeScreenPage() {
    val owner = content
    heading("Home screen")
    paragraph("Switch between installed TV launchers, install your own launcher APK, or push clean-room 1080p theme wallpapers to your TV.")

    val launchersCard = outlinedCard()
    launchersCard.addView(text("Installed TV launchers", 16, bold = true))
    val launcherList = column().apply { setPadding(0, px(8), 0, 0) }
    launcherList.addView(text("Reading installed home apps…", 14, muted))
    launchersCard.addView(launcherList)
    content.addView(launchersCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

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
                        report("Launcher installed. Choose it from Installed TV launchers or Home Settings.")
                        refresh()
                    }
                }
            }
        }
    )

    heading("TV Theme Wallpapers", 19)
    paragraph("Generates an original 1920×1080 wallpaper PNG and theme config locally, then saves it to Pictures › A4TVBridge on your TV.")
    tiles(
        content, 1,
        *HomeThemePreset.entries.map { preset ->
            Tile(preset.title, Mark.MIRROR, preset.subtitle) {
                confirm(
                    "Push ${preset.title} theme?",
                    "Creates a 1080p ${preset.title} wallpaper and config in /sdcard/Pictures/A4TVBridge on your TV."
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
        }.toTypedArray()
    )

    tvTask({ homeOptions() to activeHomeActivity() }) { (options, active) ->
        if (content !== owner) return@tvTask
        launcherList.removeAllViews()
        if (options.isEmpty()) {
            launcherList.addView(text("No HOME activities were reported by this TV.", 14, muted))
            return@tvTask
        }
        for (component in options) {
            val pkg = component.substringBefore('/')
            val isDefault = active != null && (component == active || pkg == active.substringBefore('/'))
            val detail = if (isDefault) "$pkg · Active default" else pkg
            tiles(launcherList, 1, Tile(displayName(pkg), Mark.HOME, detail, selected = isDefault) {
                if (isDefault) {
                    report("${displayName(pkg)} is already the active TV launcher.")
                } else {
                    confirm("Set ${displayName(pkg)} as default Home?", "Sets $component as the TV’s Home screen.") {
                        tvTask({ setHomeActivity(component) }) { applied ->
                            if (applied) {
                                report("Default Home switched to ${displayName(pkg)}.")
                                refresh()
                            } else {
                                report("Opened Home Settings on TV to confirm your launcher.")
                            }
                        }
                    }
                }
            })
        }
    }
}
