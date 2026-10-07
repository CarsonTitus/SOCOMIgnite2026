package com.evensocom.psyopvisr.tracking.domain

/**
 * Representation of a teammate tracked in the tactical space.
 *
 * All coordinates and metrics are validated upon creation.
 *
 * @property id Unique teammate identifier (e.g. UUID, callsign, or cryptographic key ID; 1..64 chars).
 * @property callsign Operator tactical callsign (UTF-8, 1..32 chars, sanitized).
 * @property latitude WGS84 latitude in degrees [-90.0, +90.0].
 * @property longitude WGS84 longitude in degrees [-180.0, +180.0].
 * @property altitudeMeters Optional elevation above WGS84 ellipsoid or sea level in meters.
 * @property headingDegrees Optional heading in degrees [0.0, 360.0). Note: null indicates heading is absent,
 *                          distinct from 0.0° (True North).
 * @property accuracyMeters Estimated horizontal 1-sigma location accuracy in meters (>= 0.0).
 * @property timestampEpochMs Measurement epoch timestamp in milliseconds (UTC).
 * @property receivedMonotonicMs Monotonic clock timestamp (e.g. SystemClock.elapsedRealtime) when this record
 *                               was received locally. Separates remote measurement age from local receipt age.
 */
data class Teammate(
    val id: String,
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val headingDegrees: Double? = null,
    val accuracyMeters: Float,
    val timestampEpochMs: Long,
    val receivedMonotonicMs: Long
) {
    init {
        require(id.isNotBlank()) { "Teammate id must not be blank" }
        require(id.length <= 64) { "Teammate id length must be <= 64 characters (was ${id.length})" }
        require(isValidIdentifier(id)) { "Teammate id contains invalid characters: $id" }

        require(callsign.isNotBlank()) { "Teammate callsign must not be blank" }
        require(callsign.length <= 32) { "Teammate callsign length must be <= 32 characters (was ${callsign.length})" }
        require(isCleanString(callsign)) { "Teammate callsign contains invalid control characters" }

        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be a finite number between -90.0 and +90.0: $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be a finite number between -180.0 and +180.0: $longitude"
        }

        if (altitudeMeters != null) {
            require(altitudeMeters.isFinite() && altitudeMeters in -1000.0..20000.0) {
                "Altitude must be finite between -1000.0m and +20000.0m: $altitudeMeters"
            }
        }

        if (headingDegrees != null) {
            require(headingDegrees.isFinite() && headingDegrees >= 0.0 && headingDegrees < 360.0) {
                "Heading must be a finite number in range [0.0, 360.0): $headingDegrees"
            }
        }

        require(accuracyMeters.isFinite() && accuracyMeters >= 0f) {
            "Accuracy meters must be a non-negative finite float: $accuracyMeters"
        }

        require(timestampEpochMs > 0L) {
            "Timestamp epoch ms must be positive: $timestampEpochMs"
        }
        require(receivedMonotonicMs >= 0L) {
            "Received monotonic ms must be non-negative: $receivedMonotonicMs"
        }
    }

    /**
     * Compute measurement age in milliseconds relative to a given UTC reference epoch.
     */
    fun measurementAgeMs(referenceEpochMs: Long): Long = referenceEpochMs - timestampEpochMs

    /**
     * Compute receipt age in milliseconds relative to local monotonic clock.
     */
    fun receiptAgeMs(nowMonotonicMs: Long): Long = nowMonotonicMs - receivedMonotonicMs

    companion object {
        private val ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")

        fun isValidIdentifier(id: String): Boolean = ID_REGEX.matches(id)

        fun isCleanString(s: String): Boolean {
            return s.none { it.isISOControl() }
        }
    }
}

/**
 * Host/wearer location fix obtained from GPS/GNSS or platform location providers.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
    val timestampEpochMs: Long,
    val elapsedRealtimeNs: Long
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be finite between -90.0 and +90.0: $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be finite between -180.0 and +180.0: $longitude"
        }
        if (altitudeMeters != null) {
            require(altitudeMeters.isFinite()) { "Altitude must be finite: $altitudeMeters" }
        }
        require(accuracyMeters.isFinite() && accuracyMeters >= 0f) {
            "Accuracy must be non-negative finite: $accuracyMeters"
        }
        if (speedMps != null) {
            require(speedMps.isFinite() && speedMps >= 0f) {
                "Speed must be non-negative finite: $speedMps"
            }
        }
        if (bearingDegrees != null) {
            require(bearingDegrees.isFinite() && bearingDegrees >= 0f && bearingDegrees < 360f) {
                "Bearing must be in range [0, 360): $bearingDegrees"
            }
        }
    }
}

/**
 * Heading source indicator specifying what device or sensor generated the orientation.
 */
enum class HeadingSource {
    PHONE_ROTATION_VECTOR,
    PHONE_GEOMAGNETIC_ROTATION_VECTOR,
    GPS_COURSE_OVER_GROUND,
    G2_RELATIVE_IMU,
    MANUAL_OVERRIDE,
    UNAVAILABLE
}

/**
 * North reference for a heading sample.
 */
enum class NorthReference {
    TRUE_NORTH,
    MAGNETIC_NORTH,
    RELATIVE_ARBITRARY,
    UNKNOWN
}

/**
 * Quality estimate of heading calibration.
 */
enum class HeadingQuality {
    HIGH,
    MEDIUM,
    LOW,
    UNRELIABLE
}

/**
 * Phone-to-wearer mounting alignment state.
 */
enum class MountAlignment {
    HEAD_ALIGNED,
    CHEST_TORSO_ALIGNED,
    HANDHELD,
    POCKET_LOOSE,
    UNKNOWN
}

/**
 * Heading sample captured by sensor fusion or external provider.
 *
 * Crucial invariants:
 * - A null heading sample or [HeadingSource.UNAVAILABLE] indicates heading is absent, distinct from 0.0° (True North).
 * - Distinguishes remote measurement time vs monotonic local sampling time.
 */
data class HeadingSample(
    val headingDegrees: Double,
    val source: HeadingSource,
    val reference: NorthReference,
    val quality: HeadingQuality,
    val alignment: MountAlignment,
    val accuracyDegrees: Float? = null,
    val timestampEpochMs: Long,
    val elapsedRealtimeNs: Long
) {
    init {
        require(headingDegrees.isFinite() && headingDegrees >= 0.0 && headingDegrees < 360.0) {
            "Heading degrees must be finite in range [0.0, 360.0): $headingDegrees"
        }
        if (accuracyDegrees != null) {
            require(accuracyDegrees.isFinite() && accuracyDegrees >= 0f) {
                "Accuracy degrees must be non-negative finite: $accuracyDegrees"
            }
        }
    }

    /**
     * Returns true if this heading sample is certified wearer head glance direction.
     */
    fun isWearerFacing(): Boolean {
        return alignment == MountAlignment.HEAD_ALIGNED &&
                source != HeadingSource.UNAVAILABLE &&
                quality != HeadingQuality.UNRELIABLE
    }
}

/**
 * Immutable snapshot of all active teammates in the current operational scope.
 *
 * @property teamId Identifier of the team/squad (1..64 chars).
 * @property sequence Monotonically increasing sequence number within the session.
 * @property sessionEpoch Unique UUID or session token identifying current relay/peer session epoch.
 * @property teammates Immutable map of teammate ID to Teammate domain object.
 * @property capturedMonotonicMs Monotonic time when this snapshot was materialized.
 */
data class TeamSnapshot(
    val teamId: String,
    val sequence: Long,
    val sessionEpoch: String,
    val teammates: Map<String, Teammate>,
    val capturedMonotonicMs: Long
) {
    init {
        require(teamId.isNotBlank() && teamId.length <= 64) { "Team id must be 1..64 chars" }
        require(sequence >= 0L) { "Sequence must be non-negative: $sequence" }
        require(sessionEpoch.isNotBlank()) { "Session epoch must not be blank" }
        require(capturedMonotonicMs >= 0L) { "Captured monotonic ms must be non-negative" }
    }

    fun getTeammate(id: String): Teammate? = teammates[id]

    fun count(): Int = teammates.size
}
