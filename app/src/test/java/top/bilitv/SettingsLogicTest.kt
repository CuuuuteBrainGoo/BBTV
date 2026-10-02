package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.settings.DanmakuTuning
import top.bilitv.data.settings.QualityOptions
import top.bilitv.player.CdnOrder
import top.bilitv.player.PlayTolerance

/**
 * 设置页「高级模式」那些**纯逻辑**的测试（2026-09-29）。
 *
 * ## 这一组钉的是什么
 *
 * 设置项本身（存 / 读 SharedPreferences）没法在 JVM 单测里跑，所以这里不测存储，
 * 只测三类**会静默出错**的逻辑：
 *
 * 1. **档位方向**（[DanmakuTuning]）。不透明度 / 字号 / 行数 / 滚动时长在
 *    **两个界面**上都能调（播放页的弹幕面板 + 设置页高级模式）。抽出来之前两处各写一套，
 *    同一个设置在两个页面上按一下会走到不同的值 —— 而界面上看不出任何异常。
 * 2. **清晰度档位**（[QualityOptions]）。`0` = 自动的语义是和
 *    `StreamSelector`（`qualityId > 0` 才当筛选条件）**约定**的，
 *    谁改坏谁就得到一个"选了自动但画质被锁死"的播放器。
 * 3. **P2P 域名判据**（[CdnOrder]）。判据是一串 `contains` / `endsWith`，
 *    写错一条的后果只是**把一条好 CDN 排到垫底** → 起播慢一点，没人会来查。
 *
 * ## 为什么这些值得单测
 *
 * 2026-09-29 直播页踩过一次"编译过、单测绿、功能全死"（角标恒显示「未开播」，
 * 根因是拿 `optInt` 的兜底值当结论）。这里的共同点是**同一个值有多处定义**：
 * 三处各写一遍边界，就该有一处替你把它们对齐。
 */
class SettingsLogicTest {

    @Test fun actualQualityLabelPreservesTierInsteadOfCroppedHeight() {
        assertEquals("1080P", QualityOptions.compactLabel(80, height = 886))
        assertEquals("1080P60", QualityOptions.compactLabel(116, height = 886))
        assertEquals("4K", QualityOptions.compactLabel(120, height = 1772))
        assertEquals("…", QualityOptions.compactLabel(0))
        assertEquals("720P60", QualityOptions.compactLabel(999, "高清 720p60"))
        assertEquals("540P", QualityOptions.compactLabel(999, height = 540))
    }

    // ============================================================ 弹幕档位

    /**
     * 不透明度：0.1 ~ 1.0，步长 0.1。
     *
     * 第一版用浮点加减（`v + 0.1f`）会被单测抓到 `0.7000001` 这种值 ——
     * 于是界面显示 70%、实际存的是 0.7000001，再按一次「+」可能跳过 0.8。
     */
    @Test
    fun alphaStepsAreExactTenths() {
        assertEquals(1.0f, DanmakuTuning.stepAlpha(0.9f, up = true), 0.0001f)
        assertEquals(0.8f, DanmakuTuning.stepAlpha(0.9f, up = false), 0.0001f)

        // 夹取：到顶再按「+」还是 1.0，到底再按「−」还是 0.1
        assertEquals(1.0f, DanmakuTuning.stepAlpha(1.0f, up = true), 0.0001f)
        assertEquals(0.1f, DanmakuTuning.stepAlpha(0.1f, up = false), 0.0001f)

        // 一整轮来回必须回到原值（浮点误差在这一档不能累积）
        var v = DanmakuTuning.DEF_ALPHA
        repeat(20) { v = DanmakuTuning.stepAlpha(v, up = false) }
        repeat(20) { v = DanmakuTuning.stepAlpha(v, up = true) }
        assertEquals(1.0f, v, 0.0001f)
    }

    @Test
    fun alphaClampAndLabel() {
        assertEquals(0.1f, DanmakuTuning.clampAlpha(0f), 0.0001f)
        assertEquals(1.0f, DanmakuTuning.clampAlpha(9f), 0.0001f)
        assertEquals("90%", DanmakuTuning.alphaLabel(0.9f))
        assertEquals("100%", DanmakuTuning.alphaLabel(1f))
    }

    @Test
    fun scaleStepsAndLabel() {
        assertEquals(0.6f, DanmakuTuning.stepScale(0.5f, up = true), 0.0001f)
        assertEquals(0.5f, DanmakuTuning.stepScale(0.5f, up = false), 0.0001f)
        assertEquals(2.0f, DanmakuTuning.stepScale(2.0f, up = true), 0.0001f)
        assertEquals("100%", DanmakuTuning.scaleLabel(1f))
        assertEquals("50%", DanmakuTuning.scaleLabel(0.5f))
    }

    /**
     * 显示行数：`0` = 不限，而且是**最高档**。
     *
     * 这条规则最容易在两个界面之间走样（原来播放页写的是
     * `if (lines <= 12) 0 else lines + 2`，从 14 往上加会得到 16，
     * 而设置页那边是从 12 直接跳「不限」）。
     */
    @Test
    fun linesSteppingIsOneWayAndReversible() {
        // 从「不限」往下退，退到最大档 20，而不是某个中间值
        assertEquals(20, DanmakuTuning.stepLines(0, up = false))
        // 已经「不限」了，再按「+」还是「不限」（没有更松的档）
        assertEquals(0, DanmakuTuning.stepLines(0, up = true))
        // 20 再往上是「不限」
        assertEquals(0, DanmakuTuning.stepLines(20, up = true))
        // 正常一档一档走
        assertEquals(4, DanmakuTuning.stepLines(2, up = true))
        assertEquals(2, DanmakuTuning.stepLines(4, up = false))
        // 到底就停在 2 行，不会变成 0（0 是"不限"，不是"没有"）
        assertEquals(2, DanmakuTuning.stepLines(2, up = false))
    }

    @Test
    fun linesClampAndLabel() {
        assertEquals(0, DanmakuTuning.clampLines(0))
        assertEquals(0, DanmakuTuning.clampLines(-5))
        // 奇数往偶数收，而不是留一个 3 行这种"界面显示得出、实际没用"的值
        assertEquals(2, DanmakuTuning.clampLines(3))
        assertEquals(2, DanmakuTuning.clampLines(1))
        assertEquals(20, DanmakuTuning.clampLines(99))
        assertEquals("不限", DanmakuTuning.linesLabel(0))
        assertEquals("12", DanmakuTuning.linesLabel(12))
    }

    /**
     * 滚动时长：**数值越大越慢**。
     *
     * 界面上的约定是「按 + 数字变大」，而数字是秒数 —— 所以 `up = true` 是"更慢"。
     * 这里钉住方向，免得以后有人凭"速度"这个词把加减反过来
     * （原来的播放页面板就是反的：按「−」反而让秒数变大）。
     */
    @Test
    fun speedDirectionMatchesDisplayedNumber() {
        assertEquals(9_000L, DanmakuTuning.stepSpeed(8_000L, up = true))
        assertEquals(7_000L, DanmakuTuning.stepSpeed(8_000L, up = false))
        assertEquals(20_000L, DanmakuTuning.stepSpeed(20_000L, up = true))
        assertEquals(3_000L, DanmakuTuning.stepSpeed(3_000L, up = false))
        assertEquals("8.0s", DanmakuTuning.speedLabel(8_000L))
        // 夹取之后再显示，避免出现 "25.0s" 这种存不进去的数字
        assertEquals("20.0s", DanmakuTuning.speedLabel(25_000L))
    }

    // ============================================================ 清晰度档位

    /**
     * 「自动」必须是 `0` 并且排在第一位 —— 界面铺 chip 的顺序就是数据顺序，
     * 而 `StreamSelector` 用 `qualityId > 0` 判断"用户是否指定了档位"。
     */
    @Test
    fun autoQualityIsZeroAndFirst() {
        assertEquals(0, QualityOptions.AUTO_ID)
        assertEquals(QualityOptions.AUTO, QualityOptions.ALL.first())
        assertFalse(QualityOptions.isExplicit(QualityOptions.AUTO_ID))
        assertTrue(QualityOptions.isExplicit(80))
    }

    /**
     * 档位 id 不许重复。
     *
     * 重复的后果很隐蔽：`labelOf` 会**认第一个**，于是界面上"1080P"点了却选中另一个，
     * 而两个 chip 长得一模一样。
     */
    @Test
    fun qualityIdsAreUniqueAndOrderedByHeight() {
        val ids = QualityOptions.ALL.map { it.id }
        assertEquals("档位 id 有重复：$ids", ids.size, ids.distinct().size)
        // 除「自动」外按清晰度从高到低（界面顺序 = 从清晰到糊）
        val explicit = ids.filter { it > 0 }
        assertEquals(explicit, explicit.sortedDescending())
    }

    /**
     * 认不出来的 id 回落成「自动」而不是空串。
     *
     * 存量设置可能是老版本写的（那时候还没有 `QualityOptions`），
     * 界面上出现一个空白 chip 比显示"自动"更让人困惑。
     */
    @Test
    fun unknownQualityFallsBackToAuto() {
        assertEquals("自动", QualityOptions.labelOf(999))
        assertEquals("自动", QualityOptions.labelOf(0))
        assertEquals("1080P", QualityOptions.labelOf(80))
    }

    // ============================================================ CDN 排序

    @Test
    fun p2pHostsAreRecognised() {
        assertTrue(CdnOrder.isP2pNode("https://xy171x43x247x145xy.mcdn.bilivideo.cn:8082/x.m4s"))
        assertTrue(CdnOrder.isP2pNode("https://foo.edge.bilivideo.com/x.m4s"))
        assertTrue(CdnOrder.isP2pNode("https://a.bilivideo.com:4483/x.m4s"))
        // 常规 CDN 不能被误判 —— 误判的后果是把最好的一条排到垫底
        assertFalse(CdnOrder.isP2pNode("https://upos-sz-mirrorcos.bilivideo.com/x.m4s"))
        assertFalse(CdnOrder.isP2pNode("https://d1--cn-gotcha209.bilivideo.com/x.m4s"))
        // 端口只认那两个；443 的 edge 域名照旧算 P2P（规则是域名，不是端口）
        assertFalse(CdnOrder.isP2pNode("https://upos-hz.bilivideo.com:443/x.m4s"))
    }

    /**
     * 默认排序：P2P 垫底，其余保持 B 站给的原始顺序（稳定排序）。
     *
     * "保持原顺序"是有意义的：B 站把延迟最低的镜像放最前面，
     * 顺手按字符串重排等于把它的选择丢掉。
     */
    @Test
    fun p2pGoesLastAndOrderIsStable() {
        val good1 = "https://upos-sz-mirrorcos.bilivideo.com/a.m4s"
        val good2 = "https://upos-hz.bilivideo.com/b.m4s"
        val p2p = "https://1x2x3x.mcdn.bilivideo.cn:8082/c.m4s"
        val ordered = CdnOrder.order(listOf(p2p, good1, good2))
        assertEquals(listOf(good1, good2, p2p), ordered)
    }

    @Test
    fun skipP2pDropsThemButKeepsCdn() {
        val good = "https://upos-sz-mirrorcos.bilivideo.com/a.m4s"
        val p2p = "https://1x2x3x.mcdn.bilivideo.cn:8082/c.m4s"
        assertEquals(listOf(good), CdnOrder.order(listOf(p2p, good), skipP2p = true))
    }

    /**
     * ★ 全部候选都是 P2P 时必须**回落成不过滤**。
     *
     * 有些视频的 `baseUrl` / `backupUrl` 全是 P2P 节点。真按开关滤干净，
     * `videoUrls` 就空了 —— 一个"线路偏好"开关把能播的视频变成不能播，
     * 这是这个开关唯一可能造成的**功能性事故**。
     */
    @Test
    fun skipP2pFallsBackWhenNothingLeft() {
        val p2p1 = "https://1x2x3x.mcdn.bilivideo.cn:8082/c.m4s"
        val p2p2 = "https://4x5x6x.mcdn.bilivideo.cn:8082/d.m4s"
        assertEquals(listOf(p2p1, p2p2), CdnOrder.order(listOf(p2p1, p2p2), skipP2p = true))
    }

    @Test
    fun blankUrlsAreDropped() {
        val good = "https://upos-sz-mirrorcos.bilivideo.com/a.m4s"
        assertEquals(listOf(good), CdnOrder.order(listOf("", "   ", good)))
        assertTrue(CdnOrder.order(emptyList()).isEmpty())
    }

    // ============================================================ 容错：CDN 偏好

    /**
     * 「指定 CDN」是**优先**，不是**只用**。
     *
     * 这条差别看着小，实际是这个开关唯一可能出事故的地方：理解成"只用"的话，
     * 用户填了一个本机连不通的镜像，视频就**彻底播不了**了 ——
     * 而他打开这个开关的初衷是"让它更稳"。
     */
    @Test
    fun cdnPreferencePutsMatchFirstButKeepsTheRest() {
        val hit = "https://upos-sz-mirrorcos.bilivideo.com/a.m4s"
        val miss1 = "https://upos-hz.bilivideo.com/b.m4s"
        val miss2 = "https://d1--cn-gotcha209.bilivideo.com/c.m4s"
        val ordered = PlayTolerance.orderByPreference(listOf(miss1, miss2, hit), "upos-sz-mirrorcos")
        assertEquals(listOf(hit, miss1, miss2), ordered)
        // 数量必须一条不少
        assertEquals(3, ordered.size)
    }

    /**
     * ★ 匹配不上任何一条时**原样返回**，而不是返回空表。
     *
     * 用户把关键词打错了（`upso-sz` 之类）是最容易发生的事。
     * 如果实现写成"保留匹配到的"（filter），这里会返回空表 →
     * `videoUrls` 空 → 报"没有任何视频候选地址，无法起播"。
     * 一个便捷开关把能播的视频变成不能播，跟 CdnOrder 那条回落是同一个道理。
     */
    @Test
    fun cdnPreferenceWithNoMatchChangesNothing() {
        val urls = listOf(
            "https://upos-hz.bilivideo.com/b.m4s",
            "https://d1--cn-gotcha209.bilivideo.com/c.m4s",
        )
        assertEquals(urls, PlayTolerance.orderByPreference(urls, "打错的词"))
        assertEquals(urls, PlayTolerance.orderByPreference(urls, ""))
        assertEquals(urls, PlayTolerance.orderByPreference(urls, "   "))
    }

    /** 大小写不敏感：域名本身不分大小写，用户从浏览器里复制出来的常常是大写的 */
    @Test
    fun cdnPreferenceIsCaseInsensitive() {
        val hit = "https://UPOS-HZ.bilivideo.com/b.m4s"
        val miss = "https://d1--cn-gotcha209.bilivideo.com/c.m4s"
        assertEquals(listOf(hit, miss), PlayTolerance.orderByPreference(listOf(miss, hit), "upos-hz"))
    }

    /** 多条同时命中时，它们之间的原顺序要保持（稳定分区） */
    @Test
    fun cdnPreferenceKeepsRelativeOrderAmongMatches() {
        val a = "https://upos-hz-1.bilivideo.com/a.m4s"
        val b = "https://upos-hz-2.bilivideo.com/b.m4s"
        val c = "https://d1--cn-gotcha209.bilivideo.com/c.m4s"
        assertEquals(listOf(a, b, c), PlayTolerance.orderByPreference(listOf(a, c, b), "upos-hz"))
    }
}
