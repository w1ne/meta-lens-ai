package com.metalens.app.intervalcapture

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BlackboxExporter {
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    sealed class Result {
        object Empty : Result()
        data class Ready(val intent: Intent, val fileCount: Int) : Result()
    }

    fun buildShareIntent(context: Context): Result {
        val ctx = context.applicationContext
        val srcDir = File(ctx.filesDir, "blackbox")
        val files = srcDir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
        if (files.isEmpty()) return Result.Empty

        val exportsDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(exportsDir, "metalens-blackbox-$stamp.zip")

        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
            files.forEach { f ->
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }

        val authority = ctx.packageName + AUTHORITY_SUFFIX
        val uri = FileProvider.getUriForFile(ctx, authority, zipFile)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MetaLens blackbox export ($stamp)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return Result.Ready(intent, files.size)
    }
}
