package com.openlauncher.app.util

import android.content.Context
import dalvik.system.PathClassLoader
import java.lang.reflect.Modifier

/**
 * Reads the vendor AIDL interfaces out of an installed package, as text.
 *
 * The interfaces behind com.syu.ms/ToolkitService are undocumented, and a binder
 * transaction built against a guessed signature fails silently rather than
 * reporting a mismatch — so they have to be read rather than assumed. Exporting
 * the APK for inspection elsewhere is the obvious route, but APKs are commonly
 * rejected as attachments, and the whole file is far more than is needed.
 *
 * Loading its dex here and reflecting over it gives the same answer as
 * disassembling would: method names, parameter and return types, and the
 * transaction constants the Stub declares. All of it plain text.
 */
object VendorInterfaceDumper {

    /**
     * Interfaces to look for. The IPC package is the one that matters; the
     * others are listed because a vendor build may put them elsewhere, and a
     * name that fails to load is itself worth reporting.
     */
    private val CLASS_NAMES = listOf(
        "com.syu.ipc.IRemoteToolkit",
        "com.syu.ipc.IRemoteToolkit\$Stub",
        "com.syu.ipc.IRemoteModule",
        "com.syu.ipc.IRemoteModule\$Stub",
        "com.syu.ipc.IModuleCallback",
        "com.syu.ipc.IModuleCallback\$Stub",
        "com.syu.ipc.RemoteModule",
        "com.syu.ipc.ModuleCallback"
    )

    /** Packages that carry the stubs — clients embed them as well as the server. */
    private val HOST_PACKAGES = listOf("com.syu.ms", "com.syu.canbus", "com.syu.carradio")

    fun dump(context: Context): String = buildString {
        append("SYU interface dump\n")
        append("=".repeat(44)).append('\n')

        for (pkg in HOST_PACKAGES) {
            val apkPath = runCatching {
                context.packageManager.getApplicationInfo(pkg, 0).sourceDir
            }.getOrNull()

            if (apkPath == null) {
                append("\n$pkg: not installed\n")
                continue
            }
            append("\n$pkg\n  apk: $apkPath\n")

            // Parent set to null so the vendor classes resolve from their own dex
            // rather than being shadowed by anything of the same name here.
            val loader = runCatching { PathClassLoader(apkPath, null, context.classLoader) }
                .getOrElse {
                    append("  loader failed: ${it.javaClass.simpleName}\n")
                    continue
                }

            var found = 0
            for (name in CLASS_NAMES) {
                val cls = runCatching { loader.loadClass(name) }.getOrNull() ?: continue
                found++
                append("\n  $name\n")
                describe(cls)
            }
            if (found == 0) append("  none of the expected classes are present\n")
        }
    }

    private fun StringBuilder.describe(cls: Class<*>) {
        // Transaction codes and the descriptor live as static fields on the Stub,
        // and the codes are what a hand-built transaction has to get right.
        cls.declaredFields
            .filter { Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(null)
                }.getOrNull()
                append("    field ${field.name} = $value\n")
            }

        cls.declaredMethods
            .sortedBy { it.name }
            .forEach { method ->
                val params = method.parameterTypes.joinToString(", ") { it.simpleName }
                append("    ${method.returnType.simpleName} ${method.name}($params)\n")
            }
    }
}
