package com.openlauncher.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

/** Below this separation in luminance, two colours stop being told apart on a panel in daylight. */
private const val MIN_SEPARATION = 0.25f

/** How much of a colour's brightness is removed, or how far it is pushed toward white. */
private const val DARKEN_FACTOR = 0.35f
private const val LIGHTEN_FRACTION = 0.6f

/**
 * The accent adjusted so it stays visible as a foreground colour on [background].
 *
 * The accent is user-chosen and defaults to white, which vanishes against the
 * light theme's near-white background — the same way white text would, which is
 * why the text colour is already forced dark in day mode. Anything tinted with
 * the raw accent (home screen icons, toggles) disappeared with it.
 *
 * Scaling the channels rather than replacing the colour keeps the hue, so a
 * tinted accent stays recognisably itself and only loses brightness.
 */
fun accentFor(chosen: Color, background: Color): Color {
    if (abs(chosen.luminance() - background.luminance()) > MIN_SEPARATION) return chosen
    return if (background.luminance() > 0.5f) {
        Color(
            chosen.red * DARKEN_FACTOR,
            chosen.green * DARKEN_FACTOR,
            chosen.blue * DARKEN_FACTOR,
            chosen.alpha
        )
    } else {
        Color(
            chosen.red + (1f - chosen.red) * LIGHTEN_FRACTION,
            chosen.green + (1f - chosen.green) * LIGHTEN_FRACTION,
            chosen.blue + (1f - chosen.blue) * LIGHTEN_FRACTION,
            chosen.alpha
        )
    }
}

/**
 * Legible foreground for text or icons drawn on top of [background].
 *
 * Needed wherever the accent is used as a fill: a selected chip labelled in
 * hardcoded black works while the accent is white, and turns unreadable the
 * moment [accentFor] darkens it for day mode.
 */
fun contrastOn(background: Color): Color =
    if (background.luminance() > 0.45f) Color.Black else Color.White
