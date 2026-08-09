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

                // Asked for first and without a limit. The row cap keeps the file
                // readable, and it truncated the largest table at four hundred of
                // its five thousand rows — with every entry for this unit's
                // decoder sitting past that. The dump answered every question
                // except the one it was run to answer.
                if (tables.contains("canbus_canbox")) {
                    // Two queries, each guarded on its own. The first needs no
                    // joins at all, so a wrong column name elsewhere cannot cost
                    // it — which is exactly what happened to its predecessor.
                    emitQuery(this, it, "ZHTD — this unit's decoder, every row", ZHTD_QUERY)
                    emitQuery(this, it, "LAND ROVER — every maker", LANDROVER_QUERY)
                }

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

    /**
     * Runs one query and writes its rows, or why it could not.
     *
     * The reason is written in full. Reporting only the exception type turned
     * "no such column" into "SQLiteException", which named the failure without
     * saying anything about it and cost a round trip to rediscover.
     */
    private fun emitQuery(
        out: StringBuilder,
        db: SQLiteDatabase,
        title: String,
        sql: String
    ) {
        out.append("\n[").append(title).append("]\n")
        runCatching {
            db.rawQuery(sql, null).use { c ->
                out.append(c.columnNames.joinToString(" | ")).append('\n')
                var rows = 0
                while (c.moveToNext()) {
                    rows++
                    out.append((0 until c.columnCount).joinToString(" | ") { i ->
                        runCatching { c.getString(i) }.getOrNull() ?: "?"
                    }).append('\n')
                }
                if (rows == 0) out.append("  none in this database\n")
            }
        }.onFailure { e ->
            out.append("  unreadable: ${e.javaClass.simpleName}: ${e.message}\n")
        }
    }

    /**
     * Every protocol belonging to this unit's decoder.
     *
     * Deliberately join-free: the ids alone are enough to find the rows, and
     * nothing here can break on a column named differently from its neighbours.
     */
    private const val ZHTD_QUERY = """
        SELECT id, canbus_canbox_en, id_value, disp, name,
               company_id, carset_id, cartype_id
        FROM canbus_canbox
        WHERE company_id = 35
        ORDER BY carset_id, cartype_id, id_value
    """

    /**
     * Every Land Rover protocol, joined to the names that make it legible.
     *
     * The English columns are used because the Chinese ones arrive mis-encoded
     * through this route, and the decoder maker is the answer being looked for —
     * a protocol number alone says nothing about which box is fitted.
     */
    private const val LANDROVER_QUERY = """
        SELECT b.id_value, b.canbus_canbox_en AS variant,
               co.canbus_company_name_en AS maker,
               t.canbus_cartype_en AS model,
               b.name AS note
        FROM canbus_canbox b
        LEFT JOIN canbus_company co ON co.id = b.company_id
        LEFT JOIN canbus_cartype t  ON t.id  = b.cartype_id
        LEFT JOIN canbus_carset  cs ON cs.id = b.carset_id
        WHERE cs.canbus_carset_name_en LIKE '%androver%'
           OR t.canbus_cartype_en LIKE '%androver%'
           OR t.canbus_cartype_en LIKE '%reelander%'
           OR t.canbus_cartype_en = 'Range'
        ORDER BY b.id_value
    """

    /** Enough to see the shape of a table without producing an unreadable file. */
    private const val ROW_LIMIT = 400
}
