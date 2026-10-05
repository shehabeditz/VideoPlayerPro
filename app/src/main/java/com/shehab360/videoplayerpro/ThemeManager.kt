package com.shehab360.videoplayerpro

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import android.content.res.Configuration
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.DynamicColors

/** থিম (System / Light / Dark / AMOLED), Dynamic color ও ভাষা এক জায়গা থেকে */
object ThemeManager {

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2
    const val MODE_AMOLED = 3

    /** থিম/ডায়নামিক কালার বদলালে বাড়ে; MainActivity এটা দেখে নিজেকে রিক্রিয়েট করে */
    @Volatile
    var version = 0

    fun applyGlobal() {
        AppCompatDelegate.setDefaultNightMode(
            when (AppPrefs.themeMode) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK, MODE_AMOLED -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** প্রতিটি সাধারণ Activity-র onCreate এ super.onCreate এর আগে ডাকতে হবে */
    fun apply(activity: Activity) {
        if (AppPrefs.dynamicColor) DynamicColors.applyToActivityIfAvailable(activity)
        if (AppPrefs.themeMode == MODE_AMOLED) {
            activity.theme.applyStyle(R.style.ThemeOverlay_VideoPlayerPro_Amoled, true)
        }
    }

    /** tag: "" = সিস্টেম, "en", "bn" */
    fun applyLanguage(tag: String) {
        val locales = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** edge-to-edge (targetSdk 35+): status/navigation bar এর আইকনের রং থিম (Light/Dark) অনুযায়ী */
    fun styleSystemBars(activity: Activity) {
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }
}
