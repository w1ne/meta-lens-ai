package com.metalens.app.intervalcapture

import android.content.Context
import android.content.Intent
import android.os.Build

object IntervalCaptureController {
    fun start(context: Context) {
        val intent = Intent(context, IntervalCaptureForegroundService::class.java).apply {
            action = IntervalCaptureForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        val intent = Intent(context, IntervalCaptureForegroundService::class.java).apply {
            action = IntervalCaptureForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }
}
