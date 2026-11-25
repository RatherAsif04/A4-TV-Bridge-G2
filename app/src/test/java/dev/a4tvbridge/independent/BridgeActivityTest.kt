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
            assertTrue(flatten(about.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == "About A4 TV Bridge" })
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
            assertEquals(android.graphics.Color.BLACK, app.surface)
            assertEquals(0xFFB0DEFF.toInt(), app.accent)
            flatten(app.window.decorView).first { it.contentDescription == "Appearance" }.performClick()
            val options = flatten(app.window.decorView).toList()
            assertTrue(options.any { it.contentDescription == "Dark" && it.isSelected })
            assertTrue(options.any { it.contentDescription == "System Default" })
            options.first { it.contentDescription == "Light" }.performClick()
            assertEquals(android.graphics.Color.WHITE, app.surface)
            assertEquals(0xFF00587B.toInt(), app.accent)
        }
    }

    @Test fun offersLocalSearchAndManualAddressFromTheWelcomeScreen() {
        val controller = Robolectric.buildActivity(BridgeActivity::class.java).setup()
        controller.use {
            val app = controller.get()
            flatten(app.window.decorView).first { it.contentDescription == "Connect your TV" }.performClick()
            val setup = ShadowDialog.getLatestDialog()
            val names = flatten(setup.window!!.decorView).mapNotNull { it.contentDescription }.toList()
            assertTrue("Find TVs on this network" in names)
            assertTrue("Enter TV address" in names)
            assertTrue("Pair with a code" in names)
            setup.dismiss()
        }
    }

    private fun flatten(node: View): Sequence<View> = sequence {
        yield(node)
        if (node is ViewGroup) {
            for (index in 0 until node.childCount) yieldAll(flatten(node.getChildAt(index)))
        }
    }
}
