package com.evensocom.psyopvisr.tracking.protocol

import com.evensocom.psyopvisr.tracking.domain.TrackingBudgets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Message types supported on the Team Relay wire protocol.
 */
enum class WireMessageType {
    JOIN,
    JOIN_ACK,
    LEAVE,
    LOCATION_UPDATE,
    HEARTBEAT,
    TEAM_SNAPSHOT,
    ERROR
}

/**
 * Common wire envelope header.
 *
 * @property version Wire protocol version integer (currently 1).
 * @property messageType Type of message.
 * @property teamId Team scope identifier.
 * @property senderId Sending node ID.
 * @property sequence Monotonic message sequence number from sender.
 * @property sessionEpoch Unique session token/epoch.
 * @property timestampEpochMs Measurement/transmission timestamp in ms UTC.
 * @property authToken Bearer authentication token.
 */
data class WireEnvelope(
    val version: Int = CURRENT_VERSION,
    val messageType: WireMessageType,
    val teamId: String,
    val senderId: String,
    val sequence: Long,
    val sessionEpoch: String,
    val timestampEpochMs: Long,
    val authToken: String,
    val payload: JSONObject
) {
    companion object {
        const val CURRENT_VERSION = 1

        /**
         * Serialize wire envelope to JSON string.
         */
        fun toJson(envelope: WireEnvelope): String {
            val root = JSONObject()
            root.put("version", envelope.version)
            root.put("type", envelope.messageType.name)
            root.put("teamId", envelope.teamId)
            root.put("senderId", envelope.senderId)
            root.put("seq", envelope.sequence)
            root.put("epoch", envelope.sessionEpoch)
            root.put("ts", envelope.timestampEpochMs)
            root.put("auth", envelope.authToken)
            root.put("payload", envelope.payload)

            val serialized = root.toString()
            require(serialized.toByteArray(Charsets.UTF_8).size <= TrackingBudgets.MAX_MESSAGE_BYTES) {
                "Message payload exceeds budget limit of ${TrackingBudgets.MAX_MESSAGE_BYTES} bytes"
            }
            return serialized
        }

        /**
         * Parse wire envelope from JSON string with strict validation.
         */
        fun fromJson(jsonStr: String): WireEnvelope {
            val bytes = jsonStr.toByteArray(Charsets.UTF_8)
            require(bytes.size <= TrackingBudgets.MAX_MESSAGE_BYTES) {
                "Message exceeds maximum wire limit of ${TrackingBudgets.MAX_MESSAGE_BYTES} bytes"
            }

            val root = JSONObject(jsonStr)

            val version = root.getInt("version")
            require(version == CURRENT_VERSION) { "Unsupported wire protocol version: $version" }

            val typeStr = root.getString("type")
            val type = try {
                WireMessageType.valueOf(typeStr)
            } catch (e: Exception) {
                throw IllegalArgumentException("Unknown message type: $typeStr", e)
            }

            val teamId = root.getString("teamId")
            require(teamId.isNotBlank() && teamId.length <= 64) { "Invalid teamId" }

            val senderId = root.getString("senderId")
            require(senderId.isNotBlank() && senderId.length <= 64) { "Invalid senderId" }

            val seq = root.getLong("seq")
            require(seq >= 0L) { "Sequence number must be non-negative" }

            val epoch = root.getString("epoch")
            require(epoch.isNotBlank() && epoch.length <= 64) { "Invalid epoch" }

            val ts = root.getLong("ts")
            require(ts > 0L) { "Invalid timestamp" }

            val auth = root.getString("auth")
            require(auth.isNotBlank()) { "Missing auth token" }

            val payload = root.getJSONObject("payload")

            return WireEnvelope(
                version = version,
                messageType = type,
                teamId = teamId,
                senderId = senderId,
                sequence = seq,
                sessionEpoch = epoch,
                timestampEpochMs = ts,
                authToken = auth,
                payload = payload
            )
        }

        // ==================== Payload Helpers ====================

        fun createJoinPayload(callsign: String): JSONObject {
            return JSONObject().apply {
                put("callsign", callsign)
            }
        }

        fun createLocationUpdatePayload(
            callsign: String,
            lat: Double,
            lon: Double,
            altMeters: Double?,
            headingDeg: Double?,
            accuracyMeters: Float
        ): JSONObject {
            require(lat.isFinite() && lat in -90.0..90.0) { "Latitude out of bounds: $lat" }
            require(lon.isFinite() && lon in -180.0..180.0) { "Longitude out of bounds: $lon" }
            if (headingDeg != null) {
                require(headingDeg.isFinite() && headingDeg >= 0.0 && headingDeg < 360.0) {
                    "Heading out of bounds: $headingDeg"
                }
            }

            return JSONObject().apply {
                put("callsign", callsign)
                put("lat", lat)
                put("lon", lon)
                if (altMeters != null) put("alt", altMeters)
                if (headingDeg != null) put("heading", headingDeg)
                put("acc", accuracyMeters.toDouble())
            }
        }

        fun createErrorPayload(code: Int, message: String): JSONObject {
            return JSONObject().apply {
                put("code", code)
                put("message", message)
            }
        }
    }
}
