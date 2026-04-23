package com.metalens.app.wakeword

import java.util.concurrent.atomic.AtomicLong

/**
 * Shared state between WakeWordService and ChatGPTAccessibilityService.
 *
 * The accessibility service lives in a system-bound process and keeps running
 * even when the app is force-stopped or the wake-word service is off. Without
 * a gate, it would auto-tap voice mode every time ChatGPT / Claude is
 * foregrounded, including manual opens. This timestamp lets the accessibility
 * service only act when the wake-word service just fired.
 */
object WakeTrigger {
    const val WINDOW_MS = 10_000L

    private val lastFiredAt = AtomicLong(0L)

    fun markFired() {
        lastFiredAt.set(System.currentTimeMillis())
    }

    fun consumeIfRecent(): Boolean {
        val t = lastFiredAt.get()
        if (t == 0L) return false
        if (System.currentTimeMillis() - t > WINDOW_MS) return false
        // Clear so a single wake-word only taps once
        return lastFiredAt.compareAndSet(t, 0L)
    }
}
