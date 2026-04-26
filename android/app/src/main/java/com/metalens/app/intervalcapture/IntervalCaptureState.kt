package com.metalens.app.intervalcapture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class IntervalCaptureStatus(
    val running: Boolean = false,
    val capturedToday: Int = 0,
    val skippedToday: Int = 0,
    val failedToday: Int = 0,
    val lastCaptureAtMs: Long? = null,
    val lastError: String? = null,
)

/**
 * Process-wide state for the interval-capture service so the UI can observe live counters
 * without being bound to the service's lifecycle.
 */
object IntervalCaptureState {
    private val _status = MutableStateFlow(IntervalCaptureStatus())
    val status: StateFlow<IntervalCaptureStatus> = _status.asStateFlow()

    fun setRunning(running: Boolean) {
        _status.update { it.copy(running = running, lastError = if (running) null else it.lastError) }
    }

    fun recordCaptured(atMs: Long) {
        _status.update {
            it.copy(
                capturedToday = it.capturedToday + 1,
                lastCaptureAtMs = atMs,
                lastError = null,
            )
        }
    }

    fun recordSkipped(reason: String) {
        _status.update {
            it.copy(
                skippedToday = it.skippedToday + 1,
                lastError = reason,
            )
        }
    }

    fun recordFailed(reason: String) {
        _status.update {
            it.copy(
                failedToday = it.failedToday + 1,
                lastError = reason,
            )
        }
    }

    fun resetCounters() {
        _status.update { it.copy(capturedToday = 0, skippedToday = 0, failedToday = 0, lastError = null) }
    }
}
