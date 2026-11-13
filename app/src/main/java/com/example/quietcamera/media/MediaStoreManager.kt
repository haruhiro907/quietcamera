package com.example.quietcamera.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import java.io.ByteArrayOutputStream
import java.io.File
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
    private fun nextDisplayName(): String {
        val base = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return if (base == lastBaseName) {
            suffixCounter++
            "%s_%03d".format(base, suffixCounter)
        } else {
            lastBaseName = base
            suffixCounter = 0
            base
        }
    }

    /**
     * @param isReversedHorizontal 前面カメラの保存ミラー指定（4.7: 既定は非ミラー）。
     * null のときは CameraX の既定に任せる。
     */
    fun newOutputOptions(
        resolver: ContentResolver,
        isReversedHorizontal: Boolean? = null,
    ): ImageCapture.OutputFileOptions {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nextDisplayName())
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
     * 1:1 撮影用: 一時ファイルに撮った JPEG を EXIF の向きで正立させ、中央を
     * 正方形に切り抜いて MediaStore へ保存する（設計書 4.3 アスペクト比）。
     * IO スレッドから呼ぶこと。
     *
     * @param flipHorizontal 前面カメラのミラー保存（10.3）
     */
    fun saveSquareJpeg(
        resolver: ContentResolver,
        source: File,
        flipHorizontal: Boolean,
        jpegQuality: Int,
    ): Uri? {
        val decoded = BitmapFactory.decodeFile(source.absolutePath) ?: return null
        val oriented = applyExifOrientation(decoded, source)
        val size = minOf(oriented.width, oriented.height)
        val x = (oriented.width - size) / 2
        val y = (oriented.height - size) / 2
        var squared = Bitmap.createBitmap(oriented, x, y, size, size)
        if (flipHorizontal) {
            val matrix = Matrix().apply { postScale(-1f, 1f) }
            squared = Bitmap.createBitmap(squared, 0, 0, size, size, matrix, true)
        }
        val bytes = ByteArrayOutputStream().also { out ->
            squared.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        }.toByteArray()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nextDisplayName())
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/QuietCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            uri
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {
            }
            null
        }
    }

    /** EXIF の Orientation を実ピクセルへ反映する（回転・反転）。 */
    private fun applyExifOrientation(bitmap: Bitmap, source: File): Bitmap {
        val orientation = try {
            ExifInterface(source.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private var lastVideoBaseName: String? = null
    private var videoSuffixCounter = 0

    /** 動画ファイル名 VID_YYYYMMDD_HHMMSS（同一秒は連番）。 */
    @Synchronized
    fun nextVideoDisplayName(): String {
        val base = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return if (base == lastVideoBaseName) {
            videoSuffixCounter++
            "%s_%03d".format(base, videoSuffixCounter)
        } else {
            lastVideoBaseName = base
            videoSuffixCounter = 0
            base
        }
    }

    /**
     * DCIM/QuietCamera 内の最新メディア（写真・動画の新しい方）を返す。
     * 左下サムネイル用。動画も写真と同じ場所に保存するので同じ条件で拾える。
     * 呼び出しは IO スレッドで行うこと。
     */
    fun latestMediaUri(resolver: ContentResolver): Uri? {
        fun newest(collection: Uri, idColumn: String, dateColumn: String, pathColumn: String): Pair<Uri, Long>? {
            val projection = arrayOf(idColumn, dateColumn)
            val selection = "$pathColumn LIKE ?"
            val args = arrayOf("DCIM/QuietCamera%")
            return try {
                resolver.query(
                    collection, projection, selection, args, "$dateColumn DESC",
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        ContentUris.withAppendedId(collection, cursor.getLong(0)) to
                            cursor.getLong(1)
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        val photo = newest(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val video = newest(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        return when {
            photo == null -> video?.first
            video == null -> photo.first
            else -> if (video.second >= photo.second) video.first else photo.first
        }
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
