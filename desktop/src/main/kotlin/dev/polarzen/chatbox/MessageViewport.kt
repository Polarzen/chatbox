package dev.polarzen.chatbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ReplyTurnNavigation(val serial: Long, val entryIndex: Int)

private class ReplyTurnAnchor(val entryIndex: Int) {
    var assistantOffset by mutableStateOf<Int?>(null)
    var anchored by mutableStateOf(true)
}

private val LocalReplyTurnAnchor = compositionLocalOf<ReplyTurnAnchor?> { null }

@Composable
internal fun Modifier.replyTurnAnchor(entryIndex: Int): Modifier {
    val target = LocalReplyTurnAnchor.current ?: return this
    if (target.entryIndex != entryIndex) return this
    return onGloballyPositioned {
        // The assistant container is a direct child of the turn's column.
        target.assistantOffset = it.positionInParent().y.roundToInt()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageViewport(
    profileId: String,
    session: String,
    entryCount: Int,
    modifier: Modifier,
    navigation: SearchHitNavigation? = null,
    replyNavigation: ReplyTurnNavigation? = null,
    content: LazyListScope.() -> Unit,
) = key(profileId, session) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Appending a turn starts following the new reply. Edits inside an existing turn
    // (including late image measurements) keep the reader's current preference.
    var following by remember(entryCount) { mutableStateOf(true) }
    var viewportCenterInRoot by remember { mutableStateOf<Float?>(null) }
    val countAtClick = remember(navigation) { entryCount }
    val search = remember(navigation) { navigation?.let(::SearchHitPresentation) }
        ?.takeIf { entryCount == countAtClick && it.navigation.entryIndex in 0 until entryCount }
    val countAtReplyClick = remember(replyNavigation) { entryCount }
    val reply = remember(replyNavigation) { replyNavigation?.let { ReplyTurnAnchor(it.entryIndex) } }
        ?.takeIf { entryCount == countAtReplyClick && it.entryIndex in 0 until entryCount }
    LaunchedEffect(reply, reply?.anchored) {
        if (reply == null || !reply.anchored) return@LaunchedEffect
        following = false
        state.stopScroll(MutatePriority.UserInput)
        snapshotFlow {
            val visible = state.layoutInfo.visibleItemsInfo.any { it.index == reply.entryIndex }
            // Remeasure after a resize or a change to the content above the reply.
            Triple(visible, reply.assistantOffset, state.layoutInfo.viewportSize)
        }.collect { (visible, offset, _) ->
            if (!reply.anchored) return@collect
            if (!visible || offset != null) state.scrollToItem(reply.entryIndex, offset ?: 0)
        }
    }
    LaunchedEffect(search, search?.anchored) {
        if (search == null || !search.anchored) return@LaunchedEffect
        following = false
        try {
            state.stopScroll(MutatePriority.UserInput)
            val target = search.navigation.entryIndex
            // Keep the actual line at the attained position, including when
            // a preceding media placeholder is replaced after the highlight expires.
            var attainedOffset = 0f
            snapshotFlow {
                val center = viewportCenterInRoot
                val line = search.lineCenterInRoot
                val visible = state.layoutInfo.visibleItemsInfo.any { it.index == target }
                visible to (if (center == null || line == null) null else line - center)
            }.collect { (visible, offset) ->
                if (!search.anchored) return@collect
                if (!visible) {
                    // A turn above a separate target turn can also grow enough
                    // to dispose it. Rematerialize the target to measure its line.
                    state.scrollToItem(target)
                    return@collect
                }
                if (offset == null) return@collect
                val delta = offset - attainedOffset
                val consumed = if (abs(delta) >= 1f) state.scrollBy(delta) else 0f
                if (search.locating) attainedOffset = offset - consumed
                search.locating = false
            }
        } finally {
            search.locating = false
        }
    }
    LaunchedEffect(search, search?.locating) {
        if (search != null && !search.locating) {
            delay(1800)
            search.highlighted = false
        }
    }
    val scrollObserver = remember(entryCount, search, reply) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Direct LazyListState scrolls do not dispatch nested scroll events.
                if (available.y != 0f) {
                    search?.anchored = false
                    reply?.anchored = false
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y > 0f && state.canScrollForward) following = false
                if (consumed.y < 0f && !state.canScrollForward) following = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(entryCount, search, reply) {
        if (entryCount == 0) return@LaunchedEffect
        val lastIndex = entryCount - 1
        snapshotFlow { if (reply?.anchored == true || search?.locating == true || state.isScrollInProgress) null else state.layoutInfo to following }
            .collect { measurement ->
                if (measurement == null) return@collect
                val (layout, shouldFollow) = measurement
                if (layout.totalItemsCount != entryCount || layout.viewportEndOffset <= layout.viewportStartOffset) return@collect
                val last = layout.visibleItemsInfo.lastOrNull { it.index == lastIndex }
                val end = layout.viewportEndOffset - layout.afterContentPadding
                if (!shouldFollow) {
                    // A taller viewport or a shorter completed reply can expose the tail
                    // without another wheel event. Resume only after that tail was measured.
                    if (last != null && last.offset + last.size <= end && !state.canScrollForward) {
                        search?.anchored = false
                        following = true
                    }
                } else if (state.canScrollForward && last == null) {
                    // Reveal the last turn so the next measurement has its actual height.
                    state.requestScrollToItem(lastIndex)
                } else if (last != null && last.offset + last.size > end && state.canScrollForward) {
                    state.requestScrollToItem(lastIndex, (last.size - end).coerceAtLeast(0))
                }
            }
    }
    Box(modifier) {
        CompositionLocalProvider(LocalViewportSearch provides search, LocalReplyTurnAnchor provides reply) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().onGloballyPositioned {
                    viewportCenterInRoot = it.positionInRoot().y + it.size.height / 2f
                }
                    .nestedScroll(scrollObserver).testTag("message-region"),
                state = state,
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
        if (!following && search?.locating != true) {
            OutlinedButton(
                onClick = {
                    search?.anchored = false
                    reply?.anchored = false
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        state.stopScroll(MutatePriority.UserInput)
                        following = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("return-to-latest"),
                shape = RoundedCornerShape(16.dp),
            ) { Text("回到最新") }
        }
    }
}
