package com.shehab360.videoplayerpro

import android.content.Context
import android.content.SharedPreferences

/** অ্যাপের সব সেটিংস (SharedPreferences) */
object AppPrefs {

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    }

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    // ---------- Appearance ----------
    var themeMode: Int
        get() = int("theme_mode", ThemeManager.MODE_SYSTEM)
        set(v) = putInt("theme_mode", v)

    var dynamicColor: Boolean
        get() = bool("dynamic_color", false)
        set(v) = putBool("dynamic_color", v)

    var language: String
        get() = sp.getString("language", "") ?: ""
        set(v) = sp.edit().putString("language", v).apply()

    // ---------- Library (list) ----------
    /** 0 তারিখ↓, 1 তারিখ↑, 2 নাম A-Z, 3 নাম Z-A, 4 সাইজ↓, 5 সাইজ↑, 6 সময়↓, 7 সময়↑ */
    var sortIndex: Int
        get() = int("sort_index", 0)
        set(v) = putInt("sort_index", v)

    var gridView: Boolean
        get() = bool("grid_view", false)
        set(v) = putBool("grid_view", v)

    var folderView: Boolean
        get() = bool("folder_view", false)
        set(v) = putBool("folder_view", v)

    // ---------- Player ----------
    var doubleTapSeconds: Int
        get() = int("double_tap_seconds", 10)
        set(v) = putInt("double_tap_seconds", v)

    var gesturesEnabled: Boolean
        get() = bool("gestures_enabled", true)
        set(v) = putBool("gestures_enabled", v)

    var longPressSpeed: Boolean
        get() = bool("long_press_speed", true)
        set(v) = putBool("long_press_speed", v)

    /** 0 ভিডিওর আকার অনুযায়ী (auto), 1 Landscape, 2 Portrait, 3 সিস্টেম */
    var orientationMode: Int
        get() = int("orientation_mode", 0)
        set(v) = putInt("orientation_mode", v)

    var backgroundPlay: Boolean
        get() = bool("background_play", false)
        set(v) = putBool("background_play", v)

    var askResume: Boolean
        get() = bool("ask_resume", true)
        set(v) = putBool("ask_resume", v)

    /** 0 HW, 1 HW+ (ফলব্যাক), 2 SW আগে */
    var decoderMode: Int
        get() = int("decoder_mode", 0)
        set(v) = putInt("decoder_mode", v)

    /** Player.REPEAT_MODE_* (0 off, 1 one, 2 all) */
    var repeatMode: Int
        get() = int("repeat_mode", 0)
        set(v) = putInt("repeat_mode", v)

    var shuffle: Boolean
        get() = bool("shuffle", false)
        set(v) = putBool("shuffle", v)

    var notificationAsked: Boolean
        get() = bool("notification_asked", false)
        set(v) = putBool("notification_asked", v)

    // ---------- Subtitles ----------
    /** শতাংশ: 75, 100, 125, 150, 200 */
    var subtitleSizePercent: Int
        get() = int("subtitle_size", 100)
        set(v) = putInt("subtitle_size", v)

    var subtitleColor: Int
        get() = int("subtitle_color", 0xFFFFFFFF.toInt())
        set(v) = putInt("subtitle_color", v)

    var subtitleBackground: Boolean
        get() = bool("subtitle_bg", false)
        set(v) = putBool("subtitle_bg", v)

    // ---------- Equalizer ----------
    var eqEnabled: Boolean
        get() = bool("eq_enabled", false)
        set(v) = putBool("eq_enabled", v)

    var bassStrength: Int
        get() = int("bass_strength", 0)
        set(v) = putInt("bass_strength", v)

    fun eqLevels(): List<Int> =
        (sp.getString("eq_levels", "") ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }

    fun setEqLevel(band: Int, level: Int) {
        val list = eqLevels().toMutableList()
        while (list.size <= band) list.add(0)
        list[band] = level
        sp.edit().putString("eq_levels", list.joinToString(",")).apply()
    }
}
