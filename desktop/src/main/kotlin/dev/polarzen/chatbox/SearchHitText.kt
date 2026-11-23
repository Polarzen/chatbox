package dev.polarzen.chatbox

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.TextUnit

internal data class SearchHitNavigation(
    val requestId: Long,
    val entryIndex: Int,
    val isAnswer: Boolean,
    val start: Int,
    val endExclusive: Int,
)

internal class SearchHitPresentation(val navigation: SearchHitNavigation) {
    var lineCenterInRoot by mutableStateOf<Float?>(null)
    var anchored by mutableStateOf(true)
    var locating by mutableStateOf(true)
    var highlighted by mutableStateOf(true)
}

internal val LocalViewportSearch = compositionLocalOf<SearchHitPresentation?> { null }
private val LocalEntrySearch = compositionLocalOf<SearchHitPresentation?> { null }

@Composable
internal fun SearchHitEntry(index: Int, content: @Composable () -> Unit) {
    val search = LocalViewportSearch.current?.takeIf { it.navigation.entryIndex == index }
    CompositionLocalProvider(LocalEntrySearch provides search, content = content)
}

// bodyOffset is the segment's start in the stored question/answer. prefixLength
// counts any display-only label before that segment in text.
@Composable
internal fun SearchHitText(
    text: String,
    isAnswer: Boolean,
    bodyOffset: Int = 0,
    prefixLength: Int = 0,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val presentation = LocalEntrySearch.current
    val hit = presentation?.navigation?.takeIf { it.isAnswer == isAnswer }
    val bodyEnd = bodyOffset + text.length - prefixLength
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var decorated = modifier
    if (hit != null && hit.start < bodyEnd && hit.endExclusive > bodyOffset) {
        val first = maxOf(hit.start, bodyOffset) - bodyOffset + prefixLength
        val end = minOf(hit.endExclusive, bodyEnd) - bodyOffset + prefixLength
        if (hit.start >= bodyOffset && presentation.anchored) {
            var topInRoot by remember { mutableStateOf<Float?>(null) }
            decorated = decorated.onGloballyPositioned { topInRoot = it.positionInRoot().y }
            val measured = layout
            val top = topInRoot
            if (measured != null && top != null && measured.layoutInput.text.text == text) {
                DisposableEffect(presentation, measured, top, first) {
                    val lineIndex = measured.getLineForOffset(first)
                    val center = top + (measured.getLineTop(lineIndex) + measured.getLineBottom(lineIndex)) / 2f
                    presentation.lineCenterInRoot = center
                    onDispose { if (presentation.lineCenterInRoot == center) presentation.lineCenterInRoot = null }
                }
            }
        }
        val palette = desktopPalette
        decorated = decorated.drawWithContent {
            val measured = layout
            if (presentation.highlighted && measured != null && measured.layoutInput.text.text == text) {
                val path = measured.getPathForRange(first, end)
                drawPath(path, palette.searchBackground)
                drawContent()
                clipPath(path) { drawText(measured, color = palette.searchInk) }
            } else drawContent()
        }
    }
    // Keep the same selectable Text across location, highlight expiry and appends.
    Text(text, modifier = decorated, color = color, fontSize = fontSize, onTextLayout = {
        layout = it
        onTextLayout(it)
    })
}
