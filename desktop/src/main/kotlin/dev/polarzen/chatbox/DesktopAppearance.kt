package dev.polarzen.chatbox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

internal data class DesktopAppearance(
    val pinned: Boolean = false,
    val dark: Boolean = false,
    val opacityPercent: Int = 100,
) {
    init { require(opacityPercent in 50..100) }
}

internal class DesktopAppearanceStore(private val dataDir: Path) {
    private val file = dataDir.resolve("appearance.json")

    fun load(): DesktopAppearance {
        val json = try {
            JSONObject(Files.readString(file))
        } catch (_: IOException) {
            return DesktopAppearance()
        } catch (_: JSONException) {
            return DesktopAppearance()
        }
        return DesktopAppearance(
            pinned = json.opt("pinned") as? Boolean ?: false,
            dark = json.opt("dark") as? Boolean ?: false,
            opacityPercent = (json.opt("opacityPercent") as? Int)?.takeIf { it in 50..100 } ?: 100,
        )
    }

    fun save(value: DesktopAppearance) {
        val json = JSONObject().put("pinned", value.pinned).put("dark", value.dark)
            .put("opacityPercent", value.opacityPercent)
        Files.createDirectories(dataDir)
        val temp = Files.createTempFile(dataDir, "appearance-", ".tmp")
        try {
            Files.writeString(temp, json.toString())
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

internal data class DesktopPalette(
    val canvas: Color, val panel: Color, val ink: Color, val muted: Color, val line: Color,
    val accent: Color, val accentSurface: Color, val onAccent: Color, val scrollbar: Color,
    val scrollbarHover: Color, val thumbnail: Color, val success: Color, val searchBackground: Color,
    val searchInk: Color, val selection: Color,
)

internal val lightDesktopPalette = DesktopPalette(
    canvas = Color(0xFFF7F6FA), panel = Color.White, ink = Color(0xFF292735), muted = Color(0xFF615E6E),
    line = Color(0xFFE0DCE7), accent = Color(0xFF6745B4), accentSurface = Color(0xFFF0EBFB),
    onAccent = Color.White, scrollbar = Color(0xFFA49FAF), scrollbarHover = Color(0xFF797284),
    thumbnail = Color(0xFFECE9F1), success = Color(0xFF176A40),
    searchBackground = Color(0xFFFFDA71), searchInk = Color(0xFF342203), selection = Color(0xFFACC5FF),
)

internal val darkDesktopPalette = DesktopPalette(
    canvas = Color(0xFF1B1922), panel = Color(0xFF25222E), ink = Color(0xFFF4F1F7), muted = Color(0xFFC5BFCE),
    line = Color(0xFF514C5D), accent = Color(0xFFC6ABFF), accentSurface = Color(0xFF3C304D),
    onAccent = Color(0xFF28173D), scrollbar = Color(0xFF81788F), scrollbarHover = Color(0xFFC9BDD8),
    thumbnail = Color(0xFF373240), success = Color(0xFF84DEAD),
    searchBackground = Color(0xFFFFD36F), searchInk = Color(0xFF2C2008), selection = Color(0xFF50456B),
)

private val LocalDesktopPalette = staticCompositionLocalOf { lightDesktopPalette }
internal val desktopPalette: DesktopPalette
    @Composable get() = LocalDesktopPalette.current

@Composable
internal fun DesktopTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) darkDesktopPalette else lightDesktopPalette
    val colors = if (dark) darkColors() else lightColors()
    MaterialTheme(colors = colors.copy(
        primary = palette.accent, primaryVariant = palette.accent, secondary = palette.accent,
        secondaryVariant = palette.accent, background = palette.canvas, surface = palette.panel,
        onPrimary = palette.onAccent, onSecondary = palette.onAccent, onBackground = palette.ink,
        onSurface = palette.ink,
    )) {
        CompositionLocalProvider(
            LocalDesktopPalette provides palette,
            LocalTextSelectionColors provides TextSelectionColors(palette.accent, palette.selection),
            content = content,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopAppearanceControls(
    appearance: DesktopAppearance,
    onPreview: (DesktopAppearance) -> Unit,
    onCommit: (DesktopAppearance) -> Unit,
) {
    val palette = desktopPalette
    val pinLabel = if (appearance.pinned) "取消置顶" else "窗口置顶"
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TooltipArea(tooltip = { AppearanceTooltip(pinLabel) }) {
            IconToggleButton(
                checked = appearance.pinned,
                onCheckedChange = { onCommit(appearance.copy(pinned = it)) },
                modifier = Modifier.size(36.dp).testTag("appearance-pin")
                    .semantics { contentDescription = pinLabel }
                    .background(if (appearance.pinned) palette.accentSurface else Color.Transparent, RoundedCornerShape(8.dp)),
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val u = size.minDimension / 20
                    val color = if (appearance.pinned) palette.accent else palette.muted
                    val shape = androidx.compose.ui.graphics.Path().apply {
                        moveTo(6 * u, 2 * u)
                        lineTo(14 * u, 2 * u)
                        lineTo(13 * u, 5 * u)
                        lineTo(13 * u, 9 * u)
                        lineTo(16 * u, 12 * u)
                        lineTo(4 * u, 12 * u)
                        lineTo(7 * u, 9 * u)
                        lineTo(7 * u, 5 * u)
                        close()
                    }
                    if (appearance.pinned) drawPath(shape, color) else drawPath(shape, color, style = Stroke(1.5f * u))
                    drawLine(color, Offset(10 * u, 12 * u), Offset(10 * u, 18 * u), 1.5f * u)
                }
            }
        }
        Box {
            TooltipArea(tooltip = { AppearanceTooltip("窗口外观") }) {
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)
                    .testTag("appearance-open").semantics { contentDescription = "窗口外观" }) {
                    Canvas(Modifier.size(18.dp)) {
                        val diameter = size.minDimension * .85f
                        val origin = Offset(center.x - diameter / 2, center.y - diameter / 2)
                        drawCircle(palette.ink, radius = diameter / 2, style = Stroke(1.5.dp.toPx()))
                        drawArc(palette.ink, 90f, 180f, true, origin, Size(diameter, diameter))
                    }
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.width(244.dp).testTag("appearance-menu")) {
                var dragPercent by remember { mutableStateOf<Int?>(null) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                    Text("窗口外观", style = MaterialTheme.typography.subtitle1)
                    listOf(false to "浅色（Light）", true to "深色（Dark）").forEach { (dark, label) ->
                        Row(Modifier.fillMaxWidth().selectable(
                            selected = appearance.dark == dark, role = Role.RadioButton,
                            onClick = { onCommit(appearance.copy(dark = dark)) },
                        ).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = appearance.dark == dark, onClick = null)
                            Text(label, Modifier.padding(start = 5.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("窗口不透明度")
                        Text("${appearance.opacityPercent}%")
                    }
                    Slider(
                        value = appearance.opacityPercent.toFloat(), valueRange = 50f..100f,
                        // DropdownMenu queries intrinsic sizes. Slider's BoxWithConstraints cannot supply them.
                        modifier = Modifier.size(width = 216.dp, height = 48.dp).testTag("appearance-opacity"),
                        onValueChange = {
                            val percent = it.roundToInt().coerceIn(50, 100)
                            dragPercent = percent
                            onPreview(appearance.copy(opacityPercent = percent))
                        },
                        onValueChangeFinished = {
                            dragPercent?.let { onCommit(appearance.copy(opacityPercent = it)) }
                            dragPercent = null
                        },
                    )
                    TextButton(onClick = { onCommit(appearance.copy(opacityPercent = 100)) }) { Text("恢复 100%") }
                    TextButton(onClick = { onCommit(DesktopAppearance()) }) { Text("恢复默认外观") }
                }
            }
        }
    }
}

@Composable
private fun AppearanceTooltip(label: String) {
    Surface(shape = RoundedCornerShape(6.dp), elevation = 4.dp) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.caption)
    }
}
