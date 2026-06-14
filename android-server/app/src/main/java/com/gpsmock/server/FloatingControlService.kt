package com.gpsmock.server

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.DataSet
import com.google.android.gms.fitness.data.DataSource
import com.google.android.gms.fitness.data.DataType
import com.google.android.gms.fitness.data.DataPoint
import com.google.android.gms.fitness.data.Field
import com.google.android.gms.tasks.Tasks
import com.gpsmock.server.utils.GpsSimulator
import com.gpsmock.server.utils.WalkState
import com.gpsmock.server.views.JoystickView
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.time.ZonedDateTime
import java.time.ZoneId

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

    // GPS 狀態顯示
    private lateinit var statusGps: TextView
    private lateinit var roamLabel: TextView
    private lateinit var btnGoogleSync: Button

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    // Google Fit
    private val fitOptions by lazy {
        FitnessOptions.builder()
            .addDataType(DataType.TYPE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_WRITE)
            .build()
    }
    private var googleAuthorized = false

    // 廣播接收器 — 接收 Google 授權結果
    private val googleResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == GoogleSignInActivity.EXTRA_FINISH) {
                googleAuthorized = true
                runOnUiThread { btnGoogleSync.text = "✅ Google 已連結" }
                Log.i(TAG, "Google Fit 授權成功")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        pm = getSystemService(POWER_SERVICE) as PowerManager
        registerReceiver(googleResultReceiver, IntentFilter(GoogleSignInActivity.EXTRA_FINISH),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0)
        createChan()
    }

    override fun onStartCommand(i: Intent?, f: Int, si: Int): Int {
        startForeground(NID, notif("🎮 運行中"))
        if (overlay == null) show()
        return START_STICKY
    }

    override fun onBind(i: Intent?) = null

    override fun onDestroy() {
        hide(); scope.cancel(); releaseLock()
        try { unregisterReceiver(googleResultReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

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

            btnGoogleSync = findViewById(R.id.btn_google_sync)
            btnGoogleSync.setOnClickListener {
                startActivity(Intent(this@FloatingControlService, GoogleSignInActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
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
        checkGoogleAuth()
        acquireLock()
        startGpsLoop()
    }

    private fun hide() {
        stopGpsLoop(); releaseLock(); removeMockProvider()
        overlay?.let { try { wm.removeView(it) } catch(_: Exception){} }; overlay = null
    }

    // ====================================================================
    // Mock Provider 註冊（修復 GPS 不動的關鍵）
    // ====================================================================
    @Suppress("DEPRECATION")
    private fun registerMockProvider() {
        try {
            lm?.let {
                // 先移除舊的 Provider
                try { it.removeTestProvider(PROVIDER) } catch (_: Exception) {}
                try { it.removeTestProvider("GPSMockProvider") } catch (_: Exception) {}

                it.addTestProvider(PROVIDER, false,false,false,false,true,true,true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                it.setTestProviderEnabled(PROVIDER, true)
                providerReady = true
                runOnUiThread { statusGps.text = "✅ GPS 運行中"; statusGps.setTextColor(0xFF88FF88.toInt()) }
                Log.i(TAG, "✅ Mock Provider 已註冊")
            }
        } catch (e: SecurityException) {
            providerReady = false
            runOnUiThread {
                statusGps.text = "⚠️ 請設定模擬位置 App（開發者選項）"
                statusGps.setTextColor(0xFFFF4444.toInt())
            }
            Log.e(TAG, "✗ Mock Location 權限不足")
        } catch (e: Exception) {
            providerReady = false
            runOnUiThread { statusGps.text = "⚠️ GPS 註冊失敗: ${e.message}" }
            Log.e(TAG, "✗ 註冊失敗: ${e.message}")
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
    // 主循環
    // ====================================================================
    private fun startGpsLoop() {
        loopJob = scope.launch {
            var counter = 0
            while (isActive) {
                val p = walk.next(); injectLoc(p); walkedSteps++; counter++
                if (counter % 5 == 0) {
                    val steps = walkedSteps
                    val roam = isRoam
                    runOnUiThread {
                        roamLabel.text = "游蕩 ${if(roam) "🌙" else "✋"} | ${steps}步"
                    }
                }
                delay(1000)
            }
        }

        // 每分鐘寫步數到 Google Fit（如果已授權）
        scope.launch {
            while (isActive) {
                delay(60000)
                if (googleAuthorized) writeStepsToGoogleFit(walkedSteps)
            }
        }
    }

    private fun stopGpsLoop() { loopJob?.cancel() }

    // ====================================================================
    // Google Fit 步數寫入（不需 Google Fit App）
    // ====================================================================
    private fun checkGoogleAuth() {
        try {
            val a = GoogleSignIn.getAccountForExtension(this, fitOptions)
            googleAuthorized = GoogleSignIn.hasPermissions(a, fitOptions)
            runOnUiThread {
                btnGoogleSync.text = if (googleAuthorized) "✅ Google 已連結" else "🔗 Google 同步步數"
            }
        } catch (_: Exception) {}
    }

    private val MAX_DAILY_STEPS = 15000

    private fun writeStepsToGoogleFit(totalSteps: Long) {
        if (!googleAuthorized) return
        try {
            val account = GoogleSignIn.getAccountForExtension(this, fitOptions)
            val now = ZonedDateTime.now()
            val startMs = now.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val source = DataSource.Builder()
                .setAppPackageName(this)
                .setDataType(DataType.TYPE_STEP_COUNT_DELTA)
                .setType(DataSource.TYPE_RAW)
                .build()

            val count = minOf(totalSteps, MAX_DAILY_STEPS.toLong()).toInt()
            val point = DataPoint.builder(source)
                .setTimestamp(startMs, TimeUnit.MILLISECONDS)
                .setField(Field.FIELD_STEPS, count)
                .build()

            val dataSet = DataSet.create(source)
            dataSet.add(point)

            Tasks.await(
                Fitness.getHistoryClient(this, account).insertData(dataSet),
                30, TimeUnit.SECONDS
            )
            Log.i(TAG, "✅ 已寫入 $count 步到 Google Fit")
        } catch (e: Exception) {
            Log.e(TAG, "Google Fit 寫入失敗: ${e.message}")
        }
    }

    // ====================================================================
    // 工具函數
    // ====================================================================
    private fun runOnUiThread(a: () -> Unit) { android.os.Handler(mainLooper).post(a) }

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
