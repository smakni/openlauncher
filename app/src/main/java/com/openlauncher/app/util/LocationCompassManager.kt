package com.openlauncher.app.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speedMps: Float = 0f
)

class LocationCompassManager(context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager   = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _location  = MutableStateFlow<LocationData?>(null)
    private val _bearing   = MutableStateFlow(0f)
    val location: StateFlow<LocationData?> = _location
    val bearing: StateFlow<Float> = _bearing

    private val gravity      = FloatArray(3)
    private val geomagnetic  = FloatArray(3)
    // Circular low-pass filter for smooth bearing (avoids 0°/360° wrap artifacts)
    private var bearingSin   = 0f
    private var bearingCos   = 1f   // initial: pointing north
    private var lastLocationForBearing: Location? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, gravity, 0, 3)
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                }
            }
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                val azimuthRad = orientation[0].toDouble()
                // Circular low-pass filter — correctly handles 0°/360° wrap-around
                val alpha = 0.10f
                bearingSin = alpha * sin(azimuthRad).toFloat() + (1f - alpha) * bearingSin
                bearingCos = alpha * cos(azimuthRad).toFloat() + (1f - alpha) * bearingCos
                _bearing.value = ((Math.toDegrees(atan2(bearingSin.toDouble(), bearingCos.toDouble())) + 360) % 360).toFloat()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** When the last satellite fix arrived, used to keep network fixes from displacing it. */
    private var lastGpsFixMs = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val isGps = loc.provider == LocationManager.GPS_PROVIDER
            if (isGps) {
                lastGpsFixMs = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastGpsFixMs < GPS_PREFERENCE_WINDOW_MS) {
                // Both providers feed this one listener. A network fix carries no
                // speed, so letting one through while satellite fixes are still
                // arriving drops the speed readout to zero between them.
                return
            }

            _location.value = LocationData(
                latitude  = loc.latitude,
                longitude = loc.longitude,
                altitude  = loc.altitude,
                accuracy  = loc.accuracy,
                speedMps  = standstillCorrected(loc)
            )

            // Heading only means anything while the car is moving. A stationary
            // receiver keeps reporting one and it wanders, which had the compass
            // turning on a parked car, so the noise-gated speed doubles as the
            // movement test. Standing still simply holds the last heading.
            if (standstillCorrected(loc) > 0f) {
                if (loc.hasBearing()) {
                    // Tested with hasBearing alone: the old check also rejected a
                    // bearing of exactly 0, discarding a valid due-north heading.
                    smoothBearing(loc.bearing)
                } else {
                    // No hardware bearing — derive it from consecutive positions.
                    // Works offline and without sensors, which is the only path
                    // available on units that ship without a magnetometer.
                    val lastLoc = lastLocationForBearing
                    if (lastLoc == null) {
                        lastLocationForBearing = loc
                    } else if (lastLoc.distanceTo(loc) > BEARING_MIN_DISTANCE_M) {
                        smoothBearing(lastLoc.bearingTo(loc))
                        lastLocationForBearing = loc
                    }
                }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        // Must be overridden explicitly: the interface only gained default
        // implementations in API 30, so omitting them throws AbstractMethodError
        // on older head units when the GPS provider is toggled.
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * Seeds the heading from the last one known.
     *
     * Heading only updates while moving, so a freshly started unit would
     * otherwise read due north until the car is driven — indistinguishable from
     * a compass that does not work.
     */
    fun restoreBearing(degrees: Float) {
        if (_bearing.value == 0f && degrees != 0f) {
            val radians = Math.toRadians(degrees.toDouble())
            bearingSin = sin(radians).toFloat()
            bearingCos = cos(radians).toFloat()
            _bearing.value = degrees
        }
    }

    fun start() {
        // Sensors
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }

        // Location — Robust offline-first registration
        // GPS Provider (Works 100% offline, sat-based)
        try {
            if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                // No interval or distance filter: the speed readout should track
                // the car, and a 3s/5m filter made it lag by seconds and stall
                // outright at low speed. GPS hardware caps itself near 1Hz, so
                // asking for everything simply means every fix it produces.
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 0L, 0f, locationListener
                )
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    locationListener.onLocationChanged(it)
                }
            }
        } catch (_: Exception) {}

        // Network Provider (Works online, cell/wifi-based)
        try {
            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 5000L, 10f, locationListener
                )
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let {
                    locationListener.onLocationChanged(it)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Feeds a heading through the circular low-pass filter and publishes it.
     *
     * The filter was previously only reachable from the sensor listener, so on a
     * unit without a magnetometer — where GPS is the only source there is — the
     * compass received raw values and jumped between fixes.
     *
     * Filtering sine and cosine rather than the angle is what keeps 359 degrees
     * and 1 degree adjacent; averaging the numbers directly would sweep the
     * needle the long way round through south.
     *
     * A far higher weight than the sensor path uses: fixes arrive about once a
     * second instead of sixty times, and the sensor's weight at that rate would
     * take the better part of a minute to follow a turn.
     */
    private fun smoothBearing(degrees: Float) {
        val radians = Math.toRadians(degrees.toDouble())
        bearingSin = GPS_BEARING_ALPHA * sin(radians).toFloat() + (1f - GPS_BEARING_ALPHA) * bearingSin
        bearingCos = GPS_BEARING_ALPHA * cos(radians).toFloat() + (1f - GPS_BEARING_ALPHA) * bearingCos
        _bearing.value =
            ((Math.toDegrees(atan2(bearingSin.toDouble(), bearingCos.toDouble())) + 360) % 360).toFloat()
    }

    /**
     * GPS speed with standstill noise removed.
     *
     * A receiver parked still reports a metre or so per second of drift, and with
     * no distance filter on the updates every one of those wandering fixes now
     * reaches the readout — a stationary car shows a creeping speed.
     *
     * Where the chip estimates its own speed accuracy, a reading smaller than
     * that estimate is indistinguishable from zero and is treated as such. Older
     * chips that report no accuracy fall back to a fixed floor, set below walking
     * pace so it cannot mask real movement.
     */
    private fun standstillCorrected(loc: Location): Float {
        if (!loc.hasSpeed()) return 0f
        val speed = loc.speed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasSpeedAccuracy()) {
            val noiseFloor = loc.speedAccuracyMetersPerSecond
            if (speed <= noiseFloor) return 0f
        }
        return if (speed < STANDSTILL_FLOOR_MPS) 0f else speed
    }

    fun stop() {
        sensorManager.unregisterListener(sensorListener)
        locationManager.removeUpdates(locationListener)
        lastLocationForBearing = null
        lastGpsFixMs = 0L
    }

    private companion object {
        /**
         * How long a satellite fix keeps precedence over network fixes. Comfortably
         * longer than the ~1Hz GPS produces, so network positions only take over
         * once satellites have genuinely dropped out.
         */
        const val GPS_PREFERENCE_WINDOW_MS = 10_000L

        /** ~2.5 km/h — under walking pace, so real movement is never masked. */
        const val STANDSTILL_FLOOR_MPS = 0.7f

        /** Metres between fixes before a derived heading is trusted over GPS scatter. */
        const val BEARING_MIN_DISTANCE_M = 3f

        /** Filter weight for GPS headings, which arrive per second rather than per frame. */
        const val GPS_BEARING_ALPHA = 0.45f
    }
}
