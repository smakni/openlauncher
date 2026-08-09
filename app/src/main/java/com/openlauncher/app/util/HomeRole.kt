package com.openlauncher.app.util

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Reports and requests the home role.
 *
 * Which application the system actually treats as the home screen has been
 * assumed rather than checked, and the manifest was misdeclaring the role for
 * long enough that the assumption was wrong. Resolving the intent says who wins
 * today, which is the only way to tell "the vendor app is the home screen" apart
 * from "the vendor app was launched over ours".
 *
 * The distinction matters because only the first is fixable from here. A head
 * unit commonly starts its own screen on ignition regardless of the home app,
 * and nothing short of system privileges stops that.
 */
object HomeRole {

    /** The package the system would open on a press of home, if any. */
    fun currentHomePackage(context: Context): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    }.getOrNull()

    fun isHeld(context: Context): Boolean =
        currentHomePackage(context) == context.packageName

    /**
     * Opens wherever the choice can be made.
     *
     * The role request dialog is the direct route and exists from Android 10,
     * which this unit is. It is not always honoured — a vendor ROM can decline
     * to offer the role at all — so the home settings screen is the fallback,
     * and the general settings screen the fallback to that. A button that opens
     * nothing would read as the launcher being broken.
     */
    fun requestOrOpenChooser(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val requested = runCatching {
                val roles = context.getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    context.startActivity(
                        roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                } else false
            }.getOrDefault(false)
            if (requested) return
        }

        val opened = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        if (opened) return

        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
