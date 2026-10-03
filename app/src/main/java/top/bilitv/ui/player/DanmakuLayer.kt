package top.bilitv.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import top.bilitv.data.danmaku.DanmakuItem
import top.bilitv.data.danmaku.window
import top.bilitv.util.AppLog
import top.bilitv.data.settings.PlaybackPerformance
import kotlin.math.max
import kotlin.math.min

/**
 * 弹幕渲染层。
 *
 * 设计取舍：
 *
 * 1. **自绘 Canvas，不用现成的弹幕库**。B 站弹幕只是"一堆文字在跑"，
 *    拉一个库进来（几百 KB + 自己的一套生命周期）得不偿失。
 *
 * 2. **位置由播放进度推导，而不是维护一套动画时钟**。
 *    每条弹幕的横坐标是 `当前播放位置` 的纯函数，所以：
 *    暂停时弹幕自动停住、拖动进度条后弹幕自动跳到正确位置 —— 不需要额外同步代码。
 *
 * 3. **帧回调只在播放中跑**。暂停时直接停掉 `withFrameNanos` 循环，
 *    电视盒子常年开机，不能白白烧 CPU。
 *
 * 4. **轨道冲突靠"上一条是否已让开"判断**，抢不到轨道的弹幕直接丢弃。
 *    这是弹幕播放器的通行做法：宁可少显示，也不能糊成一团。
 *
 * 5. **去重合并**（`merge`）。复读弹幕（"前方高能"×20）是弹幕区最常见的一种刷屏，
 *    照直全画出来，几秒钟就把画面糊死了。开启后同一句话在屏幕上**只占一条**，
 *    重复的并进去计数显示成 `前方高能 ×20` —— 既没糊屏，又保住了"大家都在说"
 *    这个信息（这正是复读弹幕本身想表达的东西）。
 */
@Composable
fun DanmakuLayer(
    items: List<DanmakuItem>,
    position: () -> Long,
    playing: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 0.9f,
    scale: Float = 1f,
    maxLines: Int = 0,
    scrollDurationMs: Long = 8_000L,
    merge: Boolean = true,
    areaFifths: Int = 5,
    overlap: Boolean = false,
    outlineWidth: Float = 2f,
    outlineMinAlpha: Int = 180,
    trackHeight: Float = 1.4f,
    performance: PlaybackPerformance = PlaybackPerformance.BALANCED,
) {
    if (!enabled) return

    val textMeasurer = rememberTextMeasurer(cacheSize = performance.textCache)
    val engine = remember { DanmakuEngine<ComposeLayout>() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    val lineHeightPx = with(LocalDensity.current) {
        (BASE_FONT_SP * scale * trackHeight).sp.toPx().toInt()
    }

    // 帧循环里要拿到最新值，但不能因为 items 变化就重启循环
    val latestItems by rememberUpdatedState(items)
    val latestPosition by rememberUpdatedState(position)

    val styleOf: (DanmakuItem) -> TextStyle = remember(scale) {
        { item ->
            TextStyle(
                color = Color(0xFF000000L or item.color.toLong()),
                fontSize = (item.fontsize * scale).sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    val posState = remember { mutableLongStateOf(0L) }
    val advanced = remember(items) { items.filter { it.advanced != null } }
    val advancedLayouts = remember(scale, performance) {
        object : LinkedHashMap<DanmakuItem, AdvancedLayout>(performance.danmakuLimit, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DanmakuItem, AdvancedLayout>): Boolean = size > performance.danmakuLimit
        }
    }
    LaunchedEffect(scale, maxLines, scrollDurationMs, merge, areaFifths, trackHeight, overlap) { engine.clear() }
    LaunchedEffect(items) {
        val allowed = items.toHashSet()
        engine.active.removeAll { it.item !in allowed }
    }

    LaunchedEffect(
        enabled, playing, viewport.width, viewport.height, lineHeightPx, maxLines, scale,
        scrollDurationMs, merge, areaFifths, trackHeight, overlap, performance,
    ) {
        if (!enabled || viewport.width <= 0 || lineHeightPx <= 0) return@LaunchedEffect

        // text 是"实际要画的字"—— 合并计数后会变成 `内容 ×N`，所以它和 item 分开传
        val measure: (DanmakuItem, String) -> ComposeLayout = { item, text ->
            ComposeLayout(
                textMeasurer.measure(
                    text = text,
                    style = styleOf(item),
                    softWrap = false,
                    maxLines = 1,
                )
            )
        }

        /*
         * ★ 平滑时钟（一阶锁相）。
         *
         * 弹幕是 60fps 的连续动画，但播放器给的 `currentPosition` **不是每帧都新**：
         * 它跟着音频时钟走，要十几毫秒才真正前进一次。直接拿它画，
         * 看起来仍然一顿一顿 —— 这是「弹幕卡顿」的第二个来源（第一个是 250ms 轮询）。
         *
         * 做法：自己维护一条位置线 `anchorMs`，每帧按**帧间隔**往前推，
         * 然后只修掉与播放器误差的 1/8。误差单调收敛，**全程不跳变**。
         *
         * 为什么不"发现偏差就直接对齐"：那确实更准，但每对齐一次就是一次可见的抖，
         * 而且会周期性重复（播放器每次前进，偏差就重新出现）。
         * 只有误差大到 0.5 秒（用户拖动进度条 / 换清晰度）才值得硬对齐 ——
         * 那种场景下画面本来就在跳，对齐反而是对的。
         */
        var anchorMs = 0L
        var anchorNanos = 0L
        var anchored = false

        fun smooth(raw: Long, nowNanos: Long): Long {
            if (!playing || !anchored) {
                anchorMs = raw
                anchorNanos = nowNanos
                anchored = true
                return raw
            }
            // 帧间隔：上限 100ms，防止应用被切到后台再回来时位置暴冲
            val elapsed = ((nowNanos - anchorNanos) / 1_000_000L).coerceIn(0L, 100L)
            anchorNanos = nowNanos
            anchorMs += elapsed

            val drift = raw - anchorMs
            anchorMs += if (drift > RESYNC_MS || drift < -RESYNC_MS) drift else drift / 8
            return anchorMs
        }

        var mergeLogAt = 0L
        var loggedMerged = 0

        fun tick(nowNanos: Long) {
            val p = smooth(latestPosition(), nowNanos)
            posState.value = p
            engine.update(
                posMs = p,
                raw = latestItems,
                viewW = viewport.width,
                viewH = viewport.height * areaFifths.coerceIn(1, 5) / 5,
                lineH = lineHeightPx,
                maxLines = maxLines,
                scrollDurationMs = scrollDurationMs,
                merge = merge,
                overlap = overlap,
                maxActive = performance.danmakuLimit,
                measure = measure,
            )
            // 3 秒最多打一条：这条日志是踩坑换来的 —— 靠肉眼看画面分不清
            // "两条一样的字"是没合并、还是其中一条压根不是弹幕
            if (nowNanos - mergeLogAt > MERGE_LOG_INTERVAL_NS) {
                mergeLogAt = nowNanos
                if (engine.mergedCount != loggedMerged) {
                    loggedMerged = engine.mergedCount
                    AppLog.i(
                        "Danmaku",
                        "去重合并累计 ${engine.mergedCount} 条，屏上 ${engine.active.size} 条",
                    )
                }
            }
        }

        if (!playing) {
            tick(System.nanoTime())
            return@LaunchedEffect
        }
        var drawnAt = 0L
        while (true) {
            withFrameNanos {
                if (performance.drawFrame(drawnAt, it)) { tick(it); drawnAt = it }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
    ) {
        val p = posState.value
        val w = viewport.width.toFloat()
        if (w <= 0f) return@Canvas

        for (a in engine.active) {
            val x = danmakuX(
                fixed = a.fixed,
                startMs = a.startMs,
                speedPxPerMs = a.speedPxPerMs,
                widthPx = a.widthPx,
                posMs = p,
                viewW = viewport.width,
                reverse = a.item.isReverse,
            )
            val y = danmakuLaneY(a.item, a.lane, viewport.height * areaFifths.coerceIn(1, 5) / 5, lineHeightPx,
                a.layout.heightPx.takeIf { it > 0 } ?: lineHeightPx).toFloat()

            val layout = a.layout.result
            val base = layout.layoutInput.style

            // 排版结果在入场时就测好了（见 measure），这里只换颜色重画，不做二次测量 ——
            // 180 条弹幕 × 每帧 2 次测量，在低端电视 SoC 上是实打实的开销
            drawText(
                layout,
                color = Color.Black.copy(alpha = maxOf(alpha * 0.75f, outlineMinAlpha.coerceIn(0, 255) / 255f)),
                topLeft = Offset(x, y),
                drawStyle = Stroke(width = outlineWidth * density),
            )
            drawText(
                layout,
                color = base.color.copy(alpha = alpha),
                topLeft = Offset(x, y),
                drawStyle = Fill,
            )
        }
        // ponytail: 超密集高级弹幕最多检查最近 180 个候选，缓存同样有界；不预排版全片。
        val candidates = advanced.window((p-60_000L).coerceAtLeast(0L), p)
        for (i in maxOf(0, candidates.size-performance.danmakuLimit) until candidates.size) {
            val item = candidates[i]
            if (p-item.timeMs > item.advanced!!.durationMs) continue
            val record = advancedLayouts.getOrPut(item) {
                AdvancedLayout(item, textMeasurer.measure(item.advanced!!.text, style = styleOf(item), softWrap = false, maxLines = 1))
            }
            drawAdvanced(record, p, alpha, outlineWidth, outlineMinAlpha)
        }
    }
}

// ------------------------------------------------------------------ 引擎

private const val BASE_FONT_SP = 25f
private const val FIXED_DURATION_MS = 4_000L
private const val FIXED_LANES = 4
private const val LANE_GAP_PX = 16
private const val MAX_ACTIVE = 180

/** 平滑时钟与播放器位置差到这个量（毫秒）就硬对齐（用户拖动进度条 / 换清晰度） */
private const val RESYNC_MS = 500L

/** 去重合并的诊断日志最短间隔（纳秒）。弹幕密的时候一秒能并几十条，不节流会把日志冲垮 */
private const val MERGE_LOG_INTERVAL_NS = 3_000_000_000L

/**
 * 引擎眼里的"排版结果"。
 *
 * 只暴露引擎真正用到的那一件事：**宽度**（算横向位置和轨道避让）。
 *
 * 为什么要抽这一层：引擎的合并/轨道逻辑是这个文件里最容易出错、
 * 也最值得单测的部分（对照 `PositionClock` 的教训 —— 那个洞是测试逼出来的），
 * 但它原来直接吃 Compose 的 `TextLayoutResult`，JVM 单测里根本造不出来。
 * 抽出这个接口之后，测试可以用一个只有宽度的假实现直接驱动引擎。
 */
internal interface DanmakuLayout {
    /** 文本宽度（px） */
    val widthPx: Int
    val heightPx: Int get() = 0
}

/** Compose 侧的适配：把 [TextLayoutResult] 接进引擎 */
private class ComposeLayout(val result: TextLayoutResult) : DanmakuLayout {
    override val widthPx: Int get() = result.size.width
    override val heightPx: Int get() = result.size.height
}

internal class ActiveDanmaku<T : DanmakuLayout>(
    val item: DanmakuItem,
    val lane: Int,
    /** 顶部/底部固定弹幕（不横向移动） */
    val fixed: Boolean,
    /** 入场时就排好版，绘制阶段直接复用，避免每帧重新测量 */
    layout: T,
    val startMs: Long,
    /**
     * 横向速度（px/ms）。**恒速播放就是靠它**。
     *
     * 固定弹幕为 0 —— 它根本不移动。
     */
    val speedPxPerMs: Float,
    /** 入场那一刻的视口宽度。用来算"要走多远才算完全离开屏幕" */
    val viewW: Int,
) {
    /**
     * [layout] 会在合并计数时**被替换**（`内容` → `内容 ×12`），所以是 `var`。
     *
     * 改它不需要额外通知：绘制循环每帧都在读 `posState`，那一读就把重绘
     * 挂在了这个 State 上，下一帧自然会用新的排版结果。
     */
    var layout: T = layout

    /** 合并进来的条数。1 = 就它自己 */
    var count: Int = 1
        private set

    val widthPx: Int get() = layout.widthPx

    /**
     * 存活时长 = 要走的总路程 ÷ 速度。
     *
     * ★ 关键：**它不进位置公式**。位置只由 [speedPxPerMs] 和 [startMs] 决定
     * （见 [danmakuX]），所以合并计数把文本变宽之后，这一项会跟着变长，
     * 而屏幕上那一条**不会跳**。反过来，如果把位置写成"按进度百分比"，
     * 宽度的任何变化都会让弹幕瞬间平移一段 —— 那种抖比不同步还难看。
     */
    val durationMs: Long
        get() = if (fixed || speedPxPerMs <= 0f) {
            FIXED_DURATION_MS
        } else {
            ((viewW + widthPx) / speedPxPerMs).toLong()
        }

    /**
     * 又来了一条同内容的 → 计数 +1 并重排。
     *
     * **只加计数，不动 [startMs]**：否则屏幕上那条会一直"续命"，
     * 一段复读密集的视频里它就永不消失。
     */
    fun bump(measure: (DanmakuItem, String) -> T) {
        count++
        layout = measure(item, mergedLabel(item.content, count))
    }
}

/**
 * 一条弹幕在给定播放位置时的**左边缘 x 坐标**。
 *
 * ## ★ 为什么这个公式里没有"宽度"
 *
 * 移动量 = `(posMs - startMs) × speed`，只跟时间和速度有关。
 * 弹幕自己有多宽，只影响**它什么时候完全走出屏幕**（见 [ActiveDanmaku.durationMs]），
 * 不影响它在任一时刻的位置。
 *
 * 这就是"恒速"的定义，也是 2026-09-29 那个"速度不均匀"bug 的正解：
 * 原来每条弹幕都固定 8 秒走完全程，而路程是「屏宽 + 自身宽度」——
 * 于是**长弹幕为了在同样时间里走更远的路，被迫跑得更快**。
 * 屏幕上同时看几条，速度明显不齐，越长越像被拽着跑。
 *
 * ## 固定弹幕（顶 / 底）
 *
 * 居中且纹丝不动。原来它也被套进了横移公式，结果是顶部的弹幕
 * 自己从右往左飘 —— 这跟"固定在顶部"是矛盾的，只是不盯着看不容易发现。
 *
 * ## 为什么要抽成独立的纯函数
 *
 * Canvas 绘制和轨道避让**共用同一份**位置公式。
 * 它们原来是各写一遍的（一模一样的五行），改一处漏一处只是时间问题。
 * 抽出来之后还能直接被 JVM 单测驱动 —— 位置公式是这次修改的核心，
 * 不能只靠肉眼看。
 */
internal fun danmakuX(
    fixed: Boolean,
    startMs: Long,
    speedPxPerMs: Float,
    widthPx: Int,
    posMs: Long,
    viewW: Int,
    reverse: Boolean,
): Float {
    if (fixed) return (viewW - widthPx) / 2f
    val travelled = (posMs - startMs).toFloat() * speedPxPerMs
    return if (reverse) -widthPx + travelled else viewW - travelled
}

/**
 * 合并后的显示文本。
 *
 * 用 `×` 而不是 `x`：和 B 站自己的观感一致，且在等宽/比例字体下都不会被误读成字母。
 */
internal fun mergedLabel(content: String, count: Int): String =
    if (count > 1) "$content ×$count" else content

internal fun danmakuLaneY(item: DanmakuItem, lane: Int, viewH: Int, lineH: Int, height: Int = lineH): Int =
    if (item.isBottom) viewH - height - lane * lineH else lane * lineH

/**
 * 轨道调度。
 *
 * 纯状态机，不碰 Compose 的组合逻辑，只有 [active] 是可观察状态；
 * 绘制阶段读它，因此每帧只会触发**重绘**，不会触发重组。
 */
internal class DanmakuEngine<T : DanmakuLayout> {

    val active: SnapshotStateList<ActiveDanmaku<T>> = mutableStateListOf()
    private var lastPos = -1L

    /**
     * 累计"被并进去"的条数（不含每条首次入场的那一条）。
     *
     * 存在的理由很实际：**光看画面判断不出合并到底有没有在干活。**
     * 屏幕上出现两条一样的字，可能是合并没有生效，也可能是其中一条根本不是弹幕
     * （视频自带的字幕 / 画面里的文字）。有个计数就能直接对比，不用猜。
     */
    var mergedCount = 0
        private set

    fun clear() {
        active.clear()
        lastPos = -1L
        mergedCount = 0
    }

    fun update(
        posMs: Long,
        raw: List<DanmakuItem>,
        viewW: Int,
        viewH: Int,
        lineH: Int,
        maxLines: Int,
        scrollDurationMs: Long,
        merge: Boolean,
        overlap: Boolean = false,
        measure: (DanmakuItem, String) -> T,
        maxActive: Int = MAX_ACTIVE,
    ) {
        if (viewW <= 0 || viewH <= 0 || lineH <= 0) return

        // 进度突变（用户拖动、换清晰度重载）→ 清空重排，避免弹幕糊满屏
        if (lastPos >= 0 && (posMs < lastPos - 300 || posMs > lastPos + 2_500)) clear()
        val from = if (lastPos < 0) posMs else lastPos
        lastPos = posMs

        active.removeAll { posMs - it.startMs > it.durationMs }
        val limit = maxActive.coerceIn(1, MAX_ACTIVE)
        if (active.size >= limit) return

        val lineCount = max(1, viewH / lineH)
        val laneLimit = if (maxLines > 0) min(maxLines, lineCount) else lineCount
        val fresh = raw.window(from + 1, posMs)
        if (fresh.isEmpty()) return

        for (item in fresh) {
            if (active.size >= limit) break
            if (!item.isRenderable) continue
            if (item.interaction && active.any { it.item.interaction }) continue

            /*
             * 去重合并。
             *
             * 判断条件是"**同内容的那条还在屏幕上**"，而不是一个固定时间窗：
             * 这样窗口长度天然等于弹幕自己的存活时间（滚动弹幕 = scrollDurationMs，
             * 固定弹幕 = 4s），不需要额外维护一张"内容 → 上次出现时间"的表，
             * 也就没有清理和内存增长的问题。复读弹幕的特征正是"挤在同一段时间里"。
             */
            if (merge) {
                val live = active.firstOrNull { it.item.interaction == item.interaction && it.item.content == item.content }
                if (live != null) {
                    live.bump(measure)
                    mergedCount++
                    continue
                }
            }

            val fixed = !item.isMoving
            val layout = measure(item, item.content)
            val height = layout.heightPx.takeIf { it > 0 } ?: lineH
            val lanes = if (fixed) min(FIXED_LANES, laneLimit) else laneLimit
            val lane = if (overlap) {
                val fitting = (0 until lanes).filter {
                    val y = danmakuLaneY(item, it, viewH, lineH, height)
                    y >= 0 && y + height <= viewH
                }
                if (fitting.isEmpty()) continue
                fitting[active.size % fitting.size]
            } else (0 until lanes).firstOrNull {
                laneFree(it, item, posMs, viewW, viewH, lineH, height)
            } ?: continue // 抢不到轨道就丢弃：宁可不显示，也不能叠成一坨

            active.add(
                ActiveDanmaku(
                    item = item,
                    lane = lane,
                    fixed = fixed,
                    layout = layout,
                    startMs = item.timeMs,
                    // ★ 恒速：一条弹幕每秒走多少像素，只由「屏幕宽度 ÷ 用户设的时长」决定，
                    // 跟它自己有多宽无关。它自己的存活时长由速度反推（宽的多活一会儿）。
                    speedPxPerMs = if (fixed) 0f else viewW.toFloat() / scrollDurationMs.coerceAtLeast(1L),
                    viewW = viewW,
                )
            )
        }
    }

    private fun laneFree(lane: Int, item: DanmakuItem, posMs: Long, viewW: Int, viewH: Int, lineH: Int, height: Int): Boolean {
        val y = danmakuLaneY(item, lane, viewH, lineH, height)
        if (y < 0 || y + height > viewH) return false
        return active.all { prev ->
            val otherHeight = prev.layout.heightPx.takeIf { it > 0 } ?: lineH
            val otherY = danmakuLaneY(prev.item, prev.lane, viewH, lineH, otherHeight)
            if (y >= otherY + otherHeight || otherY >= y + height) return@all true
            // 固定字／对向横移共用同一垂直轨道时保留空位，防止随后迎面撞上。
            if (!item.isMoving || prev.fixed || item.isReverse != prev.item.isReverse) return@all false
            val x = danmakuX(
            fixed = prev.fixed,
            startMs = prev.startMs,
            speedPxPerMs = prev.speedPxPerMs,
            widthPx = prev.widthPx,
            posMs = posMs,
            viewW = viewW,
            reverse = prev.item.isReverse,
        )
            if (prev.item.isReverse) x >= LANE_GAP_PX
            else x + prev.widthPx + LANE_GAP_PX <= viewW
        }
    }
}
