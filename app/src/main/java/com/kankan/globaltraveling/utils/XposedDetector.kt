package com.kankan.globaltraveling.utils

import android.content.Context
import android.content.SharedPreferences
import java.io.File

object XposedDetector {
    private const val MARKER_FILE = "/data/local/tmp/xposed_active"
    private const val PREFS_NAME = "shadow_prefs"
    private const val KEY_XPOSED_ACTIVE = "xposed_active"

    fun isXposedActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 检查缓存标记
        if (prefs.contains(KEY_XPOSED_ACTIVE)) {
            return prefs.getBoolean(KEY_XPOSED_ACTIVE, false)
        }
        // 读取 Xposed 写入的标记文件
        val active = try {
            File(MARKER_FILE).exists() && File(MARKER_FILE).canRead() &&
                    File(MARKER_FILE).readText().trim() == "1"
        } catch (e: Exception) {
            false
        }
        prefs.edit().putBoolean(KEY_XPOSED_ACTIVE, active).apply()
        return active
    }
}