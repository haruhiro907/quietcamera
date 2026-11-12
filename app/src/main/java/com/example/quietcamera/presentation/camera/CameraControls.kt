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
.background(Color.Black.copy(alpha = 0.45f))
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
.background(Color.Black.copy(alpha = 0.6f))
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
