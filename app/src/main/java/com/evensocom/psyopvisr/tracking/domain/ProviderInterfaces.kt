package com.evensocom.psyopvisr.tracking.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for providing local wearer location fixes.
 */
interface ILocationProvider {
    /**
     * Observable stream of current location fixes.
     * Null if no fix has been acquired yet or location is disabled.
     */
    val locationFlow: StateFlow<LocationFix?>

    /**
     * Start location updates with specified minimum interval.
     */
    fun start(intervalMs: Long = 1000L)

    /**
     * Stop location updates and release hardware resources.
     */
    fun stop()
}

/**
 * Interface for providing local wearer orientation and heading.
 */
interface IHeadingProvider {
    /**
     * Observable stream of current heading samples.
     * Null if heading sensors are unavailable or uncalibrated.
     */
    val headingFlow: StateFlow<HeadingSample?>

    /**
     * Set mounting alignment (e.g. HEAD_ALIGNED, CHEST_TORSO_ALIGNED, POCKET_LOOSE).
     */
    fun setMountAlignment(alignment: MountAlignment)

    /**
     * Start orientation tracking.
     */
    fun start()

    /**
     * Stop orientation tracking.
     */
    fun stop()
}

/**
 * Interface for synchronization and management of remote teammate states.
 */
interface ITeammateSyncEngine {
    /**
     * Current snapshot of team state.
     */
    val teamSnapshotFlow: StateFlow<TeamSnapshot>

    /**
     * Update current wearer fix to be broadcast to the team.
     */
    suspend fun publishLocalFix(fix: LocationFix, heading: HeadingSample?)

    /**
     * Connect to team network/relay.
     */
    suspend fun connect(teamId: String, callsign: String, authToken: String)

    /**
     * Disconnect from team network/relay.
     */
    suspend fun disconnect()
}

/**
 * Interface for adapting HUD states to G2 display protocol.
 */
interface IG2DisplayAdapter {
    /**
     * Submit a state update for rendering on the G2.
     * Implementations must handle MTU bounds, latest-frame coalescing,
     * and discard stale generations.
     */
    suspend fun render(state: HudState): AdapterResult

    /**
     * Reset display state (e.g. on reconnect or mode switch).
     */
    suspend fun reset()
}
