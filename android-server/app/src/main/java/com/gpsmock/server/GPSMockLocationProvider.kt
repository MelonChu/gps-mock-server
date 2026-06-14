package com.gpsmock.server

import android.content.Context
import android.location.Criteria
import android.location.LocationManager
import android.util.Log

/**
 * GPS Mock Location Provider 輔助類別
 * ======================================
 *
 * 負責在 Android LocationManager 中註冊與移除測試用的 Location Provider。
 *
 * 設定方式 (必讀)：
 * 1. 開啟手機「設定」→「開發者選項」
 * 2. 找到「選擇模擬位置資訊應用程式」
 * 3. 選擇此 App (GPS Mock Server)
 * 4. 如果開發者選項沒有此項目，請先開啟「USB 偵錯」
 *
 * 注意：
 * - Android 6.0+ (API 23+) 需要使用 `android.permission.ACCESS_MOCK_LOCATION`
 * - 此權限無法在運行時請求，必須透過開發者選項手動設定
 * - 部分中國品牌手機 (華為、小米、OPPO) 可能需要額外開啟「允許模擬位置」
 */
object GPSMockLocationProvider {

    private const val TAG = "GPSMockProvider"

    /** Mock Location Provider 名稱 */
    const val PROVIDER_NAME = "GPSMockProvider"

    /**
     * 在 LocationManager 中註冊此 App 為 Mock Location Provider。
     *
     * 註冊成功後，此 App 即可使用 setTestProviderLocation() 注入
     * 模擬的 GPS 座標。所有監聽標準 GPS Provider 的 App 都會收到
     * 這些座標更新。
     *
     * @param locationManager Android LocationManager 實例
     * @param context Context (用於檢查權限)
     * @return true 如果註冊成功
     */
    fun registerMockProvider(locationManager: LocationManager, context: Context): Boolean {
        try {
            // 移除舊的 Provider 後重新註冊 (避免重複註冊錯誤)
            removeMockProvider(locationManager)

            // 建立 Provider 的品質條件
            val criteria = Criteria().apply {
                accuracy = Criteria.ACCURACY_FINE       // 高精度 GPS
                powerRequirement = Criteria.POWER_LOW    // 低耗電（模擬不需要真實 GPS）
                isAltitudeRequired = true
                isBearingRequired = true
                isSpeedRequired = true
                isCostAllowed = false
            }

            // 註冊為測試 Provider
            locationManager.addTestProvider(
                PROVIDER_NAME,
                criteria.isAltitudeRequired,
                criteria.isBearingRequired,
                criteria.isSpeedRequired,
                false,           // 不需要監聽者變更
                criteria.isCostAllowed,
                criteria.isAltitudeRequired,
                criteria.isBearingRequired,
                criteria.isSpeedRequired,
            )

            // 啟用 Provider
            locationManager.setTestProviderEnabled(PROVIDER_NAME, true)

            Log.i(TAG, "Mock Location Provider 已成功註冊並啟用")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG,
                "權限不足 — 請在開發者選項中，\n" +
                "將「選擇模擬位置資訊應用程式」設為此 App。\n" +
                "詳細步驟：設定 → 開發者選項 → 選擇模擬位置資訊應用程式。\n" +
                "若無開發者選項，請先到「關於手機」連續點擊版本號 7 次開啟。"
            )
            return false

        } catch (e: IllegalArgumentException) {
            // Android 13+ 可能會因為 Provider 已存在而拋出此例外
            // 檢查是否已經被其他 App 佔用
            Log.w(TAG, "Provider 可能已被其他 App 註冊: ${e.message}")

            // 嘗試檢查現有 Provider
            try {
                val providers = locationManager.allProviders
                Log.i(TAG, "已註冊的 Provider: ${providers.joinToString()}")
            } catch (_: Exception) {}

            return false

        } catch (e: Exception) {
            Log.e(TAG, "註冊 Mock Provider 失敗: ${e.message}", e)
            return false
        }
    }

    /**
     * 從 LocationManager 移除 Mock Location Provider。
     *
     * 應在 Service 銷毀時呼叫，以確保系統回到真實 GPS 模式。
     *
     * @param locationManager Android LocationManager 實例
     */
    fun removeMockProvider(locationManager: LocationManager?) {
        if (locationManager == null) return

        try {
            // 停用 Provider
            locationManager.setTestProviderEnabled(PROVIDER_NAME, false)
            // 移除 Provider
            locationManager.removeTestProvider(PROVIDER_NAME)
            Log.i(TAG, "Mock Location Provider 已移除")
        } catch (e: SecurityException) {
            Log.w(TAG, "移除 Provider 時權限不足: ${e.message}")
        } catch (e: IllegalArgumentException) {
            // Provider 不存在，忽略
            Log.d(TAG, "Provider 不存在，無需移除")
        } catch (e: Exception) {
            Log.w(TAG, "移除 Provider 時發生錯誤: ${e.message}")
        }
    }
}
