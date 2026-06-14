package com.gpsmock.server

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GPS 模擬伺服器 — Android 前景服務
 * =====================================
 *
 * 功能：
 * 1. 在指定埠號上開啟 TCP Socket 伺服器
 * 2. 接收桌面客戶端傳送的 GPS 座標 (JSON)
 * 3. 將座標注入 Android LocationManager 作為 Mock Location
 * 4. 定期回傳心跳確認 (Heartbeat Ack)
 * 5. 斷線自動關閉
 *
 * 權限需求：
 * - android.permission.ACCESS_MOCK_LOCATION (在 Developer Options 設為 Mock Location App)
 * - android.permission.FOREGROUND_SERVICE
 * - android.permission.WAKE_LOCK (保持 CPU 喚醒)
 *
 * @see MainActivity 啟動此 Service 的入口 Activity
 */
class GPSServerService : Service() {

    companion object {
        private const val TAG = "GPSServerService"
        private const val CHANNEL_ID = "gps_mock_channel"
        private const val NOTIFICATION_ID = 1001

        // --- 協定常數 (須與桌面客戶端一致) ---
        const val MSG_HANDSHAKE = "handshake"
        const val MSG_HANDSHAKE_ACK = "handshake_ack"
        const val MSG_GPS_UPDATE = "gps_update"
        const val MSG_HEARTBEAT = "heartbeat"
        const val MSG_HEARTBEAT_ACK = "heartbeat_ack"
        const val MSG_DISCONNECT = "disconnect"
        const val MSG_ERROR = "error"
        const val MSG_COMMAND = "command"
    }

    // ====================================================================
    // 資料類別 — 與桌面客戶端的 JSON 對應
    // ====================================================================
    @Serializable
    data class Message(
        val type: String,
        val data: GpsData? = null,
        val timestamp: Double? = null,
        val message: String? = null,
        val protocol_version: Int? = null,
        val client_name: String? = null,
        val command: String? = null,
        val params: Map<String, String>? = null,
    )

    @Serializable
    data class GpsData(
        val lat: Double,
        val lon: Double,
        val altitude: Double = 0.0,
        val accuracy: Double = 5.0,
        val speed: Double = 0.0,
        val bearing: Double = 0.0,
        val timestamp: Double = 0.0,
    )

    // ====================================================================
    // 內部狀態
    // ====================================================================
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val isRunning = AtomicBoolean(false)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientWriter: PrintWriter? = null
    private var clientReader: BufferedReader? = null
    private var locationManager: LocationManager? = null
    private var powerWakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 最後收到的心跳時間，用於偵測客戶端斷線
    private var lastHeartbeatTime = 0L
    private var connectedClientCount = AtomicBoolean(false)

    // Mock Location Provider 名稱
    private val mockProviderName = GPSMockLocationProvider.PROVIDER_NAME

    // ====================================================================
    // Service 生命週期
    // ====================================================================
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service 建立中...")
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("PORT", 8888) ?: 8888
        Log.i(TAG, "Service 啟動，埠號: $port")

        // 顯示前景通知
        startForeground(NOTIFICATION_ID, buildNotification("啟動中..."))

        // 初始化 LocationManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 註冊 Mock Location Provider
        if (!GPSMockLocationProvider.registerMockProvider(locationManager!!, this)) {
            Log.e(TAG, "無法註冊 Mock Location Provider")
            stopSelf()
            return START_NOT_STICKY
        }

        // 啟動 TCP Server
        startTcpServer(port)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service 銷毀中...")
        stopTcpServer()
        GPSMockLocationProvider.removeMockProvider(locationManager)
        releaseWakeLock()
        serviceScope.cancel()
        isRunning.set(false)
        super.onDestroy()
    }

    // ====================================================================
    // TCP 伺服器
    // ====================================================================
    private fun startTcpServer(port: Int) {
        if (isRunning.getAndSet(true)) return

        serverJob = serviceScope.launch {
            try {
                Log.i(TAG, "正在監聽埠號 $port ...")
                serverSocket = ServerSocket(port)
                serverSocket?.soTimeout = 5000  // accept() 每 5 秒超時一次，用於檢查 isRunning

                updateNotification("等待連線... 埠號: $port")

                while (isRunning.get() && !serverSocket!!.isClosed) {
                    try {
                        // 等待客戶端連線
                        val client = serverSocket!!.accept()
                        Log.i(TAG, "客戶端連線: ${client.inetAddress.hostAddress}")
                        updateNotification("已連線: ${client.inetAddress.hostAddress}")

                        handleClient(client)

                    } catch (e: SocketTimeoutException) {
                        // accept() 超時，繼續迴圈檢查 isRunning
                        continue
                    } catch (e: IOException) {
                        if (isRunning.get()) {
                            Log.e(TAG, "接受連線時發生 IO 錯誤: ${e.message}")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "TCP 伺服器錯誤: ${e.message}", e)
                sendErrorNotification("伺服器錯誤: ${e.message}")
            } finally {
                Log.i(TAG, "TCP 伺服器已停止")
                updateNotification("已停止")
                isRunning.set(false)
            }
        }
    }

    /**
     * 處理單一客戶端連線。
     * 此方法為阻塞式，以逐行方式讀取客戶端訊息。
     * 當客戶端斷線或發生錯誤時返回。
     */
    private suspend fun handleClient(client: Socket) {
        clientSocket = client
        connectedClientCount.set(true)

        try {
            client.soTimeout = 30000  // 30 秒無資料視為超時
            clientWriter = PrintWriter(client.getOutputStream(), true)
            clientReader = BufferedReader(InputStreamReader(client.getInputStream()))

            var line: String?
            var handshakeDone = false
            lastHeartbeatTime = System.currentTimeMillis()

            // 主訊息處理迴圈
            while (isRunning.get() && !client.isClosed) {
                try {
                    line = clientReader?.readLine()
                } catch (e: SocketTimeoutException) {
                    // 讀取超時 — 檢查心跳
                    if (handshakeDone) {
                        val elapsed = System.currentTimeMillis() - lastHeartbeatTime
                        if (elapsed > 20000) {  // 20 秒無心跳視為斷線
                            Log.w(TAG, "客戶端心跳超時 (${elapsed}ms)，關閉連線")
                            sendToClient(buildJsonMessage(MSG_DISCONNECT, message = "心跳超時"))
                        }
                    }
                    continue
                }

                if (line == null) {
                    Log.i(TAG, "客戶端已斷線 (EOF)")
                    break
                }

                // 解析 JSON 訊息
                try {
                    val message = json.decodeFromString<Message>(line.trim())
                    handleMessage(message, handshakeDone)
                    // 交握完成後標記
                    if (message.type == MSG_HANDSHAKE) {
                        handshakeDone = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "無法解析訊息: ${line.take(100)} — ${e.message}")
                    sendToClient(buildJsonMessage(
                        MSG_ERROR, message = "無效的 JSON 格式"
                    ))
                }

            } // end while

        } catch (e: Exception) {
            Log.e(TAG, "客戶端處理錯誤: ${e.message}", e)
        } finally {
            Log.i(TAG, "客戶端連線關閉")
            connectedClientCount.set(false)
            updateNotification("等待連線...")
            closeClientConnection()
        }
    }

    /**
     * 根據訊息類型執行對應處理。
     */
    private fun handleMessage(message: Message, handshakeDone: Boolean) {
        when (message.type) {
            MSG_HANDSHAKE -> {
                Log.i(TAG, "收到交握: ${message.client_name ?: "未知客戶端"} " +
                        "(協定 v${message.protocol_version ?: 0})")
                sendToClient(buildJsonMessage(MSG_HANDSHAKE_ACK, message = "ok"))
            }

            MSG_GPS_UPDATE -> {
                val gps = message.data
                if (gps != null) {
                    injectMockLocation(gps)
                } else {
                    Log.w(TAG, "收到 GPS_UPDATE 但無 data 欄位")
                }
            }

            MSG_HEARTBEAT -> {
                // 更新心跳時間並回覆
                lastHeartbeatTime = System.currentTimeMillis()
                sendToClient(buildJsonMessage(MSG_HEARTBEAT_ACK))
            }

            MSG_DISCONNECT -> {
                Log.i(TAG, "客戶端要求斷線")
                // 客戶端主動斷線，關閉 Socket
                try { clientSocket?.close() } catch (_: Exception) {}
            }

            else -> {
                Log.w(TAG, "未知訊息類型: ${message.type}")
            }
        }
    }

    // ====================================================================
    // Mock Location 注入
    // ====================================================================
    /**
     * 將接收到的 GPS 座標注入 Android 的 Mock Location Provider。
     *
     * 此方法會將座標設為 Android 系統層級的模擬位置，
     * 使所有使用 LocationManager 的 App（包含遊戲）都能接收到此位置。
     */
    private fun injectMockLocation(gps: GpsData) {
        try {
            val lm = locationManager ?: return

            val mockLocation = Location(mockProviderName).apply {
                latitude = gps.lat
                longitude = gps.lon
                accuracy = gps.accuracy.toFloat()
                speed = gps.speed.toFloat()
                bearing = gps.bearing.toFloat()
                altitude = gps.altitude
                time = System.currentTimeMillis()
                // Android 10+ 需要設定 elapsedRealtimeNanos
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
            }

            // 設定模擬位置
            lm.setTestProviderLocation(mockProviderName, mockLocation)

            Log.v(TAG, "注入位置: (${gps.lat}, ${gps.lon}) " +
                    "速度=${gps.speed} m/s 方向=${gps.bearing}°")

        } catch (e: SecurityException) {
            Log.e(TAG, "權限不足: 請確認已在開發者選項中設定此 App 為 Mock Location App")
            sendToClient(buildJsonMessage(
                MSG_ERROR,
                message = "Mock Location 權限不足"
            ))
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Provider 未註冊: $mockProviderName")
            // 嘗試重新註冊
            GPSMockLocationProvider.registerMockProvider(locationManager!!, this)
        } catch (e: Exception) {
            Log.e(TAG, "注入位置時發生錯誤: ${e.message}")
        }
    }

    // ====================================================================
    // 工具方法
    // ====================================================================
    /**
     * 建立 JSON 訊息字串。
     */
    private fun buildJsonMessage(
        type: String,
        message: String? = null,
        data: GpsData? = null,
    ): String {
        val msg = Message(
            type = type,
            message = message,
            data = data,
            timestamp = System.currentTimeMillis() / 1000.0,
        )
        return json.encodeToString(msg) + "\n"
    }

    /**
     * 傳送 JSON 訊息至客戶端。
     */
    private fun sendToClient(jsonStr: String): Boolean {
        return try {
            clientWriter?.println(jsonStr)
            clientWriter?.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "傳送訊息失敗: ${e.message}")
            false
        }
    }

    /**
     * 安全關閉客戶端連線。
     */
    private fun closeClientConnection() {
        try { clientWriter?.close() } catch (_: Exception) {}
        try { clientReader?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        clientWriter = null
        clientReader = null
        clientSocket = null
    }

    /**
     * 停止 TCP 伺服器。
     */
    private fun stopTcpServer() {
        serverJob?.cancel()
        closeClientConnection()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        connectedClientCount.set(false)
    }

    // ====================================================================
    // 前景通知
    // ====================================================================
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS 模擬伺服器",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GPS 模擬伺服器正在背景運行"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS 模擬伺服器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun sendErrorNotification(text: String) {
        updateNotification("⚠ $text")
    }

    // ====================================================================
    // Wake Lock — 防止手機休眠
    // ====================================================================
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GPSServerService:WakeLock"
            )
            powerWakeLock?.acquire(8 * 60 * 60 * 1000L)  // 最長 8 小時
            Log.d(TAG, "Wake Lock 已取得")
        } catch (e: Exception) {
            Log.w(TAG, "無法取得 Wake Lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (powerWakeLock?.isHeld == true) {
                powerWakeLock?.release()
                Log.d(TAG, "Wake Lock 已釋放")
            }
        } catch (e: Exception) {
            Log.w(TAG, "釋放 Wake Lock 時發生錯誤: ${e.message}")
        }
    }
}
