package com.gpsmock.server

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.DataType

/**
 * Google 登入 Activity — 只需出現一次，授權後自動關閉
 */
class GoogleSignInActivity : AppCompatActivity() {
    companion object {
        private const val RC_SIGN_IN = 9001
        const val EXTRA_FINISH = "com.gpsmock.server.FINISH"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fitOptions = FitnessOptions.builder()
            .addDataType(DataType.TYPE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_WRITE)
            .build()

        val account = GoogleSignIn.getAccountForExtension(this, fitOptions)
        if (GoogleSignIn.hasPermissions(account, fitOptions)) {
            // 已經有權限
            sendBroadcast(Intent(EXTRA_FINISH).setPackage(packageName))
            finish()
            return
        }

        // 請求權限
        GoogleSignIn.requestPermissions(
            this, RC_SIGN_IN,
            account, fitOptions
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            sendBroadcast(Intent(EXTRA_FINISH).setPackage(packageName))
            finish()
        }
    }
}
