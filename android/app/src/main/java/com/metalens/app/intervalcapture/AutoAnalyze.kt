package com.metalens.app.intervalcapture

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.metalens.app.memory.MemoryClient
import com.metalens.app.pictureanalysis.OpenAIImageAnalysisService
import com.metalens.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs the cheap "describe what's in the photo" analysis for blackbox captures
 * and persists the result to disk + (optionally) the OpenAI Vector Store.
 *
 * Designed for the lifelog use case — the goal is a one-line caption suitable
 * for later semantic recall, not a verbose description.
 *
 * Best-effort: any failure is logged and swallowed so the capture loop keeps
 * running.
 */
object AutoAnalyze {
    private const val TAG = "AutoAnalyze"

    private const val LIFELOG_PROMPT =
        "Describe what is happening in this image in one short sentence. " +
            "Include the user's apparent activity, the setting, and any visible text on screens. " +
            "Be specific and factual — this is going into a personal lifelog."

    private val analysis = OpenAIImageAnalysisService()
    private val memory = MemoryClient()

    suspend fun run(context: Context, photoFile: File, atMs: Long) {
        if (!AppSettings.getIntervalAutoAnalyzeEnabled(context)) {
            // Still write a caption-less journal entry so the photo is logged.
            CaptureJournal.append(context, atMs, photoFile, caption = null)
            return
        }

        val apiKey = AppSettings.getOpenAiApiKey(context).trim()
        if (apiKey.isBlank()) {
            CaptureJournal.append(context, atMs, photoFile, caption = null)
            return
        }

        val caption =
            withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                        ?: error("decodeFile returned null")
                    val result = analysis.analyzeImage(
                        apiKey = apiKey,
                        model = "gpt-4o-mini",
                        prompt = LIFELOG_PROMPT,
                        bitmap = bitmap,
                    )
                    result.getOrThrow().trim()
                }.onFailure {
                    Log.w(TAG, "vision analysis failed: ${it.message}")
                }.getOrNull()
            }

        CaptureJournal.append(context, atMs, photoFile, caption)
        IntervalCaptureState.recordCaption(caption)

        if (caption != null && AppSettings.getMemoryEnabled(context)) {
            ensureVectorStoreAndUpload(context, apiKey, photoFile.name, atMs, caption)
        }
    }

    private suspend fun ensureVectorStoreAndUpload(
        context: Context,
        apiKey: String,
        photoFileName: String,
        atMs: Long,
        caption: String,
    ) {
        withContext(Dispatchers.IO) {
            var vsId = AppSettings.getMemoryVectorStoreId(context)
            if (vsId.isBlank()) {
                memory.createVectorStore(apiKey, "blackbox-lifelog")
                    .onSuccess { id ->
                        AppSettings.setMemoryVectorStoreId(context, id)
                        vsId = id
                    }
                    .onFailure {
                        Log.w(TAG, "vector store create failed: ${it.message}")
                        return@withContext
                    }
            }

            val payload = buildString {
                append("{\"ts\":")
                append("\"").append(isoTimestamp(atMs)).append("\"")
                append(",\"caption\":")
                append(quote(caption))
                append(",\"photo\":")
                append(quote(photoFileName))
                append("}\n")
            }
            val filename = "blackbox-${atMs}.jsonl"

            memory.uploadCaptionFile(apiKey, vsId, filename, payload)
                .onFailure { Log.w(TAG, "memory upload failed: ${it.message}") }
        }
    }

    private fun isoTimestamp(atMs: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return fmt.format(java.util.Date(atMs))
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
