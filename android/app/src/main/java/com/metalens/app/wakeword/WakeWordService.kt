package com.metalens.app.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.metalens.app.MainActivity
import com.metalens.app.R
import com.metalens.app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Continuously runs openWakeWord on the default audio source. On detection:
 *  - launches the ChatGPT app.
 *  - ChatGPTAccessibilityService then auto-taps Advanced Voice Mode.
 *  - Android routes mic + speaker to the active BT headset (glasses).
 *
 * Audio source is MediaRecorder.AudioSource.MIC — this picks up BT HFP when an
 * active headset has SCO open, otherwise the phone's built-in mic.
 */
class WakeWordService : LifecycleService() {

    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null
    private var detector: OpenWakeWordDetector? = null
    private var recorder: AudioRecord? = null
    private var triggerCooldownUntilMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (loopJob == null) startLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        stopLoop()
        super.onDestroy()
    }

    private fun startLoop() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO not granted, stopping")
            stopSelf()
            return
        }

        val cs = CoroutineScope(Dispatchers.Default)
        scope = cs
        loopJob = cs.launch {
            try {
                detector = OpenWakeWordDetector(
                    this@WakeWordService,
                    WAKE_WORD_BINDINGS.map { it.modelAsset },
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load wake-word models", t)
                stopSelf()
                return@launch
            }

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufSize = maxOf(minBuf, CHUNK_SAMPLES * 4)
            val rec = try {
                @Suppress("MissingPermission")
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "AudioRecord init failed", t)
                stopSelf()
                return@launch
            }

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized (state=${rec.state})")
                rec.release()
                stopSelf()
                return@launch
            }
            recorder = rec
            rec.startRecording()
            Log.i(TAG, "Wake-word loop started")

            val chunk = ShortArray(CHUNK_SAMPLES)
            while (isActive) {
                var readTotal = 0
                while (readTotal < CHUNK_SAMPLES) {
                    val got = rec.read(chunk, readTotal, CHUNK_SAMPLES - readTotal)
                    if (got <= 0) {
                        delay(5)
                        continue
                    }
                    readTotal += got
                }
                val scores = try {
                    detector?.process(chunk)
                } catch (t: Throwable) {
                    Log.e(TAG, "Detector error", t); null
                }
                if (scores != null) {
                    var bestIdx = -1
                    var bestScore = THRESHOLD
                    for (i in scores.indices) {
                        if (scores[i] > bestScore) { bestIdx = i; bestScore = scores[i] }
                    }
                    if (bestIdx >= 0) {
                        val now = System.currentTimeMillis()
                        if (now >= triggerCooldownUntilMs) {
                            triggerCooldownUntilMs = now + TRIGGER_COOLDOWN_MS
                            val binding = WAKE_WORD_BINDINGS[bestIdx]
                            Log.i(TAG, "Wake word fired: ${binding.word} (score=$bestScore) -> ${binding.pkg}")
                            launchPackage(binding.pkg)
                        }
                    }
                }
            }
        }
    }

    private fun stopLoop() {
        try {
            loopJob?.cancel()
            scope?.cancel()
        } catch (_: Throwable) {}
        try {
            recorder?.stop(); recorder?.release()
        } catch (_: Throwable) {}
        try {
            detector?.close()
        } catch (_: Throwable) {}
        recorder = null
        detector = null
        loopJob = null
        scope = null
    }

    private fun launchPackage(pkg: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent == null) {
            Log.e(TAG, "App not installed ($pkg)")
            return
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )
        try {
            startActivity(launchIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "Direct startActivity failed, falling back to full-screen notification", t)
            postLaunchNotification(launchIntent)
        }
    }

    private fun postLaunchNotification(target: Intent) {
        val channelId = "wakeword_launch"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(channelId) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Wake-word launches", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 1, target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wake word fired")
            .setContentText("Opening ChatGPT…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        nm.notify(99, notif)
    }

    private fun startForegroundNotification() {
        val channelId = "wakeword"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(channelId) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Wake word", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.wake_word_status_on))
            .setContentText("\"Hey Jarvis\" = ChatGPT   \"Hey Mycroft\" = Claude")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIF_ID = 42

        private const val SAMPLE_RATE = OpenWakeWordDetector.SAMPLE_RATE
        private const val CHUNK_SAMPLES = OpenWakeWordDetector.CHUNK_SAMPLES
        private const val THRESHOLD = 0.5f
        private const val TRIGGER_COOLDOWN_MS = 4000L

        data class WakeBinding(val word: String, val modelAsset: String, val pkg: String)

        // Built-in openWakeWord models -> assistant app.
        // openWakeWord doesn't ship a "Hey ChatGPT" / "Hey Claude" model, so we
        // borrow "Hey Jarvis" for ChatGPT and "Hey Mycroft" for Claude until we
        // train custom ones (~15 min each on a colab GPU).
        val WAKE_WORD_BINDINGS = listOf(
            WakeBinding("Hey Jarvis", "hey_jarvis_v0.1.onnx", "com.openai.chatgpt"),
            WakeBinding("Hey Mycroft", "hey_mycroft_v0.1.onnx", "com.anthropic.claude"),
        )

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
