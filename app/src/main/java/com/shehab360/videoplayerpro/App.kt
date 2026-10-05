package com.shehab360.videoplayerpro

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        PlaybackStore.init(this)
        ThemeManager.applyGlobal()
        ThemeManager.applyLanguage(AppPrefs.language)
    }
}
