package com.pikmin.standalone.utils

import kotlin.math.*

/**
 * GPS 模擬引擎 — 計算人類步行移動的 GPS 座標
 * 移植自桌面版 Python WalkSimulator
 */
object GpsSimulator {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val METERS_PER_DEG = 111_320.0
    private const val SPEED_MIN = 1.1        // m/s
    private const val SPEED_MAX = 1.5        // m/s
    private const val BEARING_DRIFT_MAX = 8.0 // 度
    private const val DRIFT_INTERVAL = 5     // 每 N 步飄移一次

    data class GpsPoint(
        val lat: Double,
        val lon: Double,
        val speed: Double = 0.0,
        val bearing: Double = 0.0,
    )

    private fun degreesToRadians(deg: Double) = deg * PI / 180.0
    private fun radiansToDegrees(rad: Double) = rad * 180.0 / PI

    /** 計算兩點距離（公尺）*/
    fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dlat = degreesToRadians(lat2 - lat1)
        val dlon = degreesToRadians(lon2 - lon1)
        val a = (sin(dlat / 2).pow(2) +
                cos(degreesToRadians(lat1)) * cos(degreesToRadians(lat2)) *
                sin(dlon / 2).pow(2))
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** 從起點沿方位角移動指定距離 */
    fun offsetPosition(lat: Double, lon: Double, distanceM: Double, bearingDeg: Double): GpsPoint {
        val d = distanceM / EARTH_RADIUS_M
        val brg = degreesToRadians(bearingDeg)
        val lat1 = degreesToRadians(lat)
        val lon1 = degreesToRadians(lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brg))
        val lon2 = lon1 + atan2(sin(brg) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(d) * cos(lat1))
        return GpsPoint(radiansToDegrees(lat2), radiansToDegrees(lon2))
    }

    /** 計算方位角 */
    fun bearingBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dlon = degreesToRadians(lon2 - lon1)
        val y = sin(dlon) * cos(degreesToRadians(lat2))
        val x = (cos(degreesToRadians(lat1)) * sin(degreesToRadians(lat2)) -
                sin(degreesToRadians(lat1)) * cos(degreesToRadians(lat2)) * cos(dlon))
        return (radiansToDegrees(atan2(y, x)) + 360) % 360
    }
}

/**
 * 步行狀態機 — 持續產生下一個 GPS 點
 */
class WalkStateMachine(
    startLat: Double = 25.0478,
    startLon: Double = 121.5170,
) {
    var currentLat = startLat; private set
    var currentLon = startLon; private set
    var currentBearing = (0..360).random().toDouble(); private set
    var isRoaming = false
    var joystickBearing: Double? = null  // 搖桿方向（非 null 時取代自動方向）

    private var driftCounter = 0
    private var currentDrift = 0.0
    private var lastTimestamp = 0L

    /** 計算下一個位置。每秒呼叫一次 */
    fun nextPoint(): GpsSimulator.GpsPoint {
        val speed = SPEED_MIN + Math.random() * (SPEED_MAX - SPEED_MIN)
        val distance = speed * 1.0  // 更新間隔 1 秒

        if (isRoaming) {
            // 自動漫遊模式：隨機飄移
            if (driftCounter % DRIFT_INTERVAL == 0) {
                currentDrift = (Math.random() * 2 - 1) * BEARING_DRIFT_MAX
            }
            val bearing = (currentBearing + currentDrift + 360) % 360
            currentBearing = bearing
        } else if (joystickBearing != null) {
            currentBearing = joystickBearing!!
        }

        val point = GpsSimulator.offsetPosition(currentLat, currentLon, distance, currentBearing)
        currentLat = point.lat
        currentLon = point.lon
        driftCounter++

        return GpsSimulator.GpsPoint(currentLat, currentLon, speed, currentBearing)
    }
}
