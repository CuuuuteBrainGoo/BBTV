package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.danmaku.DanmakuItem
import top.bilitv.ui.player.DanmakuEngine
import top.bilitv.ui.player.DanmakuLayout
import top.bilitv.ui.player.danmakuX
import top.bilitv.ui.player.mergedLabel

/**
 * 弹幕去重合并。
 *
 * ## 为什么要测这个
 *
 * `docs/11` §6.3 记着 chinasoul.bt 有 `Hide duplicate danmaku` + `Danmaku merge`
 * 两项，我们一个都没有 —— 复读弹幕（"前方高能"×20）会把画面糊死。
 * 补上之后，这几条规则必须钉死，因为它们**都不会报错，只会"看起来不太对"**：
 *
 * - 合并了但计数没涨 → 用户看到一条孤零零的"前方高能"，信息丢了
 * - 合并时把 `startMs` 也重置了 → 那条弹幕会一直"续命"，复读密集的视频里永不消失
 * - 关掉开关还继续合并 → 开关是假的（这条本项目栽过：见 `silent-dead-feature-audit`）
 *
 * 引擎原来直接吃 Compose 的 `TextLayoutResult`，JVM 里造不出来。为此在
 * `DanmakuLayer.kt` 里抽出了 [DanmakuLayout]（只暴露宽度），下面用 [FakeLayout] 驱动。
 */
class DanmakuMergeTest {
    @Test fun interactionNeverFloodsScreenEvenWithOverlapEnabled() {
        val e = engine()
        val items = (1..40).map { scroll(1000, "提示$it", 5).copy(interaction = true) }
        e.update(0, emptyList(), 1920, 1080, 60, 0, 8000, false, overlap = true, measure = measure)
        e.update(1000, items + scroll(1000, "普通弹幕"), 1920, 1080, 60, 0, 8000, false, overlap = true, measure = measure)
        assertEquals(1, e.active.count { it.item.interaction })
        assertEquals(1, e.active.count { !it.item.interaction })
        e.clear(); assertTrue(e.active.isEmpty())
    }

    @Test fun areaTracksRejectCrossTypeOverlapAndBoundCrowds() {
        val e = engine()
        val crowd = (1..100).map { scroll(1000L, "弹幕$it", if (it % 3 == 0) 6 else if (it % 3 == 1) 5 else 1) }
        e.update(0L, emptyList(), 1920, 216, 60, 0, 8000L, false, measure = measure)
        e.update(1000L, crowd, 1920, 216, 60, 0, 8000L, false, measure = measure)
        assertEquals(3, e.active.size)
        e.clear()
        e.update(0L, emptyList(), 1920, 1080, 60, 0, 8000L, false, measure = measure)
        e.update(1000L, crowd, 1920, 1080, 60, 0, 8000L, false, measure = measure)
        assertEquals(18, e.active.size)
        e.clear()
        e.update(0L, emptyList(), 1920, 216, 60, 0, 8000L, false, overlap = true, measure = measure)
        e.update(1000L, crowd, 1920, 216, 60, 0, 8000L, false, overlap = true, measure = measure)
        assertEquals(100, e.active.size)
        assertEquals(156, top.bilitv.ui.player.danmakuLaneY(scroll(0L, "底部", 4), 0, 216, 60))
        val tall = DanmakuEngine<DanmakuLayout>()
        val measureTall: (DanmakuItem, String) -> DanmakuLayout = { _, _ -> object : DanmakuLayout {
            override val widthPx = 100
            override val heightPx = 90
        } }
        tall.update(0L, emptyList(), 1920, 216, 60, 0, 8000L, false, measure = measureTall)
        tall.update(1000L, crowd, 1920, 216, 60, 0, 8000L, false, measure = measureTall)
        assertEquals(2, tall.active.size)
        assertEquals(126, top.bilitv.ui.player.danmakuLaneY(scroll(0L, "底部", 4), 0, 216, 60, 90))
    }

    /**
     * 宽度按字数估（每字 20px）：正是为了让"合并后变宽"这件事可断言。
     *
     * `text` 也留着 —— 否则断言"文本变成 `内容 ×N`"只能靠 `widthPx / 20` 反推字数，
     * 那种写法一旦估宽规则变了就会**假通过**。
     */
    private class FakeLayout(val text: String) : DanmakuLayout {
        override val widthPx: Int = text.length * 20
    }

    private val measure: (DanmakuItem, String) -> FakeLayout = { _, text ->
        FakeLayout(text)
    }

    private fun scroll(timeMs: Long, content: String, mode: Int = 1) =
        DanmakuItem(timeMs = timeMs, mode = mode, fontsize = 25, color = 0xFFFFFF, content = content)

    private fun engine() = DanmakuEngine<FakeLayout>()

    private fun DanmakuEngine<FakeLayout>.feed(
        posMs: Long,
        items: List<DanmakuItem>,
        merge: Boolean = true,
    ) = update(
        posMs = posMs, raw = items, viewW = 1920, viewH = 1080, lineH = 60,
        maxLines = 0, scrollDurationMs = 8_000L, merge = merge, measure = measure,
    )

    /**
     * 从 [fromMs] 一路推进到 [untilMs]，路上按 tick 粒度喂弹幕
     * （引擎本来就是被逐帧调用的，`posMs` 必须单调递增 —— 倒着走会触发"进度突变清空"）。
     */
    private fun DanmakuEngine<FakeLayout>.advance(
        items: List<DanmakuItem>,
        fromMs: Long,
        untilMs: Long,
        merge: Boolean = true,
        tick: Long = 250L,
    ) {
        var t = fromMs
        while (t <= untilMs) {
            feed(t, items, merge)
            t += tick
        }
    }

    private fun DanmakuEngine<FakeLayout>.play(
        items: List<DanmakuItem>,
        untilMs: Long,
        merge: Boolean = true,
        tick: Long = 250L,
    ) = advance(items, fromMs = 0L, untilMs = untilMs, merge = merge, tick = tick)

    // ------------------------------------------------------------ mergedLabel

    @Test
    fun `只有一条时不加后缀`() {
        assertEquals("前方高能", mergedLabel("前方高能", 1))
    }

    @Test
    fun `两条以上用 × 加计数`() {
        assertEquals("前方高能 ×2", mergedLabel("前方高能", 2))
        assertEquals("前方高能 ×20", mergedLabel("前方高能", 20))
    }

    // ------------------------------------------------------------ 合并

    @Test
    fun `同内容在屏幕上时只占一条_计数累加`() {
        val e = engine()
        val items = List(5) { scroll(1_000L + it * 40L, "前方高能") }

        e.play(items, untilMs = 2_000L)

        assertEquals("屏幕上应该只有一条", 1, e.active.size)
        assertEquals(5, e.active[0].count)
        assertEquals("重排后的文本要带上计数", "前方高能 ×5", e.active[0].layout.text)
        assertEquals("宽度要跟着新文本走", "前方高能 ×5".length * 20, e.active[0].widthPx)
    }

    @Test
    fun `合并不会重置 startMs_否则那条永不消失`() {
        val e = engine()
        // 第一条 1s 入场 → 存活 8s（到 9s 该消失）；第二条 8s 才来 —— 那时第一条还在屏幕上
        val items = listOf(scroll(1_000L, "复读"), scroll(8_000L, "复读"))

        e.play(items, untilMs = 8_200L)

        val only = e.active.single()
        assertEquals("8s 那条应该并进 1s 那条，而不是各占一条", 2, only.count)
        assertEquals("startMs 必须还是第一条的时间", 1_000L, only.startMs)

        // 再走到 9.5s。1s + 8s = 9s，早该没了。
        // 如果上面那次合并把 startMs 改成了 8000，它会一路活到 16s —— 这条断言就是拦它的
        e.advance(items, fromMs = 8_250L, untilMs = 9_500L)
        assertTrue("9.5s 时必须已经消失（合并不能给它续命）", e.active.isEmpty())
    }

    @Test
    fun `不同内容互不干扰`() {
        val e = engine()
        val items = listOf(scroll(500L, "AAA"), scroll(520L, "BBB"), scroll(540L, "AAA"))

        e.play(items, untilMs = 1_500L)

        assertEquals(2, e.active.size)
        assertEquals("AAA", e.active[0].item.content)
        assertEquals(2, e.active[0].count)
        assertEquals(1, e.active[1].count)
    }

    @Test
    fun `关掉开关就退回原行为_同内容各占一条`() {
        val e = engine()
        val items = List(3) { scroll(1_000L + it * 40L, "前方高能") }

        e.play(items, untilMs = 2_000L, merge = false)

        assertEquals(3, e.active.size)
        assertTrue("一条都不该被合并", e.active.all { it.count == 1 })
    }

    @Test
    fun `前一条滚出屏幕之后_同内容可以重新入场`() {
        val e = engine()
        // 第一条 1s 入场，存活 8s；第二条 12s 才来 —— 那时第一条早没了
        val items = listOf(scroll(1_000L, "高能"), scroll(12_000L, "高能"))

        e.play(items, untilMs = 13_000L)

        val only = e.active.single()
        assertEquals("这是新入场的那条，不是旧条在续命", 12_000L, only.startMs)
        assertEquals(1, only.count)
    }

    // ------------------------------------------------------------ mergedCount

    /**
     * 这个计数是给"在真机上判断合并有没有生效"用的 ——
     * 靠肉眼看画面分不清"两条一样的字"是没合并、还是其中一条压根不是弹幕。
     */
    @Test
    fun `mergedCount 记录被并进去的条数`() {
        val e = engine()
        val items = List(5) { scroll(1_000L + it * 40L, "前方高能") }

        e.play(items, untilMs = 2_000L)

        assertEquals("5 条里只有 1 条入场，另外 4 条是并进去的", 4, e.mergedCount)
    }

    @Test
    fun `关掉开关时 mergedCount 保持零`() {
        val e = engine()
        val items = List(5) { scroll(1_000L + it * 40L, "前方高能") }

        e.play(items, untilMs = 2_000L, merge = false)

        assertEquals(0, e.mergedCount)
    }

    // ------------------------------------------------------------ 合并时轨道

    @Test
    fun `合并后文本变宽_不是覆盖原来的宽度`() {
        val e = engine()
        val items = listOf(scroll(1_000L, "高能"), scroll(1_040L, "高能"))

        e.play(items, untilMs = 2_000L)

        val a = e.active.single()
        // "高能 ×2" 5 个字 → 100px；原来 "高能" 2 个字 → 40px
        assertEquals(100, a.widthPx)
        assertEquals("宽度要跟着新排版走，否则相邻轨道会被压到", 100, a.layout.widthPx)
    }

    @Test
    fun `固定弹幕_顶部_也参与合并`() {
        val e = engine()
        // mode 5 = 顶端固定弹幕
        val items = List(3) { scroll(500L + it * 100L, "名场面", mode = 5) }

        e.play(items, untilMs = 1_200L)

        assertEquals(1, e.active.size)
        assertEquals(3, e.active[0].count)
        assertTrue(e.active[0].fixed)
    }

    @Test
    fun `不可渲染的模式依然被跳过_合并开关不影响这条`() {
        val e = engine()
        // mode 7 = 高级弹幕，本阶段不渲染
        val items = listOf(scroll(500L, "高级", mode = 7))

        e.play(items, untilMs = 1_000L)

        assertTrue("高级弹幕不该进 active", e.active.isEmpty())
    }

    @Test
    fun `合并对象是同一实例_所以计数是就地累加而不是新建`() {
        val e = engine()
        val items = listOf(scroll(1_050L, "X"), scroll(1_150L, "X"))

        // 第一次调用只建立 lastPos 基准（引擎不追溯 posMs 之前的历史），1s 这里喂不进东西
        e.feed(1_000L, items)
        // 1.1s：把 1050 那条放进来（区间是 [1001,1100]，1150 那条还够不着）
        e.feed(1_100L, items)
        val first = e.active.single()
        assertEquals(1, first.count)

        // 1.2s：1150 那条来了，应该并进同一个对象
        e.feed(1_200L, items)

        assertEquals(1, e.active.size)
        assertSame("同一实例，没有重建", first, e.active.single())
        assertEquals(2, first.count)
    }

    // ------------------------------------------------------------ 恒速与位置

    @Test
    fun `宽度不同的弹幕_左边缘位置始终重合`() {
        val e = engine()
        val items = listOf(scroll(1_000L, "短"), scroll(1_000L, "这条弹幕特别长特别长"))
        e.feed(0L, emptyList())          // 先建立 lastPos 基准，否则 1000 那条喂不进去
        e.feed(1_000L, items)

        assertEquals(2, e.active.size)
        val a = e.active[0]
        val b = e.active[1]
        assertTrue("两条宽度必须不同，否则这条测试没有意义", a.widthPx != b.widthPx)

        for (t in listOf(1_000L, 1_500L, 3_000L, 6_000L)) {
            val xa = danmakuX(false, a.startMs, a.speedPxPerMs, a.widthPx, t, 1920, false)
            val xb = danmakuX(false, b.startMs, b.speedPxPerMs, b.widthPx, t, 1920, false)
            assertEquals("t=$t 时两条左边缘必须重合", xa, xb, 0.01f)
        }
    }

    @Test
    fun `位移与时间成正比_不会忽快忽慢`() {
        val e = engine()
        e.feed(0L, emptyList())
        e.feed(1_000L, listOf(scroll(1_000L, "随便一条弹幕")))
        val a = e.active.single()

        fun x(t: Long) = danmakuX(false, a.startMs, a.speedPxPerMs, a.widthPx, t, 1920, false)

        val firstSecond = x(2_000L) - x(1_000L)
        val fifthSecond = x(6_000L) - x(5_000L)
        assertEquals("每 1 秒走过的距离必须一样", firstSecond, fifthSecond, 0.01f)
    }

    @Test
    fun `宽弹幕活得更久_因为要走更远`() {
        val e = engine()
        e.feed(0L, emptyList())
        e.feed(1_000L, listOf(scroll(1_000L, "短"), scroll(1_000L, "这条弹幕特别长特别长")))

        val narrow = e.active.first { it.widthPx < 100 }
        val wide = e.active.first { it.widthPx > 100 }
        assertTrue(
            "恒速下宽弹幕要多走一段路，所以活得更久（${narrow.durationMs} vs ${wide.durationMs}）",
            wide.durationMs > narrow.durationMs,
        )
    }

    @Test
    fun `固定弹幕居中且不随播放位置移动`() {
        val e = engine()
        e.feed(0L, emptyList())
        e.feed(500L, listOf(scroll(500L, "名场面", mode = 5)))
        val a = e.active.single()

        assertTrue(a.fixed)
        assertEquals("固定弹幕速度必须是 0", 0f, a.speedPxPerMs, 0f)

        fun x(t: Long) = danmakuX(true, a.startMs, a.speedPxPerMs, a.widthPx, t, 1920, false)
        assertEquals("不同时刻位置必须一样", x(600L), x(3_000L), 0.01f)
        assertEquals("必须水平居中", (1920 - a.widthPx) / 2f, x(600L), 0.01f)
    }

    @Test
    fun `合并变宽不会让弹幕位置跳变`() {
        val e = engine()
        e.feed(0L, emptyList())
        e.feed(1_000L, listOf(scroll(1_000L, "高能")))
        val a = e.active.single()
        val widthBefore = a.widthPx
        val xBefore = danmakuX(false, a.startMs, a.speedPxPerMs, a.widthPx, 2_000L, 1920, false)

        // 同内容再进来一条 → 合并，文本变成「高能 ×2」
        e.feed(2_000L, listOf(scroll(2_000L, "高能")))

        assertTrue("文本确实变宽了", a.widthPx > widthBefore)
        val xAfter = danmakuX(false, a.startMs, a.speedPxPerMs, a.widthPx, 2_000L, 1920, false)
        assertEquals("同一时刻的位置不能因为变宽而跳", xBefore, xAfter, 0.01f)
    }
}
