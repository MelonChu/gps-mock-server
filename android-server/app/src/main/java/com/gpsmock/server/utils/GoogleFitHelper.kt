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
 * Google Fit 步數寫入器
 * 完全在我們 App 內完成，不需 Health Connect 或第三方 App
 */
class GoogleFitHelper(private val ctx: Context) {
    companion object {
        private const val TAG = "GFitHelper"
        private const val MAX_DAILY = 15000L
    }

    private val fitOptions by lazy {
        FitnessOptions.builder()
            .addDataType(DataType.TYPE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_WRITE)
            .build()
    }

    fun hasPermission(): Boolean {
        try {
            val a = GoogleSignIn.getAccountForExtension(ctx, fitOptions)
            return GoogleSignIn.hasPermissions(a, fitOptions)
        } catch (_: Exception) { return false }
    }

    /** 寫入步數到 Google Fit */
    fun writeSteps(steps: Long): Boolean {
        if (steps <= 0) return false
        val account = GoogleSignIn.getAccountForExtension(ctx, fitOptions)
        if (!GoogleSignIn.hasPermissions(account, fitOptions)) return false

        val count = minOf(steps, MAX_DAILY).toInt()
        if (count <= 0) return false

        try {
            val now = ZonedDateTime.now()
            val startMs = now.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val source = DataSource.Builder()
                .setAppPackageName(ctx)
                .setDataType(DataType.TYPE_STEP_COUNT_DELTA)
                .setType(DataSource.TYPE_RAW)
                .build()

            val point = DataPoint.builder(source)
                .setTimestamp(startMs, TimeUnit.MILLISECONDS)
                .setField(Field.FIELD_STEPS, count)
                .build()

            // DataSet.builder() 在 play-services-fitness 21.1.0 中需要 DataSource
            // 使用 DataSet.create() 替代
            val dataSet = DataSet.create(source)
            dataSet.add(point)

            Tasks.await(
                Fitness.getHistoryClient(ctx, account).insertData(dataSet),
                30, TimeUnit.SECONDS
            )
            Log.i(TAG, "✅ +$count 步 → Google Fit")
            return true
        } catch (e: ApiException) {
            Log.e(TAG, "Google Fit API 錯誤: [${e.statusCode}] ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "寫入失敗: ${e.message}")
        }
        return false
    }
}
