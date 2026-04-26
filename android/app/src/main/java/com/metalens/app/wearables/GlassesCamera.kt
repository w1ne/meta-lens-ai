package com.metalens.app.wearables

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide ownership of the glasses' camera/stream channel.
 *
 * The Meta DAT SDK only allows one [com.meta.wearable.dat.camera.StreamSession] at a time
 * against a given device. Stream, manual photo capture, and the interval-capture service all
 * coordinate through this singleton so they can never collide on the same `startStreamSession`.
 *
 * Audio-only flows (Conversation) do NOT acquire here — they don't touch the camera.
 */
object GlassesCamera {
    private val ownerRef = AtomicReference<String?>(null)

    /**
     * Try to acquire camera ownership for [by]. Returns true if acquired (caller MUST call
     * [release] when done), false if already held by someone else.
     */
    fun tryAcquire(by: String): Boolean = ownerRef.compareAndSet(null, by)

    fun release(by: String) {
        ownerRef.compareAndSet(by, null)
    }

    val currentOwner: String?
        get() = ownerRef.get()

    val isBusy: Boolean
        get() = ownerRef.get() != null
}
