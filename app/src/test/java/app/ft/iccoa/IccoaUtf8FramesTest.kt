package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.nio.charset.MalformedInputException

class IccoaUtf8FramesTest {
    @Test fun everySplitPointOfChineseNameReassemblesExactly() {
        val original = "{\"type\":1,\"name\":\"中文测试\"}"
        val bytes = original.toByteArray(Charsets.UTF_8)
        for (split in 1 until bytes.size) {
            val decoder = IccoaUtf8Frames()
            val got = decoder.accept(bytes.copyOfRange(0, split)) +
                decoder.accept(bytes.copyOfRange(split, bytes.size))
            decoder.finish()
            assertEquals("split=$split", original, got)
        }
    }

    @Test fun oneByteAtATimeFeedsCompleteUibcJsonWithoutReplacement() {
        val bytes = "{\"type\":1,\"label\":\"导航🚙\"}".toByteArray(Charsets.UTF_8)
        val events = mutableListOf<String>()
        val json = IccoaInputJson { events += it.getString("label") }
        bytes.forEach { json.accept(byteArrayOf(it)) }
        json.finish()
        assertEquals(listOf("导航🚙"), events)
    }

    @Test fun incompleteUtf8AtEndOfStreamFailsClosed() {
        val decoder = IccoaUtf8Frames()
        decoder.accept(byteArrayOf(0xE4.toByte(), 0xB8.toByte()))
        assertThrows(EOFException::class.java) { decoder.finish() }
    }

    @Test fun malformedUtf8MustNotSilentlyTurnIntoReplacementCharacter() {
        val decoder = IccoaUtf8Frames()
        assertThrows(MalformedInputException::class.java) {
            decoder.accept(byteArrayOf(0xE4.toByte(), 0x00, 0x80.toByte()))
        }
    }

    @Test fun sequentialJsonObjectsSurviveSplitUtf8Boundaries() {
        val names = mutableListOf<String>()
        val parser = IccoaInputJson { names += it.getString("name") }
        val raw = "{\"name\":\"甲\"}{\"name\":\"乙\"}".toByteArray(Charsets.UTF_8)
        raw.forEach { parser.accept(byteArrayOf(it)) }
        parser.finish()
        assertEquals(listOf("甲", "乙"), names)
    }
}
