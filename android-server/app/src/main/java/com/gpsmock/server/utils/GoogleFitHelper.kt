package com.gpsmock.server.utils

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.DataSet
import com.google.android.gms.fitness.data.DataSource
import com.google.android.gms.fitness.data.DataType
import com.google.android.gms.fitness.data.DataPoint
import com.google.android.gms.fitness.data.Field
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit
import java.time.ZonedDateTime
import java.time.ZoneId

/**
 * Google Fit 步數寫入 — 直接在我們 App 內完成
 * Pikmin Bloom 會自動從 Google Fit 讀取步數
 */
class GoogleFitHelper(private val ctx: Context) {
    companion object {
        private const val TAG = "GFitHelper"
        private const val MAX_DAILY = 15000
    }

    fun getFitnessOptions() = FitnessOptions.builder()
        .addDataType(DataType.TYPE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_WRITE)
        .addDataType(DataType.AGGREGATE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_READ)
        .build()

    fun hasPermission(): Boolean {
        val a = GoogleSignIn.getAccountForExtension(ctx, getFitnessOptions()) ?: return false
        return GoogleSignIn.hasPermissions(a, getFitnessOptions())
    }

    fun getAccount(): GoogleSignInAccount? = try {
        GoogleSignIn.getAccountForExtension(ctx, getFitnessOptions())
    } catch (_: Exception) { null }

    /** 寫入步數到 Google Fit */
    fun writeSteps(steps: Long): Boolean {
        val account = getAccount() ?: run { Log.w(TAG, "未登入"); return false }
        val count = minOf(steps, MAX_DAILY.toLong())
        if (count <= 0) return false

        try {
            val now = ZonedDateTime.now()
            val start = now.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val end = start.plusSeconds(86399)

            val source = DataSource.builder()
                .setAppPackageName(ctx)
                .setDataType(DataType.TYPE_STEP_COUNT_DELTA)
                .setType(DataSource.TYPE_RAW)
                .build()

            val point = DataPoint.builder(source)
                .setTimestamp(start.toEpochMilli(), TimeUnit.MILLISECONDS)
                .setField(Field.FIELD_STEPS, count.toInt())
                .build()

            val dataSet = DataSet.builder(source).addDataPoint(point).build()

            Tasks.await(
                Fitness.getHistoryClient(ctx, account).insertData(dataSet),
                30, TimeUnit.SECONDS
            )
            Log.i(TAG, "✅ +$count 步 → Google Fit")
            return true
        } catch (e: ApiException) {
            Log.e(TAG, "Google Fit 錯誤: ${e.statusCode} ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "寫入失敗: ${e.message}")
        }
        return false
    }
}
