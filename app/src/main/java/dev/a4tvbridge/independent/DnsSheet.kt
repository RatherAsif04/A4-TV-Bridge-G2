package dev.a4tvbridge.independent

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DnsProviderPreset(
    val name: String,
    val badge: String,
    val badgeColor: Int,
    val hostname: String,
    val summary: String,
    val requiresCustomInput: Boolean = false
)

internal val DNS_PROVIDER_PRESETS = listOf(
    DnsProviderPreset(
        name = "AdGuard DNS",
        badge = "AG",
        badgeColor = 0xFF059669.toInt(),
        hostname = "dns.adguard-dns.com",
        summary = "Blocks ads & trackers system-wide"
    ),
    DnsProviderPreset(
        name = "Mullvad Adblock",
        badge = "MV",
        badgeColor = 0xFF6D28D9.toInt(),
        hostname = "adblock.dns.mullvad.net",
        summary = "Privacy-first ad & tracker filter"
    ),
    DnsProviderPreset(
        name = "Control D",
        badge = "CD",
        badgeColor = 0xFF0284C7.toInt(),
        hostname = "p2.freedns.controld.com",
        summary = "Free ads & tracking DNS filter"
    ),
    DnsProviderPreset(
        name = "NextDNS",
        badge = "ND",
        badgeColor = 0xFFD97706.toInt(),
        hostname = "dns.nextdns.io",
        summary = "Enter your personal NextDNS endpoint",
        requiresCustomInput = true
    )
)

internal fun BridgeActivity.showDnsOptions() {
    val tv = connection ?: return
    val key = "dns-before-${tv.serial}"

    fun applyHostname(raw: String) {
        val hostname = raw.trim().lowercase()
        val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        if (hostname.length !in 4..253 || '.' !in hostname || !hostname.split('.').all(label::matches)) {
            return report("Enter a valid DNS provider hostname.")
        }
        confirm(
            "Change Private DNS?",
            "Set Private DNS to $hostname for the entire TV. You can restore the previous settings anytime if network access is interrupted."
        ) {
            tvTask({
                if (!preferences.contains(key)) {
                    val backup = org.json.JSONArray(readDns()).toString()
                    check(preferences.edit().putString(key, backup).commit()) { "Could not save the existing DNS settings." }
                }
                writeDns(listOf("hostname", hostname))
            }) { report("Private DNS updated to $hostname.") }
        }
    }

    showSheet("Block Ads · Private DNS") { popup, frame ->
        val body = column()

        // 1. Live Current TV Private DNS Status Pill Card
        val statusDot = View(this).apply { background = rounded(accent, 8) }
        val statusTitle = text("Reading current TV DNS…", 13, ink, bold = true)
        val statusHost = text("Private DNS over TLS", 11, muted).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, px(2), 0, 0)
        }
        val statusCard = row().apply {
            background = card(tint, 16, outlined = false).apply {
                setStroke(px(1), if (dark) 0xFF0284C7.toInt() else edge)
            }
            setPadding(px(14), px(11), px(14), px(11))
            addView(statusDot, LinearLayout.LayoutParams(px(8), px(8)).apply { marginEnd = px(10) })
            val copy = column().apply {
                addView(statusTitle)
                addView(statusHost)
            }
            addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        }
        body.addView(statusCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
            bottomMargin = px(14)
        })

        // 2. Provider Cards Section
        body.addView(sectionLabel("One-tap ad-blocking DNS"),
            LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })

        val providerRows = mutableMapOf<String, Pair<LinearLayout, android.widget.TextView>>()
        for (preset in DNS_PROVIDER_PRESETS) {
            val pill = text(if (preset.requiresCustomInput) "Set" else "Apply", 12, accent, bold = true).apply {
                background = card(tint, 12, outlined = false)
                setPadding(px(13), px(7), px(13), px(7))
            }
            val itemCard = row().apply {
                background = RippleDrawable(
                    ColorStateList.valueOf(ripple),
                    card(if (dark) 0xFF131D2B.toInt() else surface, 16, outlined = true),
                    null
                )
                setPadding(px(12), px(11), px(12), px(11))
                isClickable = true
                isFocusable = true
                contentDescription = "${preset.name} (${preset.hostname})"
                val badge = text(preset.badge, 12, Color.WHITE, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = card(preset.badgeColor, 10, outlined = false)
                    letterSpacing = 0.04f
                }
                addView(badge, LinearLayout.LayoutParams(px(36), px(36)).apply { marginEnd = px(12) })
                val copy = column().apply {
                    addView(text(preset.name, 15, bold = true))
                    addView(text(preset.hostname, 11, accent).apply {
                        typeface = Typeface.MONOSPACE
                        setPadding(0, px(2), 0, 0)
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(text(preset.summary, 11, muted).apply {
                        setPadding(0, px(2), 0, 0)
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
                addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(8) })
                addView(pill)
                setOnClickListener {
                    popup.dismiss()
                    if (preset.requiresCustomInput) {
                        ask("NextDNS Endpoint", "your-id.dns.nextdns.io", ::applyHostname)
                    } else {
                        applyHostname(preset.hostname)
                    }
                }
            }
            providerRows[preset.hostname] = itemCard to pill
            body.addView(itemCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
                bottomMargin = px(8)
            })
        }

        // 3. Other DNS Actions (grouped A4 hairline card)
        body.addView(sectionLabel("Custom & Reset"),
            LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(6); bottomMargin = px(8) })

        val previous = preferences.getString(key, null)
        val extraCard = outlinedCard(if (dark) 0xFF0A0F16.toInt() else surface, radius = 16, stroke = 1).apply {
            setPadding(0, px(4), 0, px(4))
        }
        val extraActions = mutableListOf<Triple<String, Int, () -> Unit>>(
            Triple("Custom Private DNS hostname", ink) {
                popup.dismiss()
                ask("Private DNS Hostname", "dns.adguard-dns.com", ::applyHostname)
            }
        )
        if (previous != null) {
            extraActions += Triple("Restore previous DNS settings", if (dark) 0xFF6EE7B7.toInt() else green) {
                popup.dismiss()
                confirm("Restore prior DNS settings?", "The TV will return to the DNS settings saved before this change.") {
                    val stored = org.json.JSONArray(previous)
                    tvTask({
                        writeDns(listOf(stored.getString(0), stored.getString(1)))
                        preferences.edit().remove(key).apply()
                    }) { report("Private DNS restored.") }
                }
            }
        }
        extraActions += Triple("Turn off Private DNS", if (dark) 0xFFFB7185.toInt() else 0xFFE11D48.toInt()) {
            popup.dismiss()
            confirm("Turn off Private DNS?", "This disables Private DNS on the TV and returns to standard network DNS.") {
                tvTask({ writeDns(listOf("off", "")) }) { report("Private DNS turned off.") }
            }
        }

        extraActions.forEachIndexed { idx, (label, textColor, action) ->
            val rowItem = row().apply {
                background = RippleDrawable(ColorStateList.valueOf(ripple), null, card(radius = 10))
                minimumHeight = px(48)
                setPadding(px(14), px(10), px(14), px(10))
                isClickable = true
                isFocusable = true
                contentDescription = label
                addView(text(label, 14, textColor, bold = true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
                addView(text("›", 19, if (textColor == ink) dim else textColor, bold = true))
                setOnClickListener { action() }
            }
            extraCard.addView(rowItem, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
            if (idx < extraActions.lastIndex) {
                extraCard.addView(rule(), LinearLayout.LayoutParams(BridgeActivity.MATCH, px(1)).apply {
                    marginStart = px(14)
                    marginEnd = px(14)
                })
            }
        }
        body.addView(extraCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))

        attachSheetBody(frame, body)

        // Load active Private DNS from TV asynchronously
        launchSheetTask {
            val dns = runCatching { withContext(Dispatchers.IO) { tv.readDns() } }.getOrDefault(listOf("", ""))
            if (!popup.isShowing) return@launchSheetTask
            val mode = dns.getOrElse(0) { "" }.trim().lowercase()
            val activeHost = dns.getOrElse(1) { "" }.trim().lowercase()
            when {
                mode == "hostname" && activeHost.isNotBlank() -> {
                    statusDot.background = rounded(greenStroke, 8)
                    statusCard.background = card(greenBg, 16, outlined = false).apply { setStroke(px(1), greenStroke) }
                    statusTitle.text = "Private DNS Active"
                    statusHost.text = activeHost
                    providerRows[activeHost]?.let { (rowView, pillView) ->
                        rowView.background = card(tint, 16, outlined = false).apply { setStroke(px(1), accent) }
                        pillView.text = "Active"
                        pillView.setTextColor(Color.WHITE)
                        pillView.background = rounded(accentPill, 12)
                    }
                }
                mode == "off" -> {
                    statusDot.background = rounded(dim, 8)
                    statusTitle.text = "Private DNS is Off"
                    statusHost.text = "Using standard router / ISP DNS"
                }
                else -> {
                    statusDot.background = rounded(accent, 8)
                    statusTitle.text = "Private DNS: Automatic"
                    statusHost.text = "Tap a provider below to block TV ads"
                }
            }
        }
    }
}
