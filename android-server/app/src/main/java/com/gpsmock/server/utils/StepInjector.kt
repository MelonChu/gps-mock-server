package com.gpsmock.server.utils

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.InsertRecordsRequest
import java.time.ZoneId
import java.time.ZonedDateTime

class StepInjector(private val ctx: Context) {
    companion object {
        private const val TAG = "StepInject"
        val PERMS = setOf(HealthPermission.getWritePermission(StepsRecord::class))
    }
    private val client by lazy { HealthConnectClient.getOrCreate(ctx) }

    fun isAvailable(): Boolean = try {
        client.provider.availability is androidx.health.connect.client.records.Availability.Installed
    } catch (_: Exception) { false }

    suspend fun canWrite(): Boolean = try {
        client.permissionController.getGrantedPermissions().containsAll(PERMS)
    } catch (_: Exception) { false }

    fun getPermIntent() = client.permissionController.getPermissionIntent(PERMS)

    suspend fun inject(count: Long) {
        if (count <= 0 || !canWrite()) return
        val start = ZonedDateTime.now().toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end = start.plusSeconds(86399)
        try {
            client.insertRecords(InsertRecordsRequest(listOf(
                StepsRecord(count = minOf(count, 15000), startTime = start, endTime = end,
                    startZoneOffset = null, endZoneOffset = null))))
            Log.i(TAG, "✅ +$count 步")
        } catch (e: Exception) { Log.e(TAG, "步數注入失敗", e) }
    }

    suspend fun injectGradual() { if (isAvailable() && canWrite()) inject((50..150).random().toLong()) }
}
