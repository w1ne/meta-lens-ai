package com.metalens.app.wakeword

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * One-shot capture of a single photo from the glasses via DAT SDK, written to
 * a cache file backed by a FileProvider URI ready to share via ACTION_SEND.
 *
 * Reuses the "temp stream for photo" pattern from WearablesViewModel: start a
 * StreamSession, wait for STREAMING, call capturePhoto(), close session.
 */
object GlassesPhotoCapture {
    private const val TAG = "GlassesPhotoCapture"
    private const val STREAM_TIMEOUT_MS = 8_000L

    /**
     * Capture one photo. Returns a FileProvider URI on success, or null if
     * glasses aren't connected, the session didn't start in time, or capture
     * failed. Safe to call from any coroutine.
     */
    suspend fun captureToFileProviderUri(ctx: Context): Uri? {
        val selector = AutoDeviceSelector()

        // Fail fast if no active device (AutoDeviceSelector will emit null then).
        val hasDevice = withTimeoutOrNull(500) {
            selector.activeDevice(Wearables.devices).first() != null
        } == true
        if (!hasDevice) {
            Log.i(TAG, "No active glasses; skipping photo capture")
            return null
        }

        val session: StreamSession = try {
            Wearables.startStreamSession(
                ctx,
                selector,
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "startStreamSession failed", t)
            return null
        }

        return try {
            val streaming = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                session.state.first { it == StreamSessionState.STREAMING }
            }
            if (streaming == null) {
                Log.w(TAG, "Timed out waiting for STREAMING")
                return null
            }
            val result = session.capturePhoto()
            val bitmap = result.getOrNull()?.let { toBitmap(it) }
            if (bitmap == null) {
                Log.w(TAG, "capturePhoto failed or decoded to null", result.exceptionOrNull())
                return null
            }
            writeAndShare(ctx, bitmap)
        } finally {
            try { session.close() } catch (_: Throwable) {}
        }
    }

    private fun toBitmap(data: PhotoData): Bitmap? = when (data) {
        is PhotoData.Bitmap -> data.bitmap
        is PhotoData.HEIC -> {
            val bytes = ByteArray(data.data.remaining()).also { data.data.get(it) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private fun writeAndShare(ctx: Context, bitmap: Bitmap): Uri {
        val dir = File(ctx.cacheDir, "glasses_photos").apply { mkdirs() }
        // Keep the cache small: retain only the latest handful.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }

        val file = File(dir, "glasses_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }
}
