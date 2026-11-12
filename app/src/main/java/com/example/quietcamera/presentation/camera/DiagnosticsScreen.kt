package com.example.quietcamera.presentation.camera

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.quietcamera.camera.CameraCapabilities
import com.example.quietcamera.camera.CaptureProbe
import kotlinx.coroutines.launch

/**
 * Phase 0 の調査画面。起動時に能力調査を自動実行して結果を表示する。
 * 実機でしか分からない項目（システム撮影音・振動の弱さ）はテストボタンで確認する。
 */
@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier, startUptimeMs: Long = 0L) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf("調査中…") }
    var captureResult by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }

    // 設計書 2.3 / 4.8: ロック状態の判定と、起動→画面表示までの時間の計測
    val keyguardLocked = remember {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
            .isKeyguardLocked
    }
    var firstLayoutElapsedMs by remember { mutableStateOf<Long?>(null) }

    val fullReport = buildString {
        append(report)
        append("\n\n--- 起動・ロック（2.3 / 4.8）---\n")
        append("起動状態: ")
        append(if (keyguardLocked) "ロック中（ロック画面の上）" else "通常起動")
        append('\n')
        append("起動→画面表示: ")
        append(firstLayoutElapsedMs?.let { "$it ms" } ?: "未計測")
        append('\n')
    }

    LaunchedEffect(Unit) {
        report = CameraCapabilities.collectReport(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .onGloballyPositioned {
                if (firstLayoutElapsedMs == null && startUptimeMs > 0L) {
                    firstLayoutElapsedMs = SystemClock.uptimeMillis() - startUptimeMs
                }
            }
    ) {
        Text(
            text = "Quiet Camera — Phase 0 能力調査",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "起動状態: " + if (keyguardLocked) "ロック中（ロック画面の上）" else "通常起動",
            fontSize = 12.sp
        )
        Text(
            text = "起動→画面表示: " +
                (firstLayoutElapsedMs?.let { "$it ms（目標 1500ms 以内・4.8）" } ?: "計測中…"),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch { report = CameraCapabilities.collectReport(context) }
            }) { Text("再調査") }
            // 4.8: ロック中は他アプリを開かない（共有シートを出さない）
            Button(enabled = !keyguardLocked, onClick = { shareText(context, fullReport) }) {
                Text("結果を共有")
            }
            Button(onClick = { copyText(context, fullReport) }) { Text("コピー") }
        }
        if (keyguardLocked) {
            Text(
                text = "ロック中は「結果を共有」を無効化しています（4.8）。解除後に共有してください。",
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Box(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            SelectionContainer {
                Text(text = report, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(text = "実機確認テスト", style = MaterialTheme.typography.titleSmall)
        captureResult?.let {
            Text(text = it, fontSize = 12.sp)
        }
        Button(
            enabled = !capturing,
            onClick = {
                capturing = true
                scope.launch {
                    captureResult = CaptureProbe.captureSilently(context, lifecycleOwner)
                    capturing = false
                }
            }
        ) {
            Text(if (capturing) "撮影中…" else "無音で撮影テスト（システム音の確認）")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vibratePredefined(context, VibrationEffect.EFFECT_TICK) }) {
                Text("振動 TICK")
            }
            Button(onClick = { vibratePredefined(context, VibrationEffect.EFFECT_CLICK) }) {
                Text("振動 CLICK")
            }
            Button(onClick = { vibrateOneShot(context) }) {
                Text("振動 OneShot 15ms")
            }
        }
    }
}

private fun vibratePredefined(context: Context, effectId: Int) {
    getVibrator(context).vibrate(VibrationEffect.createPredefined(effectId))
}

private fun vibrateOneShot(context: Context) {
    getVibrator(context).vibrate(VibrationEffect.createOneShot(15, 80))
}

private fun getVibrator(context: Context): Vibrator {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "調査結果を共有"))
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("QuietCamera Phase0", text))
}
