package com.evensocom.psyopvisr.vision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton bus that bridges inference results → phone UI.
 *
 * [frame]      — latest detection geometry for the active scan mode; null when idle.
 * [hudContent] — the full string most recently sent to the G2 glasses HUD, mirrored
 *                here so the phone screen can show exactly what the glasses see.
 *
 * Both are [StateFlow]s so [MainActivity] can collect them on the main thread
 * with Lifecycle-aware coroutines.
 */
object VisualizationBus {

    private val _frame = MutableStateFlow<VisualizationFrame?>(null)
    val frame: StateFlow<VisualizationFrame?> = _frame.asStateFlow()

    private val _hudContent = MutableStateFlow("")
    val hudContent: StateFlow<String> = _hudContent.asStateFlow()

    fun postFrame(f: VisualizationFrame?) { _frame.value = f }
    fun postHud(content: String) { _hudContent.value = content }
}
