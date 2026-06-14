package com.gpsmock.server.utils

import kotlin.math.*

object GpsSimulator {
    private const val R = 6_371_000.0
    const val SPEED_MIN = 1.1
    const val SPEED_MAX = 1.5
    private const val BEARING_DRIFT = 8.0

    data class Point(val lat: Double, val lon: Double, val speed: Double = 0.0, val bearing: Double = 0.0)
    fun offset(lat: Double, lon: Double, dist: Double, brg: Double): Point {
        val d = dist / R; val rad = brg * PI / 180
        val l1 = lat * PI / 180; val l2 = asin(sin(l1)*cos(d)+cos(l1)*sin(d)*cos(rad))
        val l3 = l1 + atan2(sin(rad)*sin(d)*cos(l1), cos(d)-sin(l1)*sin(l2))
        return Point(l2 * 180 / PI, l3 * 180 / PI)
    }
}

class WalkState {
    var lat: Double = 25.0478; var lon: Double = 121.5170
    var bearing = (0..360).random().toDouble()
    var isRoaming = false
    var stickBearing: Double? = null
    private var drift = 0.0; private var cnt = 0

    fun next(): GpsSimulator.Point {
        val speed = SPEED_MIN + Math.random() * (SPEED_MAX - SPEED_MIN)
        val dist = speed * 1.0
        if (isRoaming) {
            if (cnt % 5 == 0) drift = (Math.random() * 2 - 1) * BEARING_DRIFT
            bearing = (bearing + drift + 360) % 360
        } else if (stickBearing != null) {
            bearing = stickBearing!!
        }
        val p = GpsSimulator.offset(lat, lon, dist, bearing)
        lat = p.lat; lon = p.lon; cnt++
        return GpsSimulator.Point(lat, lon, speed, bearing)
    }
}
