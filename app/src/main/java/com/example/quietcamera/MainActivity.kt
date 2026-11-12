package com.example.quietcamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuietCameraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CameraPermissionRequester()
                    // Phase 0: 能力調査画面を表示する（Phase 1 でプレビューに置き換わる）
                    DiagnosticsScreen()
                }
            }
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
