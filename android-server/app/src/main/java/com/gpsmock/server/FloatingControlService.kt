package com.gpsmock.server

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.gpsmock.server.views.JoystickView

class FloatingControlService : Service() {
    companion object {
        private const val CHANNEL = "float_ctrl"
        private const val NID = 1001
        const val TOGGLE = "com.gpsmock.server.TOGGLE_FLOAT"
    }

    private lateinit var wm: WindowManager
    private var overlay: View? = null
    private var isRoam = false
    private var gpsIntent: Intent? = null

    override fun onCreate() { super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager; createChan() }
    override fun onStartCommand(i: Intent?, f: Int, si: Int): Int {
        when (i?.action) { TOGGLE -> { if (overlay == null) show() else hide() } }
        startForeground(NID, notif("懸浮控制 🎮")); show(); return START_STICKY
    }
    override fun onBind(i: Intent?) = null
    override fun onDestroy() { hide(); stopGps(); super.onDestroy() }

    private fun show() {
        if (overlay != null) return
        val inf = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlay = inf.inflate(R.layout.overlay_controls, null).apply {
            findViewById<JoystickView>(R.id.joystick).setListener(object : JoystickView.Listener {
                override fun onMove(angle: Double, dist: Float) {
                    sendBear(if (dist < 0.15f) -1.0 else angle)
                }
            })
            findViewById<ToggleButton>(R.id.toggle_roam).setOnCheckedChangeListener { _, c ->
                isRoam = c; findViewById<TextView>(R.id.roam_label).text = "游蕩 ${if(c) "🌙" else "✋"}"
                sendRoam(c)
            }
            findViewById<View>(R.id.drag_handle).setOnTouchListener { v, e -> drag(v, e); true }
            findViewById<View>(R.id.btn_minimize).setOnClickListener { hide() }
        }
        wm.addView(overlay, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 300 })
        startGps()
    }

    private fun hide() { overlay?.let { try { wm.removeView(it) } catch(_: Exception){} }; overlay = null }

    private var ix = 0; private var iy = 0; private var itx = 0f; private var ity = 0f
    private fun drag(v: View, e: MotionEvent): Boolean {
        val p = v.layoutParams as WindowManager.LayoutParams
        when(e.action) {
            MotionEvent.ACTION_DOWN -> { ix=p.x; iy=p.y; itx=e.rawX; ity=e.rawY }
            MotionEvent.ACTION_MOVE -> { p.x = ix+(e.rawX-itx).toInt(); p.y = iy+(e.rawY-ity).toInt(); wm.updateViewLayout(v, p) }
        }; return false
    }

    private fun startGps() { gpsIntent = Intent(this, GpsMockService::class.java).apply { action = GpsMockService.START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(gpsIntent!!) else startService(gpsIntent!!) }
    private fun stopGps() { gpsIntent?.let { it.action = GpsMockService.STOP; startService(it) }; gpsIntent = null }
    private fun sendRoam(r: Boolean) { Intent(this, GpsMockService::class.java).apply { action = GpsMockService.CMD_ROAM; putExtra(GpsMockService.EXTRA_ROAM, r) }.also { startService(it) } }
    private fun sendBear(b: Double) { Intent(this, GpsMockService::class.java).apply { action = GpsMockService.CMD_BEAR; putExtra(GpsMockService.EXTRA_BEAR, b) }.also { startService(it) } }
    private fun createChan() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Pikmin 助手", NotificationManager.IMPORTANCE_LOW)) }
    private fun notif(t: String) = NotificationCompat.Builder(this, CHANNEL).setContentTitle("Pikmin 助手").setContentText(t).setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).build()
}
