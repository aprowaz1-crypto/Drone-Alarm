package com.aegisf6.app.util

import android.util.Log
import java.util.Collections

object DiagnosticsLog {
    private const val TAG = "AegisDiagnostics"
    private val onceKeys = Collections.synchronizedSet(mutableSetOf<String>())

    fun bug(message: String) {
        Log.e(TAG, "BUG: $message")
    }

    fun bugOnce(key: String, message: String) {
        if (onceKeys.add("bug:$key")) {
            bug(message)
        }
    }

    fun toFix(message: String) {
        Log.w(TAG, "TO_FIX: $message")
    }

    fun toFixOnce(key: String, message: String) {
        if (onceKeys.add("tofix:$key")) {
            toFix(message)
        }
    }

    fun missing(message: String) {
        Log.i(TAG, "MISSING: $message")
    }

    fun missingOnce(key: String, message: String) {
        if (onceKeys.add("missing:$key")) {
            missing(message)
        }
    }
}