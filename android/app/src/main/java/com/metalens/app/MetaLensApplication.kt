package com.metalens.app

import android.app.Application
import com.metalens.app.intervalcapture.IntervalCaptureController
import com.metalens.app.settings.AppSettings

class MetaLensApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        // Restore auto-capture service if the user left it enabled previously.
        if (AppSettings.getIntervalCaptureEnabled(this)) {
            IntervalCaptureController.start(this)
        }
    }

    companion object {
        lateinit var instance: MetaLensApplication
            private set
    }
}

