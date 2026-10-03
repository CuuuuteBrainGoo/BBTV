package top.bilitv.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import top.bilitv.ui.theme.AppTheme

internal data class ScrollbarRange(val top: Float, val length: Float)
internal fun scrollbarRange(first: Float, visible: Int, total: Int): ScrollbarRange? {
    if (total <= 0 || visible <= 0 || !first.isFinite()) return null
    val length = (visible.toFloat() / total).coerceIn(.06f, .9f)
    return ScrollbarRange((first / total).coerceIn(0f, 1f - length), length)
}

@Composable
private fun Modifier.scrollbar(range: () -> ScrollbarRange?): Modifier {
    val theme = AppTheme.current
    if (!theme.showScrollbars) return this
    return drawWithContent {
        drawContent()
        range()?.let {
            val width = 3.dp.toPx()
            drawRect(theme.textSecondary.copy(alpha = .55f),
                Offset(size.width - width, size.height * it.top), Size(width, size.height * it.length))
        }
    }
}

@Composable
internal fun Modifier.verticalScrollbar(state: LazyGridState): Modifier = scrollbar {
    if (!state.canScrollBackward && !state.canScrollForward) null else {
        val info = state.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull()
        // ponytail: lazy-list thumb estimates by entries; mixed-height hero rows need pixel metrics for exact proportions.
        first?.let { scrollbarRange(it.index.toFloat(), info.visibleItemsInfo.size, info.totalItemsCount) }
            ?.let { if (!state.canScrollForward) it.copy(top = 1f - it.length) else if (!state.canScrollBackward) it.copy(top = 0f) else it }
    }
}

@Composable
internal fun Modifier.verticalScrollbar(state: LazyListState): Modifier = scrollbar {
    if (!state.canScrollBackward && !state.canScrollForward) null else {
        val info = state.layoutInfo
        scrollbarRange(state.firstVisibleItemIndex.toFloat(), info.visibleItemsInfo.size, info.totalItemsCount)
            ?.let { if (!state.canScrollForward) it.copy(top = 1f - it.length) else if (!state.canScrollBackward) it.copy(top = 0f) else it }
    }
}

@Composable
internal fun Modifier.scrollWithScrollbar(state: ScrollState = rememberScrollState()): Modifier =
    scrollbar {
        if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE) null else {
            val content = state.maxValue.toFloat() + state.viewportSize
            val length = (state.viewportSize / content).coerceIn(.06f, .9f)
            ScrollbarRange(state.value.toFloat() / state.maxValue * (1f - length), length)
        }
    }.verticalScroll(state)
