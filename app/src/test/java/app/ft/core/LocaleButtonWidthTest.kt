package app.ft.core

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Desktop-JVM source preflight for localized one-line controls.
 * Dialog 10pt is only a rough glyph-width proxy for Compose autosize, not
 * a screenshot test of the exact Toyota head unit.
 */
class LocaleButtonWidthTest {
    private fun read(folder: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val el = nodes.item(index) as Element
            el.getAttribute("name") to el.textContent.replace("\\'", "'")
        }
    }

    private fun width(text: String, pt: Int): Double {
        System.setProperty("java.awt.headless", "true")
        val font = Font("Dialog", Font.PLAIN, pt)
        val ctx = FontRenderContext(AffineTransform(), true, true)
        return font.getStringBounds(text, ctx).width
    }

    @Test fun vehicleTilesFitOneLineAtAutoSizeMinimum() {
        val keys = listOf("tile_music", "tile_videos", "tile_youtube",
            "tile_browser", "tile_apps", "tile_maps", "tile_aa",
            "tile_phone", "tile_supercast")
        listOf("values-tr", "values-de", "values-fr").forEach { locale ->
            val labels = read(locale)
            keys.forEach { key ->
                val caption = labels.getValue(key)
                assertTrue("$locale/$key has a line break", !caption.contains('\n'))
                assertTrue("$locale/$key too wide: $caption", width(caption, 10) <= 88.0)
            }
        }
    }

    @Test fun segmentedChoicesFitOneLineAtAutoSizeMinimum() {
        val keys = listOf("look_theme_auto", "look_theme_light",
            "look_theme_dark", "look_preview_day", "look_preview_night")
        listOf("values-tr", "values-de", "values-fr").forEach { locale ->
            val labels = read(locale)
            keys.forEach { key ->
                val caption = labels.getValue(key)
                assertTrue("$locale/$key too wide: $caption", width(caption, 10) <= 83.0)
            }
        }
    }

    @Test fun calibrationAndWirelessSetupButtonsFitSingleLine() {
        val halfWidth = listOf("home_touch_start", "home_touch_reset")
        val fullWidth = listOf("wireless_pair_button", "home_allow_notifications")
        listOf("values-tr", "values-de", "values-fr").forEach { locale ->
            val labels = read(locale)
            halfWidth.forEach { key ->
                val text = labels.getValue(key)
                assertTrue("$locale/$key exceeds half-row width: $text",
                    width(text, 11) <= 140.0)
            }
            fullWidth.forEach { key ->
                val text = labels.getValue(key)
                assertTrue("$locale/$key exceeds full button width: $text",
                    width(text, 11) <= 190.0)
            }
        }
    }

    @Test fun commonDialogButtonsKeepCompactCaptions() {
        val keys = listOf("action_add", "action_allow", "action_back",
            "action_cancel", "action_close", "action_copy", "action_done",
            "action_next", "action_not_now", "action_open", "action_remove",
            "action_reset", "action_share", "action_turn_on")
        listOf("values-tr", "values-de", "values-fr").forEach { locale ->
            val labels = read(locale)
            keys.forEach { key ->
                val caption = labels.getValue(key)
                assertTrue("$locale/$key too wide: $caption", width(caption, 11) <= 110.0)
            }
        }
    }
}
