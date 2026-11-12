package com.example.quietcamera.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
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

    /**
     * @param isReversedHorizontal 前面カメラの保存ミラー指定（4.7: 既定は非ミラー）。
     * null のときは CameraX の既定に任せる。
     */
    @Synchronized
    fun newOutputOptions(
        resolver: ContentResolver,
        isReversedHorizontal: Boolean? = null,
    ): ImageCapture.OutputFileOptions {
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
        val builder = ImageCapture.OutputFileOptions
            .Builder(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (isReversedHorizontal != null) {
            builder.setMetadata(
                ImageCapture.Metadata().apply {
                    this.isReversedHorizontal = isReversedHorizontal
                }
            )
        }
        return builder.build()
    }

    /**
     * 自アプリが保存した最新の写真の URI を返す（7.3 のサムネイル初期表示用）。
     * 自分のメディアへのクエリなので読み取り権限は不要。呼び出しは IO スレッドで行うこと。
     */
    fun latestPhotoUri(resolver: ContentResolver): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("DCIM/QuietCamera%")
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        return try {
            resolver.query(collection, projection, selection, args, order)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
