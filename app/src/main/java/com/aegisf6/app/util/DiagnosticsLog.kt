package com.aegisf6.app.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object DiagnosticsLog {
    private const val TAG = "AegisDiagnostics"
    private const val MAX_BUFFER = 160
    private val onceKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val ringBuffer = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun recent(limit: Int = 8): List<String> {
        return synchronized(ringBuffer) {
            ringBuffer.takeLast(limit)
        }
    }

    private fun append(level: String, message: String) {
        val line = "${timeFormat.format(Date())} $level: $message"
        synchronized(ringBuffer) {
            ringBuffer.addLast(line)
            while (ringBuffer.size > MAX_BUFFER) {
                ringBuffer.removeFirst()
            }
        }
    }

    fun bug(message: String) {
        append("BUG", message)
        Log.e(TAG, "BUG: $message")
    }

    fun bugOnce(key: String, message: String) {
        if (onceKeys.add("bug:$key")) {
            bug(message)
        }
    }

    fun toFix(message: String) {
        append("TO_FIX", message)
        Log.w(TAG, "TO_FIX: $message")
    }

    fun toFixOnce(key: String, message: String) {
        if (onceKeys.add("tofix:$key")) {
            toFix(message)
        }
    }

    fun missing(message: String) {
        append("MISSING", message)
        Log.i(TAG, "MISSING: $message")
    }

    fun missingOnce(key: String, message: String) {
        if (onceKeys.add("missing:$key")) {
            missing(message)
        }
    }

    fun notOk(message: String) {
        append("NOT_OK", message)
        Log.w(TAG, "NOT_OK: $message")
    }

    fun notOkOnce(key: String, message: String) {
        if (onceKeys.add("notok:$key")) {
            notOk(message)
        }
    }

    fun notAdded(message: String) {
        append("NOT_ADDED", message)
        Log.i(TAG, "NOT_ADDED: $message")
    }

    fun notAddedOnce(key: String, message: String) {
        if (onceKeys.add("notadded:$key")) {
            notAdded(message)
        }
    }
}