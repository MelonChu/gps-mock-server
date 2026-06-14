package com.gpsmock.server

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
 * 懸浮控制服務 — 內建 GPS 模擬 + 搖桿 + 游蕩 + 步數
 *
 * 所有功能整合在一個 Service 裡，避免 Android 14+ 背景 Service 限制。
 * 不再需要外部相依（無 Google Fit、無 Health Connect）。
 */
class FloatingControlService : Service(), SensorEventListener {
    companion object {
        private const val TAG = "FloatingCtrl"
        private const val CHANNEL = "float_ctrl"
        private const val NID = 1001
        private const val PROVIDER = "PikminMockProvider"
        const val TOGGLE = "com.gpsmock.server.TOGGLE_FLOAT"
    }

    // 系統服務
    private lateinit var wm: WindowManager
    private var lm: LocationManager? = null
    private var pm: PowerManager? = null
    private var sm: SensorManager? = null
    private var wl: PowerManager.WakeLock? = null
    private var stepSensor: Sensor? = null

    // 懸浮視窗
    private var overlay: View? = null

    // 步行狀態
    private val walk = WalkState()
    private var isRoam = false

    // 步數
    private var baseSteps = 0f     // 開啓時的初始步數
    private var lastSteps = 0f     // 上次讀到的步數
    private var walkedSteps = 0L  // 我們自己走的「虛擬」步數

    // 主循環
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    // UI 元件
    private lateinit var roamLabel: TextView
    private var stepCounter = 0  // 秒計數器

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        pm = getSystemService(POWER_SERVICE) as PowerManager
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        createChan()
    }

    override fun onStartCommand(i: Intent?, f: Int, si: Int): Int {
        when (i?.action) { TOGGLE -> { if (overlay == null) show() else hide() } }
        startForeground(NID, notif("🎮 運行中"))
        if (overlay == null) show()
        return START_STICKY
    }

    override fun onBind(i: Intent?) = null
    override fun onDestroy() { hide(); scope.cancel(); unregisterSensor(); releaseLock(); super.onDestroy() }

    // ====================================================================
    // 懸浮視窗
    // ====================================================================
    private fun show() {
        if (overlay != null) return
        val inf = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlay = inf.inflate(R.layout.overlay_controls, null).apply {
            roamLabel = findViewById(R.id.roam_label)

            // 搖桿
            findViewById<JoystickView>(R.id.joystick).setListener(object : JoystickView.Listener {
                override fun onMove(angle: Double, dist: Float) {
                    walk.stickBearing = if (dist < 0.15f) null else angle
                }
            })

            // 游蕩開關
            findViewById<ToggleButton>(R.id.toggle_roam).setOnCheckedChangeListener { _, c ->
                isRoam = c; walk.isRoaming = c
                roamLabel.text = "游蕩 ${if(c) "🌙" else "✋"}"
            }

            // 拖曳
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

        // 註冊 Mock Provider + 啟動
        registerMockProvider()
        acquireLock()
        registerSensor()
        startGpsLoop()
    }

    private fun hide() {
        stopGpsLoop()
        unregisterSensor()
        releaseLock()
        removeMockProvider()
        overlay?.let { try { wm.removeView(it) } catch(_: Exception){} }; overlay = null
    }

    // ====================================================================
    // GPS 模擬（直接內建，不需獨立 Service）
    // ====================================================================
    private fun registerMockProvider() {
        try {
            lm?.let {
                // 先移除舊的
                try { it.removeTestProvider(PROVIDER) } catch (_: Exception) {}
                @Suppress("DEPRECATION")
                it.addTestProvider(PROVIDER, false,false,false,false,true,true,true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                it.setTestProviderEnabled(PROVIDER, true)
                Log.i(TAG, "✅ Mock Provider 註冊成功")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "✗ Mock Location 權限不足 — 請在開發者選項設定此 App")
        } catch (e: Exception) {
            Log.e(TAG, "✗ 註冊失敗: ${e.message}")
        }
    }

    private fun removeMockProvider() {
        try { lm?.removeTestProvider(PROVIDER) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun injectLoc(p: GpsSimulator.Point) {
        try {
            lm?.setTestProviderLocation(PROVIDER, Location(PROVIDER).apply {
                latitude = p.lat; longitude = p.lon; accuracy = 5f
                speed = p.speed.toFloat(); bearing = p.bearing.toFloat()
                time = System.currentTimeMillis()
                if (Build.VERSION.SDK_INT >= 17) elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "✗ GPS 注入失敗 — Mock Provider 未正確註冊，請檢查開發者選項設定")
        } catch (e: Exception) {
            Log.e(TAG, "✗ GPS 注入失敗: ${e.message}")
        }
    }

    // ====================================================================
    // 步數追蹤（使用手機內建步數感測器）
    // ====================================================================
    private fun registerSensor() {
        stepSensor?.let {
            sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "✅ 步數感測器已註冊")
        } ?: Log.w(TAG, "⚠ 此手機無步數感測器")
    }

    private fun unregisterSensor() {
        try { sm?.unregisterListener(this) } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            if (baseSteps == 0f) baseSteps = event.values[0]
            lastSteps = event.values[0] - baseSteps
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ====================================================================
    // 主循環
    // ====================================================================
    private fun startGpsLoop() {
        loopJob = scope.launch {
            stepCounter = 0
            while (isActive) {
                val p = walk.next()
                injectLoc(p)
                walkedSteps += 1  // 約 1 步/秒
                stepCounter++

                // 每 10 秒更新懸浮視窗的步數顯示
                if (stepCounter % 10 == 0) {
                    val realSteps = lastSteps.toLong()
                    val totalSteps = walkedSteps
                    runOnUiThread {
                        roamLabel.text = "游蕩 ${if(isRoam)"🌙" else "✋"} | 步 ${totalSteps}"
                    }
                }

                delay(1000)
            }
        }
    }

    private fun stopGpsLoop() { loopJob?.cancel(); loopJob = null }

    // ====================================================================
    // 工具
    // ====================================================================
    private fun runOnUiThread(action: () -> Unit) {
        val handler = android.os.Handler(mainLooper)
        handler.post(action)
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
    private fun releaseLock() {
        try { wl?.release() } catch (_: Exception) {}
    }

    private fun createChan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Pikmin 助手", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notif(t: String) = NotificationCompat.Builder(this, CHANNEL)
        .setContentTitle("Pikmin 助手").setContentText(t)
        .setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).build()
}
