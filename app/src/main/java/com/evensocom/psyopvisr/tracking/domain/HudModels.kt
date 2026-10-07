package com.evensocom.psyopvisr.tracking.domain

/**
 * Operating mode of the teammate radar/HUD display.
 */
enum class HudMode {
    TEXT_ONLY,
    RADAR_DIAL,
    HYBRID,
    DEGRADED_TEXT_ONLY,
    OFF
}

/**
 * Visual warning or status flag rendered on the HUD.
 */
enum class HudStatusFlag {
    NORMAL,
    NO_GPS,
    NO_HEADING,
    GPS_COURSE_FALLBACK,
    TORSO_ALIGNED_ONLY,
    RELAY_DISCONNECTED,
    STALE_DATA,
    G2_BATTERY_LOW
}

/**
 * A 2D radar blip point projected onto the G2 display coordinate space.
 *
 * G2 Display Bounds: 576 x 288 px total.
 * Image container bounded to max 288 x 144 px.
 * Top-left origin: (0, 0).
 *
 * @property teammateId Identifier of the teammate represented by this blip.
 * @property callsign Display callsign.
 * @property x Projected X coordinate in container pixels (e.g. 0..288).
 * @property y Projected Y coordinate in container pixels (e.g. 0..144).
 * @property distanceMeters True geodetic distance in meters.
 * @property relativeBearingDegrees Bearing relative to wearer heading [0.0, 360.0).
 *                                  0° = 12 o'clock (straight ahead),
 *                                  90° = 3 o'clock (right),
 *                                  180° = 6 o'clock (behind),
 *                                  270° = 9 o'clock (left).
 * @property clockHour Relative direction as a 12-hour clock face integer (1..12).
 * @property isClipped True if teammate is beyond maximum configured radar range and pinned to the perimeter.
 * @property isStale True if teammate fix exceeds freshness threshold.
 */
data class RadarPoint(
    val teammateId: String,
    val callsign: String,
    val x: Float,
    val y: Float,
    val distanceMeters: Double,
    val relativeBearingDegrees: Double,
    val clockHour: Int,
    val isClipped: Boolean,
    val isStale: Boolean
) {
    init {
        require(teammateId.isNotBlank()) { "Teammate id must not be blank" }
        require(callsign.isNotBlank()) { "Callsign must not be blank" }
        require(x.isFinite() && y.isFinite()) { "Coordinates must be finite: ($x, $y)" }
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "Distance must be non-negative finite: $distanceMeters"
        }
        require(relativeBearingDegrees.isFinite() && relativeBearingDegrees >= 0.0 && relativeBearingDegrees < 360.0) {
            "Relative bearing must be in range [0.0, 360.0): $relativeBearingDegrees"
        }
        require(clockHour in 1..12) { "Clock hour must be 1..12: $clockHour" }
    }
}

/**
 * Complete immutable HUD state prepared for display adapter rendering.
 *
 * @property mode Current display mode.
 * @property statusFlag Active status or degradation flag.
 * @property wearerLocation Current wearer location fix (null if no GPS).
 * @property wearerHeading Current wearer heading sample (null if heading unavailable).
 * @property radarPoints Projected radar blips within current viewport.
 * @property radarRangeMeters Configured maximum radial distance in meters.
 * @property formattedTextCards Structured text lines for G2 TextContainer.
 * @property generation Monotonically incrementing generation counter to detect and discard stale render frames.
 * @property timestampMonotonicMs Monotonic timestamp of state composition.
 */
data class HudState(
    val mode: HudMode,
    val statusFlag: HudStatusFlag,
    val wearerLocation: LocationFix?,
    val wearerHeading: HeadingSample?,
    val radarPoints: List<RadarPoint>,
    val radarRangeMeters: Double,
    val formattedTextCards: List<String>,
    val generation: Long,
    val timestampMonotonicMs: Long
) {
    init {
        require(radarRangeMeters > 0.0 && radarRangeMeters.isFinite()) {
            "Radar range must be positive finite: $radarRangeMeters"
        }
        require(generation >= 0L) { "Generation must be non-negative: $generation" }
        require(timestampMonotonicMs >= 0L) { "Timestamp must be non-negative: $timestampMonotonicMs" }
    }

    /**
     * Checks if heading reference is absent or unusable.
     */
    fun isHeadingDegraded(): Boolean {
        return wearerHeading == null ||
                wearerHeading.source == HeadingSource.UNAVAILABLE ||
                wearerHeading.quality == HeadingQuality.UNRELIABLE
    }
}

/**
 * Result returned by display adapter after submitting a frame to G2.
 */
sealed class AdapterResult {
    data class Success(val generation: Long, val durationMs: Long) : AdapterResult()
    data class Dropped(val generation: Long, val reason: String) : AdapterResult()
    data class Error(val generation: Long, val error: String, val isRecoverable: Boolean) : AdapterResult()
}
