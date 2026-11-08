package com.js8firefly.core

import java.util.UUID

// Port of JS8Core/Station/WatchItem.swift. Swift's Codable is replaced by
// `WatchItem.encodeList` / `WatchItem.decodeList`, which read and write the same
// JSON the iPhone app stores ([{"id":"…","term":"…","color":"gold"}]).

/** A callsign, @GROUP or word to make stand out when it is heard (JS8Call's highlight words). */
data class WatchItem(
    var id: UUID = UUID.randomUUID(),
    /** Normalized with `WatchMatcher.normalize`. */
    var term: String,
    var color: WatchColor = WatchColor.gold,
) {
    val kind: String
        get() {
            if (term.startsWith("@")) return "Group"
            return if (Varicode.isValidCallsign(term).valid) "Station" else "Word or group"
        }

    companion object {
        fun encodeList(items: List<WatchItem>): String = StationJson.write(
            items.map { linkedMapOf("id" to it.id.toString().uppercase(), "term" to it.term, "color" to it.color.rawValue) },
        )

        /** Throws on malformed JSON or a missing key; an unknown color falls back to gold. */
        fun decodeList(json: String): List<WatchItem> {
            val arr = StationJson.parse(json) as? List<*> ?: throw IllegalArgumentException("expected array")
            return arr.map {
                @Suppress("UNCHECKED_CAST")
                val o = it as? Map<String, Any?> ?: throw IllegalArgumentException("expected object")
                WatchItem(
                    id = UUID.fromString(StationJson.string(o, "id")),
                    term = StationJson.string(o, "term"),
                    color = WatchColor.fromRaw(StationJson.string(o, "color")) ?: WatchColor.gold,
                )
            }
        }
    }
}

/**
 * The watch colors. Written as a class with one instance per case (rather than an
 * enum class) so that `name` can keep its Swift meaning ("Crimson"): Kotlin enums
 * reserve `name` for the case name. Use `allCases`, `rawValue` and `fromRaw` as in Swift.
 */
class WatchColor private constructor(val rawValue: String) {
    val id: String get() = rawValue

    val name: String get() = rawValue.replaceFirstChar { it.uppercaseChar() }

    /** sRGB components, 0...1. */
    data class RGB(val red: Double, val green: Double, val blue: Double)

    val rgb: RGB
        get() = when (this) {
            gold -> RGB(1.0, 0.82, 0.22)
            magenta -> RGB(1.0, 0.36, 0.84)
            violet -> RGB(0.68, 0.52, 1.0)
            lime -> RGB(0.72, 1.0, 0.32)
            orange -> RGB(1.0, 0.56, 0.18)
            crimson -> RGB(0.82, 0.04, 0.20)
            else -> RGB(0.94, 0.95, 1.0) // pearl
        }

    override fun toString(): String = rawValue

    companion object {
        @JvmField val gold = WatchColor("gold")
        @JvmField val magenta = WatchColor("magenta")
        @JvmField val violet = WatchColor("violet")
        @JvmField val lime = WatchColor("lime")
        @JvmField val orange = WatchColor("orange")
        @JvmField val crimson = WatchColor("crimson")
        @JvmField val pearl = WatchColor("pearl")

        val allCases: List<WatchColor> = listOf(gold, magenta, violet, lime, orange, crimson, pearl)

        fun fromRaw(raw: String): WatchColor? = allCases.firstOrNull { it.rawValue == raw }
    }
}
