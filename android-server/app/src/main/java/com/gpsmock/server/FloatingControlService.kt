package com.gpsmock.server

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.gpsmock.server.utils.GpsSimulator
import com.gpsmock.server.utils.WalkState
import com.gpsmock.server.views.JoystickView
import kotlinx.coroutines.*

/**
 * 懸浮控制服務 — 搖桿 + GPS 模擬 + 游蕩模式
 *
 * 一個 Service 搞定所有事，不需任何外部相依。
 * - 搖桿：手動控制走路方向
 * - 游蕩：自動隨機漫步（1.1~1.5 m/s）
 * - GPS 狀態顯示在頂端
 * - 步數顯示在 overlay（僅供參考，不注入外部系統）
 */
class FloatingControlService : Service() {
    companion object {
        private const val TAG = "FloatingCtrl"
        private const val CHANNEL = "float_ctrl"
        private const val NID = 1001
        private const val PROVIDER = "PikminMockProvider"
        const val TOGGLE = "com.gpsmock.server.TOGGLE_FLOAT"
    }

    private lateinit var wm: WindowManager
    private var lm: LocationManager? = null
    private var pm: PowerManager? = null
    private var wl: PowerManager.WakeLock? = null

    private var overlay: View? = null
    private val walk = WalkState()
    private var isRoam = false
    private var walkedSteps = 0L
    private var providerReady = false

    // UI
    private lateinit var statusGps: TextView
    private lateinit var roamLabel: TextView

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        pm = getSystemService(POWER_SERVICE) as PowerManager
        createChan()
    }

    override fun onStartCommand(i: Intent?, f: Int, si: Int): Int {
        startForeground(NID, notif("🎮 運行中"))
        if (overlay == null) show()
        return START_STICKY
    }

    override fun onBind(i: Intent?) = null

    override fun onDestroy() { hide(); scope.cancel(); releaseLock(); super.onDestroy() }

    // ====================================================================
    // 懸浮視窗
    // ====================================================================
    private fun show() {
        if (overlay != null) return
        val inf = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlay = inf.inflate(R.layout.overlay_controls, null).apply {
            statusGps = findViewById(R.id.status_gps)
            roamLabel = findViewById(R.id.roam_label)

            findViewById<JoystickView>(R.id.joystick).setListener(object : JoystickView.Listener {
                override fun onMove(angle: Double, dist: Float) {
                    walk.stickBearing = if (dist < 0.15f) null else angle
                }
            })

            findViewById<ToggleButton>(R.id.toggle_roam).setOnCheckedChangeListener { _, c ->
                isRoam = c; walk.isRoaming = c
            }

            findViewById<View>(R.id.drag_handle).setOnTouchListener { v, e -> drag(v, e); true }
            findViewById<View>(R.id.btn_minimize).setOnClickListener { hide() }

        }

        wm.addView(overlay, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 300 })

        // 註冊 GPS Mock Provider + 啟動
        registerMockProvider()
        acquireLock()
        startGpsLoop()
    }

    private fun hide() {
        stopGpsLoop(); releaseLock(); removeMockProvider()
        overlay?.let { try { wm.removeView(it) } catch(_: Exception){} }; overlay = null
    }

    // ====================================================================
    // Mock Provider 註冊
    // ====================================================================
    @Suppress("DEPRECATION")
    private fun registerMockProvider() {
        try {
            lm?.let {
                try { it.removeTestProvider(PROVIDER) } catch (_: Exception) {}
                it.addTestProvider(PROVIDER, false,false,false,false,true,true,true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                it.setTestProviderEnabled(PROVIDER, true)
                providerReady = true
                runOnUiThread {
                    statusGps.text = "✅ GPS 運行中"
                    statusGps.setTextColor(0xFF88FF88.toInt())
                }
                Log.i(TAG, "✅ Mock Provider 註冊成功")
            }
        } catch (e: SecurityException) {
            providerReady = false
            runOnUiThread {
                statusGps.text = "⚠️ 請設定模擬位置 App（開發者選項）"
                statusGps.setTextColor(0xFFFF4444.toInt())
            }
            Log.e(TAG, "✗ 權限不足 — 請在開發者選項設定此 App 為模擬位置")
        } catch (e: Exception) {
            providerReady = false
            runOnUiThread { statusGps.text = "⚠️ GPS 錯誤: ${e.message}" }
        }
    }

    private fun removeMockProvider() {
        try { lm?.removeTestProvider(PROVIDER) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun injectLoc(p: GpsSimulator.Point) {
        if (!providerReady) return
        try {
            lm?.setTestProviderLocation(PROVIDER, Location(PROVIDER).apply {
                latitude = p.lat; longitude = p.lon; accuracy = 5f
                speed = p.speed.toFloat(); bearing = p.bearing.toFloat()
                time = System.currentTimeMillis()
                if (Build.VERSION.SDK_INT >= 17) elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
        } catch (e: Exception) {
            Log.e(TAG, "GPS 注入失敗: ${e.message}")
        }
    }

    // ====================================================================
    // 主循環 — 每秒更新 GPS 位置
    // ====================================================================
    private fun startGpsLoop() {
        loopJob = scope.launch {
            while (isActive) {
                val p = walk.next()
                injectLoc(p)
                walkedSteps++
                delay(1000)
            }
        }

        // UI 更新（每 3 秒刷新一次）
        scope.launch {
            while (isActive) {
                delay(3000)
                if (providerReady) {
                    val steps = walkedSteps
                    val roam = isRoam
                    val lat = walk.lat
                    val lon = walk.lon
                    runOnUiThread {
                        roamLabel.text = "游蕩 ${if(roam) "🌙" else "✋"} | ${steps}步"
                        statusGps.text = "✅ ${lat.toString().take(7)}, ${lon.toString().take(7)}"
                    }
                }
            }
        }
    }

    private fun stopGpsLoop() { loopJob?.cancel() }

    // ====================================================================
    // 工具
    // ====================================================================
    private fun runOnUiThread(a: () -> Unit) {
        android.os.Handler(mainLooper).post(a)
    }

    private var ix = 0; private var iy = 0; private var itx = 0f; private var ity = 0f
    private fun drag(v: View, e: MotionEvent): Boolean {
        val p = v.layoutParams as WindowManager.LayoutParams
        when(e.action) {
            MotionEvent.ACTION_DOWN -> { ix=p.x; iy=p.y; itx=e.rawX; ity=e.rawY }
            MotionEvent.ACTION_MOVE -> { p.x = ix+(e.rawX-itx).toInt(); p.y = iy+(e.rawY-ity).toInt(); wm.updateViewLayout(v, p) }
        }; return false
    }

    private fun acquireLock() {
        try { wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:lock"); wl?.acquire(4*60*60*1000L) } catch (_: Exception) {}
    }
    private fun releaseLock() { try { wl?.release() } catch (_: Exception) {} }

    private fun createChan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Pikmin 助手", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notif(t: String) = NotificationCompat.Builder(this, CHANNEL)
        .setContentTitle("Pikmin 助手").setContentText(t)
        .setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).build()
}
