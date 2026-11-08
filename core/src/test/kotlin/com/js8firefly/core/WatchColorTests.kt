package com.js8firefly.core

import com.js8firefly.core.WatchColor.RGB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class WatchColorTests {
    /** Theme.tx in the app: the coral used for traffic addressed to our call. */
    private val directedRed = RGB(red = 1.0, green = 0.36, blue = 0.30)
    /** Theme.bg in the app. */
    private val background = RGB(red = 0.035, green = 0.045, blue = 0.075)

    @Test
    fun testCrimsonIsOffered() {
        assertEquals(
            listOf(WatchColor.gold, WatchColor.magenta, WatchColor.violet, WatchColor.lime, WatchColor.orange, WatchColor.crimson, WatchColor.pearl),
            WatchColor.allCases,
        )
        assertEquals("Crimson", WatchColor.crimson.name)
    }

    @Test
    fun testNewItemsDefaultToGold() {
        assertEquals(WatchColor.gold, WatchItem(term = "EMERGENCY").color)
    }

    @Test
    fun testCrimsonRoundTrips() {
        val item = WatchItem(term = "EMERGENCY", color = WatchColor.crimson)
        val data = WatchItem.encodeList(listOf(item))
        assertTrue(data, data.contains("\"color\":\"crimson\""))
        assertEquals(listOf(item), WatchItem.decodeList(data))
    }

    @Test
    fun testListsSavedBeforeCrimsonStillLoad() {
        val saved = """
        [{"id":"96975E04-FBAE-43D9-B4D1-E72FD70934C9","term":"K4LMB","color":"gold"},
         {"id":"AF505908-C599-4B2B-9D4A-944E805C0FE8","term":"ARES","color":"magenta"}]
        """.trimIndent()
        val items = WatchItem.decodeList(saved)
        assertEquals(listOf("K4LMB", "ARES"), items.map { it.term })
        assertEquals(listOf(WatchColor.gold, WatchColor.magenta), items.map { it.color })
    }

    @Test
    fun testUnknownColorFallsBackToGold() {
        val saved = """[{"id":"96975E04-FBAE-43D9-B4D1-E72FD70934C9","term":"K4LMB","color":"teal"}]"""
        val items = WatchItem.decodeList(saved)
        assertEquals(WatchColor.gold, items.firstOrNull()?.color)
    }

    @Test
    fun testCrimsonStandsApartFromDirectedRed() {
        val crimson = WatchColor.crimson.rgb
        assertTrue(distance(crimson, directedRed) > 0.3)
        // A deep red: clearly darker than the coral, not just a shade off it.
        assertTrue(contrast(directedRed, crimson) > 1.5)
    }

    @Test
    fun testCrimsonStandsApartFromOtherWatchColors() {
        for (other in WatchColor.allCases) {
            if (other == WatchColor.crimson) continue
            assertTrue("crimson vs $other", distance(WatchColor.crimson.rgb, other.rgb) > 0.3)
        }
    }

    @Test
    fun testCrimsonShowsOnTheDarkBackground() {
        // WCAG's 3:1 for non-text UI.
        assertTrue(contrast(WatchColor.crimson.rgb, background) >= 3)
    }

    @Test
    fun testEmergencyMatches() {
        val term = WatchMatcher.normalize("emergency")
        assertEquals("EMERGENCY", term)
        assertEquals(0, WatchMatcher.firstMatch("W7GTK: @ARES EMERGENCY NET NOW ON 3578", terms = listOf(term!!)))
        assertNull(WatchMatcher.firstMatch("W7GTK: @ARES EMERGENCYNET", terms = listOf(term)))
    }

    private fun distance(a: RGB, b: RGB): Double =
        sqrt(
            (a.red - b.red) * (a.red - b.red) + (a.green - b.green) * (a.green - b.green) +
                (a.blue - b.blue) * (a.blue - b.blue),
        )

    /** WCAG relative luminance. */
    private fun luminance(c: RGB): Double {
        fun linear(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)
    }

    private fun contrast(a: RGB, b: RGB): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
