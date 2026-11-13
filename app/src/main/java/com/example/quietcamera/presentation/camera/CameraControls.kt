package com.example.quietcamera.presentation.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.example.quietcamera.data.AppSettings
import com.example.quietcamera.data.AspectSetting
import com.example.quietcamera.data.CaptureQuality
import com.example.quietcamera.data.LaunchCamera

/** 設計書 3.3 のアクセント色（フォーカス枠・選択中表示）。 */
val AccentYellow = Color(0xFFFFD60A)

/** フラッシュモード（設計書 4.3）。 */
enum class FlashMode { AUTO, ON, OFF}

/** タイマー設定（設計書 4.3 / 4.5）。 */
enum class TimerSetting(val seconds: Int) {
OFF(0), S3(3), S10(10);

fun next(): TimerSetting = when (this) {
OFF -> S3
S3 -> S10
S10 -> OFF
}
}

fun formatZoom(ratio: Float): String {
val rounded = (ratio * 10).roundToInt() / 10f
return when {
rounded == rounded.toInt().toFloat() -> rounded.toInt().toString()
rounded < 1f -> "." + (rounded * 10).roundToInt().toString()
else -> rounded.toString()
}
}

/**
* ズームピル（設計書 4.2）。倍率ボタンは実機の報告範囲から生成された stops を表示する。
* 現在値がボタンと一致しないときは、最も近いボタンに現在値を表示する。
* 長押しでズームダイヤル（スライダー）を開く。
*/
@Composable
fun ZoomPill(
stops: List<Float>,
currentZoom: Float,
onStopSelected: (Float) -> Unit,
onDialOpen: () -> Unit,
modifier: Modifier = Modifier,
) {
val nearest = stops.minByOrNull { kotlin.math.abs(it - currentZoom)}?: 1.0f
Row(
modifier = modifier
.clip(RoundedCornerShape(50))
.background(Color.White.copy(alpha = 0.14f))
.border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
.padding(horizontal = 6.dp, vertical = 4.dp)
.pillPressGestures(stops, onStopSelected, onDialOpen),
horizontalArrangement = Arrangement.spacedBy(4.dp),
verticalAlignment = Alignment.CenterVertically,
) {
stops.forEach { stop ->
val isActive = stop == nearest
val label = if (isActive && kotlin.math.abs(currentZoom - stop) > 0.05f) {
formatZoom(currentZoom)
} else {
formatZoom(stop)
}
Box(
modifier = Modifier
.size(36.dp)
.clip(CircleShape)
.background(
if (isActive) Color.White.copy(alpha = 0.22f) else Color.Transparent
),
contentAlignment = Alignment.Center,
) {
Text(
text = label,
color = if (isActive) AccentYellow else Color.White,
fontSize = 12.sp,
fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
)
}
}
}
}

/** ズームダイヤル（設計書 4.2）: 横スライダーで連続ズーム。 */
@Composable
fun ZoomDial(
minZoom: Float,
maxZoom: Float,
currentZoom: Float,
onZoomChange: (Float) -> Unit,
onClose: () -> Unit,
modifier: Modifier = Modifier,
) {
var widthPx by remember { mutableFloatStateOf(1f)}
Column(
modifier = modifier
.clip(RoundedCornerShape(16.dp))
.background(Color.Black.copy(alpha = 0.55f))
.padding(horizontal = 16.dp, vertical = 10.dp),
) {
Row(
modifier = Modifier.fillMaxWidth(),
horizontalArrangement = Arrangement.SpaceBetween,
) {
Text(text = formatZoom(minZoom), color = Color.White, fontSize = 11.sp)
Text(
text = formatZoom(currentZoom) + "×",
color = AccentYellow,
fontSize = 12.sp,
fontWeight = FontWeight.Bold,
)
Text(text = formatZoom(maxZoom), color = Color.White, fontSize = 11.sp)
}
Box(
modifier = Modifier
.fillMaxWidth()
.height(28.dp)
.pointerInput(minZoom, maxZoom) {
widthPx = size.width.toFloat()
detectDragGestures(
onDragStart = { offset ->
val f = (offset.x / widthPx).coerceIn(0f, 1f)
onZoomChange(minZoom + f * (maxZoom - minZoom))
},
onDrag = { change, _ ->
val f = (change.position.x / widthPx).coerceIn(0f, 1f)
onZoomChange(minZoom + f * (maxZoom - minZoom))
},
onDragEnd = { onClose()},
)
},
) {
Canvas(Modifier.fillMaxWidth().height(28.dp)) {
val y = size.height / 2
drawLine(
Color.White.copy(alpha = 0.5f),
Offset(0f, y),
Offset(size.width, y),
2.dp.toPx(),
StrokeCap.Round,
)
val f = (currentZoom - minZoom) / (maxZoom - minZoom)
drawCircle(Color.White, 3.dp.toPx(), Offset(0f, y))
drawCircle(Color.White, 3.dp.toPx(), Offset(size.width, y))
drawCircle(
AccentYellow,
9.dp.toPx(),
Offset(size.width * f.coerceIn(0f, 1f), y),
)
}
}
}
}

/**
* フォーカス枠と露出スライダー（設計書 4.4 / 3.3）。
* タップ点に 72dp の黄色い角丸枠、右隣に太陽アイコン付きの縦スライダーを出す。
*/
@Composable
fun FocusIndicator(
center: Offset,
exposureFraction: Float,
scale: Float,
alpha: Float,
modifier: Modifier = Modifier,
) {
val density = LocalDensity.current
val half = with(density) { 36.dp.toPx()}
Box(
modifier = modifier
.offset { IntOffset((center.x - half).roundToInt(), (center.y - half).roundToInt())}
.size(72.dp)
.graphicsLayer {
scaleX = scale
scaleY = scale
this.alpha = alpha
},
) {
Box(
Modifier
.size(72.dp)
.border(1.5.dp, AccentYellow, RoundedCornerShape(10.dp)),
)
// 露出スライダー（枠の右隣）
Box(
Modifier
.offset(x = 80.dp)
.size(width = 20.dp, height = 72.dp),
) {
Canvas(Modifier.fillMaxWidth().height(72.dp)) {
val x = size.width / 2
drawLine(
Color.White.copy(alpha = 0.7f),
Offset(x, 8.dp.toPx()),
Offset(x, size.height - 8.dp.toPx()),
1.5.dp.toPx(),
StrokeCap.Round,
)
val y = size.height * (1f - exposureFraction.coerceIn(0f, 1f))
drawCircle(AccentYellow, 4.dp.toPx(), Offset(x, y))
}
SunIcon(
modifier = Modifier
.offset(y = (64 * (1f - exposureFraction.coerceIn(0f, 1f))).dp)
.size(16.dp),
)
}
}
}

/** 太陽アイコン（露出）。 */
@Composable
fun SunIcon(modifier: Modifier = Modifier) {
Canvas(modifier = modifier) {
val c = Offset(size.width / 2, size.height / 2)
val r = size.minDimension / 4
drawCircle(AccentYellow, r, c)
for (i in 0 until 8) {
val angle = Math.toRadians(i * 45.0)
val inner = Offset(
c.x + (r * 1.5f * kotlin.math.cos(angle)).toFloat(),
c.y + (r * 1.5f * kotlin.math.sin(angle)).toFloat(),
)
val outer = Offset(
c.x + (r * 2.1f * kotlin.math.cos(angle)).toFloat(),
c.y + (r * 2.1f * kotlin.math.sin(angle)).toFloat(),
)
drawLine(AccentYellow, inner, outer, 1.5.dp.toPx(), StrokeCap.Round)
}
}
}

/** 稲妻アイコン（フラッシュ）。off のときは斜線を重ねる。 */
@Composable
fun BoltIcon(
mode: FlashMode,
modifier: Modifier = Modifier,
) {
Canvas(modifier = modifier) {
val w = size.width
val h = size.height
val path = Path().apply {
moveTo(w * 0.58f, 0f)
lineTo(w * 0.18f, h * 0.58f)
lineTo(w * 0.44f, h * 0.58f)
lineTo(w * 0.34f, h)
lineTo(w * 0.84f, h * 0.38f)
lineTo(w * 0.56f, h * 0.38f)
close()
}
drawPath(path, if (mode == FlashMode.OFF) Color.Gray else Color.White)
if (mode == FlashMode.OFF) {
drawLine(
Color.White,
Offset(w * 0.1f, h * 0.9f),
Offset(w * 0.9f, h * 0.1f),
2.dp.toPx(),
StrokeCap.Round,
)
}
}
}

/** 上部バーの丸ボタン（フラッシュ・タイマー共通の見た目）。 */
@Composable
fun TopBarButton(
onClick: () -> Unit,
modifier: Modifier = Modifier,
content: @Composable () -> Unit,
) {
Box(
modifier = modifier
.size(44.dp)
.clip(CircleShape)
.background(Color.White.copy(alpha = 0.14f))
.clickable { onClick()},
contentAlignment = Alignment.Center,
) {
content()
}
}

/** フラッシュの選択メニュー（AUTO / ON / OFF）。 */
@Composable
fun FlashMenu(
current: FlashMode,
onSelect: (FlashMode) -> Unit,
modifier: Modifier = Modifier,
) {
Row(
modifier = modifier
.clip(RoundedCornerShape(50))
.background(Color.White.copy(alpha = 0.14f))
.border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
.padding(horizontal = 6.dp, vertical = 4.dp),
horizontalArrangement = Arrangement.spacedBy(4.dp),
) {
FlashMode.entries.forEach { mode ->
val selected = mode == current
Box(
modifier = Modifier
.clip(RoundedCornerShape(50))
.background(
if (selected) Color.White.copy(alpha = 0.25f) else Color.Transparent
)
.clickable { onSelect(mode)}
.padding(horizontal = 12.dp, vertical = 6.dp),
) {
Text(
text = mode.name,
color = if (selected) AccentYellow else Color.White,
fontSize = 12.sp,
fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
)
}
}
}
}

/**
 * ズームピルの押下処理。短押しは座標からボタンを判定して倍率切替、
 * 長押し（離さずに長押し時間を超える）でダイヤルを開く。
 * ボタン側の clickable と競合しないよう、ピル全体で一括して受ける。
 */
fun Modifier.pillPressGestures(
    stops: List<Float>,
    onStopSelected: (Float) -> Unit,
    onDialOpen: () -> Unit,
): Modifier = pointerInput(stops) {
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val released = withTimeoutOrNull(longPressTimeout) {
            waitForUpOrCancellation()
        }
        if (released == null) {
            onDialOpen()
        } else {
            val startPx = 6.dp.toPx()
            val stepPx = 36.dp.toPx() + 4.dp.toPx()
            val idx = ((down.position.x - startPx) / stepPx).toInt()
            if (idx in stops.indices) {
                val withinButton = down.position.x - startPx - idx * stepPx <= 36.dp.toPx()
                if (withinButton) onStopSelected(stops[idx])
            }
        }
    }
}


/**
 * プレビューのジェスチャ判定（単一ハンドラ）。
 * 指の本数で意味を決めるので、露出表示中でも 2 本指は必ずピンチになる。
 * - 2 本指: ピンチズームのみ（タップ・露出には化けない。ピンチ後の残り指も無視）
 * - 1 本指の短いタップ: フォーカス
 * - 1 本指の長押し: AE/AF ロック
 * - 1 本指の縦ドラッグ: 露出調整
 */
fun Modifier.previewGestures(
    onTapFocus: (Offset) -> Unit,
    onLongPressLock: (Offset) -> Unit,
    onPinchZoom: (Float) -> Unit,
    onExposureDrag: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    val touchSlop = viewConfiguration.touchSlop
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var pinch = false
        var exposure = false
        var dead = false
        var prevDist = 0f
        var prevY = down.position.y

        // 第 1 段階: 長押しタイムアウトか、意味のある最初のイベントを待つ
        val firstEvent = withTimeoutOrNull(longPressTimeout) {
            var e = awaitPointerEvent()
            while (true) {
                val pressed = e.changes.filter { it.pressed }
                when {
                    pressed.size >= 2 -> break
                    pressed.isEmpty() -> break
                    (pressed[0].position - down.position).getDistance() > touchSlop -> break
                }
                e = awaitPointerEvent()
            }
            e
        }
        if (firstEvent == null) {
            // 指を置いたまま長押し時間が経過 → AE/AF ロック
            onLongPressLock(down.position)
            while (true) {
                val e = awaitPointerEvent()
                if (e.changes.none { it.pressed }) break
            }
            return@awaitEachGesture
        }
        val firstPressed = firstEvent.changes.filter { it.pressed }
        when {
            firstPressed.isEmpty() -> {
                val upPos = firstEvent.changes.firstOrNull()?.position ?: down.position
                onTapFocus(upPos)
                return@awaitEachGesture
            }
            firstPressed.size >= 2 -> pinch = true
            else -> {
                val change = firstPressed[0]
                if (kotlin.math.abs(change.position.y - down.position.y) >
                    kotlin.math.abs(change.position.x - down.position.x)
                ) {
                    exposure = true
                    onExposureDrag(change.position.y - prevY)
                    change.consume()
                } else {
                    dead = true
                }
                prevY = change.position.y
            }
        }
        if (dead) {
            while (true) {
                val e = awaitPointerEvent()
                if (e.changes.none { it.pressed }) break
            }
            return@awaitEachGesture
        }
        // 第 2 段階: ピンチ / 露出ドラッグの継続
        while (true) {
            val e = awaitPointerEvent()
            val pressed = e.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                pinch = true
                exposure = false
                val d = (pressed[0].position - pressed[1].position).getDistance()
                if (prevDist > 0f && d > 0f) onPinchZoom(d / prevDist)
                prevDist = d
                e.changes.forEach { it.consume() }
                continue
            }
            if (pinch) continue // ピンチ後に残った 1 本指は離すまで無視する
            val change = pressed[0]
            if (exposure) {
                onExposureDrag(change.position.y - prevY)
                change.consume()
            }
            prevY = change.position.y
        }
    }
}

// ---------------- Phase 5: ガラス表現・設定パネル・グリッド・水準器 ----------------

/** ガラスの縁（設計書 3.3: tint は白 α0.12〜0.20）。 */
val GlassBorder = Color.White.copy(alpha = 0.22f)

/** このファイル内のガラス面の基本色。 */
val GlassFill = Color.White.copy(alpha = 0.14f)

/** 上部中央のシェブロン（設定パネルの開閉、設計書 3.2）。 */
@Composable
fun ChevronIcon(expanded: Boolean, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.graphicsLayer { rotationZ = if (expanded) 180f else 0f },
    ) {
        val stroke = 2.dp.toPx()
        drawLine(
            Color.White,
            Offset(size.width * 0.22f, size.height * 0.66f),
            Offset(size.width * 0.5f, size.height * 0.34f),
            stroke, StrokeCap.Round,
        )
        drawLine(
            Color.White,
            Offset(size.width * 0.5f, size.height * 0.34f),
            Offset(size.width * 0.78f, size.height * 0.66f),
            stroke, StrokeCap.Round,
        )
    }
}

/** 歯車アイコン（アプリ設定、設計書 3.2）。自作ベクター。 */
@Composable
fun GearIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val rOut = size.minDimension / 2
        drawCircle(
            Color.White, rOut * 0.62f, c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
        )
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val from = Offset(
                c.x + (rOut * 0.62f * kotlin.math.cos(a)).toFloat(),
                c.y + (rOut * 0.62f * kotlin.math.sin(a)).toFloat(),
            )
            val to = Offset(
                c.x + (rOut * 0.92f * kotlin.math.cos(a)).toFloat(),
                c.y + (rOut * 0.92f * kotlin.math.sin(a)).toFloat(),
            )
            drawLine(Color.White, from, to, 2.dp.toPx(), StrokeCap.Round)
        }
        drawCircle(Color.White, rOut * 0.16f, c)
    }
}

/** タイマーアイコン（時計）。 */
@Composable
fun TimerIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(
            Color.White,
            size.minDimension / 2 - 1.dp.toPx(),
            c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx()),
        )
        drawLine(
            Color.White, c, Offset(c.x, c.y - size.minDimension / 4),
            1.8.dp.toPx(), StrokeCap.Round,
        )
        drawLine(
            Color.White, c, Offset(c.x + size.minDimension / 5, c.y),
            1.8.dp.toPx(), StrokeCap.Round,
        )
    }
}

/** 3×3 グリッド（設計書 4.6: 細い白線 α0.4）。 */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val color = Color.White.copy(alpha = 0.4f)
        val w = size.width
        val h = size.height
        val sw = 1.dp.toPx()
        drawLine(color, Offset(w / 3, 0f), Offset(w / 3, h), sw)
        drawLine(color, Offset(2 * w / 3, 0f), Offset(2 * w / 3, h), sw)
        drawLine(color, Offset(0f, h / 3), Offset(w, h / 3), sw)
        drawLine(color, Offset(0f, 2 * h / 3), Offset(w, 2 * h / 3), sw)
    }
}

/**
 * 水準器（設計書 4.6）。中央の固定目盛（黄）に回転する水平線が重なると水平。
 * angleDeg は端末の傾き（重力センサーから算出）。
 */
@Composable
fun LevelIndicator(angleDeg: Float, modifier: Modifier = Modifier) {
    val isLevel = kotlin.math.abs(angleDeg) <= 1f
    val lineColor = if (isLevel) AccentYellow else Color.White.copy(alpha = 0.85f)
    Box(
        modifier = modifier.size(150.dp, 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(20.dp, 2.dp)
                .background(AccentYellow.copy(alpha = 0.95f)),
        )
        Row(
            modifier = Modifier.graphicsLayer { rotationZ = -angleDeg },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.width(56.dp).height(2.dp).background(lineColor))
            Box(Modifier.width(56.dp).height(2.dp).background(lineColor))
        }
    }
}

/** 設定パネル内のセグメント選択（ガラス調）。 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) Color.White.copy(alpha = 0.30f)
                        else Color.Transparent
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 設定の 1 行（ラベル + 操作部）。 */
@Composable
fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.width(128.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

private fun onOffIndex(value: Boolean) = if (value) 1 else 0
private val ON_OFF = listOf("OFF", "ON")

/**
 * 撮影設定パネル（設計書 4.3 / 5.2 / 10.1 / 10.2）。
 * Liquid Glass 風: 半透明の地に白 tint と細い縁。プレビュー映像のブラーは
 * SurfaceView のため効かないので、暗色半透明で代替する（3.5 のフォールバック）。
 */
@Composable
fun SettingsPanel(
    settings: AppSettings,
    onFlashMode: (FlashMode) -> Unit,
    onTimer: (TimerSetting) -> Unit,
    onAspect: (AspectSetting) -> Unit,
    onGrid: (Boolean) -> Unit,
    onLevel: (Boolean) -> Unit,
    onQuality: (CaptureQuality) -> Unit,
    onSoundEnabled: (Boolean) -> Unit,
    onSoundVolume: (Float) -> Unit,
    onSoundInSilent: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.66f))
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SettingRow("アスペクト比") {
            SegmentedControl(
                options = AspectSetting.entries.map { it.label },
                selectedIndex = settings.aspect.ordinal,
                onSelect = { onAspect(AspectSetting.entries[it]) },
            )
        }
        SettingRow("画質") {
            SegmentedControl(
                options = CaptureQuality.entries.map { it.label },
                selectedIndex = settings.quality.ordinal,
                onSelect = { onQuality(CaptureQuality.entries[it]) },
            )
        }
        SettingRow("フラッシュ") {
            SegmentedControl(
                options = FlashMode.entries.map { it.name },
                selectedIndex = settings.flashMode.ordinal,
                onSelect = { onFlashMode(FlashMode.entries[it]) },
            )
        }
        SettingRow("タイマー") {
            SegmentedControl(
                options = listOf("OFF", "3秒", "10秒"),
                selectedIndex = TimerSetting.entries
                    .indexOfFirst { it.seconds == settings.timerSeconds }
                    .coerceAtLeast(0),
                onSelect = { onTimer(TimerSetting.entries[it]) },
            )
        }
        SettingRow("グリッド") {
            SegmentedControl(
                options = ON_OFF,
                selectedIndex = onOffIndex(settings.gridEnabled),
                onSelect = { onGrid(it == 1) },
            )
        }
        SettingRow("水準器") {
            SegmentedControl(
                options = ON_OFF,
                selectedIndex = onOffIndex(settings.levelEnabled),
                onSelect = { onLevel(it == 1) },
            )
        }
        SettingRow("シャッター音") {
            SegmentedControl(
                options = ON_OFF,
                selectedIndex = onOffIndex(settings.soundEnabled),
                onSelect = { onSoundEnabled(it == 1) },
            )
        }
        SettingRow("音量") {
            Slider(
                value = settings.soundVolume,
                onValueChange = onSoundVolume,
                enabled = settings.soundEnabled,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.85f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                ),
            )
        }
        SettingRow("マナーモード時も鳴らす") {
            SegmentedControl(
                options = ON_OFF,
                selectedIndex = onOffIndex(settings.soundInSilentMode),
                onSelect = { onSoundInSilent(it == 1) },
            )
        }
        SettingRow("触覚フィードバック") {
            SegmentedControl(
                options = ON_OFF,
                selectedIndex = onOffIndex(settings.hapticsEnabled),
                onSelect = { onHaptics(it == 1) },
            )
        }
        // アプリ設定（旧・歯車ボタン）はパネル内に統合した（Phase 6 追加分）
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(50))
                .background(GlassFill)
                .border(1.dp, GlassBorder, RoundedCornerShape(50))
                .clickable { onOpenAppSettings() }
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(
                text = "アプリ設定（起動カメラ・ミラー保存・初期化）",
                color = Color.White,
                fontSize = 12.sp,
            )
        }
    }
}

/** アプリ設定画面（設計書 10.3）。歯車から開く全画面オーバーレイ。 */
@Composable
fun AppSettingsScreen(
    settings: AppSettings,
    onLaunchCamera: (LaunchCamera) -> Unit,
    onMirror: (Boolean) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "設定",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Box(Modifier.weight(1f))
                TopBarButton(onClick = onClose) {
                    Text(text = "✕", color = Color.White, fontSize = 15.sp)
                }
            }
            SettingRow("起動時のカメラ", modifier = Modifier.padding(top = 16.dp)) {
                SegmentedControl(
                    options = listOf("背面", "前面"),
                    selectedIndex = settings.launchCamera.ordinal,
                    onSelect = { onLaunchCamera(LaunchCamera.entries[it]) },
                )
            }
            SettingRow("前面カメラのミラー保存") {
                SegmentedControl(
                    options = ON_OFF,
                    selectedIndex = onOffIndex(settings.mirrorFrontPhoto),
                    onSelect = { onMirror(it == 1) },
                )
            }
            Box(
                modifier = Modifier
                    .padding(top = 28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(GlassFill)
                    .border(1.dp, GlassBorder, RoundedCornerShape(50))
                    .clickable { onReset() }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "設定を初期化",
                    color = Color(0xFFFF453A),
                    fontSize = 13.sp,
                )
            }
            Box(Modifier.weight(1f))
            Text(
                text = "Quiet Camera 0.3.0",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 11.sp,
            )
        }
    }
}
