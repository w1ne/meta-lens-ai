package com.metalens.app.intervalcapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.metalens.app.R
import com.metalens.app.settings.AppSettings
import com.metalens.app.wearables.GlassesCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IntervalCaptureForegroundService : Service() {
    companion object {
        private const val TAG = "IntervalCapture"
        private const val CHANNEL_ID = "interval_capture"
        private const val CHANNEL_NAME = "Auto-capture (Blackbox)"
        private const val NOTIFICATION_ID = 43

        const val ACTION_START = "com.metalens.app.intervalcapture.action.START"
        const val ACTION_STOP = "com.metalens.app.intervalcapture.action.STOP"

        private const val OWNER_KEY = "interval_capture"
        private const val STREAM_WARMUP_TIMEOUT_MS = 8_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val deviceSelector = AutoDeviceSelector()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCaptureLoop()
            ACTION_STOP -> stopCaptureLoop()
            else -> { /* no-op */ }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCaptureLoop(stopSelf = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCaptureLoop() {
        if (loopJob?.isActive == true) return

        startInForegroundIfNeeded()
        ensureWakeLock()
        IntervalCaptureState.setRunning(true)

        loopJob = serviceScope.launch {
            while (isActive) {
                val intervalMs = (AppSettings.getIntervalCaptureSeconds(applicationContext) * 1000L)
                    .coerceAtLeast(60_000L)

                val tickStart = System.currentTimeMillis()
                runOneTick()

                val elapsed = System.currentTimeMillis() - tickStart
                val remaining = (intervalMs - elapsed).coerceAtLeast(1_000L)
                delay(remaining)
            }
        }
    }

    private fun stopCaptureLoop(stopSelf: Boolean = true) {
        loopJob?.cancel()
        loopJob = null

        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
            // ignore
        }
        wakeLock = null

        IntervalCaptureState.setRunning(false)

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
            // ignore
        }

        if (stopSelf) stopSelf()
    }

    private suspend fun runOneTick() {
        // Skip if camera is busy (stream / manual capture in progress).
        if (!GlassesCamera.tryAcquire(OWNER_KEY)) {
            val owner = GlassesCamera.currentOwner ?: "unknown"
            Log.d(TAG, "skip tick: camera busy ($owner)")
            IntervalCaptureState.recordSkipped("camera busy ($owner)")
            return
        }

        try {
            val ctx = applicationContext
            val quality = AppSettings.getCameraVideoQuality(ctx)

            val session =
                try {
                    Wearables.startStreamSession(
                        ctx,
                        deviceSelector,
                        StreamConfiguration(videoQuality = quality, 24),
                    )
                } catch (t: Throwable) {
                    val msg = t.message ?: "startStreamSession failed"
                    Log.w(TAG, "skip tick: $msg")
                    // No active device or transient failure -> count as skipped, not failed.
                    IntervalCaptureState.recordSkipped(msg)
                    return
                }

            try {
                val streamingState =
                    withTimeoutOrNull(STREAM_WARMUP_TIMEOUT_MS) {
                        session.state.first { it == StreamSessionState.STREAMING }
                    }
                if (streamingState == null) {
                    IntervalCaptureState.recordSkipped("stream warmup timeout")
                    return
                }

                val result = session.capturePhoto()
                val bytes = result.fold(
                    onSuccess = { encodeToJpeg(it) },
                    onFailure = { err ->
                        IntervalCaptureState.recordFailed(err.message ?: err.javaClass.simpleName)
                        return
                    },
                )

                if (bytes == null) {
                    IntervalCaptureState.recordFailed("decode failed")
                    return
                }

                val now = System.currentTimeMillis()
                val photoFile = writeJpeg(bytes, now)
                IntervalCaptureState.recordCaptured(now)

                // Vision analysis + memory upload run after we've already counted the
                // capture as successful. They're best-effort and must not affect the
                // running counters or block subsequent ticks.
                runCatching { AutoAnalyze.run(applicationContext, photoFile, now) }
            } finally {
                runCatching { session.close() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "tick failed", t)
            IntervalCaptureState.recordFailed(t.message ?: t.javaClass.simpleName)
        } finally {
            GlassesCamera.release(OWNER_KEY)
        }
    }

    private fun encodeToJpeg(photoData: PhotoData): ByteArray? {
        return when (photoData) {
            is PhotoData.HEIC -> {
                val bb = photoData.data
                val arr = ByteArray(bb.remaining())
                bb.get(arr)
                arr
            }
            is PhotoData.Bitmap -> {
                val out = java.io.ByteArrayOutputStream()
                val ok = photoData.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                if (ok) out.toByteArray() else null
            }
        }
    }

    private fun writeJpeg(bytes: ByteArray, atMs: Long): File {
        val dir = File(applicationContext.filesDir, "blackbox").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(atMs)) + ".jpg"
        val target = File(dir, name)
        FileOutputStream(target).use { it.write(bytes) }
        return target
    }

    private fun startInForegroundIfNeeded() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MetaLensAI:IntervalCapture").apply {
                setReferenceCounted(false)
                acquire(8 * 60 * 60 * 1000L) // 8h safety; we release on stop
            }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Auto-captures photos from your glasses on a timer"
            }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.smart_glasses_icon)
            .setContentTitle("Meta Lens AI")
            .setContentText("Auto-capture running…")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
