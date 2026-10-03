package top.bilitv

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.settings.CardSize
import top.bilitv.ui.settings.dragScrollStep
import top.bilitv.ui.theme.CardGridCells

class CardGridTest {
    private fun sizes(width: Int, size: CardSize = CardSize.STANDARD, columns: Int = 0,
                      density: Float = 1f, fontScale: Float = 1f): List<Int> {
        val grid: GridCells = CardGridCells(204.dp * size.scale, columns)
        val units = Density(density, fontScale)
        return with(grid) {
            with(units) { calculateCrossAxisCellSizes(width.dp.roundToPx(), 20.dp.roundToPx()) }
        }
    }

    @Test fun `auto keeps standard cards and fills wide screens across densities`() {
        for (density in listOf(1f, 2f, 3.5f)) {
            for ((width, count) in listOf(880 to 4, 1340 to 6, 2000 to 9)) {
                val cells = sizes(width, density = density)
                assertEquals(count, cells.size)
                val units = Density(density)
                assertEquals(with(units) { width.dp.roundToPx() },
                    cells.sum() + (cells.size - 1) * with(units) { 20.dp.roundToPx() })
                assertTrue(cells.max() - cells.min() <= 1)
            }
        }
    }

    @Test fun `presets and manual override remain readable in narrow windows`() {
        assertEquals(5, sizes(880, CardSize.COMPACT).size)
        assertEquals(3, sizes(880, CardSize.LARGE).size)
        assertEquals(2, sizes(880, CardSize.EXTRA_LARGE).size)
        assertEquals(6, sizes(880, CardSize.EXTRA_LARGE, columns = 6).size)
        assertEquals(3, sizes(400, columns = 9).size)
        assertEquals(1, sizes(100).size)
        assertEquals(2, sizes(880, fontScale = 1.5f).size)
        assertEquals(4, sizes(880, columns = 9, fontScale = 1.5f).size)
    }

    @Test fun `old or malformed settings fall back to automatic standard`() {
        assertEquals(CardSize.STANDARD, CardSize.of("unknown"))
        for (invalid in listOf(-1, 1, 10, Int.MAX_VALUE)) assertEquals(0, CardSize.columns(invalid))
        assertEquals(9, CardSize.columns(9))
    }

    @Test fun `drag edge scroll is directional bounded and idle away from edges`() {
        assertEquals(0f, dragScrollStep(250f, 100f, 500f, 48f, 12f), 0f)
        assertEquals(-12f, dragScrollStep(80f, 100f, 500f, 48f, 12f), 0f)
        assertEquals(12f, dragScrollStep(520f, 100f, 500f, 48f, 12f), 0f)
        assertEquals(-6f, dragScrollStep(124f, 100f, 500f, 48f, 12f), 0f)
        assertEquals(0f, dragScrollStep(0f, 0f, 0f, 48f, 12f), 0f)
    }
}
