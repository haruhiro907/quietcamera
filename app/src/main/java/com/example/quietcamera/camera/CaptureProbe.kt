package com.example.quietcamera.camera

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Phase 0 専用の撮影プローブ。
 *
 * 設計書 2.3「システム側の撮影音が鳴るか」は、アプリ側で一切音を鳴らさずに
 * 撮影してみないと分からない。その確認のためだけに、最小限の ImageCapture で
 * 1枚撮影して MediaStore (DCIM/QuietCamera) に保存する。
 * これは Phase 2 の撮影機能の実装ではなく、調査用コードである。
 */
object CaptureProbe {

    private const val TAG = "QuietCameraDiag"

    suspend fun captureSilently(context: Context, lifecycleOwner: LifecycleOwner): String {
        val provider = try {
            withContext(Dispatchers.Default) {
                ProcessCameraProvider.getInstance(context).get()
            }
        } catch (e: Exception) {
            return "カメラの初期化に失敗: ${e.message}"
        }

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        return try {
            withContext(Dispatchers.Main) {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageCapture
                )
            }
            val name = "QC_PHASE0_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/QuietCamera")
            }
            val options = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).build()

            suspendCancellableCoroutine { cont ->
                imageCapture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.i(TAG, "Phase0 silent capture saved: ${output.savedUri}")
                            if (cont.isActive) {
                                cont.resume(
                                    "保存しました: ${output.savedUri}\n" +
                                        "撮影の瞬間にシステム音が鳴ったか、耳で確認してください。"
                                )
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Phase0 silent capture failed", exception)
                            if (cont.isActive) {
                                cont.resume("撮影に失敗: ${exception.message}")
                            }
                        }
                    }
                )
            }
        } catch (e: Exception) {
            "撮影に失敗: ${e.message}"
        } finally {
            withContext(Dispatchers.Main) { provider.unbindAll() }
        }
    }
}
