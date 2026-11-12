package com.example.quietcamera.presentation.camera

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.quietcamera.camera.OrientationProvider
import com.example.quietcamera.media.MediaStoreManager
import kotlinx.coroutines.delay

/**
 * Phase 2: 背面カメラのプレビュー + 撮影・保存。
 *
 * レイアウトは設計書 3.2（4:3 プレビューを上下の黒帯の間に配置）。
 * シャッターボタンの本格的な見た目（4.1）とサムネイルは Phase 3 で実装するため、
 * ここでは簡易ボタンと確認用の表示（起動状態・プレビュー時間・保存結果）を置く。
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
    var previewReady by remember { mutableStateOf(false) }
    var captureStatus by remember { mutableStateOf<String?>(null) }
    var flashVisible by remember { mutableStateOf(false) }

    // 設計書 3.2: カメラ画面表示中は画面を消灯させない
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val aspect43Selector = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
    }

    // 撮影（7.1 / 8.1）: 標準は MINIMIZE_LATENCY、JPEG 品質 Q90
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(90)
            .setResolutionSelector(aspect43Selector)
            .build()
    }

    // 端末の向きを ImageCapture.targetRotation へ反映する（6.3）
    DisposableEffect(imageCapture) {
        val provider = OrientationProvider(context) { rotation ->
            imageCapture.targetRotation = rotation
        }
        provider.start()
        onDispose { provider.stop() }
    }

    // プレビューのフレームが流れ始めた時刻を「表示」とみなして計測する（4.8）
    DisposableEffect(previewView) {
        val observer = Observer<PreviewView.StreamState> { state ->
            if (state == PreviewView.StreamState.STREAMING) {
                previewReady = true
                if (previewElapsedMs == null) {
                    previewElapsedMs = SystemClock.uptimeMillis() - startUptimeMs
                }
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
                val preview = Preview.Builder()
                    .setResolutionSelector(aspect43Selector)
                    .build()
                preview.surfaceProvider = previewView.surfaceProvider
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    // 撮影確認の暗転（3.4）: 撮影のたびにプレビューを一瞬だけ暗くする
    LaunchedEffect(flashVisible) {
        if (flashVisible) {
            delay(90)
            flashVisible = false
        }
    }

    fun takePhoto() {
        // 設計書 11.2: プレビュー開始前は撮影を受け付けない
        if (!previewReady) return
        val options = MediaStoreManager.newOutputOptions(context.contentResolver)
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    flashVisible = true
                    captureStatus = "保存しました（DCIM/QuietCamera）"
                }

                override fun onError(exception: ImageCaptureException) {
                    // 設計書 11.2: 保存失敗は通知を出し、撮影画面は維持する
                    captureStatus = "保存に失敗しました: ${exception.message}"
                }
            },
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
                captureStatus?.let {
                    Text(text = it, color = Color.White, fontSize = 11.sp)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
        ) {
            if (cameraGranted) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "カメラ権限が必要です",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
            }
            if (flashVisible) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                )
            }
        }

        // 下部コントロール領域（黒帯）。簡易シャッター（Phase 3 で 4.1 の見た目に置き換える）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(if (previewReady) Color.White else Color.Gray)
                    .clickable(enabled = previewReady) { takePhoto() },
            )
        }
    }
}
