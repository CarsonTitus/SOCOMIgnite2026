package com.evensocom.psyopvisr.tracking.domain

/**
 * Tactical and system defaults and measurable performance/resource budgets
 * as defined in the Architecture Decision Record (ADR).
 */
object TrackingBudgets {
    // Member Limits
    const val MAX_TEAM_MEMBERS = 16
    const val MAX_MESSAGE_BYTES = 4096
    const val MAX_QUEUE_DEPTH = 32

    // Freshness & Range Defaults
    const val DEFAULT_FRESHNESS_THRESHOLD_MS = 10_000L   // 10 seconds
    const val STALE_PRUNE_THRESHOLD_MS = 60_000L          // 1 minute before dropping inactive peer
    const val DEFAULT_RADAR_RANGE_METERS = 500.0         // 500 meters
    const val MAX_RADAR_RANGE_METERS = 5000.0            // 5 km max range

    // Timestamp Skew Limits
    const val MAX_FUTURE_SKEW_MS = 5_000L                // Reject timestamps > 5s in the future
    const val MAX_PAST_SKEW_MS = 300_000L                // Reject timestamps > 5m in the past for live telemetry

    // G2 Display Constraints
    const val G2_CANVAS_WIDTH = 576
    const val G2_CANVAS_HEIGHT = 288
    const val G2_RADAR_CONTAINER_WIDTH = 288
    const val G2_RADAR_CONTAINER_HEIGHT = 144
    const val G2_IMAGE_MIN_INTERVAL_MS = 500L            // Max 2 Hz update rate
    const val G2_TEXT_MIN_INTERVAL_MS = 200L             // Max 5 Hz update rate
    const val G2_HEARTBEAT_INTERVAL_MS = 20_000L         // 20s keepalive

    // Measurable Latency Budgets
    const val MAX_LOCAL_PIPELINE_LATENCY_MS = 50L        // Sensor/math -> HUD state < 50ms
    const val MAX_RELAY_TRANSIT_LATENCY_MS = 250L        // Wire hop < 250ms on LAN/LTE
    const val MAX_BLE_DELIVERY_LATENCY_MS = 150L         // BLE write status < 150ms

    // Memory & Storage Budgets
    const val MAX_MEMORY_OVERHEAD_BYTES = 10 * 1024 * 1024 // <= 10 MB heap footprint
    const val ZERO_LOCATION_DISK_RETENTION = true          // In-memory ephemeral only, no telemetry logged to disk

    // Coordinate & Value Bounds
    const val MIN_LATITUDE = -90.0
    const val MAX_LATITUDE = 90.0
    const val MIN_LONGITUDE = -180.0
    const val MAX_LONGITUDE = 180.0
    const val MIN_ALTITUDE_METERS = -1000.0
    const val MAX_ALTITUDE_METERS = 20000.0
    const val MIN_HEADING_DEGREES = 0.0
    const val MAX_HEADING_DEGREES = 360.0
}
