package dev.polarzen.chatbox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class DesktopChatIcon { Chats, Search, Settings, More, Add, Pin, Close }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatIconButton(
    icon: DesktopChatIcon, label: String, tag: String, onClick: () -> Unit,
    selected: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier,
) {
    val palette = desktopPalette
    TooltipArea(tooltip = {
        Surface(shape = RoundedCornerShape(6.dp), elevation = 4.dp) {
            Text(label, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 12.sp)
        }
    }) {
        Box(
            modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(if (selected) palette.accentSurface else Color.Transparent)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label }.testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            val color = (if (selected) palette.accent else palette.muted).copy(alpha = if (enabled) 1f else .45f)
            Canvas(Modifier.size(20.dp)) {
                val u = size.minDimension / 24f
                val stroke = Stroke(1.65f * u, cap = StrokeCap.Round)
                fun point(x: Int, y: Int) = Offset(x * u, y * u)
                fun line(x: Int, y: Int, endX: Int, endY: Int) =
                    drawLine(color, point(x,y), point(endX,endY), stroke.width, StrokeCap.Round)
                when (icon) {
                    DesktopChatIcon.Chats -> {
                        val path = Path().apply {
                            moveTo(5*u, 4*u); lineTo(19*u, 4*u); quadraticTo(21*u, 4*u, 21*u, 6*u)
                            lineTo(21*u, 15*u); quadraticTo(21*u, 17*u, 19*u, 17*u)
                            lineTo(10*u, 17*u); lineTo(4*u, 21*u); lineTo(4*u, 17*u)
                            quadraticTo(3*u, 17*u, 3*u, 15*u); lineTo(3*u, 6*u)
                            quadraticTo(3*u, 4*u, 5*u, 4*u); close()
                        }
                        drawPath(path, color, style = stroke)
                        line(7, 9, 17, 9); line(7, 12, 13, 12)
                    }
                    DesktopChatIcon.Search -> {
                        drawCircle(color, 6.5f*u, point(10,10), style = stroke); line(15,15,21,21)
                    }
                    DesktopChatIcon.Settings -> {
                        line(3,7,7,7); line(12,7,21,7); line(3,17,12,17); line(17,17,21,17)
                        drawCircle(color, 2.5f*u, point(9,7), style = stroke)
                        drawCircle(color, 2.5f*u, point(15,17), style = stroke)
                    }
                    DesktopChatIcon.More -> for (x in listOf(5,12,19)) drawCircle(color, 1.5f*u, point(x,12))
                    DesktopChatIcon.Add -> { line(12,4,12,20); line(4,12,20,12) }
                    DesktopChatIcon.Pin -> {
                        line(8,4,16,4); line(9,5,9,11); line(15,5,15,11); line(9,11,6,15)
                        line(15,11,18,15); line(6,15,18,15); line(12,16,12,22)
                    }
                    DesktopChatIcon.Close -> { line(6,6,18,18); line(18,6,6,18) }
                }
            }
        }
    }
}
