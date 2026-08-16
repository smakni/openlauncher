package com.openlauncher.app.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.openlauncher.app.model.FuelType

enum class ClockStyle { DIGITAL, ANALOG }
enum class UnitSystem { METRIC, IMPERIAL }
enum class AppFont { SYSTEM, JETBRAINS_MONO, SOURCE_CODE_PRO }
enum class DayNightMode { DARK, LIGHT, AUTO, SYSTEM }
enum class SidebarPosition { LEFT, RIGHT, BOTTOM }
enum class GradientDirection { TOP_TO_BOTTOM, LEFT_TO_RIGHT, DIAGONAL, RADIAL }

enum class DefaultShortcutIcon {
    NONE,
    // Navigation & vehicle
    RADIO, CAMERA, PHONE, MAP, NAVIGATION, CAR, GAS_STATION, DASHBOARD, CARPLAY,
    // Files
    FILE_MANAGER,
    // Audio & media
    MUSIC, SPEAKER, HEADSET, EQUALIZER, VOLUME_UP,
    // Connectivity
    BLUETOOTH, WIFI,
    // Lighting & climate
    LIGHTBULB, BRIGHTNESS, AC, THERMOSTAT,
    // General utility
    TV, VIDEOCAM, STAR, MESSAGE, TIMER, LOCK, SETTINGS, FAVORITE,
    // Web / location
    GLOBE
}

data class SoundPadConfig(
    val label: String,
    val audioUri: String = "",
    val synthType: String = "BEEP"
)

fun defaultSoundboardPads() = listOf(
    SoundPadConfig("mario_jump",   synthType = "mario_jump"),
    SoundPadConfig("mario_coin",   synthType = "mario_coin"),
    SoundPadConfig("boom",         synthType = "boom"),
    SoundPadConfig("loud_fart",    synthType = "loud_fart"),
    SoundPadConfig("+",            synthType = ""),
    SoundPadConfig("+",            synthType = "")
)

data class ShortcutConfig(
    val packageName: String = "",
    val label: String = "",
    val isDefault: Boolean = false,
    val defaultIcon: DefaultShortcutIcon = DefaultShortcutIcon.NONE,
    // null = native app icon; non-null = override with this vector icon
    val customIconOverride: DefaultShortcutIcon? = null
)

// Sized for the ultrawide panels these units ship: 1920x660dp of usable space
// once the system bar is taken out leaves roughly 225x205dp per cell, which is
// close to square. A 3x2 grid on the same panel produces 620x310dp cells — six
// tiles so large the screen reads as mostly empty.
const val GRID_COLS = 8
const val GRID_ROWS = 3

data class WidgetConfig(
    val id: String,          // "CLOCK" | "WEATHER" | "TELEMETRY" | "NOW_PLAYING"
    val gridX: Int,          // column 0..(GRID_COLS-1)
    val gridY: Int,          // row    0..(GRID_ROWS-1)
    val spanX: Int = 1,
    val spanY: Int = 1,
    val enabled: Boolean = true
)

data class AppSettings(
    val vehicleName: String = "MY CAR",
    val accentColor: Int = Color.White.toArgb(),
    val backgroundColor: Int = Color.Black.toArgb(),
    val fontColor: Int = Color.White.toArgb(),
    val wallpaperUri: String = "",
    val fontBold: Boolean = false,
    val textScale: Float = 1.2f,
    val uiScale: Float = 1.0f,
    val clockStyle: ClockStyle = ClockStyle.DIGITAL,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val appFont: AppFont = AppFont.JETBRAINS_MONO,
    val showWeather: Boolean = true,
    val showClock: Boolean = true,
    val showTelemetry: Boolean = true,
    val showNowPlaying: Boolean = true,
    val shortcuts: List<ShortcutConfig> = defaultShortcuts(),
    val widgetLayout: List<WidgetConfig> = defaultWidgetLayout(),
    val carPlayPackage: String = "",
    val androidAutoPackage: String = "",
    val useGradient: Boolean = false,
    val gradientEndColor: Int = Color.Black.toArgb(),
    val wallpaperDim: Float = 0.55f,
    val sidebarPosition: SidebarPosition = SidebarPosition.LEFT,
    val bottomBarShortcutsRight: Boolean = false,
    val showAltimeter: Boolean = false,
    val showSpeedometer: Boolean = false,
    val dayNightMode: DayNightMode = DayNightMode.DARK,
    val showPip: Boolean = false,
    val pipAppPackage: String = "",
    // Head unit's radio app — mirrored & controlled via its MediaSession
    val radioPackage: String = "",
    val onboardingCompleted: Boolean = false,
    val showVitals: Boolean = false,
    val showTripTracker: Boolean = false,
    val compassOffset: Float = 0f,
    // Last heading seen, kept across restarts. Heading only updates while
    // moving, so without this a unit that has just booted shows due north until
    // the car is driven — which reads as a broken compass rather than an unknown
    // one.
    val lastBearing: Float = 0f,
    // Where the vehicle was last seen. Without it the map opens on the whole
    // planet and stays there until a fix arrives, which on a cold start is
    // minutes — the launcher looks broken for the entire time.
    val lastLatitude: Double = 0.0,
    val lastLongitude: Double = 0.0,
    val showSoundboard: Boolean = false,
    val soundboardPads: List<SoundPadConfig> = defaultSoundboardPads(),
    val vitalsAsBars: Boolean = false,
    val speedometerDigitalOnly: Boolean = false,
    // The rev counter shares the speed widget rather than taking a cell of its
    // own: both answer "how hard is the car working", and split across two
    // widgets they have to be read together anyway.
    val speedometerShowTacho: Boolean = false,
    val gradientDirection: GradientDirection = GradientDirection.DIAGONAL,
    val useCustomBackgroundColor: Boolean = false,
    // OBD-II adapter. The MAC identifies the dongle; the name is kept alongside it
    // so settings can label the selection without holding the Bluetooth permission.
    val obdEnabled: Boolean = false,
    val obdDeviceMac: String = "",
    val obdDeviceName: String = "",
    val fuelType: FuelType = FuelType.PETROL,
    val showMap: Boolean = false,
    // Remote PMTiles build the map falls back to, and downloads regions from.
    // Protomaps publish these daily and name them by date, so this needs
    // changing when a build is retired — hence a setting rather than a constant.
    val pmtilesUrl: String = "https://build.protomaps.com/20260801.pmtiles",
    val offlineMapRadiusKm: Int = 25,
    // Deepest zoom fetched when downloading. Street detail over a whole region
    // is mostly waste — it is wanted where the car is parked, not across two
    // hundred kilometres — and each level up quadruples the tile count.
    val offlineMapMaxZoom: Int = 15,
    // A ZXY template, when set, replaces the PMTiles source. It is what makes
    // region download possible at all: the downloader enumerates per-tile URLs,
    // which an archive addressed by byte range does not have.
    val tileUrlTemplate: String = "",
    // Metres within which the vehicle marker is pulled onto a road. Zero turns
    // it off; beyond it the raw fix is kept, so crossing a car park does not
    // put the marker confidently on a street it is not on.
    val roadSnapMetres: Int = 20,
    // Points of interest — shops, stations, parks. Off by default: at speed
    // they are clutter, and the road labels are what actually help.
    val mapShowPlaces: Boolean = false,
    val showVehicle: Boolean = false,
    val showTemperature: Boolean = false,
    val showFuel: Boolean = false,
    // Tank capacity, for turning the litres the decoder sends into a bar. The
    // car does not report it, and the Evoque diesel carries seventy.
    val fuelTankLitres: Int = 70,
    // The last outside temperature seen, and when it was seen. The decoder only
    // sends on change, so a launcher that has just started waits — sometimes a
    // long while — for a value the car has been holding steady all along. Kept
    // for the same reason as the last heading and the last position: a blank at
    // startup reads as broken rather than as pending.
    val lastAmbientTempC: Float = 0f,
    val lastAmbientTempAtMs: Long = 0L,
    // How close the map sits when it is following the car. The tiles stop at
    // fifteen, so past that is the same geometry drawn larger — closer is a
    // preference about how much road to see ahead, not about detail.
    val mapDefaultZoom: Int = 18
)

fun defaultShortcuts() = listOf(
    ShortcutConfig(label = "Radio", isDefault = true, defaultIcon = DefaultShortcutIcon.RADIO),
    ShortcutConfig(label = "Camera", isDefault = true, defaultIcon = DefaultShortcutIcon.CAMERA),
    ShortcutConfig(label = "Music", isDefault = true, defaultIcon = DefaultShortcutIcon.MUSIC),
    ShortcutConfig(label = "Phone", isDefault = true, defaultIcon = DefaultShortcutIcon.PHONE)
)

/**
 * Default arrangement on the 8x3 grid.
 *
 * Widgets span several cells rather than sitting one per cell: at this density a
 * single cell is too small to read at a glance while driving. Columns 6 and 7 are
 * deliberately left free so there is somewhere obvious to drop a widget added
 * from the library without having to rearrange first.
 */
fun defaultWidgetLayout() = listOf(
    WidgetConfig("CLOCK",       gridX = 0, gridY = 0, spanX = 2, spanY = 1),
    WidgetConfig("WEATHER",     gridX = 2, gridY = 0, spanX = 2, spanY = 1),
    WidgetConfig("TELEMETRY",   gridX = 4, gridY = 0, spanX = 2, spanY = 3),
    WidgetConfig("NOW_PLAYING", gridX = 0, gridY = 1, spanX = 4, spanY = 2)
)

fun AppSettings.activeWidgetIds(): Set<String> = buildSet {
    if (showClock) add("CLOCK")
    if (showWeather) add("WEATHER")
    if (showNowPlaying) add("NOW_PLAYING")
    if (showTelemetry) add("TELEMETRY")
    if (showAltimeter) add("ALTIMETER")
    if (showSpeedometer) add("SPEEDOMETER")
    if (showVitals) add("VITALS")
    if (showTripTracker) add("TRIP_TRACKER")
    if (showSoundboard) add("SOUNDBOARD")
    if (showVehicle) add("VEHICLE")
    if (showTemperature) add("TEMPERATURE")
    if (showFuel) add("FUEL")
    if (showMap) add("MAP")
}

/**
 * Moves [movingId] to ([targetX], [targetY]) and pushes any displaced widgets to the
 * first available free cell, cascading until all conflicts are resolved.
 * Operates only on the supplied [layout] list — callers should pass only active/enabled widgets.
 */
fun computeWidgetMove(
    layout: List<WidgetConfig>,
    movingId: String,
    targetX: Int,
    targetY: Int
): List<WidgetConfig> {
    val moving = layout.find { it.id == movingId } ?: return layout
    val placed = moving.copy(
        gridX = targetX.coerceIn(0, GRID_COLS - moving.spanX),
        gridY = targetY.coerceIn(0, GRID_ROWS - moving.spanY)
    )

    val others  = layout.filter { it.id != movingId }
    val result  = mutableListOf(placed)
    val occupied = buildOccupied(result).toMutableSet()

    // Stable widgets that don't conflict go first; displaced ones are pushed afterwards
    val (stable, displaced) = others.partition { w -> result.none { widgetsOverlap(it, w) } }

    for (w in stable) {
        result.add(w)
        for (dx in 0 until w.spanX) for (dy in 0 until w.spanY) occupied.add(w.gridX + dx to w.gridY + dy)
    }

    for (w in displaced) {
        val pos = firstFreeGridPos(w.spanX, w.spanY, occupied)
        val resolved = if (pos != null) w.copy(gridX = pos.first, gridY = pos.second) else w
        result.add(resolved)
        for (dx in 0 until resolved.spanX) for (dy in 0 until resolved.spanY) occupied.add(resolved.gridX + dx to resolved.gridY + dy)
    }

    return result
}

private fun buildOccupied(widgets: List<WidgetConfig>) = buildSet<Pair<Int, Int>> {
    widgets.forEach { w -> for (dx in 0 until w.spanX) for (dy in 0 until w.spanY) add(w.gridX + dx to w.gridY + dy) }
}

private fun widgetsOverlap(a: WidgetConfig, b: WidgetConfig): Boolean =
    a.gridX < b.gridX + b.spanX && a.gridX + a.spanX > b.gridX &&
    a.gridY < b.gridY + b.spanY && a.gridY + a.spanY > b.gridY

private fun firstFreeGridPos(spanX: Int, spanY: Int, occupied: Set<Pair<Int, Int>>): Pair<Int, Int>? {
    for (row in 0 until GRID_ROWS) for (col in 0 until GRID_COLS) {
        if (col + spanX > GRID_COLS || row + spanY > GRID_ROWS) continue
        if ((0 until spanX).all { dx -> (0 until spanY).all { dy -> (col + dx to row + dy) !in occupied } })
            return col to row
    }
    return null
}
