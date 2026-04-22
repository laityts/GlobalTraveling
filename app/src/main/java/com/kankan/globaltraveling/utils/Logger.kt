package com.kankan.globaltraveling.utils

import android.util.Log
import java.io.File
import java.io.FileWriter

object Logger {
    private const val TAG = "ShadowSim"
    private const val LOG_FILE = "/data/local/tmp/shadow_log.txt"
    private var enabled = true

    fun enableLogging(enable: Boolean) {
        enabled = enable
    }

    fun d(msg: String) {
        if (enabled) Log.d(TAG, msg)
    }

    fun e(msg: String, tr: Throwable? = null) {
        if (enabled) {
            Log.e(TAG, msg, tr)
            writeToFile("ERROR: $msg\n${tr?.stackTraceToString()}")
        }
    }

    fun i(msg: String) {
        if (enabled) {
            Log.i(TAG, msg)
            writeToFile("INFO: $msg")
        }
    }

    private fun writeToFile(content: String) {
        try {
            File(LOG_FILE).appendText("${System.currentTimeMillis()} $content\n")
        } catch (_: Exception) {}
    }
}