package com.khasmek.birdwatch.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Keeps the last uncaught exception in the app's private files so "Copy diagnostics" can
 * include it after a restart. The trace is [Diagnostics.scrub]bed before it is written, so
 * nothing identifying ever reaches the file, let alone a bug report. After recording, the
 * previous handler (Android's) runs as usual, so the normal crash dialog still appears.
 */
object CrashRecorder {

    private const val TAG = "BirdWatch/Crash"
    private const val FILE = "last_crash.txt"

    fun install(context: Context, versionName: String) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                record(app, versionName, thread, throwable)
            } catch (e: Throwable) {
                Log.e(TAG, "could not record crash", e)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun record(context: Context, versionName: String, thread: Thread, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine(Diagnostics.line("crashed_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()))
            appendLine(Diagnostics.line("version", versionName))
            appendLine(Diagnostics.line("thread", thread.name))
            appendLine()
            append(Diagnostics.scrub(trace).lines().take(MAX_TRACE_LINES).joinToString("\n") { it.take(MAX_LINE_CHARS) })
        }
        // Synchronous: the process is about to die and there is no later.
        File(context.filesDir, FILE).writeText(text)
    }

    /** The scrubbed record of the last crash, or null if none was recorded. */
    fun lastCrash(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    private const val MAX_TRACE_LINES = 60
    private const val MAX_LINE_CHARS = 400
}
