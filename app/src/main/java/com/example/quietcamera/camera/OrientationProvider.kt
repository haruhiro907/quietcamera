package com.example.quietcamera.camera

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface

/**
 * 端末の向きを検出して Surface の回転値で通知する（設計書 6.3）。
 * 画面は縦固定のため、保存画像の向きは ImageCapture.targetRotation で合わせる。
 */
class OrientationProvider(
    context: Context,
    private val onRotationChanged: (Int) -> Unit,
) {
    private var currentRotation = Surface.ROTATION_0

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = when {
                orientation in 45..134 -> Surface.ROTATION_270
                orientation in 135..224 -> Surface.ROTATION_180
                orientation in 225..314 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            if (rotation != currentRotation) {
                currentRotation = rotation
                onRotationChanged(rotation)
            }
        }
    }

    fun start() {
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun stop() {
        listener.disable()
    }
}
