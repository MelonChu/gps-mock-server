package com.pikmin.standalone.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.pikmin.standalone.R
import com.pikmin.standalone.views.JoystickView

/**
 * 懸浮控制服務 — 在遊戲上方顯示搖桿 + 游蕩開關
 *
 * 功能：
 * - 🎮 虛擬搖桿：拖動控制移動方向
 * - 🌸 游蕩開關：切換自動走路模式
 * - 可任意拖動位置，不擋住遊戲畫面
 *
 * 需要 SYSTEM_ALERT_WINDOW 權限
 */
class FloatingControlService : Service() {
    companion object {
        private const val TAG = "FloatingCtrl"
        private const val CHANNEL_ID = "float_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE = "com.pikmin.standalone.TOGGLE_FLOAT"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var joystickView: JoystickView? = null
    private var isRoaming = false
    private var gpsServiceIntent: Intent? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                if (overlayView == null) showOverlay()
                else hideOverlay()
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification("懸浮控制已啟動"))
        showOverlay()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideOverlay()
        stopGpsService()
        super.onDestroy()
    }

    /** 顯示懸浮視窗 */
    private fun showOverlay() {
        if (overlayView != null) return

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_controls, null).apply {
            // 搖桿
            joystickView = findViewById(R.id.joystick)
            joystickView?.setOnJoystickListener(object : JoystickView.OnJoystickListener {
                override fun onJoystickMoved(angle: Double, distance: Float) {
                    if (distance < 0.15f) {
                        // 不移動 = 清除搖桿方向
                        sendBearingCommand(-1.0)
                        return
                    }
                    sendBearingCommand(angle)
                }
            })

            // 游蕩開關
            val roamToggle: ToggleButton = findViewById(R.id.toggle_roam)
            roamToggle.setOnCheckedChangeListener { _, isChecked ->
                isRoaming = isChecked
                val text = "遊蕩模式 ${if (isChecked) "🌙" else "✋"}"
                (findViewById<View>(R.id.roam_label) as TextView).text = text
                sendRoamingCommand(isChecked)
            }

            // 拖移整塊視窗
            val dragHandle: View = findViewById(R.id.drag_handle)
            dragHandle.setOnTouchListener { _, event ->
                overlayView?.let { handleDrag(it, event) }
                true
            }

            // 最小化按鈕
            findViewById<View>(R.id.btn_minimize).setOnClickListener {
                hideOverlay()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        windowManager.addView(overlayView, params)
        startGpsService()
    }

    /** 隱藏懸浮視窗 */
    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        joystickView = null
    }

    // ====================================================================
    // 視窗拖曳
    // ====================================================================
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private fun handleDrag(view: View, event: MotionEvent): Boolean {
        val params = view.layoutParams as WindowManager.LayoutParams
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = initialX + (event.rawX - initialTouchX).toInt()
                params.y = initialY + (event.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(view, params)
            }
        }
        return false
    }

    // ====================================================================
    // 控制 GPS 服務
    // ====================================================================
    private fun startGpsService() {
        gpsServiceIntent = Intent(this, GpsMockService::class.java).apply {
            action = GpsMockService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(gpsServiceIntent!!)
        } else {
            startService(gpsServiceIntent!!)
        }
    }

    private fun stopGpsService() {
        gpsServiceIntent?.let {
            it.action = GpsMockService.ACTION_STOP
            startService(it)
        }
        gpsServiceIntent = null
    }

    private fun sendRoamingCommand(roaming: Boolean) {
        Intent(this, GpsMockService::class.java).apply {
            action = GpsMockService.ACTION_SET_ROAMING
            putExtra(GpsMockService.EXTRA_ROAMING, roaming)
            startService(this)
        }
    }

    private fun sendBearingCommand(bearing: Double) {
        Intent(this, GpsMockService::class.java).apply {
            action = GpsMockService.ACTION_SET_BEARING
            putExtra(GpsMockService.EXTRA_BEARING, bearing)
            startService(this)
        }
    }

    // ====================================================================
    // 通知
    // ====================================================================
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "懸浮控制", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getService(this, 0,
            Intent(this, FloatingControlService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pikmin 助手")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(pi)
            .setDeleteIntent(pi)
            .build()
    }
}
