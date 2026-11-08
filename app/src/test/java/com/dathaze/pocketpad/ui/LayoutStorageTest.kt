package com.dathaze.pocketpad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved-layout format, including the rules that have to hold for existing
 * users: a layout written before pinch-to-resize (three fields) and one written
 * before width and height could differ (four fields) must both still load, at
 * the shape those builds drew.
 */
class LayoutStorageTest {

    @Test
    fun `positions sizes and both axes survive a round trip`() {
        val saved = listOf(
            LayoutStorage.Entry("dpad", 0.26f, 0.28f, 1.0f, 1.0f, 1.0f),
            LayoutStorage.Entry("fbottom", 0.74f, 0.42f, 1.5f, 1.25f, 0.8f),
            LayoutStorage.Entry("options", 0.76f, 0.79f, 0.6f, 2.1f, 0.55f)
        )

        val decoded = LayoutStorage.decode(LayoutStorage.encode(saved))

        assertEquals(saved.size, decoded.size)
        for (i in saved.indices) {
            assertEquals(saved[i].key, decoded[i].key)
            assertEquals(saved[i].x, decoded[i].x, 0.0001f)
            assertEquals(saved[i].y, decoded[i].y, 0.0001f)
            assertEquals(saved[i].size, decoded[i].size, 0.0001f)
            assertEquals(saved[i].width, decoded[i].width, 0.0001f)
            assertEquals(saved[i].height, decoded[i].height, 0.0001f)
        }
    }

    @Test
    fun `a layout saved before resizing loads at the built-in size`() {
        val legacy = "dpad:0.259:0.28;lstick:0.28:0.56;home:0.5:0.79"

        val decoded = LayoutStorage.decode(legacy)

        assertEquals(3, decoded.size)
        assertEquals(listOf("dpad", "lstick", "home"), decoded.map { it.key })
        assertEquals(0.259f, decoded[0].x, 0.0001f)
        assertEquals(0.28f, decoded[0].y, 0.0001f)
        assertTrue("legacy entries must load at 1x", decoded.all { it.size == 1f })
        assertTrue("legacy entries must load round", decoded.all { it.width == 1f })
        assertTrue("legacy entries must load round", decoded.all { it.height == 1f })
    }

    @Test
    fun `a layout saved before width and height loads round`() {
        // What 2.5.0 wrote: key:x:y:size.
        val decoded = LayoutStorage.decode("fbottom:0.74:0.42:1.5")

        assertEquals(1, decoded.size)
        assertEquals(1.5f, decoded[0].size, 0.0001f)
        assertEquals(1f, decoded[0].width, 0.0001f)
        assertEquals(1f, decoded[0].height, 0.0001f)
    }

    @Test
    fun `legacy resized and stretched entries can be mixed`() {
        val mixed = "dpad:0.2:0.3;" +
            "fbottom:0.7:0.4:1.75;" +
            "lstick:0.28:0.56:1.0:1.4:0.75"

        val decoded = LayoutStorage.decode(mixed)

        assertEquals(3, decoded.size)
        assertEquals(1f, decoded[0].size, 0.0001f)
        assertEquals(1.75f, decoded[1].size, 0.0001f)
        assertEquals(1.4f, decoded[2].width, 0.0001f)
        assertEquals(0.75f, decoded[2].height, 0.0001f)
    }

    @Test
    fun `malformed entries are dropped without losing the rest`() {
        val messy = "dpad:0.2:0.3:1.2:1.0:1.0;" + // good
            "broken;" +                          // no fields at all
            ":0.5:0.5:1.0;" +                    // empty key
            "lstick:abc:0.5:1.0;" +              // unparsable x
            "rstick:0.3:0.4:oops;" +             // unparsable size
            "home:0.5:0.5:0;" +                  // zero size would erase the control
            "options:0.5:0.6:1.0:9;" +           // too few fields for an axis pair
            "share:0.2:0.8:1.0:0:1.0;" +         // zero width would erase it sideways
            "touchpad:0.2:0.8:1.0:1.0:-2;" +     // negative height
            "gear:0.08:0.92:0.9"                 // good

        val decoded = LayoutStorage.decode(messy)

        assertEquals(listOf("dpad", "gear"), decoded.map { it.key })
    }

    @Test
    fun `an absent layout decodes to nothing`() {
        assertTrue(LayoutStorage.decode(null).isEmpty())
        assertTrue(LayoutStorage.decode("").isEmpty())
        assertTrue(LayoutStorage.decode("   ").isEmpty())
    }

    @Test
    fun `encoding an empty layout yields an empty string`() {
        assertEquals("", LayoutStorage.encode(emptyList()))
        assertTrue(LayoutStorage.decode(LayoutStorage.encode(emptyList())).isEmpty())
    }
}
