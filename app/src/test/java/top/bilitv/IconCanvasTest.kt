package top.bilitv

import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.ui.components.RailIcons

class IconCanvasTest {
    @Test fun everyIconHasFinitePositiveCanvasBeforePlayerDraws() {
        // Read the monitoring icon first; every exposed icon must have a valid canvas.
        val first = listOf(RailIcons.Retry)
        val all = RailIcons::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == ImageVector::class.java }
            .map { it.invoke(RailIcons) as ImageVector }
        assertTrue(all.size >= first.size)
        for (icon in first + all) {
            assertTrue(icon.name, icon.defaultWidth.value.isFinite() && icon.defaultWidth.value > 0f)
            assertTrue(icon.name, icon.defaultHeight.value.isFinite() && icon.defaultHeight.value > 0f)
            assertTrue(icon.name, icon.viewportWidth.isFinite() && icon.viewportWidth > 0f)
            assertTrue(icon.name, icon.viewportHeight.isFinite() && icon.viewportHeight > 0f)
        }
    }
}
