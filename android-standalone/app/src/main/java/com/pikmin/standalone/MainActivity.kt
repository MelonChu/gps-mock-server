package com.pikmin.standalone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pikmin.standalone.services.FloatingControlService
import com.pikmin.standalone.utils.StepInjector
import kotlinx.coroutines.*

/**
 * 主畫面 — 權限管理與啟動控制
 *
 * 引導使用者授予 3 項必要權限：
 * 1. 懸浮視窗 (SYSTEM_ALERT_WINDOW)
 * 2. 模擬位置 (ACCESS_MOCK_LOCATION)
 * 3. 健康資料 (Health Connect)
 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_HEALTH_PERMS = 1002
        private const val REQUEST_NOTIFICATION = 1003
    }

    private lateinit var permOverlay: TextView
    private lateinit var permMock: TextView
    private lateinit var permHealth: TextView
    private lateinit var btnPermissions: Button
    private lateinit var btnStart: Button
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stepInjector: StepInjector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permOverlay = findViewById(R.id.perm_overlay)
        permMock = findViewById(R.id.perm_mock)
        permHealth = findViewById(R.id.perm_health)
        btnPermissions = findViewById(R.id.btn_permissions)
        btnStart = findViewById(R.id.btn_start)

        stepInjector = StepInjector(this)

        btnPermissions.setOnClickListener { requestAllPermissions() }
        btnStart.setOnClickListener { startFloatingService() }

        // 如果已經有「懸浮視窗」權限，直接啟動服務
        if (checkOverlayPermission()) {
            btnStart.isEnabled = true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ====================================================================
    // 權限檢查
    // ====================================================================
    private fun refreshPermissionStatus() {
        // ① 懸浮視窗
        if (checkOverlayPermission()) {
            permOverlay.text = "✅ 懸浮視窗"
            permOverlay.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            permOverlay.text = "❌ 懸浮視窗"
            permOverlay.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }

        // ② 模擬位置
        if (checkMockLocation()) {
            permMock.text = "✅ 模擬位置"
            permMock.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            permMock.text = "⚠️ 模擬位置（未設定）"
            permMock.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        }

        // ③ Health Connect
        scope.launch {
            val healthOk = stepInjector?.hasWritePermission() == true
            if (healthOk) {
                permHealth.text = "✅ 健康資料"
                permHealth.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
            } else {
                permHealth.text = "❌ 健康資料"
                permHealth.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
            }
        }

        // 懸浮視窗權限通過後才可啟動
        btnStart.isEnabled = checkOverlayPermission()
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun checkMockLocation(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            lm.addTestProvider("TempCheck", false, false, false, false,
                false, false, false,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE)
            lm.removeTestProvider("TempCheck")
            true
        } catch (e: SecurityException) { false }
        catch (e: Exception) { false }
    }

    // ====================================================================
    // 權限請求
    // ====================================================================
    private fun requestAllPermissions() {
        // ① 懸浮視窗
        if (!checkOverlayPermission()) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }

        // ② 通知權限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
                return
            }
        }

        // ③ 模擬位置 — 引導至開發者選項
        if (!checkMockLocation()) {
            AlertDialog.Builder(this)
                .setTitle("需要模擬位置權限")
                .setMessage("請到「開發者選項」→「選擇模擬位置資訊應用程式」\n→ 選擇「Pikmin 助手」\n\n" +
                        "若無開發者選項，請到「關於手機」點版本號碼 7 次")
                .setPositiveButton("前往設定") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
                .setNegativeButton("稍後", null)
                .show()
            return
        }

        // ④ Health Connect
        scope.launch {
            if (stepInjector?.isAvailable() == true) {
                try {
                    val intent = stepInjector?.getPermissionIntent()
                    if (intent != null) {
                        startActivityForResult(intent, REQUEST_HEALTH_PERMS)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Health Connect 需要先安裝", Toast.LENGTH_LONG).show()
                }
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Health Connect")
                    .setMessage("需要安裝 Google Health Connect（Play Store）才能寫入步數。\n" +
                            "安裝後請將 Health Connect 連接到 Google Fit / Samsung Health。")
                    .setPositiveButton("前往安裝", { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                    })
                    .setNegativeButton("跳過", null)
                    .show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refreshPermissionStatus()
        // 如果懸浮權限給了，自動繼續請求下一個
        if (requestCode == REQUEST_OVERLAY && checkOverlayPermission()) {
            requestAllPermissions()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissionStatus()
    }

    // ====================================================================
    // 啟動服務
    // ====================================================================
    private fun startFloatingService() {
        if (!checkOverlayPermission()) {
            Toast.makeText(this, "請先授予懸浮視窗權限", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, FloatingControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "懸浮控制已啟動 🎮", Toast.LENGTH_SHORT).show()
        finish()  // 關掉設定畫面，讓玩家回到遊戲
    }
}
