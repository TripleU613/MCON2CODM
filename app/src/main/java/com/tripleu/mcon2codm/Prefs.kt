package com.tripleu.mcon2codm

import android.content.Context
import android.content.SharedPreferences

/** Tiny wrapper around SharedPreferences for persisted app state. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext
        .getSharedPreferences("mcon2codm", Context.MODE_PRIVATE)

    var lastSelectedPath: String?
        get() = sp.getString("last_selected", null)
        set(value) = sp.edit().putString("last_selected", value).apply()

    var adbPaired: Boolean
        get() = sp.getBoolean("adb_paired", false)
        set(value) = sp.edit().putBoolean("adb_paired", value).apply()

    companion object {
        fun of(ctx: Context): Prefs = Prefs(ctx)
    }
}
