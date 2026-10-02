package top.bilitv.data.danmaku

import org.json.JSONArray

/** mode 7 的动画文字数据；解析 JSON 不执行弹幕中的代码。 */
data class AdvancedDanmaku(
    val text: String, val x: Float, val y: Float, val endX: Float, val endY: Float,
    val alphaFrom: Float, val alphaTo: Float, val durationMs: Long,
    val moveMs: Long, val delayMs: Long, val rotateZ: Float, val rotateY: Float,
    val outline: Boolean, val path: String,
)

fun parseAdvancedDanmaku(raw: String): AdvancedDanmaku? = runCatching {
    require(raw.length <= 16_384)
    val a = JSONArray(raw)
    require(a.length() >= 5)
    fun number(i: Int, fallback: Float = 0f): Float = a.optDouble(i, fallback.toDouble()).toFloat().also { require(it.isFinite()) }
    val alpha = a.optString(2, "1-1").split('-').map { it.toFloat().coerceIn(0f, 1f) }
    require(alpha.all { it.isFinite() })
    val text = a.getString(4).take(2048)
    require(text.isNotBlank())
    val x = number(0); val y = number(1)
    AdvancedDanmaku(text, x, y, number(7, x), number(8, y), alpha.first(), alpha.last(),
        (number(3, 4f).coerceIn(.1f, 60f) * 1000).toLong(),
        number(9).coerceIn(0f, 60_000f).toLong(), number(10).coerceIn(0f, 60_000f).toLong(),
        number(5).coerceIn(-3600f, 3600f), number(6).coerceIn(-3600f, 3600f),
        a.optString(11, "true") != "false", a.optString(14).take(4096))
}.getOrNull()

/** 0..1 为比例坐标，其他坐标使用协议的 682×438 基准。 */
fun advancedCoordinate(value: Float, extent: Int, reference: Float): Float =
    if (value in 0f..1f) value * extent else value * extent / reference

fun AdvancedDanmaku.moveFraction(ageMs: Long): Float =
    if (moveMs <= 0L) 0f else ((ageMs - delayMs).toFloat() / moveMs).coerceIn(0f, 1f)
