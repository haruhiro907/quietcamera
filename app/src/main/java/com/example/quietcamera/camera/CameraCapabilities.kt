package com.example.quietcamera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/**
 * Phase 0 の能力調査（設計書 2.3）。
 * 端末・OS とカメラ群の能力を集め、画面表示と Logcat の両方へ出せるテキストを作る。
 * ここで得た値は、後続フェーズの前提（0.5x の可否、露出範囲、解像度候補など）を決める。
 */
object CameraCapabilities {

    private const val TAG = "QuietCameraDiag"

    suspend fun collectReport(context: Context): String = withContext(Dispatchers.Default) {
        val sb = StringBuilder()
        fun line(s: String = "") {
            sb.append(s).append('\n')
        }

        line("=== Quiet Camera Phase 0 能力調査 ===")
        line()
        line("■ 端末 / OS")
        line("メーカー: ${Build.MANUFACTURER} / モデル: ${Build.MODEL}")
        line("Android バージョン: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("ビルド番号: ${Build.ID} / 表示: ${Build.DISPLAY}")
        line("セキュリティパッチ: ${Build.VERSION.SECURITY_PATCH}")
        line()

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = cameraManager.cameraIdList.toList()
        line("■ Camera2 カメラ一覧 (cameraIdList: ${ids.size}件)")
        for (id in ids) {
            val ch = cameraManager.getCameraCharacteristics(id)
            val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK(背面)"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT(前面)"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "不明"
            }
            val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "不明"
            }
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()
            val isLogical = caps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            )
            val physicalIds = try {
                ch.physicalCameraIds
            } catch (e: Exception) {
                emptySet<String>()
            }
            val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            line(
                "ID $id: 向き=$facing / HWレベル=$level / 論理マルチカメラ=$isLogical" +
                    " / 物理ID=$physicalIds / フラッシュ=$flash"
            )
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            line("  JPEG出力解像度: ${jpegSizes.size}件")
            val byAspect = jpegSizes.groupBy { aspectLabel(it) }
            for ((label, sizes) in byAspect.toSortedMap()) {
                val sorted = sizes.sortedByDescending { it.width.toLong() * it.height }
                line("    [$label] ${sorted.joinToString(", ") { "${it.width}x${it.height}" }}")
            }
        }
        line()

        line("■ CameraX")
        val provider = try {
            ProcessCameraProvider.getInstance(context).get()
        } catch (e: Exception) {
            line("ProcessCameraProvider の取得に失敗: ${e.message}")
            null
        }
        if (provider != null) {
            val infos = provider.availableCameraInfos
            line("availableCameraInfos: ${infos.size}件")
            for (info in infos) {
                val camId = try {
                    Camera2CameraInfo.from(info).cameraId
                } catch (e: Exception) {
                    "?"
                }
                val facingName = when (info.lensFacing) {
                    CameraSelector.LENS_FACING_BACK -> "BACK"
                    CameraSelector.LENS_FACING_FRONT -> "FRONT"
                    else -> "不明"
                }
                line("CameraInfo id=$camId facing=$facingName flash=${info.hasFlashUnit()}")
                val zoom = try {
                    info.zoomState.value
                } catch (e: Exception) {
                    null
                }
                if (zoom != null) {
                    line("  zoomState: min=${zoom.minZoomRatio} / max=${zoom.maxZoomRatio}")
                    line(
                        "  → 0.5x超広角: " +
                            if (zoom.minZoomRatio < 1.0f) "利用可能 (min < 1.0)" else "利用不可 (min = 1.0)"
                    )
                } else {
                    line("  zoomState: 取得不可")
                }
                val exp = try {
                    info.exposureState
                } catch (e: Exception) {
                    null
                }
                if (exp != null) {
                    if (exp.isExposureCompensationSupported) {
                        val step = exp.exposureCompensationStep.toFloat()
                        val range = exp.exposureCompensationRange
                        line(
                            "  露出補正: 対応 / 範囲=$range / ステップ=$step" +
                                " (EV換算 約 ${range.lower * step} 〜 ${range.upper * step})"
                        )
                    } else {
                        line("  露出補正: 非対応")
                    }
                } else {
                    line("  exposureState: 取得不可")
                }
            }

            line()
            line("■ CameraX Extensions")
            try {
                val extManager = ExtensionsManager.getInstanceAsync(context, provider).get()
                val modes = listOf(
                    ExtensionMode.AUTO to "AUTO",
                    ExtensionMode.BOKEH to "BOKEH(ポートレート)",
                    ExtensionMode.HDR to "HDR",
                    ExtensionMode.NIGHT to "NIGHT(夜景)",
                    ExtensionMode.FACE_RETOUCH to "FACE_RETOUCH(美肌)"
                )
                val selectors = listOf(
                    "BACK" to CameraSelector.DEFAULT_BACK_CAMERA,
                    "FRONT" to CameraSelector.DEFAULT_FRONT_CAMERA
                )
                for ((mode, name) in modes) {
                    for ((selectorName, selector) in selectors) {
                        val available = try {
                            extManager.isExtensionAvailable(selector, mode)
                        } catch (e: Exception) {
                            false
                        }
                        line("$name / $selectorName: ${if (available) "対応" else "非対応"}")
                    }
                }
            } catch (e: Exception) {
                line("ExtensionsManager の取得に失敗: ${e.message}")
            }
        }
        line()
        line("■ 実機で耳・指で確認する項目（この画面の自動調査では判定不可）")
        line("・「無音で撮影テスト」を押し、システム撮影音が鳴るか確認する")
        line("・「振動テスト」で EFFECT_TICK / CLICK / OneShot の弱さを体感する")
        line("=== 調査ここまで ===")

        val report = sb.toString()
        report.lines().forEach { Log.i(TAG, it) }
        report
    }

    private fun aspectLabel(size: Size): String {
        val w = size.width
        val h = size.height
        if (w == h) return "1:1"
        val ratio = maxOf(w, h).toDouble() / minOf(w, h).toDouble()
        return when {
            abs(ratio - 4.0 / 3.0) < 0.02 -> "4:3"
            abs(ratio - 16.0 / 9.0) < 0.02 -> "16:9"
            abs(ratio - 3.0 / 2.0) < 0.02 -> "3:2"
            else -> String.format(Locale.US, "%.2f:1", ratio)
        }
    }
}
