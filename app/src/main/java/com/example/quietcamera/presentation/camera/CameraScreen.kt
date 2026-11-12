package com.example.quietcamera.presentation.camera

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Phase 3: 基本 UI（設計書 3.2 / 4.1 / 7.3）。
 *
 * - 4:3 プレビューを上下の黒帯の間に配置
 * - 下部: 左に最新写真のサムネイル、中央に iPhone 風シャッター、右に前後切替
 * - 最下部に PHOTO 表示
 * - 上部バー（フラッシュ等）は Phase 4、ガラス表現は Phase 5 で実装する
 */
@Composable
fun CameraScreen(
    cameraGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    val keyguardLocked = remember {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
            .isKeyguardLocked
    }

    var useFrontCamera by remember { mutableStateOf(false) }
    var previewReady by remember { mutableStateOf(false) }
    var errorStatus by remember { mutableStateOf<String?>(null) }
    var flashVisible by remember { mutableStateOf(false) }

    // サムネイル（7.3: 自アプリで撮影した最新の写真）
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    // シャッターの押下・撮影中状態（4.1: 内側円の縮小）
    var shutterPressed by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    // 端末の向き（アイコン回転用・3.2 共通仕様）
    var iconRotation by remember { mutableFloatStateOf(0f) }
    val animatedIconRotation by animateFloatAsState(
        targetValue = iconRotation,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "iconRotation",
    )

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

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(90)
            .setResolutionSelector(aspect43Selector)
            .build()
    }

    // 端末の向き → ImageCapture.targetRotation とアイコン回転（6.3 / 3.2）
    DisposableEffect(imageCapture) {
        val provider = OrientationProvider(context) { rotation ->
            imageCapture.targetRotation = rotation
            iconRotation = when (rotation) {
                Surface.ROTATION_90 -> 90f
                Surface.ROTATION_180 -> 180f
                Surface.ROTATION_270 -> -90f
                else -> 0f
            }
        }
        provider.start()
        onDispose { provider.stop() }
    }

    DisposableEffect(previewView) {
        val observer = Observer<PreviewView.StreamState> { state ->
            if (state == PreviewView.StreamState.STREAMING) previewReady = true
        }
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        onDispose { previewView.previewStreamState.removeObserver(observer) }
    }

    // カメラのバインド（前後切替で再バインド）
    LaunchedEffect(cameraGranted, useFrontCamera) {
        if (!cameraGranted) return@LaunchedEffect
        previewReady = false
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
                    if (useFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    },
                    preview,
                    imageCapture,
                )
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    // 起動時: 自アプリの最新写真をサムネイルにする（7.3）
    LaunchedEffect(Unit) {
        val uri = withContext(Dispatchers.IO) {
            MediaStoreManager.latestPhotoUri(context.contentResolver)
        }
        if (uri != null && lastSavedUri == null) lastSavedUri = uri
    }

    // サムネイル画像の読み込み
    LaunchedEffect(lastSavedUri) {
        val uri = lastSavedUri
        thumbnail = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.loadThumbnail(uri, Size(240, 240), null)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    // 撮影確認の暗転（3.4）
    LaunchedEffect(flashVisible) {
        if (flashVisible) {
            delay(90)
            flashVisible = false
        }
    }

    // エラー表示は数秒で消す
    LaunchedEffect(errorStatus) {
        if (errorStatus != null) {
            delay(3000)
            errorStatus = null
        }
    }

    fun takePhoto() {
        // 設計書 11.2: プレビュー開始前は撮影を受け付けない
        if (!previewReady || capturing) return
        capturing = true
        val options = MediaStoreManager.newOutputOptions(
            context.contentResolver,
            // 4.7: 前面の保存は既定で非ミラー
            isReversedHorizontal = if (useFrontCamera) false else null,
        )
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    flashVisible = true
                    // 7.2: 保存結果の URI でサムネイルを更新する
                    lastSavedUri = output.savedUri ?: lastSavedUri
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    // 11.2: 保存失敗は通知を出し、撮影画面は維持する
                    errorStatus = "保存に失敗しました"
                }
            },
        )
    }

    fun openThumbnail() {
        val uri = lastSavedUri ?: return
        // 4.8: ロック中は写真アプリを開かない
        if (keyguardLocked) return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            errorStatus = "写真アプリを開けませんでした"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 上部バー（黒帯）。フラッシュ／シェブロン／歯車は Phase 4 以降
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            errorStatus?.let {
                Text(text = it, color = Color.White, fontSize = 12.sp)
            }
        }

        // プレビュー（4:3）
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

        // 下部コントロール（黒帯）: サムネイル／シャッター／切替 + PHOTO 表示
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左下: サムネイル（7.3）
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .graphicsLayer { rotationZ = animatedIconRotation }
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2C2C2E))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .clickable(enabled = lastSavedUri != null && !keyguardLocked) {
                                openThumbnail()
                            },
                    ) {
                        thumbnail?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "最新の写真",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // 中央下: シャッター（4.1: 外径 76dp、リング 4dp、内側円 60dp）
                ShutterButton(
                    enabled = previewReady,
                    pressed = shutterPressed,
                    capturing = capturing,
                    onPressedChange = { shutterPressed = it },
                    onShutter = { takePhoto() },
                )

                // 右下: 前面／背面カメラ切替
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable { useFrontCamera = !useFrontCamera },
                        contentAlignment = Alignment.Center,
                    ) {
                        CameraSwitchIcon(
                            modifier = Modifier
                                .size(26.dp)
                                .graphicsLayer { rotationZ = animatedIconRotation },
                        )
                    }
                }
            }

            // 最下部: PHOTO 表示（3.2）
            Text(
                text = "PHOTO",
                color = Color.White,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp),
            )
        }
    }
}

/**
 * iPhone 風シャッターボタン（設計書 4.1）。
 * 押下で内側円が 0.92 倍に縮小し、指を離した瞬間に撮影する。
 * 撮影中は縮小を保ち、保存コールバックで戻る（capturing は呼び出し側が管理）。
 */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    pressed: Boolean,
    capturing: Boolean,
    onPressedChange: (Boolean) -> Unit,
    onShutter: () -> Unit,
) {
    val innerScale by animateFloatAsState(
        targetValue = if (pressed || capturing) 0.92f else 1f,
        animationSpec = spring(
            stiffness = Spring.StiffnessHigh,
            dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        label = "shutterInnerScale",
    )
    Box(
        modifier = Modifier
            .size(76.dp)
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        onPressedChange(true)
                        val released = tryAwaitRelease()
                        onPressedChange(false)
                        if (released && enabled) onShutter()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // 外周リング（約 4dp）
        Box(
            Modifier
                .size(76.dp)
                .border(4.dp, Color.White, CircleShape),
        )
        // 内側の白い円（約 60dp）
        Box(
            Modifier
                .size(60.dp)
                .graphicsLayer {
                    scaleX = innerScale
                    scaleY = innerScale
                }
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color.Gray),
        )
    }
}

/** カメラ切替アイコン（2 本の弧と矢印で回転切替を表す簡易描画）。 */
@Composable
private fun CameraSwitchIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val inset = 3.dp.toPx()
        val arcSize = androidx.compose.ui.geometry.Size(
            size.width - inset * 2,
            size.height - inset * 2,
        )
        val topLeft = Offset(inset, inset)
        drawArc(
            color = Color.White,
            startAngle = -30f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = Color.White,
            startAngle = 150f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        // 矢印の先端（右上と左下に短い線）
        val cx = size.width / 2
        val cy = size.height / 2
        val r = size.width / 2 - inset
        val a1 = Math.toRadians(-30.0)
        val p1 = Offset(cx + (r * kotlin.math.cos(a1)).toFloat(), cy + (r * kotlin.math.sin(a1)).toFloat())
        drawLine(Color.White, p1, p1 + Offset(-6.dp.toPx(), 1.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White, p1, p1 + Offset(-1.dp.toPx(), 6.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        val a2 = Math.toRadians(150.0)
        val p2 = Offset(cx + (r * kotlin.math.cos(a2)).toFloat(), cy + (r * kotlin.math.sin(a2)).toFloat())
        drawLine(Color.White, p2, p2 + Offset(6.dp.toPx(), -1.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White, p2, p2 + Offset(1.dp.toPx(), -6.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
    }
}
