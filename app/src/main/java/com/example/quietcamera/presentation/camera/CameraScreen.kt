package com.example.quietcamera.presentation.camera

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Phase 1: 背面カメラのプレビュー画面。
 *
 * レイアウトは設計書 3.2 に従い、4:3 プレビューを上下の黒帯の間に置く。
 * 上部バーの中身（フラッシュ等）と下部コントロールは Phase 3 で実装するため、
 * Phase 1 では実機確認用の小さい表示（起動状態・プレビュー表示までの時間）だけを置く。
 */
@Composable
fun CameraScreen(
    cameraGranted: Boolean,
    startUptimeMs: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    val keyguardLocked = remember {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
            .isKeyguardLocked
    }
    var previewElapsedMs by remember { mutableStateOf<Long?>(null) }

    // 設計書 3.2: カメラ画面表示中は画面を消灯させない
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val previewView = remember {
        PreviewView(context).apply {
            // 3.5: ガラス効果は Phase 5 で検討。今は既定の PERFORMANCE（SurfaceView）
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // プレビューのフレームが流れ始めた時刻を「表示」とみなして計測する（4.8 の目標確認用）
    DisposableEffect(previewView) {
        val observer = Observer<PreviewView.StreamState> { state ->
            if (state == PreviewView.StreamState.STREAMING && previewElapsedMs == null) {
                previewElapsedMs = SystemClock.uptimeMillis() - startUptimeMs
            }
        }
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        onDispose { previewView.previewStreamState.removeObserver(observer) }
    }

    LaunchedEffect(cameraGranted) {
        if (!cameraGranted) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider = future.get()
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder()
                    .setResolutionSelector(selector)
                    .build()
                preview.surfaceProvider = previewView.surfaceProvider
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                )
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 上部バー（黒帯）。Phase 3 でフラッシュ／シェブロン／歯車を実装する
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "起動状態: " + if (keyguardLocked) "ロック経由" else "通常起動",
                    color = Color.White,
                    fontSize = 11.sp,
                )
                Text(
                    text = "プレビュー表示まで: " +
                        (previewElapsedMs?.let { "$it ms（目標 1500ms 以内・4.8）" } ?: "計測中…"),
                    color = Color.White,
                    fontSize = 11.sp,
                )
            }
        }

        if (cameraGranted) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "カメラ権限が必要です",
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }
        }

        // 下部コントロール領域（黒帯）。Phase 3 でシャッター等を実装する
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}
