package com.gpsmock.server

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
import com.gpsmock.server.utils.GpsSimulator
import com.gpsmock.server.utils.WalkState
import com.gpsmock.server.utils.GoogleFitHelper
import kotlinx.coroutines.*

class GpsMockService : Service() {
    companion object {
        private const val TAG = "GpsMockSvc"
        private const val CHANNEL = "gps_svc"
        private const val NID = 3003
        const val PROVIDER = "GPSMockProvider"
        const val START = "com.gpsmock.server.START_GPS"
        const val STOP = "com.gpsmock.server.STOP_GPS"
        const val CMD_ROAM = "com.gpsmock.server.ROAM"
        const val CMD_BEAR = "com.gpsmock.server.BEARING"
        const val EXTRA_ROAM = "roam"
        const val EXTRA_BEAR = "bearing"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lm: LocationManager? = null
    private var wl: PowerManager.WakeLock? = null
    private val walk = WalkState()
    private var job: Job? = null
    private var googleFit: GoogleFitHelper? = null
    private var totalSteps = 0L

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        googleFit = GoogleFitHelper(this)
        registerMock()
        acquireLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Pikmin 助手", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, si: Int): Int {
        when (i?.action) {
            START -> { startForeground(NID, notif("🌸 運行中")); startLoop() }
            STOP -> stopSelf()
            CMD_ROAM -> { walk.isRoaming = i.getBooleanExtra(EXTRA_ROAM, false); updateNotif(if (walk.isRoaming) "🌸 遊蕩" else "🎮 手動") }
            CMD_BEAR -> { walk.stickBearing = i.getDoubleExtra(EXTRA_BEAR, -1.0).let { if (it < 0) null else it } }
        }; return START_STICKY
    }

    override fun onBind(i: Intent?) = null
    override fun onDestroy() { scope.cancel(); job?.cancel(); removeMock(); releaseLock(); super.onDestroy() }

    private fun startLoop() {
        job = scope.launch {
            var s = 0; while (isActive) {
                val p = walk.next(); injectLoc(p); s++
                // 每 60 步（約 1 分鐘）寫入步數到 Google Fit
                if (s % 60 == 0) {
                    totalSteps += 100
                    val gf = googleFit
                    if (gf != null && gf.hasPermission()) {
                        gf.writeSteps(totalSteps)
                    }
                }
                delay(1000)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun injectLoc(p: GpsSimulator.Point) {
        try { lm?.setTestProviderLocation(PROVIDER, Location(PROVIDER).apply {
            latitude = p.lat; longitude = p.lon; accuracy = 5f; speed = p.speed.toFloat()
            bearing = p.bearing.toFloat(); time = System.currentTimeMillis()
            if (Build.VERSION.SDK_INT >= 17) elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }) } catch (_: Exception) { Log.e(TAG, "GPS注入失敗") }
    }

    @Suppress("DEPRECATION")
    private fun registerMock() { try { lm?.let {
        it.addTestProvider(PROVIDER, false,false,false,false,true,true,true,Criteria.POWER_LOW,Criteria.ACCURACY_FINE)
        it.setTestProviderEnabled(PROVIDER, true)
    }} catch (_: Exception) {} }
    private fun removeMock() { try { lm?.removeTestProvider(PROVIDER) } catch (_: Exception) {} }
    private fun acquireLock() { try { wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:lock"); wl?.acquire(4*60*60*1000L) } catch (_: Exception) {} }
    private fun releaseLock() { try { wl?.release() } catch (_: Exception) {} }
    private fun notif(t: String) = NotificationCompat.Builder(this, CHANNEL).setContentTitle("Pikmin 助手").setContentText(t).setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).build()
    private fun updateNotif(t: String) { try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NID, notif(t)) } catch (_: Exception) {} }
}
