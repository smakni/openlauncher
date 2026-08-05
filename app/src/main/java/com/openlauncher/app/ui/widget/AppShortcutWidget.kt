package com.openlauncher.app.ui.widget

import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.openlauncher.app.data.AppTileConfig

/**
 * A grid cell that launches one app.
 *
 * The grid keys widgets by a unique id and allows one instance of each, so
 * several shortcuts means several registered ids rather than one widget holding
 * a list. The widget header shows the assigned app's name, which is what makes
 * two of these tell each other apart on the home screen.
 *
 * Tapping an unassigned widget picks the app, tapping an assigned one launches
 * it, and a long press reassigns — matching the sidebar shortcut gestures.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppShortcutWidget(
    tile: AppTileConfig,
    accent: Color,
    installedIconFor: (String) -> Drawable?,
    onLaunch: (String) -> Unit,
    onAssign: () -> Unit,
    isDayMode: Boolean = false,
    isEditing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val hintColor = if (isDayMode) Color(0xFF999999) else Color(0xFF666666)
    val textColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground

    Box(
        modifier = modifier
            // Taps are ignored in edit mode so dragging the widget across the
            // grid cannot launch the app when the finger lifts.
            .combinedClickable(
                enabled = !isEditing,
                onClick = {
                    if (tile.packageName.isEmpty()) onAssign() else onLaunch(tile.packageName)
                },
                onLongClick = { onAssign() }
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            tile.packageName.isEmpty() -> Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.AddCircleOutline,
                    contentDescription = "Choose app",
                    tint = hintColor,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text("TAP TO ASSIGN", color = hintColor, fontSize = 9.sp, letterSpacing = 1.sp)
            }

            else -> AppIcon(
                tile             = tile,
                installedIconFor = installedIconFor,
                accent           = accent,
                textColor        = textColor,
                hintColor        = hintColor
            )
        }
    }
}

@Composable
private fun AppIcon(
    tile: AppTileConfig,
    installedIconFor: (String) -> Drawable?,
    accent: Color,
    textColor: Color,
    hintColor: Color
) {
    val drawable = installedIconFor(tile.packageName)
    // Remembered against the drawable: an un-remembered toBitmap allocates a new
    // bitmap on every frame the widget recomposes.
    val bitmap = remember(drawable) {
        drawable?.let { runCatching { it.toBitmap(96, 96) }.getOrNull() }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (bitmap != null) {
            Image(
                painter            = BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = tile.label,
                modifier           = Modifier.size(48.dp)
            )
        } else {
            // Assigned to an app that is no longer installed. The stored label
            // still names it, so say so rather than showing an empty cell.
            Icon(
                Icons.Default.HelpOutline,
                contentDescription = null,
                tint = hintColor,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text("NOT INSTALLED", color = hintColor, fontSize = 8.sp, letterSpacing = 1.sp)
        }

        if (tile.label.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text      = tile.label,
                color     = textColor,
                fontSize  = 10.sp,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
