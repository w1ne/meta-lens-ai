package com.metalens.app.intervalcapture

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only daily JSONL log of capture events.
 *
 * Path: filesDir/blackbox/journal-YYYYMMDD.jsonl
 * Each line: {"ts": "<iso-8601>", "file": "<filename>", "caption": "<text-or-null>"}
 *
 * Survives backend changes (vector store, summarization model) — the raw stream of
 * what happened today is always re-readable from disk.
 */
object CaptureJournal {
    private val DAY_FMT = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    fun append(context: Context, atMs: Long, photoFile: File, caption: String?) {
        val day = DAY_FMT.format(Date(atMs))
        val dir = File(context.applicationContext.filesDir, "blackbox").apply { mkdirs() }
        val journal = File(dir, "journal-$day.jsonl")

        val entry = JSONObject()
            .put("ts", ISO_FMT.format(Date(atMs)))
            .put("file", photoFile.name)
            .apply { if (!caption.isNullOrBlank()) put("caption", caption) }
            .toString()

        FileWriter(journal, true).use { it.appendLine(entry) }
    }
}
