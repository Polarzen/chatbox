package dev.polarzen.chatbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageViewport(
    profileId: String,
    session: String,
    entryCount: Int,
    modifier: Modifier,
    content: LazyListScope.() -> Unit,
) = key(profileId, session) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Appending a turn starts following the new reply. Edits inside an existing turn
    // (including late image measurements) keep the reader's current preference.
    var following by remember(entryCount) { mutableStateOf(true) }
    val scrollObserver = remember(entryCount) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y > 0f && state.canScrollForward) following = false
                if (consumed.y < 0f && !state.canScrollForward) following = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(entryCount) {
        if (entryCount == 0) return@LaunchedEffect
        val lastIndex = entryCount - 1
        snapshotFlow { if (state.isScrollInProgress) null else state.layoutInfo to following }
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollObserver).testTag("message-region"),
            state = state,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        if (!following) {
            OutlinedButton(
                onClick = {
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
