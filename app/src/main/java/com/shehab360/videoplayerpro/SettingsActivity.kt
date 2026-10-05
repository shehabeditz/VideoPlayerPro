package com.shehab360.videoplayerpro

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.shehab360.videoplayerpro.databinding.ActivitySettingsBinding

/** থিম, ভাষা, ডাবল-ট্যাপ সময়, জেসচার, ওরিয়েন্টেশন, ডিকোডার, সাবটাইটেল ইত্যাদি */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.settingsRoot.applySystemBarsPadding()
        ThemeManager.styleSystemBars(this)
        binding.settingsToolbar.setNavigationOnClickListener { finish() }
        build()
    }

    private fun build() {
        val c = binding.settingsContainer
        c.removeAllViews()

        // ---------------- Appearance ----------------
        header(R.string.settings_appearance)
        choice(R.string.theme, resources.getStringArray(R.array.theme_options).toList(), { AppPrefs.themeMode }) {
            AppPrefs.themeMode = it
            ThemeManager.applyGlobal()
            ThemeManager.version++
            recreate()
        }
        toggle(R.string.dynamic_color, R.string.dynamic_color_summary, { AppPrefs.dynamicColor }) {
            AppPrefs.dynamicColor = it
            ThemeManager.version++
            recreate()
        }
        val langTags = listOf("", "en", "bn")
        choice(
            R.string.language,
            listOf(getString(R.string.language_system), "English", "বাংলা"),
            { langTags.indexOf(AppPrefs.language).coerceAtLeast(0) }
        ) {
            AppPrefs.language = langTags[it]
            ThemeManager.applyLanguage(langTags[it])   // সিস্টেম নিজেই Activity রিক্রিয়েট করে
        }

        // ---------------- Player ----------------
        header(R.string.settings_player)
        val tapValues = listOf(5, 10, 15, 30)
        choice(
            R.string.double_tap_time,
            tapValues.map { getString(R.string.seconds_n, it) },
            { tapValues.indexOf(AppPrefs.doubleTapSeconds).let { i -> if (i < 0) 1 else i } }
        ) { AppPrefs.doubleTapSeconds = tapValues[it] }
        toggle(R.string.gestures, R.string.gestures_summary, { AppPrefs.gesturesEnabled }) { AppPrefs.gesturesEnabled = it }
        toggle(R.string.long_press_speed, R.string.long_press_speed_summary, { AppPrefs.longPressSpeed }) { AppPrefs.longPressSpeed = it }
        choice(R.string.default_orientation, resources.getStringArray(R.array.orientation_options).toList(), { AppPrefs.orientationMode }) {
            AppPrefs.orientationMode = it
        }
        toggle(R.string.background_play, R.string.background_play_summary, { AppPrefs.backgroundPlay }) { AppPrefs.backgroundPlay = it }
        toggle(R.string.ask_resume, R.string.ask_resume_summary, { AppPrefs.askResume }) { AppPrefs.askResume = it }
        choice(R.string.decoder, resources.getStringArray(R.array.decoder_options).toList(), { AppPrefs.decoderMode }) {
            AppPrefs.decoderMode = it
            // সার্ভিস নতুন করে চালু হলে তবেই কার্যকর
            PlaybackService.instance?.shutdownIfIdle()
            android.widget.Toast.makeText(this, R.string.decoder_restart_note, android.widget.Toast.LENGTH_LONG).show()
        }

        // ---------------- Subtitles ----------------
        header(R.string.settings_subtitles)
        val sizes = listOf(75, 100, 125, 150, 200)
        choice(
            R.string.subtitle_size,
            sizes.map { "$it%" },
            { sizes.indexOf(AppPrefs.subtitleSizePercent).let { i -> if (i < 0) 1 else i } }
        ) { AppPrefs.subtitleSizePercent = sizes[it] }
        val colors = listOf(android.graphics.Color.WHITE, android.graphics.Color.YELLOW, android.graphics.Color.GREEN, android.graphics.Color.CYAN)
        choice(
            R.string.subtitle_color,
            listOf(R.string.color_white, R.string.color_yellow, R.string.color_green, R.string.color_cyan).map { getString(it) },
            { colors.indexOf(AppPrefs.subtitleColor).let { i -> if (i < 0) 0 else i } }
        ) { AppPrefs.subtitleColor = colors[it] }
        toggle(R.string.subtitle_background, null, { AppPrefs.subtitleBackground }) { AppPrefs.subtitleBackground = it }

        // ---------------- About ----------------
        header(R.string.about)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        row(getString(R.string.version), version, null)
    }

    // ------------------------------------------------------------------
    // সারি তৈরির সহায়ক
    // ------------------------------------------------------------------
    private fun header(titleRes: Int) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting_header, binding.settingsContainer, false) as TextView
        v.setText(titleRes)
        binding.settingsContainer.addView(v)
    }

    private fun row(title: String, summary: String?, onClick: (() -> Unit)?): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting, binding.settingsContainer, false)
        v.findViewById<TextView>(R.id.tvSettingTitle).text = title
        val s = v.findViewById<TextView>(R.id.tvSettingSummary)
        if (summary.isNullOrEmpty()) s.visibility = View.GONE else s.text = summary
        if (onClick != null) v.setOnClickListener { onClick() } else v.isClickable = false
        binding.settingsContainer.addView(v)
        return v
    }

    private fun toggle(titleRes: Int, summaryRes: Int?, get: () -> Boolean, set: (Boolean) -> Unit) {
        val v = row(getString(titleRes), summaryRes?.let { getString(it) }, null)
        val sw = v.findViewById<MaterialSwitch>(R.id.swSetting)
        sw.visibility = View.VISIBLE
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, checked -> set(checked) }
        v.setOnClickListener { sw.toggle() }
    }

    private fun choice(titleRes: Int, options: List<String>, current: () -> Int, onPick: (Int) -> Unit) {
        lateinit var summaryView: TextView
        val v = row(getString(titleRes), options.getOrNull(current()), null)
        summaryView = v.findViewById(R.id.tvSettingSummary)
        v.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(options.toTypedArray(), current()) { d, which ->
                    onPick(which)
                    summaryView.text = options.getOrNull(which)
                    summaryView.visibility = View.VISIBLE
                    d.dismiss()
                }
                .show()
        }
    }
}
