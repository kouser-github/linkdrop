package com.sentrocx.app

import android.app.Application
import com.google.android.material.color.DynamicColors

class SentRocxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this) // wallpaper colours on Android 12+
    }
}
