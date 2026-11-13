package com.example.quietcamera.presentation.camera

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraMetadata
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import android.view.Surface
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.camera.view.ScreenFlashView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.quietcamera.R
import com.example.quietcamera.camera.OrientationProvider
import com.example.quietcamera.data.AppSettings
import com.example.quietcamera.data.AspectSetting
import com.example.quietcamera.data.CaptureQuality
import com.example.quietcamera.data.LaunchCamera
import com.example.quietcamera.data.SettingsStore
import com.example.quietcamera.media.MediaStoreManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** アスペクト比に応じた解像度セレクタ。1:1 は 4:3 で撮って保存時に切り抜く。 */
private fun selectorFor(aspect: AspectSetting): ResolutionSelector =
    ResolutionSelector.Builder()
        .setAspectRatioStrategy(
            when (aspect) {
                AspectSetting.RATIO_16_9 ->
                    AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                else -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            }
        )
        .build()

/** 撮影モード（写真 / 動画）。動画は Phase 6 のユーザー要望で追加した。 */
enum class ShootMode { PHOTO, VIDEO }

private fun jpegQualityFor(quality: CaptureQuality): Int = when (quality) {
    CaptureQuality.STANDARD -> 90
    CaptureQuality.HIGH -> 97
}

/**
 * Phase 5: Liquid Glass 風 UI とシャッター体験（設計書 3.2 / 4.3 / 4.6 / 5 / 10）。
 *
 * - 設定は DataStore で永続化し、シェブロンから開くガラス風パネルで変更する
 * - シャッター音は自作 WAV（付録 A）を SoundPool で再生し、触覚と同期させる
 * - アスペクト比でプレビュー配置が変わり（3.2）、操作系は重ねて表示する
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

    // ---- 設定（DataStore 永続化、10.4） ----
    val settingsStore = remember { SettingsStore(context.applicationContext) }
    val settings by settingsStore.settings.collectAsState(initial = AppSettings())
    val flashMode = settings.flashMode
    val timerSetting = TimerSetting.entries
        .firstOrNull { it.seconds == settings.timerSeconds } ?: TimerSetting.OFF

    var panelOpen by remember { mutableStateOf(false) }
    val panelScrollState = rememberScrollState()
    var appSettingsOpen by remember { mutableStateOf(false) }

    // 動画モード（音声なし。マイク権限を要求しない）
    var mode by remember { mutableStateOf(ShootMode.PHOTO) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    val videoCapture = remember {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        VideoCapture.withOutput(recorder)
    }

    var useFrontCamera by remember { mutableStateOf(false) }
    var launchCameraApplied by remember { mutableStateOf(false) }
    // 起動時カメラは DataStore の「最初の実データ」で一度だけ適用する。
    // collectAsState の初期値（既定値）は実データではないので、適用判定には使わない。
    LaunchedEffect(Unit) {
        settingsStore.settings.collect { loaded ->
            if (!launchCameraApplied) {
                useFrontCamera = loaded.launchCamera == LaunchCamera.FRONT
                launchCameraApplied = true
            }
        }
    }

    var previewReady by remember { mutableStateOf(false) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
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

    // フラッシュメニュー / タイマーのカウントダウン
    var flashMenuOpen by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }

    // ズームダイヤル
    var dialOpen by remember { mutableStateOf(false) }

    // サムネイル
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    // シャッター
    var shutterPressed by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    // 端末の向き（アイコン回転と撮影の回転）
    var deviceRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
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

    // アスペクト比・画質が変わったら ImageCapture を作り直す（8.1）
    val imageCapture = remember(settings.aspect, settings.quality) {
        val builder = ImageCapture.Builder()
            .setCaptureMode(
                when (settings.quality) {
                    CaptureQuality.STANDARD -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    CaptureQuality.HIGH -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                }
            )
            .setJpegQuality(jpegQualityFor(settings.quality))
            .setResolutionSelector(selectorFor(settings.aspect))
        if (settings.quality == CaptureQuality.HIGH) {
            // 高画質では Camera2 Interop でノイズ低減・エッジ強調を高品質にする（8.1）
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                )
                .setCaptureRequestOption(
                    CaptureRequest.EDGE_MODE,
                    CameraMetadata.EDGE_MODE_HIGH_QUALITY,
                )
        }
        builder.build()
    }

    DisposableEffect(Unit) {
        val provider = OrientationProvider(context) { rotation ->
            deviceRotation = rotation
        }
        provider.start()
        onDispose { provider.stop() }
    }

    LaunchedEffect(deviceRotation, imageCapture) {
        imageCapture.targetRotation = deviceRotation
        iconRotation = when (deviceRotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> -90f
            else -> 0f
        }
    }

    DisposableEffect(previewView) {
        val observer = Observer<PreviewView.StreamState> { state ->
            if (state == PreviewView.StreamState.STREAMING) previewReady = true
        }
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        onDispose { previewView.previewStreamState.removeObserver(observer) }
    }

    // ---- シャッター音（5.2: SoundPool + 自作 WAV） ----
    var soundPool by remember { mutableStateOf<SoundPool?>(null) }
    var shutterSoundId by remember { mutableIntStateOf(0) }
    var soundLoaded by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val pool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        val id = pool.load(context, R.raw.shutter_soft, 1)
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (sampleId == id && status == 0) soundLoaded = true
        }
        soundPool = pool
        shutterSoundId = id
        onDispose { pool.release() }
    }

    fun playShutterFeedback() {
        if (settings.hapticsEnabled) vibrateTick(context)
        if (!settings.soundEnabled || !soundLoaded) return
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val silent = audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL
        if (silent && !settings.soundInSilentMode) return
        val volume = settings.soundVolume
        soundPool?.play(shutterSoundId, volume, volume, 1, 0, 1f)
    }

    // ---- 水準器センサー（4.6: 重力センサーから傾きを出す） ----
    var levelAngle by remember { mutableFloatStateOf(0f) }
    DisposableEffect(settings.levelEnabled) {
        if (!settings.levelEnabled) {
            onDispose { }
        } else {
            val sensorManager =
                context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val gx = event.values[0]
                    val gy = event.values[1]
                    val angle = Math.toDegrees(
                        kotlin.math.atan2(gx.toDouble(), -gy.toDouble())
                    ).toFloat()
                    levelAngle += (angle - levelAngle) * 0.25f
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            if (gravity != null) {
                sensorManager.registerListener(
                    listener, gravity, SensorManager.SENSOR_DELAY_UI
                )
            }
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    // カメラのバインド（前後切替・アスペクト比・画質・モードの変更で再バインド）
    LaunchedEffect(cameraGranted, useFrontCamera, imageCapture, mode) {
        if (!cameraGranted) return@LaunchedEffect
        previewReady = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider = future.get()
                val preview = Preview.Builder()
                    .setResolutionSelector(selectorFor(settings.aspect))
                    .build()
                preview.surfaceProvider = previewView.surfaceProvider
                val selector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.unbindAll()
                camera = if (mode == ShootMode.PHOTO) {
                    // 前面は画面フラッシュ（4.7）。背面は物理フラッシュを使う
                    imageCapture.setScreenFlash(
                        if (useFrontCamera) screenFlashView.screenFlash else null
                    )
                    provider.bindToLifecycle(
                        lifecycleOwner, selector, preview, imageCapture,
                    )
                } else {
                    provider.bindToLifecycle(
                        lifecycleOwner, selector, preview, videoCapture,
                    )
                }
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

    // 動画モードではフラッシュ ON 設定をトーチ（ライト）として使う
    LaunchedEffect(mode, flashMode, camera) {
        if (mode == ShootMode.VIDEO) {
            camera?.cameraControl?.enableTorch(flashMode == FlashMode.ON)
        }
    }

    // フラッシュモードを ImageCapture へ反映する
    LaunchedEffect(flashMode, imageCapture) {
        imageCapture.flashMode = when (flashMode) {
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        }
    }

    LaunchedEffect(Unit) {
        val uri = withContext(Dispatchers.IO) {
            MediaStoreManager.latestMediaUri(context.contentResolver)
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

    // 押下時点でフィードバック済みなら、撮影時の再生を 1 回だけ抑止する
    var suppressCaptureFeedback by remember { mutableStateOf(false) }

    /**
     * シャッターを押した瞬間のフィードバック（音・触覚・全画面の暗転）。
     * 撮影完了を待たずに反応させることで体感の遅延をなくす（Phase 6 の指摘）。
     * タイマー撮影とカウントダウンのキャンセルでは鳴らさない。
     */
    fun onShutterPressStart() {
        if (mode == ShootMode.PHOTO && countdown == null &&
            timerSetting == TimerSetting.OFF
        ) {
            playShutterFeedback()
            flashVisible = true
            suppressCaptureFeedback = true
        }
    }

    fun doCapture() {
        if (!previewReady || capturing) return
        capturing = true
        if (suppressCaptureFeedback) {
            suppressCaptureFeedback = false
        } else {
            playShutterFeedback()
            flashVisible = true
        }
        if (settings.aspect == AspectSetting.RATIO_1_1) {
            // 1:1 は一時ファイルへ撮り、中央を正方形に切り抜いて保存する
            val file = File(context.cacheDir, "qc_capture_tmp.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                val saved = MediaStoreManager.saveSquareJpeg(
                                    context.contentResolver,
                                    file,
                                    flipHorizontal = useFrontCamera &&
                                        settings.mirrorFrontPhoto,
                                    jpegQuality = jpegQualityFor(settings.quality),
                                )
                                file.delete()
                                saved
                            }
                            capturing = false
                            if (uri != null) {
                                lastSavedUri = uri
                            } else {
                                errorStatus = "保存に失敗しました"
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        capturing = false
                        errorStatus = "保存に失敗しました"
                    }
                },
            )
            return
        }
        val options = MediaStoreManager.newOutputOptions(
            context.contentResolver,
            isReversedHorizontal = if (useFrontCamera) settings.mirrorFrontPhoto else null,
        )
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    lastSavedUri = output.savedUri ?: lastSavedUri
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    errorStatus = "保存に失敗しました"
                }
            },
        )
    }

    // 長押し連続撮影（Phase 6 で追加）
    var burstJob by remember { mutableStateOf<Job?>(null) }
    fun startBurst() {
        if (countdown != null || timerSetting != TimerSetting.OFF) return
        if (burstJob?.isActive == true) return
        burstJob = scope.launch {
            while (true) {
                doCapture()
                delay(300)
            }
        }
    }

    fun stopBurst() {
        burstJob?.cancel()
        burstJob = null
    }

    // 録画時間の表示用カウント
    LaunchedEffect(recording) {
        if (recording != null) {
            recordSeconds = 0
            while (true) {
                delay(1000)
                recordSeconds++
            }
        }
    }

    /** 動画の録画開始 / 停止。保存先は写真と同じ DCIM/QuietCamera。 */
    fun toggleRecording() {
        val active = recording
        if (active != null) {
            active.stop()
            recording = null
            if (settings.hapticsEnabled) vibrateTick(context)
            return
        }
        if (!previewReady) return
        val values = ContentValues().apply {
            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStoreManager.nextVideoDisplayName(),
            )
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/QuietCamera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()
        playShutterFeedback()
        recording = videoCapture.output
            .prepareRecording(context, options)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    if (event.hasError()) {
                        errorStatus = "動画の保存に失敗しました"
                    } else {
                        errorStatus = "動画を保存しました"
                        lastSavedUri = event.outputResults.outputUri
                    }
                }
            }
    }

    fun onShutter() {
        if (mode == ShootMode.VIDEO) {
            toggleRecording()
            return
        }
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
            if (settings.hapticsEnabled) vibrateTick(context)
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
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            errorStatus = "写真アプリを開けませんでした"
        }
    }

    // 黒帯の配分（ユーザー提供図 2026-11-13 の再構成）: ファインダー高さは
    // 横幅×アスペクトで固定し、残りの高さを上 375 : 下 537 で分ける。
    // ファインダー実高は onSizeChanged の実測値で補正する（16:9 でも追随）。
    val density = LocalDensity.current
    val previewTopOffset = if (rootSize.height > 0 && previewSize.height > 0) {
        val rootDp = rootSize.height / density.density
        val finderDp = previewSize.height / density.density
        ((rootDp - finderDp) * (375f / 912f)).dp.coerceAtLeast(0.dp)
    } else {
        140.dp
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
    val previewRatio = when (settings.aspect) {
        AspectSetting.RATIO_4_3 -> 4f / 3f
        AspectSetting.RATIO_16_9 -> 9f / 16f
        AspectSetting.RATIO_1_1 -> 1f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { rootSize = it },
    ) {
        // プレビュー（アスペクト比で配置が変わる、3.2）+ ジェスチャ + オーバーレイ
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = previewTopOffset)
                .fillMaxWidth()
                .aspectRatio(previewRatio)
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
            if (settings.gridEnabled) {
                GridOverlay()
            }
            if (settings.levelEnabled) {
                LevelIndicator(
                    angleDeg = levelAngle,
                    modifier = Modifier.align(Alignment.Center),
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
            // 録画中インジケータ
            if (recording != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color.Red),
                    )
                    Text(
                        text = "%d:%02d".format(recordSeconds / 60, recordSeconds % 60),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            // ズームピル / ダイヤルはプレビューの内側下部（パネル開時は隠す）
            if (!panelOpen) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            bottom = if (settings.aspect == AspectSetting.RATIO_16_9) {
                                131.dp
                            } else {
                                21.dp
                            },
                        ),
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
                            .padding(horizontal = 24.dp),
                    )
                } else {
                    ZoomPill(
                        stops = zoomStops,
                        currentZoom = zoomRatio,
                        onStopSelected = { animateZoomTo(it) },
                        onDialOpen = { dialOpen = true },
                    )
                    }
                }
            }
        }

        // 上部バー + 設定パネル（3.2: 左フラッシュ / 中央シェブロン / 右タイマー・歯車）
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    TopBarButton(onClick = { flashMenuOpen = !flashMenuOpen }) {
                        BoltIcon(mode = flashMode, modifier = Modifier.size(20.dp))
                    }
                }
                TopBarButton(
                    onClick = { if (!keyguardLocked) panelOpen = !panelOpen },
                ) {
                    ChevronIcon(
                        expanded = panelOpen,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (errorStatus != null) {
                        Text(
                            text = errorStatus!!,
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // フラッシュメニューはバーの下にドロップダウン表示する
            // （バー内に横並びすると幅が足りず表示が崩れるため）
            if (flashMenuOpen) {
                FlashMenu(
                    current = flashMode,
                    onSelect = {
                        scope.launch { settingsStore.setFlashMode(it) }
                        flashMenuOpen = false
                    },
                    modifier = Modifier.padding(top = 8.dp, start = 2.dp),
                )
            }

            // 設定パネル（3.4: 約 300ms のスプリングで展開）
            AnimatedVisibility(
                visible = panelOpen && !keyguardLocked,
                enter = expandVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                ) + fadeIn(),
                exit = shrinkVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                ) + fadeOut(),
            ) {
                SettingsPanel(
                    settings = settings,
                    onFlashMode = { scope.launch { settingsStore.setFlashMode(it) } },
                    onTimer = { scope.launch { settingsStore.setTimer(it) } },
                    onAspect = { scope.launch { settingsStore.setAspect(it) } },
                    onGrid = { scope.launch { settingsStore.setGridEnabled(it) } },
                    onLevel = { scope.launch { settingsStore.setLevelEnabled(it) } },
                    onQuality = { scope.launch { settingsStore.setQuality(it) } },
                    onSoundEnabled = {
                        scope.launch { settingsStore.setSoundEnabled(it) }
                    },
                    onSoundVolume = {
                        scope.launch { settingsStore.setSoundVolume(it) }
                    },
                    onSoundInSilent = {
                        scope.launch { settingsStore.setSoundInSilentMode(it) }
                    },
                    onHaptics = {
                        scope.launch { settingsStore.setHapticsEnabled(it) }
                    },
                    onOpenAppSettings = {
                        panelOpen = false
                        appSettingsOpen = true
                    },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(
                            max = if (rootSize.height > 0) {
                                (rootSize.height / density.density).dp - 238.dp
                            } else {
                                560.dp
                            }.coerceAtLeast(200.dp),
                        )
                        .verticalScroll(panelScrollState),
                )
            }
        }

        // 下部: モード帯 + コントロール行（黒帯 593px = 226dp の内訳）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
                    burstEnabled = mode == ShootMode.PHOTO && countdown == null &&
                        timerSetting == TimerSetting.OFF,
                    recording = recording != null,
                    videoMode = mode == ShootMode.VIDEO,
                    pressed = shutterPressed,
                    capturing = capturing || countdown != null,
                    onPressedChange = { shutterPressed = it },
                    onPressStart = { onShutterPressStart() },
                    onBurstStart = { startBurst() },
                    onBurstEnd = { stopBurst() },
                    onShutter = { onShutter() },
                )

                Box(modifier = Modifier.weight(1f)) {}
            }

            Spacer(Modifier.height(7.dp))
            // 最下段: モード帯（中央）と切り替えボタン（右、小さめ）
            Box(modifier = Modifier.fillMaxWidth()) {
                // モード帯（VIDEO / PHOTO）は最下段の中央
                Row(
                    modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                listOf(ShootMode.VIDEO, ShootMode.PHOTO).forEach { m ->
                    val selected = mode == m
                    Text(
                        text = if (m == ShootMode.VIDEO) "VIDEO" else "PHOTO",
                        color = if (selected) {
                            AccentYellow
                        } else {
                            Color.White.copy(alpha = 0.55f)
                        },
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 2.sp,
                        style = TextStyle(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.65f),
                                offset = Offset(0f, 1f),
                                blurRadius = 4f,
                            ),
                        ),
                        modifier = Modifier
                            .clickable(enabled = recording == null) { mode = m }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable(enabled = recording == null) {
                            useFrontCamera = !useFrontCamera
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    CameraSwitchIcon(
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = animatedIconRotation },
                    )
                }
            }
        }

        // 撮影フィードバックの暗転は画面全体にかける（Phase 6 の指摘）
        if (flashVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
            )
        }

        // アプリ設定画面（10.3）
        if (appSettingsOpen) {
            AppSettingsScreen(
                settings = settings,
                onLaunchCamera = {
                    scope.launch { settingsStore.setLaunchCamera(it) }
                },
                onMirror = {
                    scope.launch { settingsStore.setMirrorFrontPhoto(it) }
                },
                onReset = { scope.launch { settingsStore.clearAll() } },
                onClose = { appSettingsOpen = false },
            )
        }

        // 前面の画面フラッシュ用オーバーレイ（タッチは透過させる）
        AndroidView(
            factory = { screenFlashView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * iPhone 風シャッターボタン（設計書 4.1）。
 * 短いタップは離した瞬間に 1 枚、400ms 以上の長押しは連続撮影になる（Phase 6）。
 */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    burstEnabled: Boolean,
    recording: Boolean,
    videoMode: Boolean,
    pressed: Boolean,
    capturing: Boolean,
    onPressedChange: (Boolean) -> Unit,
    onPressStart: () -> Unit,
    onBurstStart: () -> Unit,
    onBurstEnd: () -> Unit,
    onShutter: () -> Unit,
) {
    val buttonScope = rememberCoroutineScope()
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
            .size(68.dp)
            .pointerInput(enabled, burstEnabled) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onPressedChange(true)
                    onPressStart()
                    var burst = false
                    val burstTimer = if (burstEnabled) {
                        buttonScope.launch {
                            delay(400)
                            burst = true
                            onBurstStart()
                        }
                    } else {
                        null
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                    burstTimer?.cancel()
                    onPressedChange(false)
                    if (burst) {
                        onBurstEnd()
                    } else if (enabled) {
                        onShutter()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(68.dp)
                .border(4.dp, Color.White, CircleShape),
        )
        if (recording) {
            // 録画中は赤い四角（停止ボタン）
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.Red),
            )
        } else {
            Box(
                Modifier
                    .size(54.dp)
                    .graphicsLayer {
                        scaleX = innerScale
                        scaleY = innerScale
                    }
                    .clip(CircleShape)
                    .background(
                        when {
                            videoMode -> Color.Red
                            enabled -> Color.White
                            else -> Color.Gray
                        }
                    ),
            )
        }
    }
}

/** カメラ切替アイコン。2 本の円弧と、接線方向に付けた矢じりで描く。 */
@Composable
private fun CameraSwitchIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = size.minDimension * 0.09f,
            cap = StrokeCap.Round,
        )
        val cx = size.width / 2
        val cy = size.height / 2
        val r = size.minDimension / 2 - size.minDimension * 0.15f
        val topLeft = Offset(cx - r, cy - r)
        val arcSize = androidx.compose.ui.geometry.Size(r * 2, r * 2)
        // 上の弧（190°→350°、天辺を通る）と下の弧（10°→170°、底を通る）
        drawArc(
            color = Color.White,
            startAngle = 190f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = Color.White,
            startAngle = 10f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        // 矢じりは塗りつぶしの三角。右（0°）は下向き、左（180°）は上向き
        val ah = size.minDimension * 0.21f // 矢じりの長さ
        val aw = size.minDimension * 0.17f // 矢じりの半幅
        val rightTip = Offset(cx + r, cy)
        drawPath(
            Path().apply {
                moveTo(rightTip.x, rightTip.y + ah)
                lineTo(rightTip.x - aw, rightTip.y - ah * 0.65f)
                lineTo(rightTip.x + aw, rightTip.y - ah * 0.65f)
                close()
            },
            color = Color.White,
        )
        val leftTip = Offset(cx - r, cy)
        drawPath(
            Path().apply {
                moveTo(leftTip.x, leftTip.y - ah)
                lineTo(leftTip.x - aw, leftTip.y + ah * 0.65f)
                lineTo(leftTip.x + aw, leftTip.y + ah * 0.65f)
                close()
            },
            color = Color.White,
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
