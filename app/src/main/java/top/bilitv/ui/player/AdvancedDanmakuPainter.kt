package top.bilitv.ui.player

import android.graphics.Camera
import android.graphics.Matrix
import android.graphics.PathMeasure
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.core.graphics.PathParser
import top.bilitv.data.danmaku.AdvancedDanmaku
import top.bilitv.data.danmaku.DanmakuItem
import top.bilitv.data.danmaku.advancedCoordinate
import top.bilitv.data.danmaku.moveFraction

/** 字形／路径／矩阵在入场前准备，帧中只按播放时钟修改数值并绘制。 */
internal class AdvancedLayout(val item: DanmakuItem, val layout: TextLayoutResult) {
    val spec: AdvancedDanmaku = requireNotNull(item.advanced)
    val path = if (spec.path.isBlank()) null else runCatching {
        PathParser.createPathFromPathData(spec.path)?.let { PathMeasure(it, false) }
    }.getOrNull()
    val point = FloatArray(2)
    val camera = Camera()
    val matrix = Matrix()
}

internal fun DrawScope.drawAdvanced(record: AdvancedLayout, positionMs: Long, alpha: Float, outline: Float, minAlpha: Int) {
    val a = record.spec
    val age = positionMs - record.item.timeMs
    if (age < 0L || age > a.durationMs) return
    val moved = a.moveFraction(age)
    val w = size.width.toInt(); val h = size.height.toInt()
    var x = advancedCoordinate(a.x, w, 682f) + (advancedCoordinate(a.endX, w, 682f) - advancedCoordinate(a.x, w, 682f)) * moved
    var y = advancedCoordinate(a.y, h, 438f) + (advancedCoordinate(a.endY, h, 438f) - advancedCoordinate(a.y, h, 438f)) * moved
    record.path?.let { path ->
        if (path.getPosTan(path.length * moved, record.point, null)) {
            x = record.point[0] * w / 682f; y = record.point[1] * h / 438f
        }
    }
    val opacity = (a.alphaFrom + (a.alphaTo - a.alphaFrom) * (age.toFloat() / a.durationMs)) * alpha
    if (opacity <= 0f) return
    record.camera.save(); record.camera.rotateY(a.rotateY); record.camera.getMatrix(record.matrix); record.camera.restore()
    withTransform({ translate(x, y); rotate(a.rotateZ, Offset.Zero) }) {
        drawContext.canvas.nativeCanvas.concat(record.matrix)
        if (a.outline) drawText(record.layout, color = Color.Black.copy(alpha = maxOf(opacity * .75f, minAlpha / 255f)),
            drawStyle = Stroke(width = outline * density))
        drawText(record.layout, color = record.layout.layoutInput.style.color.copy(alpha = opacity.coerceIn(0f, 1f)), drawStyle = Fill)
    }
}
