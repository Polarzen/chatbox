package dev.polarzen.chatbox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

private data class PickerColor(val hue: Float, val saturation: Float, val brightness: Float) {
    fun hex(): String = String.format(Locale.ROOT, "#%06X",
        Color.hsv(hue, saturation, brightness).toArgb() and 0xffffff)

    companion object {
        fun read(hex: String): PickerColor {
            val rgb = hex.drop(1).toInt(16)
            val r = ((rgb ushr 16) and 255) / 255f
            val g = ((rgb ushr 8) and 255) / 255f
            val b = (rgb and 255) / 255f
            val maximum = maxOf(r, g, b)
            val delta = maximum - minOf(r, g, b)
            val sector = when {
                delta == 0f -> 0f
                maximum == r -> (g - b) / delta
                maximum == g -> (b - r) / delta + 2f
                else -> (r - g) / delta + 4f
            }
            return PickerColor((sector * 60f + 360f) % 360f, if (maximum == 0f) 0f else delta / maximum, maximum)
        }
    }
}

@Composable
internal fun ChatColorPicker(accent: String?, dark: Boolean, onAccentChange: (String?) -> Unit) {
    val defaultHex = if (dark) "#C6ABFF" else "#6745B4"
    val effective = accent ?: defaultHex
    var picked by remember { mutableStateOf(PickerColor.read(effective)) }
    var input by remember { mutableStateOf(effective) }
    LaunchedEffect(effective) {
        if (!picked.hex().equals(effective, ignoreCase = true)) picked = PickerColor.read(effective)
        input = effective
    }
    val sendAccent by rememberUpdatedState(onAccentChange)
    fun change(next: PickerColor) {
        picked = next
        input = next.hex()
        sendAccent(input)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val rainbow = remember { (0..6).map { Color.hsv(it * 60f, 1f, 1f) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            fun nudge(ds: Float, dv: Float): Boolean {
                change(picked.copy(saturation = (picked.saturation + ds).coerceIn(0f, 1f),
                    brightness = (picked.brightness + dv).coerceIn(0f, 1f)))
                return true
            }
            Canvas(Modifier.size(116.dp).semantics {
                contentDescription = "饱和度与明度"
                stateDescription = "饱和度 ${(picked.saturation * 100).roundToInt()}%，明度 ${(picked.brightness * 100).roundToInt()}%"
                customActions = listOf(
                    CustomAccessibilityAction("增加饱和度") { nudge(.05f, 0f) },
                    CustomAccessibilityAction("减少饱和度") { nudge(-.05f, 0f) },
                    CustomAccessibilityAction("提高明度") { nudge(0f, .05f) },
                    CustomAccessibilityAction("降低明度") { nudge(0f, -.05f) },
                )
            }.onKeyEvent { event ->
                event.type == KeyEventType.KeyDown && when (event.key) {
                    Key.DirectionLeft -> nudge(-.01f, 0f)
                    Key.DirectionRight -> nudge(.01f, 0f)
                    Key.DirectionUp -> nudge(0f, .01f)
                    Key.DirectionDown -> nudge(0f, -.01f)
                    else -> false
                }
            }.focusable().pointerInput(Unit) {
                awaitEachGesture {
                    var point = awaitFirstDown()
                    val id = point.id
                    while (true) {
                        change(picked.copy(saturation = (point.position.x / size.width).coerceIn(0f, 1f),
                            brightness = 1f - (point.position.y / size.height).coerceIn(0f, 1f)))
                        point.consume()
                        if (!point.pressed) break
                        point = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                    }
                }
            }) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(picked.hue, 1f, 1f))))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val radius = 5.dp.toPx()
                val mark = Offset((size.width * picked.saturation).coerceIn(radius, size.width - radius),
                    (size.height * (1f - picked.brightness)).coerceIn(radius, size.height - radius))
                drawCircle(Color.Black, radius, mark, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, radius, mark, style = Stroke(1.5.dp.toPx()))
            }
            Column(Modifier.weight(1f)) {
                OutlinedTextField(value = input, onValueChange = { raw ->
                    input = raw
                    val digits = raw.trim().removePrefix("#")
                    if (digits.length == 6 && digits.all { it in "0123456789abcdefABCDEF" }) {
                        val hex = "#" + digits.uppercase(Locale.ROOT)
                        picked = PickerColor.read(hex)
                        onAccentChange(hex)
                    }
                }, label = { Text("色值 #RRGGBB") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    input = defaultHex
                    picked = PickerColor.read(defaultHex)
                    onAccentChange(null)
                }) { Text("默认色") }
            }
        }
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(8.dp)
                .background(Brush.horizontalGradient(rainbow), RoundedCornerShape(4.dp)))
            Slider(value = picked.hue, onValueChange = { change(picked.copy(hue = it)) },
                valueRange = 0f..360f, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "色相" },
                colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
        }
    }
}
