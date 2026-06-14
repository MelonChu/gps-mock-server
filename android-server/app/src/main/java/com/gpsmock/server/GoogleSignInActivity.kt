package com.gpsmock.server

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.DataType

/**
 * Google 登入 Activity — 授權 Google Fit 步數寫入權限
 * 如需下載任何東西，此畫面會帶使用者去 Play Store
 */
class GoogleSignInActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "GoogleSignInAct"
        private const val RC_SIGN_IN = 9001
        const val EXTRA_FINISH = "com.gpsmock.server.FINISH"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Google Sign-In 開始")

        try {
            val fitOptions = FitnessOptions.builder()
                .addDataType(DataType.TYPE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_WRITE)
                .build()

            // 如果連 Google Play Services 的 Fitness API 都沒有，引導安裝
            if (!isFitnessAvailable()) {
                Toast.makeText(this, "此裝置不支援 Google Fit 步數同步（GPS 仍可正常使用）", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            val account = GoogleSignIn.getAccountForExtension(this, fitOptions)

            if (GoogleSignIn.hasPermissions(account, fitOptions)) {
                Log.i(TAG, "已有權限")
                sendBroadcast(Intent(EXTRA_FINISH).setPackage(packageName))
                finish()
                return
            }

            // 請求權限（會跳出 Google 帳號選擇視窗）
            GoogleSignIn.requestPermissions(this, RC_SIGN_IN, account, fitOptions)

        } catch (e: Exception) {
            Log.e(TAG, "授權失敗: ${e.message}", e)
            Toast.makeText(this, "Google 同步不可用（GPS 仍可正常使用）", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun isFitnessAvailable(): Boolean {
        return try {
            com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this) == com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (_: Exception) { false }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            Log.i(TAG, "授權結果: resultCode=$resultCode")
            sendBroadcast(Intent(EXTRA_FINISH).setPackage(packageName))
            finish()
        }
    }
}
