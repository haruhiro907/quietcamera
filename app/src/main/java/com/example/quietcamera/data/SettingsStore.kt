package com.example.quietcamera.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.quietcamera.presentation.camera.FlashMode
import com.example.quietcamera.presentation.camera.TimerSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** アスペクト比設定（設計書 4.3）。 */
enum class AspectSetting(val label: String) {
    RATIO_4_3("4:3"),
    RATIO_16_9("16:9"),
    RATIO_1_1("1:1"),
}

/** 撮影品質（設計書 8.1）。 */
enum class CaptureQuality(val label: String) {
    STANDARD("標準"),
    HIGH("高画質"),
}

/** 起動時のカメラ（設計書 10.3）。 */
enum class LaunchCamera {
    BACK,
    FRONT,
}

/** 設定一式（設計書 10.4 のキーと既定値に対応）。 */
data class AppSettings(
    val flashMode: FlashMode = FlashMode.AUTO,
    val timerSeconds: Int = 0,
    val aspect: AspectSetting = AspectSetting.RATIO_4_3,
    val gridEnabled: Boolean = false,
    val levelEnabled: Boolean = false,
    val quality: CaptureQuality = CaptureQuality.STANDARD,
    val soundEnabled: Boolean = true,
    val soundVolume: Float = 0.5f,
    val soundInSilentMode: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val launchCamera: LaunchCamera = LaunchCamera.BACK,
    val mirrorFrontPhoto: Boolean = false,
    val geotagEnabled: Boolean = false,
    val qrEnabled: Boolean = true,
)

private val Context.appDataStore by preferencesDataStore(name = "quiet_camera_settings")

/** DataStore Preferences による設定の永続化（設計書 10.4）。 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val FLASH_MODE = stringPreferencesKey("flash_mode")
        val TIMER_SECONDS = intPreferencesKey("timer_seconds")
        val ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        val GRID_ENABLED = booleanPreferencesKey("grid_enabled")
        val LEVEL_ENABLED = booleanPreferencesKey("level_enabled")
        val CAPTURE_QUALITY = stringPreferencesKey("capture_quality")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val SOUND_VOLUME = floatPreferencesKey("sound_volume")
        val SOUND_IN_SILENT = booleanPreferencesKey("sound_in_silent_mode")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val LAUNCH_CAMERA = stringPreferencesKey("launch_camera")
        val MIRROR_FRONT = booleanPreferencesKey("mirror_front_photo")
        val GEOTAG_ENABLED = booleanPreferencesKey("geotag_enabled")
        val QR_ENABLED = booleanPreferencesKey("qr_enabled")
    }

    val settings: Flow<AppSettings> = context.appDataStore.data.map { prefs ->
        AppSettings(
            flashMode = runCatching {
                FlashMode.valueOf(prefs[Keys.FLASH_MODE] ?: "AUTO")
            }.getOrDefault(FlashMode.AUTO),
            timerSeconds = prefs[Keys.TIMER_SECONDS] ?: 0,
            aspect = runCatching {
                AspectSetting.valueOf(prefs[Keys.ASPECT_RATIO] ?: "RATIO_4_3")
            }.getOrDefault(AspectSetting.RATIO_4_3),
            gridEnabled = prefs[Keys.GRID_ENABLED] ?: false,
            levelEnabled = prefs[Keys.LEVEL_ENABLED] ?: false,
            quality = runCatching {
                CaptureQuality.valueOf(prefs[Keys.CAPTURE_QUALITY] ?: "STANDARD")
            }.getOrDefault(CaptureQuality.STANDARD),
            soundEnabled = prefs[Keys.SOUND_ENABLED] ?: true,
            soundVolume = prefs[Keys.SOUND_VOLUME] ?: 0.5f,
            soundInSilentMode = prefs[Keys.SOUND_IN_SILENT] ?: false,
            hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
            launchCamera = runCatching {
                LaunchCamera.valueOf(prefs[Keys.LAUNCH_CAMERA] ?: "BACK")
            }.getOrDefault(LaunchCamera.BACK),
            mirrorFrontPhoto = prefs[Keys.MIRROR_FRONT] ?: false,
            geotagEnabled = prefs[Keys.GEOTAG_ENABLED] ?: false,
            qrEnabled = prefs[Keys.QR_ENABLED] ?: true,
        )
    }

    suspend fun setFlashMode(mode: FlashMode) {
        context.appDataStore.edit { it[Keys.FLASH_MODE] = mode.name }
    }

    suspend fun setTimerSeconds(seconds: Int) {
        context.appDataStore.edit { it[Keys.TIMER_SECONDS] = seconds }
    }

    suspend fun setTimer(setting: TimerSetting) = setTimerSeconds(setting.seconds)

    suspend fun setAspect(aspect: AspectSetting) {
        context.appDataStore.edit { it[Keys.ASPECT_RATIO] = aspect.name }
    }

    suspend fun setGridEnabled(enabled: Boolean) {
        context.appDataStore.edit { it[Keys.GRID_ENABLED] = enabled }
    }

    suspend fun setLevelEnabled(enabled: Boolean) {
        context.appDataStore.edit { it[Keys.LEVEL_ENABLED] = enabled }
    }

    suspend fun setQuality(quality: CaptureQuality) {
        context.appDataStore.edit { it[Keys.CAPTURE_QUALITY] = quality.name }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.appDataStore.edit { it[Keys.SOUND_ENABLED] = enabled }
    }

    suspend fun setSoundVolume(volume: Float) {
        context.appDataStore.edit { it[Keys.SOUND_VOLUME] = volume }
    }

    suspend fun setSoundInSilentMode(enabled: Boolean) {
        context.appDataStore.edit { it[Keys.SOUND_IN_SILENT] = enabled }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.appDataStore.edit { it[Keys.HAPTICS_ENABLED] = enabled }
    }

    suspend fun setLaunchCamera(camera: LaunchCamera) {
        context.appDataStore.edit { it[Keys.LAUNCH_CAMERA] = camera.name }
    }

    suspend fun setMirrorFrontPhoto(enabled: Boolean) {
        context.appDataStore.edit { it[Keys.MIRROR_FRONT] = enabled }
    }

    suspend fun setQrEnabled(enabled: Boolean) {
        context.appDataStore.edit { it[Keys.QR_ENABLED] = enabled }
    }

    suspend fun clearAll() {
        context.appDataStore.edit { it.clear() }
    }
}
