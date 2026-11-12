package com.example.quietcamera.presentation.camera

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.ScreenFlashView
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.quietcamera.camera.OrientationProvider
import com.example.quietcamera.media.MediaStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Phase 4: カメラ制御（設計書 4.2 ズーム / 4.4 フォーカス・露出 / 4.3 フラッシュ / 4.5 タイマー）。
 *
 * レイアウトは 3.2 のまま（4:3 プレビュー + 上下の黒帯）。
 * 設定パネルとガラス表現は Phase 5 で実装する。
 */
@Composable
fun CameraScreen(
    cameraGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val keyguardLocked = remember {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
            .isKeyguardLocked
    }

    var useFrontCamera by remember { mutableStateOf(false) }
    var previewReady by remember { mutableStateOf(false) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var errorStatus by remember { mutableStateOf<String?>(null) }
    var flashVisible by remember { mutableStateOf(false) }

    // バインド済みカメラと制御値
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    val latestZoom by rememberUpdatedState(zoomRatio)
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var exposureIndex by remember { mutableIntStateOf(0) }
    var exposureMin by remember { mutableIntStateOf(0) }
    var exposureMax by remember { mutableIntStateOf(0) }
    var exposureStepEv by remember { mutableFloatStateOf(1f / 6f) }
    var exposureFloat by remember { mutableFloatStateOf(0f) }

    // フォーカス UI
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusLocked by remember { mutableStateOf(false) }
    var focusShowKey by remember { mutableIntStateOf(0) }
    var focusVisible by remember { mutableStateOf(false) }

    // フラッシュ / タイマー
    var flashMode by remember { mutableStateOf(FlashMode.AUTO) }
    var flashMenuOpen by remember { mutableStateOf(false) }
    var timerSetting by remember { mutableStateOf(TimerSetting.OFF) }
    var countdown by remember { mutableStateOf<Int?>(null) }

    // ズームダイヤル
    var dialOpen by remember { mutableStateOf(false) }

    // サムネイル
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    // シャッター
    var shutterPressed by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    // 端末の向き（アイコン回転）
    var iconRotation by remember { mutableFloatStateOf(0f) }
    val animatedIconRotation by animateFloatAsState(
        targetValue = iconRotation,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "iconRotation",
    )

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

    val screenFlashView = remember {
        ScreenFlashView(context).apply {
            isClickable = false
            isFocusable = false
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

    // カメラのバインド（前後切替で再バインドし、制御値は初期化する）
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
                // 前面は画面フラッシュ（4.7）。背面は物理フラッシュを使う
                imageCapture.setScreenFlash(
                    if (useFrontCamera) screenFlashView.screenFlash else null
                )
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    if (useFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    },
                    preview,
                    imageCapture,
                )
                exposureIndex = 0
                exposureFloat = 0f
                focusLocked = false
                focusPoint = null
                countdown = null
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    // バインド済みカメラからズーム・露出の状態を取り込む
    DisposableEffect(camera) {
        val cam = camera
        if (cam == null) {
            onDispose { }
        } else {
            val zoomObserver = Observer<androidx.camera.core.ZoomState> { state ->
                zoomRatio = state.zoomRatio
                minZoom = state.minZoomRatio
                maxZoom = state.maxZoomRatio
            }
            cam.cameraInfo.zoomState.observe(lifecycleOwner, zoomObserver)
            val exposureState = cam.cameraInfo.exposureState
            if (exposureState.isExposureCompensationSupported) {
                exposureMin = exposureState.exposureCompensationRange.lower
                exposureMax = exposureState.exposureCompensationRange.upper
                exposureStepEv = exposureState.exposureCompensationStep.toFloat()
            }
            onDispose { cam.cameraInfo.zoomState.removeObserver(zoomObserver) }
        }
    }

    // フラッシュモードを ImageCapture へ反映する
    LaunchedEffect(flashMode) {
        imageCapture.flashMode = when (flashMode) {
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        }
    }

    LaunchedEffect(Unit) {
        val uri = withContext(Dispatchers.IO) {
            MediaStoreManager.latestPhotoUri(context.contentResolver)
        }
        if (uri != null && lastSavedUri == null) lastSavedUri = uri
    }

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

    LaunchedEffect(flashVisible) {
        if (flashVisible) {
            delay(90)
            flashVisible = false
        }
    }

    LaunchedEffect(errorStatus) {
        if (errorStatus != null) {
            delay(3000)
            errorStatus = null
        }
    }

    // フォーカス枠の表示制御（3.4: 1.3→1.0 倍、約 1.5 秒表示、フェードアウト）
    val focusScale by animateFloatAsState(
        targetValue = if (focusVisible) 1f else 1.3f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "focusScale",
    )
    val focusAlpha by animateFloatAsState(
        targetValue = if (focusVisible) 1f else 0f,
        label = "focusAlpha",
    )
    LaunchedEffect(focusShowKey) {
        if (focusShowKey == 0) return@LaunchedEffect
        focusVisible = true
        if (!focusLocked) {
            delay(1500)
            focusVisible = false
        }
    }
    LaunchedEffect(focusLocked) {
        if (focusLocked) focusVisible = true
    }

    fun setZoom(ratio: Float) {
        val cam = camera ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(minZoom, maxZoom))
    }

    var zoomAnimJob by remember { mutableStateOf<Job?>(null) }
    fun animateZoomTo(target: Float) {
        zoomAnimJob?.cancel()
        val start = zoomRatio
        val startTime = android.os.SystemClock.uptimeMillis()
        zoomAnimJob = scope.launch {
            while (true) {
                val t = ((android.os.SystemClock.uptimeMillis() - startTime) / 250f)
                    .coerceAtMost(1f)
                val eased = 1f - (1f - t) * (1f - t)
                setZoom(start + (target - start) * eased)
                if (t >= 1f) break
                delay(16)
            }
        }
    }

    fun focusAt(point: Offset, lock: Boolean) {
        val cam = camera ?: return
        val meteringPoint = previewView.meteringPointFactory.createPoint(point.x, point.y)
        val builder = FocusMeteringAction.Builder(
            meteringPoint,
            FocusMeteringAction.FLAG_AF or
                FocusMeteringAction.FLAG_AE or
                FocusMeteringAction.FLAG_AWB,
        )
        if (lock) builder.disableAutoCancel()
        cam.cameraControl.startFocusAndMetering(builder.build())
        focusPoint = point
        focusLocked = lock
        focusVisible = false
        focusShowKey++
    }

    fun adjustExposure(dragAmountY: Float) {
        val cam = camera ?: return
        exposureFloat = (exposureFloat - dragAmountY / 28f)
            .coerceIn(exposureMin.toFloat(), exposureMax.toFloat())
        val newIndex = exposureFloat.roundToInt()
        if (newIndex != exposureIndex) {
            exposureIndex = newIndex
            cam.cameraControl.setExposureCompensationIndex(newIndex)
        }
        if (focusPoint == null && previewSize != IntSize.Zero) {
            focusPoint = Offset(previewSize.width / 2f, previewSize.height / 2f)
        }
        focusShowKey++
        focusVisible = true
    }

    fun doCapture() {
        if (!previewReady || capturing) return
        capturing = true
        val options = MediaStoreManager.newOutputOptions(
            context.contentResolver,
            isReversedHorizontal = if (useFrontCamera) false else null,
        )
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    flashVisible = true
                    lastSavedUri = output.savedUri ?: lastSavedUri
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    errorStatus = "保存に失敗しました"
                }
            },
        )
    }

    fun onShutter() {
        when {
            countdown != null -> countdown = null // 4.5: カウント中の再タップでキャンセル
            timerSetting != TimerSetting.OFF -> countdown = timerSetting.seconds
            else -> doCapture()
        }
    }

    // タイマーのカウントダウン（4.5: 1 秒ごとに弱い触覚）
    LaunchedEffect(countdown) {
        var remaining = countdown ?: return@LaunchedEffect
        while (remaining > 0) {
            vibrateTick(context)
            delay(1000)
            remaining--
            if (countdown == null) return@LaunchedEffect // キャンセルされた
            countdown = remaining
        }
        countdown = null
        doCapture()
    }

    fun openThumbnail() {
        val uri = lastSavedUri ?: return
        if (keyguardLocked) return // 4.8: ロック中は写真アプリを開かない
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

    val zoomStops = remember(minZoom, maxZoom) {
        // 設計書 4.2 の表（0.6 / 1 / 2 + 端末の最大倍率）。範囲外の倍率は表示しない
        val base = listOf(0.6f, 1.0f, 2.0f)
            .filter { it >= minZoom - 0.001f && it <= maxZoom + 0.001f }
        val withMax =
            if (base.none { kotlin.math.abs(it - maxZoom) < 0.05f }) base + maxZoom else base
        withMax.ifEmpty { listOf(minZoom) }
    }
    val exposureFraction =
        if (exposureMax > exposureMin) {
            (exposureIndex - exposureMin).toFloat() / (exposureMax - exposureMin)
        } else {
            0.5f
        }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 上部バー: 左フラッシュ、右タイマー（設定パネルは Phase 5）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopBarButton(onClick = { flashMenuOpen = !flashMenuOpen }) {
                        BoltIcon(mode = flashMode, modifier = Modifier.size(20.dp))
                    }
                    if (flashMenuOpen) {
                        FlashMenu(
                            current = flashMode,
                            onSelect = {
                                flashMode = it
                                flashMenuOpen = false
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Box(Modifier.weight(1f))
                    if (errorStatus != null) {
                        Text(
                            text = errorStatus!!,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    TopBarButton(onClick = { timerSetting = timerSetting.next() }) {
                        if (timerSetting == TimerSetting.OFF) {
                            Canvas(Modifier.size(20.dp)) {
                                val c = Offset(size.width / 2, size.height / 2)
                                drawCircle(
                                    Color.White,
                                    size.minDimension / 2 - 1.dp.toPx(),
                                    c,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        1.8.dp.toPx()
                                    ),
                                )
                                drawLine(
                                    Color.White, c,
                                    Offset(c.x, c.y - size.minDimension / 4),
                                    1.8.dp.toPx(), StrokeCap.Round,
                                )
                                drawLine(
                                    Color.White, c,
                                    Offset(c.x + size.minDimension / 5, c.y),
                                    1.8.dp.toPx(), StrokeCap.Round,
                                )
                            }
                        } else {
                            Text(
                                text = "${timerSetting.seconds}s",
                                color = AccentYellow,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // プレビュー（4:3）+ ジェスチャ + オーバーレイ
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .onSizeChanged { previewSize = it }
                    .previewGestures(
                        onTapFocus = { focusAt(it, lock = false) },
                        onLongPressLock = { focusAt(it, lock = true) },
                        onPinchZoom = { factor -> setZoom(latestZoom * factor) },
                        onExposureDrag = { dy -> adjustExposure(dy) },
                    ),
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
                // フォーカス枠（タップ位置が取れているときだけ）
                val fp = focusPoint
                if (fp != null && focusAlpha > 0.01f) {
                    FocusIndicator(
                        center = fp,
                        exposureFraction = exposureFraction,
                        scale = focusScale,
                        alpha = focusAlpha,
                    )
                }
                // AE/AF ロック表示（4.4）
                if (focusLocked) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "AE/AF ロック",
                            color = AccentYellow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                // タイマーのカウントダウン（4.5）
                countdown?.let { remaining ->
                    Text(
                        text = remaining.toString(),
                        color = Color.White,
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            // 下部: ズームピル + コントロール行 + PHOTO
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (dialOpen) {
                    ZoomDial(
                        minZoom = minZoom,
                        maxZoom = maxZoom,
                        currentZoom = zoomRatio,
                        onZoomChange = { setZoom(it) },
                        onClose = { dialOpen = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                } else {
                    ZoomPill(
                        stops = zoomStops,
                        currentZoom = zoomRatio,
                        onStopSelected = { animateZoomTo(it) },
                        onDialOpen = { dialOpen = true },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.35f),
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable(
                                    enabled = lastSavedUri != null && !keyguardLocked
                                ) { openThumbnail() },
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

                    ShutterButton(
                        enabled = previewReady,
                        pressed = shutterPressed,
                        capturing = capturing || countdown != null,
                        onPressedChange = { shutterPressed = it },
                        onShutter = { onShutter() },
                    )

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

                Text(
                    text = "PHOTO",
                    color = Color.White,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        // 前面の画面フラッシュ用オーバーレイ（タッチは透過させる）
        AndroidView(
            factory = { screenFlashView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** iPhone 風シャッターボタン（設計書 4.1）。 */
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
        Box(
            Modifier
                .size(76.dp)
                .border(4.dp, Color.White, CircleShape),
        )
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

/** カメラ切替アイコン。 */
@Composable
private fun CameraSwitchIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
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
        val cx = size.width / 2
        val cy = size.height / 2
        val r = size.width / 2 - inset
        val a1 = Math.toRadians(-30.0)
        val p1 = Offset(
            cx + (r * kotlin.math.cos(a1)).toFloat(),
            cy + (r * kotlin.math.sin(a1)).toFloat(),
        )
        drawLine(
            Color.White, p1, p1 + Offset(-6.dp.toPx(), 1.dp.toPx()),
            2.dp.toPx(), StrokeCap.Round,
        )
        drawLine(
            Color.White, p1, p1 + Offset(-1.dp.toPx(), 6.dp.toPx()),
            2.dp.toPx(), StrokeCap.Round,
        )
        val a2 = Math.toRadians(150.0)
        val p2 = Offset(
            cx + (r * kotlin.math.cos(a2)).toFloat(),
            cy + (r * kotlin.math.sin(a2)).toFloat(),
        )
        drawLine(
            Color.White, p2, p2 + Offset(6.dp.toPx(), -1.dp.toPx()),
            2.dp.toPx(), StrokeCap.Round,
        )
        drawLine(
            Color.White, p2, p2 + Offset(1.dp.toPx(), -6.dp.toPx()),
            2.dp.toPx(), StrokeCap.Round,
        )
    }
}

private fun vibrateTick(context: Context) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    } catch (e: Exception) {
        // 触覚は補助機能なので失敗しても撮影フローは止めない
    }
}
