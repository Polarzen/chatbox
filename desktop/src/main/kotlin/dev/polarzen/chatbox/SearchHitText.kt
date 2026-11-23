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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

private val chatUrlPattern = Regex(
    """(?i)(?<![a-z0-9+.-])https?://(?=[\p{L}\p{N}_\[])[^\s\p{Z}\p{Cc}<>{}"'`，。！？；：、…“”‘’（）【】《》〈〉]+""",
)

internal fun chatUrlRanges(raw: String): List<TextRange> = if ("://" !in raw) emptyList() else buildList {
    for (match in chatUrlPattern.findAll(raw)) {
        var end = match.range.last + 1
        var parentheses = 0
        var brackets = 0
        for (index in match.range) {
            when (raw[index]) {
                '(' -> parentheses++
                ')' -> parentheses--
                '[' -> brackets++
                ']' -> brackets--
            }
            if (parentheses < 0 || brackets < 0) {
                end = index
                break
            }
        }
        while (end > match.range.first && raw[end - 1] in ".,!?;:") end--
        add(TextRange(match.range.first, end))
    }
}

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
    urlRanges: List<TextRange>? = null,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val palette = desktopPalette
    val ranges = urlRanges ?: remember(text) { chatUrlRanges(text) }
    val sourceStart = if (urlRanges == null) prefixLength else bodyOffset
    val styledText = remember(text, ranges, sourceStart, prefixLength, palette.accent) {
        if (ranges.isEmpty()) AnnotatedString(text) else buildAnnotatedString {
            append(text)
            val sourceEnd = sourceStart + text.length - prefixLength
            val style = SpanStyle(color = palette.accent, textDecoration = TextDecoration.Underline)
            val boundary = ranges.binarySearchBy(sourceStart) { it.end }
            val firstUrl = if (boundary < 0) -boundary - 1 else boundary + 1
            for (index in firstUrl until ranges.size) {
                val url = ranges[index]
                if (url.start >= sourceEnd) break
                addStyle(style, prefixLength + maxOf(url.start, sourceStart) - sourceStart,
                    prefixLength + minOf(url.end, sourceEnd) - sourceStart)
            }
        }
    }
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
        val ink = remember(palette.searchInk) { Paint().apply { colorFilter = ColorFilter.tint(palette.searchInk) } }
        decorated = decorated.drawWithContent {
            val measured = layout
            if (presentation.highlighted && measured != null && measured.layoutInput.text.text == text) {
                val path = measured.getPathForRange(first, end)
                drawPath(path, palette.searchBackground)
                drawContent()
                // Tint the glyphs including explicit URL colors while preserving
                // the selectable text layout during the transient highlight.
                clipPath(path) {
                    drawContext.canvas.saveLayer(path.getBounds(), ink)
                    drawText(measured)
                    drawContext.canvas.restore()
                }
            } else drawContent()
        }
    }
    // Keep the same selectable Text across location, highlight expiry and appends.
    Text(styledText, modifier = decorated, color = color, fontSize = fontSize, onTextLayout = {
        layout = it
        onTextLayout(it)
    })
}
