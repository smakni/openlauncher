package com.openlauncher.app.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash where it can be read without a cable.
 *
 * There is no usable ADB on this unit, so every fault so far has been diagnosed
 * by reasoning backwards from a description — which has been wrong as often as
 * right, and costs a build and a drive each time. A launcher that records why it
 * died turns that into reading a file.
 *
 * The previous handler is always called afterwards. Swallowing the exception
 * would leave the process alive in whatever state caused the crash, which is
 * worse than the crash.
 */
object CrashLog {

    private const val FILE_NAME = "crash.txt"
    private const val STEP_FILE = "last-step.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Nothing here may throw: an exception inside the handler replaces a
            // legible crash with an illegible one.
            runCatching { write(app, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Records that a risky step is about to be taken.
     *
     * A native fault kills the process below the level any handler can reach, so
     * it leaves no stack trace at all — which is what "it crashed and there is no
     * report" means. Writing the step before taking it means the file names
     * whatever was in progress when the process died, which is the next best
     * thing to a trace and the only thing available without a cable.
     */
    fun step(context: Context, name: String) {
        runCatching {
            val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            File(dir(context), STEP_FILE).writeText("$stamp  $name")
        }
    }

    fun lastStep(context: Context): String? =
        runCatching { File(dir(context), STEP_FILE).takeIf { it.isFile }?.readText() }.getOrNull()

    private fun dir(context: Context): File =
        File(context.getExternalFilesDir(null), "vendor").apply { mkdirs() }

    fun file(context: Context): File =
        File(dir(context), FILE_NAME)

    fun read(context: Context): String? =
        runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun write(context: Context, threadName: String, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()

        file(context).writeText(
            buildString {
                append("Crash\n")
                append("=".repeat(52)).append('\n')
                append("when    : ").append(stamp).append('\n')
                append("version : ").append(version).append('\n')
                append("thread  : ").append(threadName).append('\n')
                append("device  : ").append(Build.MANUFACTURER).append(' ')
                    .append(Build.MODEL).append(" · Android ")
                    .append(Build.VERSION.RELEASE).append('\n')
                append('\n')
                append(stack)
            }
        )
    }
}
