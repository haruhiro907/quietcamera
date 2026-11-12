package com.example.quietcamera

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.quietcamera.presentation.camera.CameraScreen
import com.example.quietcamera.presentation.theme.QuietCameraTheme

class MainActivity: ComponentActivity() {

/**
* ロック画面経由のセッションか（設計書 4.8）。
* 起動時点でロック中だった場合と、STILL_IMAGE_CAMERA_SECURE で起動した場合に真。
*/
private var lockSession = false

override fun onCreate(savedInstanceState: Bundle?) {
val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
lockSession = keyguard.isKeyguardLocked ||
intent?.action == MediaStore.ACTION_IMAGE_CAPTURE_SECURE
super.onCreate(savedInstanceState)

// 設計書 4.8 の不具合対策（Phase 0 実機確認で判明）:
// showWhenLocked をマニフェストで常時有効にすると、通常セッションでも
// 再点灯時にロック画面へアプリが重なって表示される。ロック経由の
// セッションのときだけ、プログラムから有効化する。
if (lockSession) {
setShowWhenLocked(true)
setTurnScreenOn(true)
}

setContent {
QuietCameraTheme {
Surface(
modifier = Modifier.fillMaxSize(),
color = MaterialTheme.colorScheme.background
) {
var granted by remember {
mutableStateOf(
ContextCompat.checkSelfPermission(
this@MainActivity,
Manifest.permission.CAMERA
) == PackageManager.PERMISSION_GRANTED
)
}
val launcher = rememberLauncherForActivityResult(
ActivityResultContracts.RequestPermission()
) { granted = it}
LaunchedEffect(Unit) {
if (!granted) launcher.launch(Manifest.permission.CAMERA)
}

CameraScreen(cameraGranted = granted)
}
}
}
}

override fun onStop() {
super.onStop()
// 設計書 4.8: ロック経由のセッションは、画面が消灯したら終了する。
// 通常セッションは対象外。
if (lockSession) {
val power = getSystemService(Context.POWER_SERVICE) as PowerManager
if (!power.isInteractive) finish()
}
}
}
