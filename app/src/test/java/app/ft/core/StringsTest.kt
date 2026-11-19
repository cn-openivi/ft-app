package app.ft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringsTest {
    private class Table(val strings: Map<String, String>, val arrays: Map<String, List<String>>)

    private fun read(folder: String): Table {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$folder/strings.xml"))
        val strings = linkedMapOf<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            if (e.getAttribute("translatable") != "false") strings[e.getAttribute("name")] = e.textContent
        }
        val arrays = linkedMapOf<String, List<String>>()
        val lists = doc.getElementsByTagName("string-array")
        for (i in 0 until lists.length) {
            val e = lists.item(i) as Element
            val items = e.getElementsByTagName("item")
            arrays[e.getAttribute("name")] = (0 until items.length).map { items.item(it).textContent }
        }
        return Table(strings, arrays)
    }

    private val english = read("values")
    private val translations = mapOf(
        "Arabic" to read("values-ar"),
        "Russian" to read("values-ru")
    )

    private fun placeholders(s: String) = Regex("%(\\d+)\\$([sd])").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun everyLanguageHasEveryString() {
        translations.forEach { (name, t) ->
            assertEquals("$name strings", english.strings.keys, t.strings.keys)
            assertEquals("$name lists", english.arrays.keys, t.arrays.keys)
            english.arrays.forEach { (key, items) -> assertEquals("$name $key", items.size, t.arrays.getValue(key).size) }
        }
    }

    @Test
    fun everyTranslationKeepsTheSameBlanks() {
        translations.forEach { (name, t) ->
            english.strings.forEach { (key, value) ->
                assertEquals("$name $key", placeholders(value), placeholders(t.strings.getValue(key)))
            }
        }
    }

    @Test
    fun arabicDoesNotContainKurdishSpecificLetters() {
        val kurdishOnly = "یکەڵڕێۆڤگچپژ".toSet()
        val arabic = translations.getValue("Arabic")
        (arabic.strings.values + arabic.arrays.values.flatten()).forEach { value ->
            assertTrue("Kurdish-specific letter in Arabic: $value", value.none { it in kurdishOnly })
        }
    }

    @Test
    fun newLocalesHaveRealStarterTranslationsWithSafeEnglishFallback() {
        // New locales are deliberately partial. Omitted Android resources fall
        // back to default English rather than pretending all 664 strings have
        // already been translated and linguistically reviewed.
        mapOf(
            "Turkish" to read("values-tr"),
            "German" to read("values-de"),
            "French" to read("values-fr")
        ).forEach { (name, locale) ->
            assertTrue("$name has too few reviewed starter strings", locale.strings.size >= 70)
            locale.strings.forEach { (key, value) ->
                assertTrue("$name unknown key $key", english.strings.containsKey(key))
                assertEquals("$name placeholders for $key",
                    placeholders(english.strings.getValue(key)), placeholders(value))
                assertTrue("$name empty translation for $key", value.isNotBlank())
            }
            assertTrue("$name locale is all English",
                locale.strings.any { (key, value) -> value != english.strings.getValue(key) })
        }
    }

    @Test
    fun visibleLanguagesAreExplicitAndLegacyKurdishPrefsFallBackSafely() {
        assertEquals(
            listOf("", "ru", "en", "tr", "de", "fr", "ar"),
            Language.entries.map { it.key })
        assertEquals(Language.SYSTEM, Language.of("ckb"))
        assertEquals(Language.SYSTEM, Language.of("kmr"))
        assertEquals(Language.TURKISH, Language.of("tr"))
        assertEquals(Language.GERMAN, Language.of("de"))
        assertEquals(Language.FRENCH, Language.of("fr"))
    }

    @Test
    fun russianIsWrittenInCyrillic() {
        val t = translations.getValue("Russian")
        (t.strings.values + t.arrays.values.flatten()).forEach { s -> assertTrue("Arabic script in Russian: $s", s.none { it in '\u0600'..'\u06FF' }) }
        val cyrillic = t.strings.values.count { s -> s.any { it in '\u0400'..'\u04FF' } }
        assertTrue("too few Russian strings are in Cyrillic: $cyrillic", cyrillic > t.strings.size * 3 / 4)
    }
}
