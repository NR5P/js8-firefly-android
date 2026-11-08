package com.js8firefly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchMatcherTests {
    @Test
    fun testNormalize() {
        assertEquals("K4LMB", WatchMatcher.normalize(" k4lmb "))
        assertEquals("ARES", WatchMatcher.normalize("#ares"))
        assertEquals("@ARES", WatchMatcher.normalize("@ares"))
        assertNull(WatchMatcher.normalize(""))
        assertNull(WatchMatcher.normalize("@"))
        assertNull(WatchMatcher.normalize("TWO WORDS"))
    }

    @Test
    fun testCallsigns() {
        assertEquals(0, WatchMatcher.firstMatch("K4LMB: NR5P GM NATHAN", terms = listOf("K4LMB")))
        assertEquals(0, WatchMatcher.firstMatch("K4LMB/P: @HB HEARTBEAT EM73", terms = listOf("K4LMB")))
        assertEquals(0, WatchMatcher.firstMatch("W7GTK: K4LMB SNR?", terms = listOf("K4LMB")))
        assertNull(WatchMatcher.firstMatch("KK4LMB: HELLO", terms = listOf("K4LMB")))
        assertNull(WatchMatcher.firstMatch("K4LMB: HELLO", terms = listOf("K4LMB/P")))
    }

    @Test
    fun testGroupsAndWords() {
        assertEquals(0, WatchMatcher.firstMatch("N0VBQ: @ARES NET TONIGHT", terms = listOf("ARES")))
        assertEquals(0, WatchMatcher.firstMatch("N0VBQ: @ARES NET TONIGHT", terms = listOf("@ARES")))
        assertEquals(0, WatchMatcher.firstMatch("N0VBQ: W7GTK ARES DRILL SAT.", terms = listOf("ARES")))
        assertNull(WatchMatcher.firstMatch("N0VBQ: W7GTK ARES DRILL", terms = listOf("@ARES")))
        assertNull(WatchMatcher.firstMatch("N0VBQ: W7GTK SQUARES", terms = listOf("ARES")))
    }

    @Test
    fun testFirstTermWins() {
        assertEquals(0, WatchMatcher.firstMatch("K4LMB: @ARES CHECK IN", terms = listOf("@ARES", "K4LMB")))
        assertEquals(1, WatchMatcher.firstMatch("K4LMB: @ARES CHECK IN", terms = listOf("W7GTK", "K4LMB")))
        assertNull(WatchMatcher.firstMatch("K4LMB: HELLO", terms = listOf()))
    }
}
