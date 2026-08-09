package com.openlauncher.app.util

import android.content.Context
import dalvik.system.DexFile
import dalvik.system.PathClassLoader
import java.lang.reflect.Modifier

/**
 * Reads the data ids out of the vendor's own code.
 *
 * There is no published list to look them up in, and the reason is structural
 * rather than an oversight: this platform ships some two and a half thousand
 * CAN boxes across roughly six hundred implementation classes, and each class
 * declares its own set. A table for one unit says nothing about another, so
 * nobody has written one down.
 *
 * But the class this unit runs is installed on it. The ids are constants in
 * that class, and constants have names — which is more than watching a value
 * move can ever give, since that yields a number and leaves the meaning to be
 * inferred. Reading them turns identification into a lookup.
 *
 * Every class in the vendor packages is enumerated and the ones that look like
 * signal definitions are dumped. Deliberately broad: guessing the naming
 * convention and filtering hard is how the right class gets skipped.
 */
object CanIdDumper {

    private val HOST_PACKAGES = listOf(
        "com.syu.ms",
        "com.syu.canbus",
        "com.syu.protocolupdate"
    )

    /**
     * Fragments that mark a class worth dumping.
     *
     * Matched against the whole class name in lower case. Kept wide because a
     * missed class is a wasted round trip while an extra one is a few lines of
     * file — the costs are not remotely symmetric.
     */
    private val INTERESTING = listOf(
        "canbus", "canbox", "cardata", "carinfo", "datacan",
        "zhtd", "landrover", "define", "constant", "protocol"
    )

    /** Long enough to hold real definitions; short enough to skip an activity. */
    private const val MAX_FIELDS_PER_CLASS = 400

    fun dump(context: Context): String = buildString {
        append("Vendor data ids\n")
        append("=".repeat(52)).append('\n')
        append("\nEach CAN box class declares its own ids, so this is read from\n")
        append("the classes installed here rather than from any published list.\n")

        for (pkg in HOST_PACKAGES) {
            val apkPath = runCatching {
                context.packageManager.getApplicationInfo(pkg, 0).sourceDir
            }.getOrNull()

            if (apkPath == null) {
                append("\n$pkg: not installed\n")
                continue
            }
            append("\n\n--- $pkg ---\n")

            val classNames = runCatching { classNamesIn(apkPath) }.getOrElse {
                append("cannot enumerate classes: ${it.javaClass.simpleName}: ${it.message}\n")
                continue
            }
            append("classes: ${classNames.size}\n")

            val candidates = classNames.filter { name ->
                val lower = name.lowercase()
                INTERESTING.any { lower.contains(it) }
            }
            append("candidates: ${candidates.size}\n")

            // Parent set to null so vendor classes resolve from their own dex
            // rather than being shadowed by anything of the same name here.
            val loader = runCatching { PathClassLoader(apkPath, null, context.classLoader) }
                .getOrElse {
                    append("loader failed: ${it.javaClass.simpleName}\n")
                    continue
                }

            for (name in candidates) {
                // Loading runs the class initialiser, which on a vendor class can
                // reach for a service that is not there. A failure here costs one
                // class, not the dump.
                val constants = runCatching { intConstants(loader, name) }.getOrNull()
                if (constants.isNullOrEmpty()) continue

                append("\n[$name]\n")
                constants.take(MAX_FIELDS_PER_CLASS).forEach { (field, value) ->
                    append("  %-40s %s\n".format(field, value))
                }
                if (constants.size > MAX_FIELDS_PER_CLASS) {
                    append("  … ${constants.size - MAX_FIELDS_PER_CLASS} more\n")
                }
            }
        }
    }

    /**
     * Every class name in the APK.
     *
     * DexFile is deprecated and is the only way to ask a dex what it contains
     * without a parser of one's own. It works on this Android version, which is
     * what matters — the alternative is guessing class names, and a guess that
     * misses looks exactly like a class that is not there.
     */
    @Suppress("DEPRECATION")
    private fun classNamesIn(apkPath: String): List<String> {
        val dex = DexFile(apkPath)
        return try {
            // An Enumeration rather than anything iterable, and DexFile is not
            // closeable on this API level — so neither `use` nor a for-loop
            // applies and it is drained by hand.
            java.util.Collections.list(dex.entries()).filterNotNull()
        } finally {
            runCatching { dex.close() }
        }
    }

    /**
     * Static integer constants, which is what a data id is.
     *
     * Only ints: the ids are passed as ints across the AIDL boundary, so a
     * String or an object cannot be one and would only pad the file.
     */
    private fun intConstants(loader: ClassLoader, name: String): List<Pair<String, Int>> {
        val cls = loader.loadClass(name)
        return cls.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) }
            .filter { it.type == Int::class.javaPrimitiveType }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.name to field.getInt(null)
                }.getOrNull()
            }
    }
}
