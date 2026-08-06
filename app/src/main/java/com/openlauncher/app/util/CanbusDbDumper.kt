package com.openlauncher.app.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.zip.ZipFile

/**
 * Pulls the vendor's CAN database out of its APK and dumps it as text.
 *
 * Data ids differ between CAN box models, which is why sweeping the service
 * blind was the only way to find anything. But the mapping is not actually
 * unknown — the protocol update package ships a SQLite database describing it,
 * and that package is installed here.
 *
 * Reading it turns guesswork into a lookup: rather than toggling a control and
 * watching which number moves, the id for a signal can simply be read off.
 */
object CanbusDbDumper {

    private const val SOURCE_PACKAGE = "com.syu.protocolupdate"

    fun dump(context: Context): String = buildString {
        append("Vendor CAN database\n")
        append("=".repeat(52)).append('\n')

        val apkPath = runCatching {
            context.packageManager.getApplicationInfo(SOURCE_PACKAGE, 0).sourceDir
        }.getOrNull()

        if (apkPath == null) {
            append("\n$SOURCE_PACKAGE is not installed\n")
            return@buildString
        }
        append("\napk: $apkPath\n")

        val extracted = runCatching { extractDatabases(context, File(apkPath)) }
            .getOrElse {
                append("extract failed: ${it.javaClass.simpleName}\n")
                return@buildString
            }

        if (extracted.isEmpty()) {
            append("no database found inside the apk\n")
            return@buildString
        }

        for (db in extracted) {
            append("\n--- ${db.name} (${db.length() / 1024} KB) ---\n")
            append(describe(db))
        }
    }

    /**
     * An APK is a zip, so its assets can be read without installing or
     * decompiling anything. Entries are copied out because SQLite opens a path,
     * not a stream.
     */
    private fun extractDatabases(context: Context, apk: File): List<File> {
        val outDir = File(context.getExternalFilesDir(null), "vendor").apply { mkdirs() }
        val found = mutableListOf<File>()

        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { entry ->
                    val name = entry.name.lowercase()
                    name.endsWith(".db") || name.endsWith(".sqlite") || name.contains("canbus")
                }
                .forEach { entry ->
                    val target = File(outDir, entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    found += target
                }
        }
        return found
    }

    /**
     * Dumps every table wholesale rather than looking for expected columns: the
     * schema is not documented either, and a guess at its shape would quietly
     * skip whatever it got wrong.
     */
    private fun describe(file: File): String = runCatching {
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
        buildString {
            db.use {
                val tables = mutableListOf<String>()
                it.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'", null
                ).use { cursor ->
                    while (cursor.moveToNext()) tables += cursor.getString(0)
                }
                append("tables: ${tables.joinToString(", ")}\n")

                for (table in tables) {
                    append("\n[$table]\n")
                    runCatching {
                        it.rawQuery("SELECT * FROM \"$table\" LIMIT $ROW_LIMIT", null).use { c ->
                            append(c.columnNames.joinToString(" | ")).append('\n')
                            while (c.moveToNext()) {
                                append((0 until c.columnCount).joinToString(" | ") { i ->
                                    runCatching { c.getString(i) }.getOrNull() ?: "?"
                                }).append('\n')
                            }
                        }
                    }.onFailure { e -> append("  unreadable: ${e.javaClass.simpleName}\n") }
                }
            }
        }
    }.getOrElse { "cannot open as SQLite: ${it.javaClass.simpleName}\n" }

    /** Enough to see the shape of a table without producing an unreadable file. */
    private const val ROW_LIMIT = 400
}
