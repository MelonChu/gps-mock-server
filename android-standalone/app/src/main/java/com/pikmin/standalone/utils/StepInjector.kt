package com.pikmin.standalone.utils

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.InsertRecordsRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 步數注入器 — 透過 Health Connect API 將步數寫入手機健康系統
 *
 * 流程：
 * Health Connect → Google Fit / Samsung Health → Pikmin Bloom
 *
 * 使用方式：
 * 1. 安裝 Health Connect (Play Store)
 * 2. 將 Health Connect 連接到 Google Fit
 * 3. 授予此 App 寫入步數的權限
 * 4. Pikmin Bloom 會自動讀取到新增的步數
 */
class StepInjector(private val context: Context) {
    companion object {
        private const val TAG = "StepInjector"

        /** 每日可注入的最大步數（避免被偵測）*/
        private const val MAX_DAILY_STEPS = 15000

        /** Health Connect 要求的權限 */
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getWritePermission(StepsRecord::class)
        )
    }

    private val healthClient by lazy { HealthConnectClient.getOrCreate(context) }

    /** 檢查 Health Connect 是否可用 */
    fun isAvailable(): Boolean {
        return try {
            val availability = healthClient.provider.availability
            Log.d(TAG, "HealthConnect 可用性: $availability")
            availability is androidx.health.connect.client.records.Availability.Installed
        } catch (e: Exception) {
            Log.e(TAG, "HealthConnect 不可用: ${e.message}")
            false
        }
    }

    /** 檢查是否已有寫入權限 */
    suspend fun hasWritePermission(): Boolean {
        return try {
            val granted = healthClient.permissionController.getGrantedPermissions()
            granted.containsAll(REQUIRED_PERMISSIONS)
        } catch (e: Exception) {
            Log.e(TAG, "權限檢查失敗: ${e.message}")
            false
        }
    }

    /** 取得權限 Intent */
    fun getPermissionIntent() = healthClient.permissionController.getPermissionIntent(REQUIRED_PERMISSIONS)

    /** 注入步數 */
    suspend fun injectSteps(count: Long) {
        if (count <= 0) return
        if (!hasWritePermission()) {
            Log.w(TAG, "無寫入權限，略過")
            return
        }

        val now = ZonedDateTime.now()
        val startOfDay = now.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val endOfDay = startOfDay.plusSeconds(86399)

        // 不超過每日上限
        val safeCount = minOf(count, MAX_DAILY_STEPS.toLong())

        try {
            val record = StepsRecord(
                count = safeCount,
                startTime = startOfDay,
                endTime = endOfDay,
                startZoneOffset = null,
                endZoneOffset = null,
            )
            healthClient.insertRecords(InsertRecordsRequest(listOf(record)))
            Log.i(TAG, "✅ 已注入 $safeCount 步 (每日累計)")
        } catch (e: Exception) {
            Log.e(TAG, "注入步數失敗: ${e.message}")
        }
    }

    /** 分段注入步數（每 5 分鐘加一些步數，更自然）*/
    suspend fun injectStepsGradual() {
        if (!isAvailable() || !hasWritePermission()) return

        // 每次加 50~150 步（約 1~3 分鐘的步行量）
        val steps = (50..150).random().toLong()
        injectSteps(steps)
        Log.d(TAG, "分段注入 $steps 步")
    }
}
