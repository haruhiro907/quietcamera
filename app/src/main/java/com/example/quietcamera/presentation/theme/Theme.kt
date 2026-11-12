package com.example.quietcamera.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** Phase 0 用の最小テーマ。デザインの作り込みは Phase 5 で行う。 */
@Composable
fun QuietCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content
    )
}
