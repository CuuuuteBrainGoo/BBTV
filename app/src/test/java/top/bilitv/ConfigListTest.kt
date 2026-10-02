package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.ui.NavTab
import top.bilitv.ui.home.HomeSection
import top.bilitv.ui.player.PlayerBarButton

/**
 * 三份「有序 id 名单」的**解析与兜底**测试（2026-09-30）。
 *
 * ## 为什么必须有这一组
 *
 * 少爷第 8 条要求"首页 / 侧栏 / 播放器控制栏都能配"，三处用的是同一个模型
 * （`docs/33` §4.1）：**一份有序 id 名单 —— 顺序即显示顺序，不在名单里即隐藏**。
 * 存的是逗号分隔的字符串，读回来要经过 `parse`。
 *
 * 这个 `parse` 是**全项目最容易静默出错的几行**，因为：
 *
 * - 它只在"盘上的数据和代码里的枚举对不上"时才起作用 —— 也就是
 *   **老用户升级、枚举改名、手改过盘上文件**这三种情况。
 *   开发机上一切正常，所以任何一次重构都可能在它身上留下一道裂缝而不自知。
 * - 出错的后果不是崩溃，而是**看着像正常，其实配置丢了**：
 *   用户明明关掉了 5 个侧栏项，升级后 8 个全回来了，他不会来报 bug，他会觉得"这软件记不住设置"。
 *
 * 所以这一组**不测 SharedPreferences**（JVM 上跑不了），只测三个 `parse`：
 * 纯函数、边界就那几个、每条都能钉死一个真实会发生的场景。
 */
class ConfigListTest {

    // ---------------------------------------------------------------- 首页分区

    /**
     * 一个都不能少：`HomeSection` 的 id 是**存进盘里**的东西，
     * 改 id 等于让所有用户的分区配置失效。所以列表本身也要钉住。
     */
    @Test
    fun `首页分区的 id 互不重复且不含逗号`() {
        val ids = HomeSection.entries.map { it.id }
        assertEquals("id 有重复", ids.size, ids.toSet().size)
        // 逗号是分隔符。id 里带上逗号 = 存进去之后再读回来会被劈成两个假的 id
        assertTrue("id 里出现了分隔符逗号", ids.none { ',' in it })
        assertTrue("id 里出现了空白", ids.none { it.isBlank() })
    }

    @Test
    fun `首页分区 空名单回落成默认全量`() {
        assertEquals(HomeSection.DEFAULT, HomeSection.parse(emptyList()))
    }

    @Test
    fun `首页分区 认不出的 id 被丢掉 其余保持原顺序`() {
        val got = HomeSection.parse(listOf("movie", "no_such_section", "music"))
        assertEquals(listOf(HomeSection.MOVIE, HomeSection.MUSIC), got)
    }

    @Test
    fun `首页分区 重复的 id 只保留一次`() {
        val got = HomeSection.parse(listOf("movie", "movie", "music", "movie"))
        assertEquals(listOf(HomeSection.MOVIE, HomeSection.MUSIC), got)
    }

    // ---------------------------------------------------------------- 侧栏

    @Test
    fun `侧栏 空名单回落成全部项`() {
        assertEquals(NavTab.entries.toList(), NavTab.parse(emptyList()))
    }

    /**
     * ★ 这一条是整组里最重要的：**四个固定项都必须补回来**。
     *
     * 场景：某次升级前用户的侧栏配置里还没有「设置」这一项（或者他改过盘上的文件）。
     * 如果 `parse` 老老实实照着名单来，用户会得到一个**进不去设置页**的 App ——
     * 而设置页是唯一能把配置改回来的地方，等于永久锁死，只能清数据。
     *
     * 2026-09-30 扩到 4 项：少爷第 3 条把「搜索」「我的」也定为不可关
     * （判据：关掉它用户会不会"没路可走"）。
     */
    @Test
    fun `侧栏 缺失的固定项会被补回来`() {
        val got = NavTab.parse(listOf("CINEMA", "MINE"))
        for (must in NavTab.PINNED) {
            assertTrue("固定项 $must 没被补回来", must in got)
        }
        // 用户自己给的中间项还在一起、且**相对顺序不变**
        val middle = got.filter { it !in NavTab.PINNED }
        assertEquals(listOf(NavTab.CINEMA), middle)
    }

    /**
     * ★ 2026-09-30 新增规格（少爷第 3 条）：
     * **首页必须第 1 位、设置必须最后一位 —— 而且是在"读"的时候就归位。**
     *
     * ⛔ 为什么必须在 `parse` 里做，不能只在设置页的选择器里拦：
     * 盘上可能存着历史版本写下的任意顺序（比如上一版把「设置」放在中间）。
     * 只在选择器里拦"不许拖"，那份老配置在**侧栏本身上**依然是错的 ——
     * 用户会看到一个"设置不在最后"的侧栏，而我们还在设置页里告诉他这俩是固定的。
     * **规则要在"读"的地方生效，不能只在"写"的地方生效。**
     */
    @Test
    fun `侧栏 首页钉第一位 不管用户把它放哪`() {
        val got = NavTab.parse(listOf("CINEMA", "HOME", "LIVE"))
        assertEquals("首页必须在第 1 位", NavTab.HOME, got.first())
    }

    @Test
    fun `侧栏 设置钉最后一位 不管用户把它放哪`() {
        val got = NavTab.parse(listOf("SETTINGS", "CINEMA", "LIVE"))
        assertEquals("设置必须在最后一位", NavTab.SETTINGS, got.last())
        // 顺带把老配置里"设置排第一"那版纠正过来
        assertTrue("首页也要补进来", NavTab.HOME in got)
    }

    @Test
    fun `侧栏 中间项的相对顺序保持不变`() {
        val got = NavTab.parse(listOf("LIVE", "CINEMA", "HISTORY"))
        val middle = got.filter { it !in NavTab.PINNED }
        assertEquals(
            "中间那几项必须保持用户给的相对顺序（归位只动头尾）",
            listOf(NavTab.LIVE, NavTab.CINEMA, NavTab.HISTORY),
            middle,
        )
    }

    @Test
    fun `侧栏 认不出的名字被丢掉`() {
        val got = NavTab.parse(listOf("CINEMA", "NOT_A_TAB", "MINE"))
        assertTrue("认不出的名字不该出现在结果里", got.none { it.name == "NOT_A_TAB" })
        assertEquals(NavTab.CINEMA, got[1])   // got[0] 是钉死的首页
    }

    /**
     * 固定集合从 2 个扩到 4 个（少爷 2026-09-30 第 3 条）。
     *
     * 判据（我给他的建议，他采纳了）：**关掉它，用户会不会"没路可走"**。
     * 首页/搜索/我的/设置 各自是某一件事**唯一**的入口，所以不可关；
     * 动态/历史/直播/影视 都是"内容来源型"，少一个只是少一个来源。
     */
    @Test
    fun `侧栏 固定项集合是首页搜索我的设置`() {
        assertEquals(
            setOf(NavTab.HOME, NavTab.SEARCH, NavTab.MINE, NavTab.SETTINGS),
            NavTab.PINNED,
        )
    }

    @Test
    fun `侧栏 头尾是首页和设置`() {
        assertEquals(NavTab.HOME, NavTab.HEAD)
        assertEquals(NavTab.SETTINGS, NavTab.TAIL)
    }

    /**
     * ★ 一道**穷举式**的守护：不管喂什么输入，头尾都必须对。
     *
     * 单点用例挡不住"某天有人加了个新分支忘了归位"这类回归。
     * 这里把所有排列里最容易出问题的几种一次扫掉 ——
     * 尤其是「TAIL 出现在中间」「HEAD 出现在末尾」「名单里两个都没有」。
     */
    @Test
    fun `侧栏 头尾归位对任意输入都成立`() {
        val cases = listOf(
            emptyList(),
            listOf("SETTINGS"),
            listOf("SETTINGS", "HOME"),
            listOf("LIVE", "SETTINGS", "CINEMA", "HOME"),
            listOf("NOT_A_TAB", "MYSTERY"),
            listOf("HOME", "SETTINGS"),
        )
        for (case in cases) {
            val got = NavTab.parse(case)
            assertEquals("输入 $case：首页没在第 1 位", NavTab.HOME, got.first())
            assertEquals("输入 $case：设置没在最后一位", NavTab.SETTINGS, got.last())
            assertEquals("输入 $case：出现重复项", got.size, got.distinct().size)
        }
    }

    // ---------------------------------------------------------------- 播放器控制栏

    @Test
    fun `控制栏 空名单回落成默认全量`() {
        assertEquals(PlayerBarButton.DEFAULT, PlayerBarButton.parse(emptyList()))
    }

    @Test
    fun `控制栏 缺失的播放键补齐 已移除入口不恢复`() {
        assertEquals(listOf(PlayerBarButton.SPEED, PlayerBarButton.PLAY), PlayerBarButton.parse(listOf("log", "speed", "back")))
        assertEquals(listOf(PlayerBarButton.LIKE, PlayerBarButton.PLAY), PlayerBarButton.parse(listOf("like")))
        assertEquals(listOf(PlayerBarButton.SPEED, PlayerBarButton.PLAY), PlayerBarButton.parse(listOf("speed", "speed", "不存在", "aspect")))
    }

    @Test
    fun `控制栏 默认名单等于全部枚举且无重复`() {
        assertEquals(PlayerBarButton.entries.size, PlayerBarButton.DEFAULT.size)
        assertEquals(
            "默认名单不该有重复",
            PlayerBarButton.DEFAULT.size,
            PlayerBarButton.DEFAULT.toSet().size,
        )
    }

    /**
     * ★ 快进 / 快退**已经不在控制栏里了**（2026-09-30，少爷实机反馈 6）。
     *
     * 少爷原话：
     * > 快进10秒和后退10秒不要做成图标在播放器控制栏……
     *
     * 依据 BT 的「按键设置（播放时）」：左右键锁死是快退/快进，控制栏里没有这两颗
     * （`docs/37` §4.4）。所以 `rewind` / `forward` 两个枚举值被删了。
     *
     * 这条测试防的是**"有人觉得少了点什么，就把它加回来"** ——
     * 加回来就等于把少爷点名不要的东西又摆回屏幕上。
     */
    @Test
    fun `控制栏 不再有快进快退这两颗`() {
        assertTrue("默认名单里不该有快进", PlayerBarButton.DEFAULT.none { it.id == "forward" })
        assertTrue("默认名单里不该有快退", PlayerBarButton.DEFAULT.none { it.id == "rewind" })
        assertEquals("rewind / forward 不该还能被查到", null, PlayerBarButton.byId("rewind"))
        assertEquals(null, PlayerBarButton.byId("forward"))
    }

    /**
     * ★ 老配置里存着的 `"rewind"` / `"forward"` 必须被**静默丢弃**，不能画出假按钮。
     *
     * 少爷的手机/电视上装的旧版本已经把这两个 id 写进盘了。删枚举之后如果
     * `parse` 不认这两个 id 又不丢弃，`ControlBar` 的 `forEach { when(it) }`
     * 就会**漏掉一个分支** —— 轻则崩，重则画出一颗点了没反应的键。
     */
    @Test
    fun `控制栏 老配置里的快进快退 id 被安全丢弃`() {
        // 整份名单只有这两个脏 id → 认不出的全丢 → 空 → 回落成默认全量
        assertEquals(PlayerBarButton.DEFAULT, PlayerBarButton.parse(listOf("rewind", "forward")))

        // 脏 id 混在好 id 中间 → 只丢脏的，好的一颗不少
        val got = PlayerBarButton.parse(listOf("rewind", "like", "forward", "speed"))
        assertEquals(
            listOf(PlayerBarButton.LIKE, PlayerBarButton.SPEED, PlayerBarButton.PLAY),
            got,
        )
    }

    @Test
    fun `控制栏 每一颗都有非空 id 且不含逗号`() {
        val ids = PlayerBarButton.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { ',' in it })
        assertTrue(ids.none { it.isBlank() })
    }

    /**
     * `parse` 出来的名单**必须已经包含全部固定项**。
     *
     * 这条是"消费者视角"的断言：`ControlBar` 只做 `forEach { when(it) {...} }`，
     * 自己不检查固定项有没有在 —— 一旦 `parse` 漏了，用户看到的就是
     * "按遍所有键都退不出去"。
     */
    @Test
    fun `控制栏 parse 的结果永远含全部固定项`() {
        val inputs = listOf(
            emptyList(),
            listOf("log"),
            listOf("speed", "aspect"),
            listOf("", ","),
            listOf("play"),
            listOf("back"),
        )
        inputs.forEach { input ->
            val got = PlayerBarButton.parse(input)
            PlayerBarButton.entries.filter { it.pinned }.forEach { fixed ->
                assertTrue("输入 $input 时丢了固定项 ${fixed.id}", fixed in got)
            }
        }
    }

    @Test
    fun `侧栏 parse 的结果永远含全部固定项`() {
        val inputs = listOf(
            emptyList(),
            listOf("CINEMA"),
            listOf("HOME"),
            listOf("SETTINGS"),
            listOf("乱写的名字"),
        )
        inputs.forEach { input ->
            val got = NavTab.parse(input)
            NavTab.PINNED.forEach { fixed ->
                assertTrue("输入 $input 时丢了固定项 ${fixed.name}", fixed in got)
            }
        }
    }
}
