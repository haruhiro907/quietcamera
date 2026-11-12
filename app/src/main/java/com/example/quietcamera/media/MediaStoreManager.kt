package com.example.quietcamera.media

import android.content.ContentResolver
import android.content.ContentValues
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
* MediaStore への保存先を用意する（設計書 7.1）。
*
* - 保存先: DCIM/QuietCamera（RELATIVE_PATH）
* - ファイル名: IMG_YYYYMMDD_HHMMSS。同一秒の重複には _001 からの連番を付ける
* - 書き込み中は IS_PENDING を立てる（ImageCapture 側が保存完了時に解除する）
* - 位置情報は付けない（既定オフ・7.1）
*/
object MediaStoreManager {

private var lastBaseName: String? = null
private var suffixCounter = 0

@Synchronized
fun newOutputOptions(resolver: ContentResolver): ImageCapture.OutputFileOptions {
val base = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
val displayName = if (base == lastBaseName) {
suffixCounter++
"%s_%03d".format(base, suffixCounter)
} else {
lastBaseName = base
suffixCounter = 0
base
}
val values = ContentValues().apply {
put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/QuietCamera")
put(MediaStore.Images.Media.IS_PENDING, 1)
}
return ImageCapture.OutputFileOptions
.Builder(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
.build()
}
}
