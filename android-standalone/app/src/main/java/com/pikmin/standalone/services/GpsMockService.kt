package com.pikmin.standalone.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pikmin.standalone.R
import com.pikmin.standalone.utils.GpsSimulator
import com.pikmin.standalone.utils.StepInjector
import com.pikmin.standalone.utils.WalkStateMachine
import kotlinx.coroutines.*

/**
 * GPS 模擬服務 — 背景執行的 GPS 座標注入引擎
 *
 * 每秒更新一次 GPS 位置，並定期寫入步數到 Health Connect。
 * 可與 FloatingControlService 搭配使用（接收搖桿指令）。
 */
class GpsMockService : Service() {
    companion object {
        private const val TAG = "GpsMockService"
        private const val CHANNEL_ID = "gps_channel"
        private const val NOTIFICATION_ID = 2002
        const val PROVIDER_NAME = "PikminMockProvider"

        const val ACTION_START = "com.pikmin.standalone.START"
        const val ACTION_STOP = "com.pikmin.standalone.STOP"
        const val ACTION_SET_ROAMING = "com.pikmin.standalone.SET_ROAMING"
        const val ACTION_SET_BEARING = "com.pikmin.standalone.SET_BEARING"
        const val EXTRA_ROAMING = "roaming"
        const val EXTRA_BEARING = "bearing"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val walkState = WalkStateMachine()
    private var stepInjector: StepInjector? = null
    private var stepJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        stepInjector = StepInjector(this)
        registerMockProvider()
        acquireWakeLock()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("運行中 🌸"))
                startWalkLoop()
            }
            ACTION_STOP -> stopSelf()
            ACTION_SET_ROAMING -> {
                walkState.isRoaming = intent.getBooleanExtra(EXTRA_ROAMING, false)
                Log.i(TAG, "漫遊模式: ${walkState.isRoaming}")
                updateNotification(if (walkState.isRoaming) "漫遊中 🌸" else "手動控制 🎮")
            }
            ACTION_SET_BEARING -> {
                val bearing = intent.getDoubleExtra(EXTRA_BEARING, -1.0)
                walkState.joystickBearing = if (bearing >= 0) bearing else null
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        stepJob?.cancel()
        removeMockProvider()
        releaseWakeLock()
        super.onDestroy()
    }

    /** 每秒更新 GPS 的主循環 */
    private fun startWalkLoop() {
        stepJob = scope.launch {
            var stepCounter = 0
            while (isActive) {
                val point = walkState.nextPoint()
                injectMockLocation(point)
                stepCounter++

                // 每 60 步（約 1 分鐘）注入一次步數
                if (stepCounter % 60 == 0) {
                    stepInjector?.injectStepsGradual()
                }

                delay(1000)
            }
        }
    }

    /** 注入 Mock GPS 到系統 */
    @Suppress("DEPRECATION")
    private fun injectMockLocation(point: GpsSimulator.GpsPoint) {
        try {
            val lm = locationManager ?: return
            val loc = Location(PROVIDER_NAME).apply {
                latitude = point.lat
                longitude = point.lon
                accuracy = 5.0f
                speed = point.speed.toFloat()
                bearing = point.bearing.toFloat()
                time = System.currentTimeMillis()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
            }
            lm.setTestProviderLocation(PROVIDER_NAME, loc)
        } catch (e: SecurityException) {
            Log.e(TAG, "權限不足: 請設定 Mock Location App")
        } catch (e: Exception) {
            Log.e(TAG, "注入 GPS 失敗: ${e.message}")
        }
    }

    /** 註冊 Mock Provider */
    @Suppress("DEPRECATION")
    private fun registerMockProvider() {
        try {
            locationManager?.let { lm ->
                removeMockProvider()
                lm.addTestProvider(
                    PROVIDER_NAME, false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(PROVIDER_NAME, true)
                Log.i(TAG, "Mock Provider 已註冊")
            }
        } catch (e: Exception) {
            Log.e(TAG, "註冊 Mock Provider 失敗: ${e.message}")
        }
    }

    private fun removeMockProvider() {
        try {
            locationManager?.removeTestProvider(PROVIDER_NAME)
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:Wakelock")
            wakeLock?.acquire(4 * 60 * 60 * 1000L)
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "GPS 模擬", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getService(this, 0,
            Intent(this, GpsMockService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pikmin 助手")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
