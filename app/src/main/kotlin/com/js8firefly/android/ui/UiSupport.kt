package com.js8firefly.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Time and formatting helpers shared by the views. */
object AppTime {
    /** Corrected UTC wall clock (AppClock), in epoch milliseconds. */
    fun nowMillis(): Long = millisOf(com.js8firefly.android.audio.AppClock.now())

    /** Epoch milliseconds for whatever date type the model hands us. */
    fun millisOf(d: Any?): Long = when (d) {
        is Instant -> d.toEpochMilli()
        is java.util.Date -> d.time
        is Long -> d
        is Double -> (d * 1000).toLong()
        is Int -> d.toLong()
        else -> 0L
    }

    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC)
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneOffset.UTC)

    fun utcTime(d: Any?): String = hms.format(Instant.ofEpochMilli(millisOf(d)))
    fun utcShort(d: Any?): String = hm.format(Instant.ofEpochMilli(millisOf(d)))

    /** JS8Station.since: "now", "15s", "3m", "2h", "1d". */
    fun since(d: Any?, nowMillis: Long = nowMillis()): String {
        val delta = ((nowMillis - millisOf(d)) / 1000).toInt()
        return when {
            delta >= 86400 -> "${delta / 86400}d"
            delta >= 3600 -> "${delta / 3600}h"
            delta >= 60 -> "${delta / 60}m"
            delta >= 15 -> "${delta - delta % 15}s"
            else -> "now"
        }
    }
}

private val grouping: NumberFormat = NumberFormat.getIntegerInstance(Locale.getDefault())

/** An integer with thousands separators, as SwiftUI shows "\(int)" in Text. */
fun grouped(v: Int): String = grouping.format(v)
fun grouped(v: Long): String = grouping.format(v)

fun copyToClipboard(context: Context, s: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("JS8 Firefly", s))
}
