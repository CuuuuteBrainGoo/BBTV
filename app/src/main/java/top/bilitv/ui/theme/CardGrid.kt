package top.bilitv.ui.theme

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Native grid measurement receives the remaining content width, not the screen's pixel width. */
internal data class CardGridCells(val minWidth: Dp, val columns: Int = 0) : GridCells {
    init { require(minWidth.value.isFinite() && minWidth > 0.dp); require(columns in 0..9) }

    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val readable = 120.dp * fontScale.coerceAtLeast(1f)
        val cells = if (columns == 0) GridCells.Adaptive(minWidth * fontScale.coerceAtLeast(1f))
        else {
            // A manual count remains an upper bound when the window cannot fit readable cards.
            val fit = ((availableSize + spacing) / (readable.roundToPx() + spacing).coerceAtLeast(1)).coerceAtLeast(1)
            GridCells.Fixed(columns.coerceAtMost(fit))
        }
        return with(cells) { calculateCrossAxisCellSizes(availableSize, spacing) }
    }
}

internal fun BiliTheme.gridCells(poster: Boolean = false): GridCells =
    CardGridCells(cardMinWidth * if (poster) .62f else 1f, if (poster) collectionColumns else cardColumns)

/** Preload two actual rows, so a wide grid does not wait until its last eight cards. */
internal fun LazyGridLayoutInfo.nearEnd(total: Int): Boolean {
    val last = visibleItemsInfo.lastOrNull()?.index ?: return false
    val columns = (visibleItemsInfo.maxOfOrNull { it.column + 1 } ?: 1).coerceAtLeast(1)
    return last >= total - columns * 2
}
