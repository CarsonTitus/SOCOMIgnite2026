package com.evensocom.psyopvisr.tracking

import com.evensocom.psyopvisr.tracking.domain.*
import com.evensocom.psyopvisr.tracking.protocol.WireEnvelope
import com.evensocom.psyopvisr.tracking.protocol.WireMessageType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackingDomainContractTest {

    @Test
    fun testValidTeammateCreation() {
        val teammate = Teammate(
            id = "alpha-1",
            callsign = "GHOST",
            latitude = 34.0522,
            longitude = -118.2437,
            altitudeMeters = 150.0,
            headingDegrees = 45.0,
            accuracyMeters = 3.5f,
            timestampEpochMs = 1700000000000L,
            receivedMonotonicMs = 50000L
        )

        assertEquals("alpha-1", teammate.id)
        assertEquals("GHOST", teammate.callsign)
        assertEquals(34.0522, teammate.latitude, 1e-6)
        assertEquals(-118.2437, teammate.longitude, 1e-6)
        assertEquals(150.0, teammate.altitudeMeters!!, 1e-6)
        assertEquals(45.0, teammate.headingDegrees!!, 1e-6)
        assertEquals(3.5f, teammate.accuracyMeters, 1e-6f)
    }

    @Test
    fun testAbsentHeadingIsDistinctFromZeroDegrees() {
        val teammateWithNorth = Teammate(
            id = "team-1",
            callsign = "LEAD",
            latitude = 10.0,
            longitude = 20.0,
            headingDegrees = 0.0, // Explicit True North
            accuracyMeters = 2.0f,
            timestampEpochMs = 1700000000000L,
            receivedMonotonicMs = 1000L
        )

        val teammateNoHeading = Teammate(
            id = "team-2",
            callsign = "SCOUT",
            latitude = 10.0,
            longitude = 20.0,
            headingDegrees = null, // Absent heading
            accuracyMeters = 2.0f,
            timestampEpochMs = 1700000000000L,
            receivedMonotonicMs = 1000L
        )

        assertNotNull(teammateWithNorth.headingDegrees)
        assertEquals(0.0, teammateWithNorth.headingDegrees!!, 1e-6)
        assertNull(teammateNoHeading.headingDegrees)
        assertNotEquals(teammateWithNorth.headingDegrees, teammateNoHeading.headingDegrees)
    }

    @Test
    fun testSeparationOfMeasurementAgeAndReceiptMonotonicTime() {
        val measurementUtc = 1700000000000L
        val localReceiptMono = 5000L

        val teammate = Teammate(
            id = "t-1",
            callsign = "EAGLE",
            latitude = 0.0,
            longitude = 0.0,
            accuracyMeters = 5.0f,
            timestampEpochMs = measurementUtc,
            receivedMonotonicMs = localReceiptMono
        )

        val referenceUtcNow = measurementUtc + 3000L
        val localMonoNow = localReceiptMono + 500L

        assertEquals(3000L, teammate.measurementAgeMs(referenceUtcNow))
        assertEquals(500L, teammate.receiptAgeMs(localMonoNow))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidLatitudeThrows() {
        Teammate(
            id = "t-1",
            callsign = "INVALID",
            latitude = 95.0, // Out of bounds
            longitude = 0.0,
            accuracyMeters = 1.0f,
            timestampEpochMs = 1000L,
            receivedMonotonicMs = 10L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidLongitudeThrows() {
        Teammate(
            id = "t-1",
            callsign = "INVALID",
            latitude = 0.0,
            longitude = -190.0, // Out of bounds
            accuracyMeters = 1.0f,
            timestampEpochMs = 1000L,
            receivedMonotonicMs = 10L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testHeading360Throws() {
        // [0.0, 360.0) range: 360 must be normalized to 0.0
        Teammate(
            id = "t-1",
            callsign = "INVALID",
            latitude = 0.0,
            longitude = 0.0,
            headingDegrees = 360.0,
            accuracyMeters = 1.0f,
            timestampEpochMs = 1000L,
            receivedMonotonicMs = 10L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBlankIdThrows() {
        Teammate(
            id = "   ",
            callsign = "BLANK",
            latitude = 0.0,
            longitude = 0.0,
            accuracyMeters = 1.0f,
            timestampEpochMs = 1000L,
            receivedMonotonicMs = 10L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testControlCharactersInCallsignThrows() {
        Teammate(
            id = "t-1",
            callsign = "GHOST\u0000MALICIOUS",
            latitude = 0.0,
            longitude = 0.0,
            accuracyMeters = 1.0f,
            timestampEpochMs = 1000L,
            receivedMonotonicMs = 10L
        )
    }

    @Test
    fun testHeadingSampleWearerAlignmentLogic() {
        val headMounted = HeadingSample(
            headingDegrees = 180.0,
            source = HeadingSource.PHONE_ROTATION_VECTOR,
            reference = NorthReference.TRUE_NORTH,
            quality = HeadingQuality.HIGH,
            alignment = MountAlignment.HEAD_ALIGNED,
            timestampEpochMs = 1000L,
            elapsedRealtimeNs = 1000000L
        )
        assertTrue(headMounted.isWearerFacing())

        val chestMounted = headMounted.copy(alignment = MountAlignment.CHEST_TORSO_ALIGNED)
        assertFalse("Chest-mounted phone cannot claim wearer head glance", chestMounted.isWearerFacing())

        val loosePocket = headMounted.copy(alignment = MountAlignment.POCKET_LOOSE)
        assertFalse("Pocket-held phone cannot claim wearer heading", loosePocket.isWearerFacing())

        val gpsCourse = headMounted.copy(
            alignment = MountAlignment.HEAD_ALIGNED,
            source = HeadingSource.GPS_COURSE_OVER_GROUND
        )
        // Even if on head, GPS course is trajectory not glance
        assertFalse(gpsCourse.copy(quality = HeadingQuality.UNRELIABLE).isWearerFacing())
    }

    @Test
    fun testRadarPointProjectionInvariants() {
        val point = RadarPoint(
            teammateId = "alpha-1",
            callsign = "ALPHA",
            x = 144f,
            y = 72f,
            distanceMeters = 250.0,
            relativeBearingDegrees = 90.0,
            clockHour = 3,
            isClipped = false,
            isStale = false
        )

        assertEquals(144f, point.x, 1e-4f)
        assertEquals(72f, point.y, 1e-4f)
        assertEquals(3, point.clockHour)
        assertEquals(90.0, point.relativeBearingDegrees, 1e-6)
    }

    @Test
    fun testHudStateDegradationDetection() {
        val normalState = HudState(
            mode = HudMode.HYBRID,
            statusFlag = HudStatusFlag.NORMAL,
            wearerLocation = LocationFix(
                latitude = 0.0,
                longitude = 0.0,
                accuracyMeters = 5f,
                timestampEpochMs = 1000L,
                elapsedRealtimeNs = 1000000L
            ),
            wearerHeading = HeadingSample(
                headingDegrees = 0.0,
                source = HeadingSource.PHONE_ROTATION_VECTOR,
                reference = NorthReference.TRUE_NORTH,
                quality = HeadingQuality.HIGH,
                alignment = MountAlignment.HEAD_ALIGNED,
                timestampEpochMs = 1000L,
                elapsedRealtimeNs = 1000000L
            ),
            radarPoints = emptyList(),
            radarRangeMeters = 500.0,
            formattedTextCards = listOf("[000° N] All clear"),
            generation = 1L,
            timestampMonotonicMs = 2000L
        )

        assertFalse(normalState.isHeadingDegraded())

        val degradedState = normalState.copy(wearerHeading = null)
        assertTrue(degradedState.isHeadingDegraded())
    }

    @Test
    fun testWireEnvelopeRoundtrip() {
        val payload = WireEnvelope.createLocationUpdatePayload(
            callsign = "VIPER",
            lat = 37.7749,
            lon = -122.4194,
            altMeters = 45.0,
            headingDeg = 270.0,
            accuracyMeters = 4.0f
        )

        val envelope = WireEnvelope(
            version = 1,
            messageType = WireMessageType.LOCATION_UPDATE,
            teamId = "team-recon-9",
            senderId = "operator-02",
            sequence = 42L,
            sessionEpoch = "epoch-session-uuid-1234",
            timestampEpochMs = 1700000010000L,
            authToken = "bearer-token-abc",
            payload = payload
        )

        val json = WireEnvelope.toJson(envelope)
        val deserialized = WireEnvelope.fromJson(json)

        assertEquals(envelope.version, deserialized.version)
        assertEquals(envelope.messageType, deserialized.messageType)
        assertEquals(envelope.teamId, deserialized.teamId)
        assertEquals(envelope.senderId, deserialized.senderId)
        assertEquals(envelope.sequence, deserialized.sequence)
        assertEquals(envelope.sessionEpoch, deserialized.sessionEpoch)
        assertEquals(envelope.timestampEpochMs, deserialized.timestampEpochMs)
        assertEquals(envelope.authToken, deserialized.authToken)
        assertEquals("VIPER", deserialized.payload.getString("callsign"))
        assertEquals(37.7749, deserialized.payload.getDouble("lat"), 1e-6)
        assertEquals(-122.4194, deserialized.payload.getDouble("lon"), 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testWireEnvelopeRejectsExceededSize() {
        val largeString = "A".repeat(TrackingBudgets.MAX_MESSAGE_BYTES + 100)
        val payload = JSONObject().apply { put("data", largeString) }

        val envelope = WireEnvelope(
            messageType = WireMessageType.LOCATION_UPDATE,
            teamId = "t",
            senderId = "s",
            sequence = 1L,
            sessionEpoch = "e",
            timestampEpochMs = 1000L,
            authToken = "auth",
            payload = payload
        )

        WireEnvelope.toJson(envelope)
    }
}
