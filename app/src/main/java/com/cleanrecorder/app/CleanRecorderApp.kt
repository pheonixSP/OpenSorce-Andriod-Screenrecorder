package com.cleanrecorder.app

import android.app.Application

class CleanRecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }
}
