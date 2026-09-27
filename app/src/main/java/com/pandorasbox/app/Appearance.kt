package com.pandorasbox.app

import android.app.Activity
import android.content.Context
import androidx.annotation.AttrRes

object Appearance {
    private const val PREFS = "appearance"
    private const val MODE = "mode"
    private const val ACCENT = "accent"

    val modes = listOf("Dark", "Light", "AMOLED")
    val accents = listOf("Blue", "Green", "Purple", "Amber", "Rose")

    fun mode(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(MODE, 0).coerceIn(modes.indices)

    fun accent(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(ACCENT, 0).coerceIn(accents.indices)

    fun set(context: Context, mode: Int, accent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(MODE, mode.coerceIn(modes.indices))
            .putInt(ACCENT, accent.coerceIn(accents.indices))
            .apply()
    }

    fun apply(activity: Activity) {
        val selectedMode = mode(activity)
        val selectedAccent = accent(activity)
        activity.setTheme(when (selectedMode) {
            1 -> R.style.Theme_PandorasBox_Light
            2 -> R.style.Theme_PandorasBox_Amoled
            else -> R.style.Theme_PandorasBox
        })
        val dark = intArrayOf(R.style.Accent_Blue, R.style.Accent_Green,
            R.style.Accent_Purple, R.style.Accent_Amber, R.style.Accent_Rose)
        val light = intArrayOf(R.style.Accent_Light_Blue, R.style.Accent_Light_Green,
            R.style.Accent_Light_Purple, R.style.Accent_Light_Amber, R.style.Accent_Light_Rose)
        activity.theme.applyStyle(if (selectedMode == 1) light[selectedAccent] else dark[selectedAccent], true)
    }

    fun color(context: Context, @AttrRes attr: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attr))
        return try { values.getColor(0, 0) } finally { values.recycle() }
    }
}
