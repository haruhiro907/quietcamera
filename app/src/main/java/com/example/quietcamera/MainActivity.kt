package com.example.quietcamera

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.quietcamera.presentation.camera.DiagnosticsScreen
import com.example.quietcamera.presentation.theme.QuietCameraTheme

class MainActivity : ComponentActivity() {

    /** 起動した瞬間にロックされていたか（設計書 4.8 の判定に使う）。 */
    private var launchedWhileLocked = false

    /** 起動→画面表示の計測開始点（onCreate 先頭）。 */
    private var createUptimeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        createUptimeMs = SystemClock.uptimeMillis()
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        launchedWhileLocked = keyguard.isKeyguardLocked
        super.onCreate(savedInstanceState)
        setContent {
            QuietCameraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CameraPermissionRequester()
                    // Phase 0: 能力調査画面を表示する（Phase 1 でプレビューに置き換わる）
                    DiagnosticsScreen(startUptimeMs = createUptimeMs)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 設計書 4.8【仮決定】: ロック画面から起動したセッションは、画面が消灯したら終了する。
        // 通常起動のセッションは対象外（launchedWhileLocked のときだけ判定する）。
        if (launchedWhileLocked) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!power.isInteractive) finish()
        }
    }
}

/**
 * 起動時に CAMERA 権限を要求する。調査項目の大半（Camera2 特性）は権限なしでも
 * 取得できるため、拒否されても画面自体は表示し続ける。
 */
@Composable
private fun CameraPermissionRequester() {
    val context = LocalContext.current
    var requested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 結果は使わない（撮影プローブ側で失敗として表示される） */ }

    LaunchedEffect(Unit) {
        if (!requested &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
}
