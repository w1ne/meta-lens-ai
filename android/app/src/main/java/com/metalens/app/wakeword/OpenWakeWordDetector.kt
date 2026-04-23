package com.metalens.app.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Runs the openWakeWord three-stage pipeline (melspectrogram → embedding → wake word)
 * on 16 kHz mono PCM audio. Apache-2.0 models bundled as assets.
 *
 * Pipeline reference: https://github.com/dscripka/openWakeWord
 *
 * Processing unit: 80 ms chunks (1280 samples @ 16 kHz). Each chunk:
 *   - Append audio to a rolling input buffer.
 *   - Run melspec on the accumulated audio → extract last 76×32 mel window.
 *   - Run embedding on that window → 96-dim vector.
 *   - Append to a 16-deep rolling embedding buffer.
 *   - Run wake-word classifier → score.
 *   - Return score; caller decides threshold.
 */
class OpenWakeWordDetector(context: Context) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val melSession: OrtSession
    private val embedSession: OrtSession
    private val wordSession: OrtSession

    private val audioBuffer = FloatArray(SAMPLE_RATE * AUDIO_WINDOW_SEC) // rolling 4s
    private var audioBufferCursor = 0

    private val melBuffer = Array(MEL_WINDOW_FRAMES) { FloatArray(MEL_BANDS) }
    private var melFilledFrames = 0

    private val embeddingBuffer = Array(EMBED_WINDOW) { FloatArray(EMBED_DIM) }
    private var embeddingFilledCount = 0

    init {
        melSession = loadFromAssets(context, "wakeword/melspectrogram.onnx")
        embedSession = loadFromAssets(context, "wakeword/embedding_model.onnx")
        wordSession = loadFromAssets(context, "wakeword/hey_jarvis_v0.1.onnx")
    }

    private fun loadFromAssets(context: Context, path: String): OrtSession {
        context.assets.open(path).use { input ->
            val bytes = input.readBytes()
            return env.createSession(bytes)
        }
    }

    /**
     * Feed one 80 ms chunk (1280 int16 samples) and get the latest wake-word score.
     * Returns null while the pipeline is still filling its buffers.
     */
    fun process(chunkInt16: ShortArray): Float? {
        require(chunkInt16.size == CHUNK_SAMPLES) {
            "Expected $CHUNK_SAMPLES samples, got ${chunkInt16.size}"
        }

        // int16 → float in [-1,1] and push onto rolling audio buffer
        appendAudio(chunkInt16)

        if (audioBufferCursor < SAMPLE_RATE * MEL_MIN_WINDOW_SEC) {
            return null // not enough audio yet
        }

        // Run melspec on the last MEL_RUN_WINDOW_SEC of audio to get fresh frames
        val runStart = (audioBufferCursor - SAMPLE_RATE * MEL_RUN_WINDOW_SEC).coerceAtLeast(0)
        val runLen = audioBufferCursor - runStart
        val input = FloatArray(runLen)
        System.arraycopy(audioBuffer, runStart, input, 0, runLen)
        val melFrames = runMelspec(input) // [time, 32]
        if (melFrames.isEmpty()) return null

        // Keep only the last MEL_WINDOW_FRAMES frames
        val keep = melFrames.takeLast(MEL_WINDOW_FRAMES)
        for (i in keep.indices) melBuffer[i] = keep[i]
        melFilledFrames = keep.size
        if (melFilledFrames < MEL_WINDOW_FRAMES) return null

        // Embedding: 76×32 → 96
        val embedding = runEmbedding(melBuffer)

        // Push embedding into rolling window
        for (i in 0 until EMBED_WINDOW - 1) {
            embeddingBuffer[i] = embeddingBuffer[i + 1]
        }
        embeddingBuffer[EMBED_WINDOW - 1] = embedding
        embeddingFilledCount = (embeddingFilledCount + 1).coerceAtMost(EMBED_WINDOW)
        if (embeddingFilledCount < EMBED_WINDOW) return null

        // Wake-word classifier: [1, 16, 96] → [1, 1]
        return runWakeWord(embeddingBuffer)
    }

    private fun appendAudio(chunk: ShortArray) {
        val remaining = audioBuffer.size - audioBufferCursor
        val newFloats = FloatArray(chunk.size) { chunk[it] / 32768f }

        if (newFloats.size <= remaining) {
            System.arraycopy(newFloats, 0, audioBuffer, audioBufferCursor, newFloats.size)
            audioBufferCursor += newFloats.size
        } else {
            // Shift the buffer left to make room
            val shift = newFloats.size - remaining
            System.arraycopy(audioBuffer, shift, audioBuffer, 0, audioBuffer.size - shift)
            audioBufferCursor -= shift
            System.arraycopy(newFloats, 0, audioBuffer, audioBufferCursor, newFloats.size)
            audioBufferCursor += newFloats.size
        }
    }

    private fun runMelspec(audio: FloatArray): List<FloatArray> {
        val buf = FloatBuffer.wrap(audio)
        val shape = longArrayOf(1, audio.size.toLong())
        OnnxTensor.createTensor(env, buf, shape).use { input ->
            melSession.run(mapOf("input" to input)).use { result ->
                val out = result[0].value
                // Output is [time, 1, clip_time, 32]
                @Suppress("UNCHECKED_CAST")
                val raw = out as Array<Array<Array<FloatArray>>>
                val frames = mutableListOf<FloatArray>()
                for (t in raw.indices) {
                    for (inner in raw[t][0].indices) {
                        // openWakeWord normalizes: (mel/10.0) + 2.0
                        val mel = raw[t][0][inner].copyOf()
                        for (i in mel.indices) mel[i] = (mel[i] / 10f) + 2f
                        frames += mel
                    }
                }
                return frames
            }
        }
    }

    private fun runEmbedding(mel: Array<FloatArray>): FloatArray {
        // Input shape [1, 76, 32, 1]
        val flat = FloatArray(MEL_WINDOW_FRAMES * MEL_BANDS * 1)
        for (f in 0 until MEL_WINDOW_FRAMES) {
            System.arraycopy(mel[f], 0, flat, f * MEL_BANDS, MEL_BANDS)
        }
        val buf = FloatBuffer.wrap(flat)
        val shape = longArrayOf(1, MEL_WINDOW_FRAMES.toLong(), MEL_BANDS.toLong(), 1)
        OnnxTensor.createTensor(env, buf, shape).use { input ->
            embedSession.run(mapOf("input_1" to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<Array<Array<FloatArray>>>
                return out[0][0][0].copyOf()
            }
        }
    }

    private fun runWakeWord(embeds: Array<FloatArray>): Float {
        val flat = FloatArray(EMBED_WINDOW * EMBED_DIM)
        for (i in 0 until EMBED_WINDOW) {
            System.arraycopy(embeds[i], 0, flat, i * EMBED_DIM, EMBED_DIM)
        }
        val buf = FloatBuffer.wrap(flat)
        val shape = longArrayOf(1, EMBED_WINDOW.toLong(), EMBED_DIM.toLong())
        OnnxTensor.createTensor(env, buf, shape).use { input ->
            wordSession.run(mapOf("x.1" to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<FloatArray>
                return out[0][0]
            }
        }
    }

    override fun close() {
        try {
            melSession.close(); embedSession.close(); wordSession.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing sessions", t)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 1280        // 80 ms
        const val MEL_BANDS = 32
        const val MEL_WINDOW_FRAMES = 76      // ~1.92 s of mel context (openWakeWord default)
        const val EMBED_DIM = 96
        const val EMBED_WINDOW = 16           // ~1.28 s of embeddings (openWakeWord default)
        const val AUDIO_WINDOW_SEC = 4
        const val MEL_MIN_WINDOW_SEC = 2      // wait for at least 2 s of audio before first run
        const val MEL_RUN_WINDOW_SEC = 2      // run melspec over last 2 s of audio each chunk
        private const val TAG = "OpenWakeWord"
    }
}

@Suppress("unused")
private inline fun <T : AutoCloseable?, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        try { this?.close() } catch (ignored: Throwable) {}
    }
}
