package dev.a4tvbridge.independent

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class BridgeActivityTest {
    @Test fun launchesAndOffersFiveDestinationsAndControlPopup() {
        val controller = Robolectric.buildActivity(BridgeActivity::class.java).setup()
        controller.use {
            val app = controller.get()
            val welcome = flatten(app.window.decorView)
            assertTrue(welcome.any { it.contentDescription == "Connect your TV" })
            val controls = flatten(app.dock(Panel.DASHBOARD, app::controls)).toList()
            for (expected in listOf("Dashboard", "Apps & Files", "Control", "ADB Shell / Terminal", "TV Care")) {
                assertTrue(controls.any { it.contentDescription == expected })
            }
            controls.first { it.contentDescription == "Control" }.performClick()
            val popup = ShadowDialog.getLatestDialog()
            assertNotNull(popup)
            val options = flatten(popup.window!!.decorView).map { it.contentDescription }.filterNotNull().toList()
            for (label in listOf("Remote", "Screen Mirror", "Screenshot", "Screen Record")) {
                assertTrue("$label should be in the Control sheet", label in options)
            }
        }
    }

    @Test fun opensAboutAndDisplaysIndependentLicense() {
        val controller = Robolectric.buildActivity(BridgeActivity::class.java).setup()
        controller.use {
            val app = controller.get()
            flatten(app.window.decorView).first { it.contentDescription == "About A4 TV Bridge" }.performClick()
            val about = ShadowDialog.getLatestDialog()
            val aboutTexts = flatten(about.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertTrue("About A4 TV Bridge" in aboutTexts)
            assertTrue("v${BuildConfig.VERSION_NAME}" in aboutTexts)
            assertTrue(aboutTexts.none { it.contains("Build ", ignoreCase = true) })
            flatten(about.window!!.decorView).first { it.contentDescription == "Open-source notices" }.performClick()
            val notices = ShadowDialog.getLatestDialog()
            assertTrue(flatten(notices.window!!.decorView).any { it.contentDescription == "App source license" })
            assertTrue(app.assets.open("licenses/a4-tv-bridge.txt").bufferedReader().use {
                it.readText().contains("A4 TV Bridge contributors")
            })
        }
    }

    @Test fun restoresTheOldDarkSelectionAndShowsTheOriginalOptions() {
        val controller = Robolectric.buildActivity(BridgeActivity::class.java)
        controller.use {
            val app = controller.get()
            app.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
                .edit().putString("theme", "DARK").commit()
            controller.setup()
            assertEquals(0xFF05070A.toInt(), app.surface)
            assertEquals(0xFF38BDF8.toInt(), app.accent)
            flatten(app.window.decorView).first { it.contentDescription == "Appearance" }.performClick()
            val options = flatten(app.window.decorView).toList()
            assertTrue(options.any { it.contentDescription == "Dark" && it.isSelected })
            assertTrue(options.any { it.contentDescription == "System Default" })
            options.first { it.contentDescription == "Light" }.performClick()
            assertEquals(0xFFF1F5F9.toInt(), app.surface)
            assertEquals(0xFF00587B.toInt(), app.accent)
        }
    }

    @Test fun offersLocalSearchAndManualAddressFromTheWelcomeScreen() {
        val controller = Robolectric.buildActivity(BridgeActivity::class.java).setup()
        controller.use {
            val app = controller.get()
            flatten(app.window.decorView).first { it.contentDescription == "Connect your TV" }.performClick()
            val scannerViews = flatten(app.window.decorView).toList()
            val names = scannerViews.mapNotNull { it.contentDescription }
            val texts = scannerViews.filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("Rescan" in names)
            assertTrue("Enter IP Manually" in names)
            assertTrue("Pair with Code" in names)
            assertTrue("Scan" in texts)
            assertTrue("Connect" in texts)
            assertTrue("Approve on TV" in texts)

            scannerViews.first { it.contentDescription?.toString()?.contains("ADB Debugging Guide") == true }.performClick()
            val guideTexts = flatten(app.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("ADB Debugging Guide" in guideTexts)
            assertTrue("Google TV" in guideTexts)
            assertTrue("Fire TV" in guideTexts)
        }
    }

    @Test fun supportsDarkLightSystemAppearanceAndVersion144() {
        assertEquals("1.4.4", BuildConfig.VERSION_NAME)
        assertEquals(15, BuildConfig.VERSION_CODE)
        assertEquals(Appearance.DARK, Appearance.read("AMOLED"))
        val controller = Robolectric.buildActivity(BridgeActivity::class.java).setup()
        controller.use {
            val app = controller.get()
            flatten(app.window.decorView).first { it.contentDescription == "Appearance" }.performClick()
            val options = flatten(app.window.decorView).toList()
            assertTrue(options.none { it.contentDescription == "AMOLED Black" })
            assertTrue(options.any { it.contentDescription == "Dark" })
            options.first { it.contentDescription == "Dark" }.performClick()
            assertEquals(0xFF05070A.toInt(), app.surface)
        }
    }

    @Test fun showsSavedTvsForOneTapReconnectOnWelcomeScreen() {
        val controller = Robolectric.buildActivity(BridgeActivity::class.java)
        controller.use {
            val app = controller.get()
            app.getSharedPreferences("bridge", android.content.Context.MODE_PRIVATE)
                .edit()
                .putStringSet("tv-addresses", setOf("192.168.1.50:5555"))
                .putString("tv-model-192.168.1.50:5555", "Living Room TV")
                .commit()
            controller.setup()
            val texts = flatten(app.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("Living Room TV" in texts)
            assertTrue(texts.any { "192.168.1.50:5555" in it })
        }
    }

    @Test fun pushesAppUpdateImmediatelyWhenAppOpens() {
        val controller = Robolectric.buildActivity(BridgeActivity::class.java)
        controller.use {
            val app = controller.get()
            var fetchCount = 0
            app.updateFetcher = {
                fetchCount++
                AppUpdateInfo(
                    tagName = "v9.9.9",
                    version = "9.9.9",
                    title = "A4 TV Bridge 9.9.9",
                    notes = "Faster updates",
                    htmlUrl = "https://github.com/RatherAsif04/A4-TV-Bridge/releases/tag/v9.9.9",
                    apkAsset = ReleaseAssetInfo(
                        name = "A4-TV-Bridge-9.9.9-release.apk",
                        downloadUrl = "https://github.com/RatherAsif04/A4-TV-Bridge/releases/download/v9.9.9/A4-TV-Bridge-9.9.9-release.apk",
                        sizeBytes = 4_200_000L
                    ),
                    checksumsUrl = null
                )
            }
            controller.setup()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()

            assertEquals(1, fetchCount)
            val firstDialog = ShadowDialog.getLatestDialog()
            assertNotNull(firstDialog)
            assertTrue(firstDialog.isShowing)
            val firstTexts = flatten(firstDialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertTrue(firstTexts.any { it.contains("Update available · v9.9.9") })
            assertTrue(firstTexts.any { it.contains("Download & install v9.9.9") })
            assertTrue("Later" in firstTexts)

            // Dismissing with Later closes for now, and reopening the app immediately pushes again
            flatten(firstDialog.window!!.decorView).first { it is TextView && it.text.toString() == "Later" }.performClick()
            assertTrue(!firstDialog.isShowing)

            controller.stop().start()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()

            assertEquals(2, fetchCount)
            val reopenedDialog = ShadowDialog.getLatestDialog()
            assertNotNull(reopenedDialog)
            assertTrue(reopenedDialog.isShowing)
        }
    }

    @Test fun inputFieldsNeverApplyAccentBackgroundTint() {
        val controller = Robolectric.buildActivity(BridgeActivity::class.java).setup()
        controller.use {
            val app = controller.get()
            app.ask("Rename TV", "Android TV Box 1") {}
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull(dialog)
            val edit = flatten(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().first()
            assertEquals("Android TV Box 1", edit.text.toString())
            assertEquals(null, edit.backgroundTintList)
            assertEquals(app.ink, edit.currentTextColor)
        }
    }

    private fun flatten(node: View): Sequence<View> = sequence {
        yield(node)
        if (node is ViewGroup) {
            for (index in 0 until node.childCount) yieldAll(flatten(node.getChildAt(index)))
        }
    }
}
