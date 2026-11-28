package app.vesper.ui

// The Brain map's base colour follows the local time through four soft purple phases, blended by the minute. Calm: no glow, no motion.

/** (minute of day, rgb) keys, hours as the middle of each phase; the day wraps from the last key back to the first. */
private val SKY_KEYS = listOf(
    5 * 60 to 0x05030A, // night (22:00-05:00)
    6 * 60 to 0x1C1033, // dawn (06:00-08:00)
    8 * 60 to 0x1C1033,
    10 * 60 to 0x2A1A4A, // day (10:00-16:00)
    16 * 60 to 0x2A1A4A,
    18 * 60 to 0x160B2A, // dusk (18:00-20:00)
    20 * 60 to 0x160B2A,
    22 * 60 to 0x05030A,
)

/** The map's base colour (0xRRGGBB) at [minuteOfDay] (0..1439, wraps), smoothly blended between the phases. */
internal fun skyRgb(minuteOfDay: Int): Int {
    val m = ((minuteOfDay % 1440) + 1440) % 1440
    // night wraps: before the first key it blends from the last key of the evening
    val i = SKY_KEYS.indexOfLast { it.first <= m }
    val (m0, c0) = if (i >= 0) SKY_KEYS[i] else SKY_KEYS.last().let { it.first - 1440 to it.second }
    val (m1, c1) = if (i + 1 < SKY_KEYS.size) SKY_KEYS[i + 1] else SKY_KEYS.first().let { it.first + 1440 to it.second }
    return mixRgb(c0, c1, (m - m0).toFloat() / (m1 - m0))
}
