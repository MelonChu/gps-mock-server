package com.gpsmock.server

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * GPS 模擬伺服器 — 主 Activity
 * ==============================
 *
 * 提供 UI 控制介面：
 * - 顯示本機 IP 與監聽埠號
 * - 啟動／停止伺服器
 * - 引導使用者開啟開發者選項與 Mock Location 設定
 * - 顯示連線狀態
 *
 * 運行前檢查 (Preflight)：
 * 1. 檢查 App 是否被設為 Mock Location 應用程式
 * 2. 檢查開發者選項是否開啟
 * 3. 檢查 Wi-Fi 連線
 * 4. 顯示本機 IP
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_PORT = 8888

        // 用於偵測開發者選項的設定值
        private const val SETTINGS_DEVELOPMENT_ENABLED = "development_settings_enabled"
    }

    // UI 元件
    private lateinit var ipAddressText: TextView
    private lateinit var portText: TextView
    private lateinit var statusText: TextView
    private lateinit var startStopButton: Button
    private lateinit var clientInfoText: TextView

    // 服務狀態
    private var isServiceRunning = false

    // Wi-Fi 狀態接收器
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateIpAddress()
        }
    }

    // ====================================================================
    // Activity 生命週期
    // ====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 綁定 UI 元件
        ipAddressText = findViewById(R.id.ip_address)
        portText = findViewById(R.id.port)
        statusText = findViewById(R.id.status)
        startStopButton = findViewById(R.id.start_stop_button)
        clientInfoText = findViewById(R.id.client_info)

        // 預設埠號
        portText.text = DEFAULT_PORT.toString()

        // 設定按鈕事件
        startStopButton.setOnClickListener { onStartStopClicked() }

        // 註冊 Wi-Fi 狀態變化監聽
        registerReceiver(
            wifiReceiver,
            IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        )
    }

    override fun onResume() {
        super.onResume()

        // 更新 IP
        updateIpAddress()

        // 檢查服務狀態
        updateServiceStatus()

        // 檢查是否已設定 Mock Location
        if (!isMockLocationApp()) {
            showMockLocationSetupDialog()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(wifiReceiver) } catch (_: Exception) {}
    }

    // ====================================================================
    // 按鈕事件
    // ====================================================================
    private fun onStartStopClicked() {
        if (isServiceRunning) {
            // 停止服務
            stopService()
        } else {
            // 啟動服務 — 先執行運行前檢查
            if (!preflightCheck()) {
                return
            }
            startService()
        }
    }

    // ====================================================================
    // 運行前檢查
    // ====================================================================
    /**
     * 啟動服務前的完整檢查。
     *
     * 檢查項目：
     * 1. App 是否已設為 Mock Location App
     * 2. 開發者選項是否開啟
     * 3. Wi-Fi 是否連線且 IP 可用
     * 4. 是否有網路權限
     *
     * @return true 如果所有檢查通過
     */
    private fun preflightCheck(): Boolean {
        // 檢查 1: Mock Location 權限
        if (!isMockLocationApp()) {
            showAlert(
                "需要 Mock Location 權限",
                "請先在「開發者選項」→「選擇模擬位置資訊應用程式」\n" +
                "將此 App 設為模擬位置應用程式。\n\n" +
                "如果找不到開發者選項，請到「關於手機」\n" +
                "連續點擊「版本號碼」7 次。"
            )
            return false
        }

        // 檢查 2: 開發者選項是否開啟
        if (!isDeveloperOptionsEnabled()) {
            showAlert(
                "開發者選項未開啟",
                "請到「設定」→「關於手機」\n" +
                "連續點擊「版本號碼」7 次開啟開發者選項。"
            )
            return false
        }

        // 檢查 3: Wi-Fi 連線
        val wifiManager = applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiManager.connectionInfo?.ssid == "<unknown ssid>" ||
            wifiManager.connectionInfo?.ssid == null
        ) {
            showAlert(
                "Wi-Fi 未連線",
                "請確認手機已連線至 Wi-Fi。\n" +
                "桌面客戶端將透過 Wi-Fi 與手機通訊。"
            )
            return false
        }

        // 檢查 4: IP 位址
        val ip = getLocalIpAddress()
        if (ip == null || ip == "0.0.0.0") {
            showAlert(
                "無法取得 IP 位址",
                "請確認 Wi-Fi 連線正常。\n" +
                "可能需要重新連線 Wi-Fi 或重新開機。"
            )
            return false
        }

        return true
    }

    /**
     * 檢查此 App 是否已被設定為 Mock Location 應用程式。
     *
     * Android 6.0+ 需要透過開發者選項手動設定，
     * 無法在運行時請求此權限。
     */
    private fun isMockLocationApp(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // 嘗試註冊 provider，如果能成功則表示有權限
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                val tempProps = android.location.provider.ProviderProperties.Builder()
                    .setHasAltitude(false)
                    .setHasBearing(false)
                    .setHasSpeed(false)
                    .build()
                lm.addTestProvider("TempCheckProvider", tempProps)
                lm.removeTestProvider("TempCheckProvider")
                return true
            } catch (e: SecurityException) {
                return false
            } catch (e: Exception) {
                return false
            }
        }
        // Android 5.x 及更早版本不需要此設定
        return true
    }

    /**
     * 檢查開發者選項是否已開啟。
     */
    private fun isDeveloperOptionsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return Settings.Global.getInt(
                contentResolver,
                SETTINGS_DEVELOPMENT_ENABLED,
                0
            ) == 1
        }
        return true
    }

    // ====================================================================
    // 服務控制
    // ====================================================================
    private fun startService() {
        val intent = Intent(this, GPSServerService::class.java).apply {
            putExtra("PORT", DEFAULT_PORT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isServiceRunning = true
        updateServiceStatus()

        Toast.makeText(this, "伺服器已啟動，埠號: $DEFAULT_PORT", Toast.LENGTH_SHORT).show()
    }

    private fun stopService() {
        val intent = Intent(this, GPSServerService::class.java)
        stopService(intent)
        isServiceRunning = false
        updateServiceStatus()

        Toast.makeText(this, "伺服器已停止", Toast.LENGTH_SHORT).show()
    }

    // ====================================================================
    // UI 更新
    // ====================================================================
    private fun updateServiceStatus() {
        if (isServiceRunning) {
            statusText.text = "運行中"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            startStopButton.text = "停止伺服器"
        } else {
            statusText.text = "已停止"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            startStopButton.text = "啟動伺服器"
        }
    }

    private fun updateIpAddress() {
        val ip = getLocalIpAddress()
        ipAddressText.text = ip ?: "無法取得 IP"
    }

    /**
     * 取得裝置 Wi-Fi 的區域網路 IP 位址。
     *
     * 此 IP 為桌面客戶端連線至手機時需要填入的目標 IP。
     */
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces?.hasMoreElements() == true) {
                val networkInterface = interfaces.nextElement()
                // 跳過 loopback (127.0.0.1) 與未啟用的介面
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                // 優先使用 wlan (Wi-Fi) 介面
                if (!networkInterface.name.contains("wlan", ignoreCase = true)) continue

                val addresses = networkInterface.inetAddresses
                while (addresses?.hasMoreElements() == true) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }

            // 備用：遍歷所有介面 (可能包含 hotspot 等)
            val allInterfaces = NetworkInterface.getNetworkInterfaces()
            while (allInterfaces?.hasMoreElements() == true) {
                val networkInterface = allInterfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses?.hasMoreElements() == true) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        // 排除 0.0.0.0
                        val ip = addr.hostAddress ?: continue
                        if (ip != "0.0.0.0") {
                            return ip
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "取得 IP 位址時發生錯誤: ${e.message}")
        }
        return null
    }

    // ====================================================================
    // 對話方塊
    // ====================================================================
    private fun showMockLocationSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要設定 Mock Location")
            .setMessage(
                "此 App 需要被設為「模擬位置資訊應用程式」才能運作。\n\n" +
                "請依以下步驟設定：\n\n" +
                "1. 開啟「設定」→「開發者選項」\n" +
                "2. 找到「選擇模擬位置資訊應用程式」\n" +
                "3. 選擇「GPS Mock Server」\n\n" +
                "若無開發者選項：\n" +
                "設定 → 關於手機 → 點擊版本號碼 7 次"
            )
            .setPositiveButton("前往設定") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            .setNegativeButton("稍後再說", null)
            .show()
    }

    private fun showAlert(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("我知道了", null)
            .show()
    }
}
