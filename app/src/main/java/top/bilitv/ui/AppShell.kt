package top.bilitv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.bilitv.data.history.HistoryEntry
import top.bilitv.data.settings.ThemeSkin
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.observeFocus
import top.bilitv.ui.dynamic.DynamicScreen
import top.bilitv.ui.history.HistoryScreen
import top.bilitv.ui.home.HomeScreen
import top.bilitv.ui.live.LiveScreen
import top.bilitv.ui.mine.MineScreen
import top.bilitv.ui.pgc.PgcScreen
import top.bilitv.ui.search.SearchScreen
import top.bilitv.ui.settings.SettingsScreen
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.components.LocalNavigationFocus
import top.bilitv.ui.components.NavigationFocus
import top.bilitv.ui.components.RailIcons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import top.bilitv.BiliTvApp
import top.bilitv.data.settings.StartupFocus

/**
 * 一级导航项。
 *
 * ## 2026-09-29 改版：对齐 BT 的"内容板块"划分
 *
 * 参照 `chinasoul.bt` 的九宫格（首页/动态/收藏/历史/直播/影视/我的/搜索/设置），
 * 以及少爷给的推进顺序（视觉 → 历史续播 → 搜索 → 关注 → 直播 → 设置），
 * 定成下面这 9 项。
 *
 * ★ **"热门"从一级降级了**：它现在是首页底下的一个子标签
 * （见 `ui.home.HomeTab`）。这是 BT 的做法 —— 一级入口是"内容板块"，
 * 板块内部的"内容种类"用顶部子标签表达。好处是侧栏不会为了加一个内容源
 * 就多一行，而"热门/推荐/每周必看"本来也是同一个接口层次的东西。
 *
 * ★ 2026-09-29 改版：**从文字导航改成纯图标导航**。
 *
 * 原来这里写着"不放图标：低内存电视上，文字导航够用" —— **那条已被少爷推翻**。
 * 原话：
 * > 侧边栏不再使用小字号文字，改为使用绘制的图标；图标需参照 B 站对应功能的官方图标风格与样式。
 *
 * 三段理由都站不住/已变化：
 * - "低内存电视"：矢量 `ImageVector` 是**代码里的路径**，不是位图资源，
 *   没有解码开销，也没有"一套图标资源"的包袱 —— 这条当初就想错了；
 * - "文字够用"：少爷看到真机后明确不要文字（`docs/32` 第一节解释了为什么：
 *   3 米外 10.5px 的字物理上读不出来，却占掉竖向预算）；
 * - 对比度：颜色由 `Icon(tint=…)` 统一给，本来就跟着皮肤走，不是问题。
 *
 * ★ 同时 **`关注` 不再是侧栏项** —— 少爷要求做到「我的」页里。
 * 它仍然是一个页面，只是改成**二级页**（`Screen.Follow`，见 `Nav.kt`），
 * 从「我的」进去、按返回回来。侧栏因此从 9 项变 **8 项**。
 */
enum class NavTab(val label: String) {
    HOME("首页"),
    CINEMA("影视"),
    DYNAMIC("动态"),
    HISTORY("历史"),
    LIVE("直播"),
    SEARCH("搜索"),
    MINE("我的"),
    SETTINGS("设置"),
    ;

    fun startupFocus(requested: StartupFocus?): StartupFocus? =
        if (requested == StartupFocus.TABS && this !in setOf(HOME, CINEMA, LIVE)) StartupFocus.RAIL else requested

    companion object {
        fun startup(name: String?, visible: List<NavTab>): NavTab =
            visible.firstOrNull { it.name == if (name == "FAV") MINE.name else name } ?: HOME
        /**
         * **不允许隐藏**的项（少爷 2026-09-30 第 3 条拍板，采纳我推荐的名单）。
         *
         * 判据只有一条：**关掉它，用户会不会"没路可走"**。
         *
         * | 项 | 为什么不能关 |
         * |---|---|
         * | [HOME] | 返回键的落点 + 唯一的内容总入口 |
         * | [SEARCH] | **唯一"主动找东西"的入口**，其余全是"被动刷" |
         * | [MINE] | 登录 / 收藏 / 关注 的唯一入口 |
         * | [SETTINGS] | 唯一能改回配置的地方（关了等于把自己锁在门外） |
         *
         * 其余四项（动态 / 历史 / 直播 / 影视）都是**内容来源型** ——
         * 少一个只是少一个来源，用户不会没路走（番剧电影还能从首页分区进，
         * 续播在播放页里也有），所以允许关。
         */
        val PINNED = setOf(HOME, SEARCH, MINE, SETTINGS)

        /**
         * **钉死在第一位**的项。少爷原话：「侧边栏固定首页第一个不能拖动」。
         *
         * 首页必须是第 1 位，理由不只是"习惯"：它是**返回键的落点**。
         * 用户在任何二级页连按返回，最后都会回到侧栏第 1 项；
         * 如果首页被挪到中间，"一路按左回首页"就不成立了 —— 肌肉记忆会失灵。
         */
        val HEAD: NavTab = HOME

        /** **钉死在最后一位**的项。少爷原话：「设置最后一个也不能拖动」。 */
        val TAIL: NavTab = SETTINGS

        /**
         * 把一份存下来的名字名单翻译成实际的侧栏项。
         *
         * 四道兜底，任何一道都不能省（老配置 + 改过枚举 + 手改过盘上文件都会触发）：
         * 1. 认不出的名字直接丢掉（枚举改名/删项后老配置不会让用户卡在空白侧栏）
         * 2. 去重
         * 3. **固定项补回来** —— 盘上存的是旧版本、里面没有「设置」时也要能进得去
         * 4. ★ 2026-09-30：**把 [HEAD] 摆到第 1 位、[TAIL] 摆到最后一位**
         *
         * 第 4 条为什么必须在**解析层**做，而不是只在设置页的选择器里做：
         * 盘上可能存着历史版本写下的任意顺序（比如上一版把「设置」放在中间）。
         * 只在选择器里拦"不许拖"，那份老配置在**侧栏本身上**依然是错的 ——
         * 用户会看到一个"设置不在最后"的侧栏，而我们在设置页里还告诉他这是固定的。
         * **规则要在"读"的地方生效，不能只在"写"的地方生效。**
         */
        fun parse(names: List<String>): List<NavTab> {
            val list = names.mapNotNull { n -> entries.firstOrNull { it.name == n } }.distinct()
            if (list.isEmpty()) return entries.toList()
            val filled = list + PINNED.filterNot { it in list }
            val middle = filled.filter { it != HEAD && it != TAIL }
            return listOf(HEAD) + middle + listOf(TAIL)
        }
    }
}

/**
 * 应用外壳：左侧一级导航 + 右侧内容区。
 *
 * ## 为什么必须有这一层
 *
 * 两个理由，第二个才是关键：
 *
 * 1. 七个一级入口在 `docs/03` §2.4 里早就定了，但代码里**一个都没有** ——
 *    在这之前整个 App 只有一个首页。这是视觉落地必须先补的结构。
 *
 * 2. **侧栏是全局的焦点兜底。** `docs/11` §5 里最难缠的一类问题是
 *    "某个页面里没有任何可聚焦元素，用户按方向键毫无反应，只能强退"。
 *    有了常驻侧栏，任何页面（包括还没做的占位页）至少有一个可以落脚的地方，
 *    这类问题从"每页都要单独防"变成"结构上不会发生"。
 *
 * ## 二级页面为什么不带侧栏
 *
 * 详情页和播放页是全屏覆盖的（见 [BiliTvRoot]）。播放要沉浸，详情要放大图，
 * 而且这两页各自都有明确的返回入口。把侧栏塞进去只会让焦点链变复杂
 * —— 用户按 LEFT 走出画面时，期望的是"回到上一级"，不是"跑到侧栏某个 Tab"。
 *
 * @param onOpenVideo 点开一个 UGC 视频。
 * @param onOpenSeason 点开一部影视/番剧（PGC）。
 * @param onOpenUp 点开一个 UP 主的主页（关注页进来的）。三个参数都带过去，
 *   免得主页再问一次接口要昵称/头像 —— 见 `Screen.UpSpace` 的说明。
 * @param onOpenLogin 打开扫码登录页。
 * @param onSkinChange 换肤。直接改设置并让根节点重组，做到即时生效。
 */
@Composable
fun AppShell(
    /**
     * 当前一级 Tab 的**枚举名**（不是序号 —— 序号会在枚举插项时整体错位）。
     *
     * ★ 2026-09-29 从内部状态提升为参数：返回键需要知道"现在在不在首页"
     * 才能决定该回首页还是退出 App，而返回键收口在 [BiliTvRoot]（见 `Nav.kt`）。
     * 状态提升之后这一层变成**受控组件**，自己不持有 tab。
     */
    tabName: String,
    /** 用户点了侧栏某一项 */
    onSelectTab: (NavTab) -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenSeason: (Long) -> Unit,
    onOpenUp: (Long, String, String) -> Unit,
    onOpenLogin: () -> Unit,
    /** 打开「关注」二级页（从「我的」进）。它不再是侧栏项 —— 见 [NavTab] 的说明。 */
    onOpenFollow: () -> Unit,
    /** 打开「收藏」二级页（从「我的」进）。 */
    onOpenFav: () -> Unit,
    /** 点一个直播间 → 直接进直播播放页（见 `Screen.LivePlayer`） */
    onOpenLive: (top.bilitv.data.model.LiveRoom) -> Unit,
    onSkinChange: (ThemeSkin) -> Unit,
    /**
     * 侧栏当前显示哪些项、什么顺序。
     *
     * ★ 2026-09-30 少爷第 8 条：「不只首页，**侧栏也能配**」。
     * 状态住在 [top.bilitv.MainActivity]（和皮肤同一个理由：侧栏在外壳最外层，
     * 设置页改了它必须立刻重排，不能等下次进页面）。
     * 翻译/兜底由 [NavTab.parse] 负责，这里**直接当成品用**。
     */
    railTabs: List<NavTab>,
    /** 设置页改了侧栏配置 → 落盘 + 让界面重排。 */
    onRailTabsChange: (List<String>) -> Unit,
    /** 从观看记录点一条 → **直接续播**。播放页会自己读本机记录里的位置 */
    onResume: (HistoryEntry) -> Unit,
    startupFocus: StartupFocus? = null,
    onStartupFocusHandled: () -> Unit = {},
) {
    val theme = AppTheme.current
    val settings = (LocalContext.current.applicationContext as BiliTvApp).settings
    var showClock by remember { mutableStateOf(settings.showClock) }

    /*
     * 名字认不出来时回落到首页 —— 枚举改名/删项后老状态不会让用户卡在空白页。
     *
     * ⚠️ 还要兜住第二种情况：**当前 Tab 被用户在设置里藏起来了**。
     * 那时 `railTabs` 里找不到它，得落到名单里的第一项，而不是硬塞一个
     * 侧栏上根本不存在的页面 —— 那样侧栏没有任何一项是亮的，像坏了。
     */
    val tab = railTabs.firstOrNull { it.name == tabName }
        ?: railTabs.firstOrNull()
        ?: NavTab.HOME
    val onSelect: (NavTab) -> Unit = onSelectTab

    // 冷启动由同一焦点入口认领指定区域；之后按实际导航焦点保护异步刷新。
    val railFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val tabState = rememberSaveableStateHolder()
    val navigationFocus = remember { NavigationFocus(tab.startupFocus(startupFocus)).apply {
        confirmTabs = settings.lowMemoryMode
    } }
    DisposableEffect(settings) {
        val stop = settings.observeChanges {
            showClock = settings.showClock
            navigationFocus.confirmTabs = settings.lowMemoryMode
        }
        onDispose { stop() }
    }
    LaunchedEffect(navigationFocus.pendingStartup) {
        if (navigationFocus.pendingStartup == null) onStartupFocusHandled()
    }

    CompositionLocalProvider(LocalNavigationFocus provides navigationFocus) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            /*
             * ★ 2026-09-30 少爷要求：**按遥控器菜单键刷新当前页**。
             *
             * 「一级拦、二级放行」就是写在这里：
             * - 这一层是**一级页面**的外壳（首页/影视/动态/历史/直播/搜索/我的/设置都在这下面）；
             * - 二级页面（详情/播放/关注/登录）会把整个 `AppShell` **从组合里摘掉**，
             *   那时这段代码根本不存在 —— 菜单键自然落到它们手里，**我们不会抢**。
             *
             * 为什么不做成"在最外层全局吃掉"：那样以后播放页想用菜单键弹菜单时
             * **永远收不到**，而且症状是"按了没反应、不知道被谁吃了"，极难查。
             *
             * 用 `KeyUp` 而不是 `KeyDown`：按住不放不该连续刷新（`KeyDown` 会重复触发）。
             */
            .onPreviewKeyEvent { e ->
                // 用户已经开始操作，就不再让尚未完成的启动请求改动焦点。
                if (e.type == KeyEventType.KeyDown) navigationFocus.pendingStartup = null
                if (e.key == Key.Menu && e.type == KeyEventType.KeyUp) {
                    RefreshBus.request()
                    true
                } else {
                    false
                }
            },
    ) {
        NavRail(
            tabs = railTabs,
            current = tab,
            onSelect = onSelectTab,
            // 当前侧栏项始终挂载，启动和页面左键返回都落在这里。
            selectedRequester = railFocus,
            settingsFocus = settingsFocus,
        )

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // 给时间留出独立一行，避免挡住长标签、筛选或管理按钮。
            Box(Modifier.fillMaxSize().padding(top = if (showClock) 28.dp else 0.dp)) {
            /*
             * 每个 Tab 单独存状态。
             *
             * 切 Tab 时上一个页面的内容会**整个离开组合**，`remember` 归零。
             * 不包这一层的话，在首页滚了 10 行 → 去设置 → 切回首页，
             * 会发现自己回到最顶上，得重新滚一遍。
             * 包一层之后滚动位置和焦点下标都还在（它们内部用的都是 `rememberSaveable`）。
             */
            tabState.SaveableStateProvider(tab.name) {
                when (tab) {
                    NavTab.HOME -> HomeScreen(
                        onOpen = onOpenVideo,
                        // 首页网格里可能有 PGC 卡（番剧/电影…），那些没有 bvid，走 seasonId
                        onOpenSeason = onOpenSeason,
                    )
                    NavTab.CINEMA -> PgcScreen(onOpenSeason = onOpenSeason)
                    NavTab.DYNAMIC -> DynamicScreen(
                        onOpenVideo = onOpenVideo,
                        onNeedLogin = onOpenLogin,
                        // 空列表时那个按钮的去处。它同时是这一页空着时的唯一焦点落点
                        onGoHome = { onSelectTab(NavTab.HOME) },
                    )
                    NavTab.HISTORY -> HistoryScreen(
                        onResume = onResume,
                        // 历史页空着时那个「去首页看看」按钮 —— 它是那一页唯一的焦点落点
                        onGoHome = { onSelectTab(NavTab.HOME) },
                    )
                    NavTab.SEARCH -> SearchScreen(onOpen = onOpenVideo)
                    NavTab.LIVE -> LiveScreen(onOpen = onOpenLive)
                    NavTab.MINE -> MineScreen(
                        onNeedLogin = onOpenLogin,
                        onOpenUp = onOpenUp,
                        // ★ 2026-09-29：`关注` 从侧栏搬到「我的」页（少爷要求）。
                        //   它现在是二级页，从这一行进去、按返回回来。
                        onOpenFollow = onOpenFollow,
                        onOpenFav = onOpenFav,
                    )
                    NavTab.SETTINGS -> SettingsScreen(
                        onSkinChange = onSkinChange,
                        railFocus = railFocus,
                        entryFocus = settingsFocus,
                        railTabs = railTabs.map { it.name },
                        onRailTabsChange = onRailTabsChange,
                    )
                    /*
                     * ★ 这里**故意没有 `else`**。
                     *
                     * 2026-09-29：侧栏 9 项全部落地了（动态页是最后一个占位页），
                     * 所以原先那个「占位页」兜底分支连同 `ComingSoon` 一起删掉了。
                     *
                     * 不留 `else` 是要**让编译器替我们守着**：以后谁往 [NavTab] 里
                     * 加第 10 项，这里立刻编译不过，逼他当场回答"这一页怎么落地"，
                     * 而不是悄悄跑出一个空白页 —— 空白页在遥控器上等于死键。
                     */
                }
            }
            }
            if (showClock) InterfaceClock(Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 4.dp))
        }
    }

    RequestFocusOnAppear(railFocus, navigationFocus.pendingStartup == StartupFocus.RAIL,
        role = StartupFocus.RAIL)
    }
}

/**
 * 左侧一级导航。
 *
 * 选中态**完全由皮肤决定**（[AppTheme] 的 `navSelectedFill` / `navSelectedText`）：
 * 影院皮肤是"表面色高亮 + 主色文字"，经典皮肤是"主色填充 + 白字"。
 * 这里一行 `if (皮肤 == ...)` 都不写 —— 换肤靠换数值，不靠换代码。
 */
@Composable
private fun NavRail(
    /**
     * 侧栏当前显示哪些项、什么顺序（用户在设置里配的，见 `NavTabPicker`）。
     *
     * ★ 2026-09-30 之前这里写的是 `NavTab.entries` —— 枚举即界面。
     * 少爷第 8 条要求"侧栏也能配"之后，**枚举只提供"有哪些项"，
     * "显示哪些、什么顺序"由用户定**，所以必须显式传进来。
     */
    tabs: List<NavTab>,
    current: NavTab,
    onSelect: (NavTab) -> Unit,
    selectedRequester: FocusRequester?,
    settingsFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val theme = AppTheme.current
    val navigation = LocalNavigationFocus.current

    /*
     * ★ 侧栏的显式焦点链。
     *
     * ## 为什么必须显式写出来（2026-09-29 模拟器实测）
     *
     * 「上 / 下」在侧栏里会**跳出侧栏**：
     * 焦点在「动态」（y≈285）时按「上」，落的不是「影视」（y≈185），
     * 而是内容区顶部的「推荐」子标签（y≈56）—— 因为 Compose 默认的二维焦点搜索
     * 只在算直线距离，而子标签那一行又宽又靠上，赢过了侧栏里真正的邻居。
     *
     * 后果：用户想从「历史」走到「首页」，每按一次「上」都跑到内容区里，
     * 得先按「左」再找回侧栏 —— 而侧栏是全局导航，走不通等于导航废掉。
     *
     * 这是 `docs/13` §8 里"声明式焦点图还没做"那条待办的**第一个真正需要的场景**：
     * 默认搜索在"两个候选离得差不多远"的时候会选错，只能显式指定。
     *
     * 侧栏是纵向的，所以只需要钉住 `up` / `down`：
     * 到头的项**指向自己**（而不是放行），否则按到头又会弹到内容区去。
     * `left` 同理 —— 侧栏左边没有别的东西，钉住自己比让它跑到某个看不见的地方强。
     *
     * 进 / 出侧栏（`right`）**故意不钉**：那里几何搜索的表现是对的
     * （侧栏在左、内容在右，任何一项往右都该回内容区），钉死反而会限制住。
     */
    val itemRequesters = remember(tabs) { List(tabs.size) { FocusRequester() } }

    Column(
        modifier = modifier
            .onFocusChanged { navigation?.railFocused = it.hasFocus }
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .background(theme.navBackground)
            /*
             * ★ 2026-09-30 实测修正：**9 项在 1080p 电视上会溢出屏幕**。
             *
             * 在 `1920×1080 @ 320dpi`（= 少爷目标设备的真实配置）下用
             * `ui_probe.py tree` 量出来的 bounds：
             * ```
             *   首页 [20,226]  影视 [20,326]  动态 [20,426]  历史 [20,526]
             *   直播 [20,626]  关注 [20,726]  搜索 [20,826][156,891]  ← 底部已被裁
             *   我的 [0,0][0,0]                      ← 完全在屏幕外
             *   设置 [0,0][0,0]                      ← 完全在屏幕外
             * ```
             * 每项 stride 100px，第 9 项底部要 `226 + 900 = 1126px`，屏幕只有 1080px。
             *
             * **后果比"不好看"严重得多**：焦点能走到那两项（焦点链是写死的），
             * 但**屏幕上什么都看不见** —— 用户在遥控器上按「下」，画面纹丝不动，
             * 而「设置」是唯一能改解码器 / 换皮肤 / 清缓存的地方。等于功能被锁死。
             *
             * 修法选**压缩自身尺寸**而不是加滚动条，理由：
             *  - 侧栏是全局导航，9 项"一屏看全"是它的设计意图，能装下就不该让人滚；
             *  - 只动侧栏自己的两个数值，**不碰全局 typography**，其他页面零影响。
             *
             * 数值来自两次实测定档：第一次压到 8dp/3dp 后「我的」回来了、
             * 但「设置」仍被裁到只剩 11px（实测可视区高 **891px ≈ 445dp**，
             * 比 540dp 设计值小 —— 横屏手机按宽度缩放后高度会缩水）。
             * 再压一档到 6dp/2dp：每项 `2×6 + 22 + 2 = 36dp`，9 项 324dp + 上下 12dp = 336dp，
             * 对 445dp 的可视区有 100dp 以上余量。
             */
            .padding(vertical = RAIL_V_PADDING),
        verticalArrangement = Arrangement.spacedBy(RAIL_ITEM_GAP),
    ) {
        tabs.forEachIndexed { index, item ->
            val isCurrent = item == current
            val self = itemRequesters[index]
            NavItem(
                icon = item.icon,
                desc = item.label,
                selected = isCurrent,
                onClick = {
                    // 焦点留在侧栏上，别跳进内容区（少爷 2026-09-30）
                    if (!isCurrent) onSelect(item)
                },
                modifier = Modifier
                    .onFocusChanged {
                        if (it.isFocused && navigation?.pendingStartup == null && navigation?.confirmTabs == false && !isCurrent) onSelect(item)
                    }
                    .padding(horizontal = 8.dp)
                    /*
                     * 焦点链。两件事都必须在 `clickable` **之前** ——
                     * `focusRequester` / `focusProperties` 认的是"它后面最近的那个焦点目标"，
                     * 而焦点目标是 `NavItem` 内部的 `clickable`。
                     */
                    .focusRequester(self)
                    .focusProperties {
                        up = if (index > 0) itemRequesters[index - 1] else self
                        down = if (index < tabs.size - 1) itemRequesters[index + 1] else self
                        left = self
                        if (isCurrent && current == NavTab.SETTINGS) right = settingsFocus
                    }
                    .then(
                        // 认领 requester：让"内容区没东西可聚焦"的页面有地方落脚
                        if (isCurrent && selectedRequester != null) {
                            Modifier.focusRequester(selectedRequester)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/**
 * 侧栏的尺寸。**2026-09-29 按少爷要求重定**：
 * > 侧边栏不要太宽，就以图标绘制的大小稍微扩大一点，
 * > 做成大小与图标相当的方形圆角。不要做成长条状的。
 *
 * 所以每一项是**正方形 + 圆角**（不是铺满侧栏宽度的长条），
 * 侧栏宽度 = 方块宽 + 左右各 8dp。
 *
 * ```
 * 竖向合计 = 14×2（上下内衬）+ 8×44（8 个方块）+ 7×6（间隔）= 422dp ≤ 540dp ✅
 * 富余 118dp —— 比原来 9 项挤到 336dp/445dp 的处境宽松得多（去掉「关注」+ 去掉文字标签两层收益）
 * ```
 */
private val RAIL_WIDTH = 52.dp
private val RAIL_ITEM_SIZE = 40.dp
private val RAIL_ITEM_GAP = 6.dp
private val RAIL_V_PADDING = 14.dp
private val RAIL_ICON_SIZE = 24.dp

/**
 * Tab → 图标。
 *
 * `when` 里**不留 `else`** —— 和 `AppShell` 的 `when (tab)` 同一个意图：
 * 以后往 [NavTab] 加第 9 项，这里立刻编译不过，逼着当场给它配图标，
 * 而不是让用户看到一个空方块。
 */
private val NavTab.icon: ImageVector
    get() = when (this) {
        NavTab.HOME -> RailIcons.Home
        NavTab.CINEMA -> RailIcons.Cinema
        NavTab.DYNAMIC -> RailIcons.Dynamic
        NavTab.HISTORY -> RailIcons.History
        NavTab.LIVE -> RailIcons.Live
        NavTab.SEARCH -> RailIcons.Search
        NavTab.MINE -> RailIcons.Mine
        NavTab.SETTINGS -> RailIcons.Settings
    }

/**
 * 一个导航项：**正方形圆角块 + 居中图标**（少爷要求，不是长条）。
 *
 * 不用 [top.bilitv.ui.components.TvCard]：TvCard 的焦点态是"放大 + 描边"，
 * 那是给**卡片**设计的（放大是为了看清你选中了哪张封面）。
 * 侧栏项靠"整块变色"表达选中更清楚，而且它紧贴屏幕左边缘，
 * 放大一圈会被裁掉 —— 用了 TvCard 反而要额外去治这个问题。
 */
@Composable
private fun NavItem(
    icon: ImageVector,
    /**
     * 语义名。**没有文字标签之后，读屏和 `tools/ui_probe.py` 全靠它** ——
     * 不给的话侧栏在结构化焦点数据里就是一堆没有名字的方块，
     * 探针读不出"焦点到了哪一项"，自动验收会直接瘫掉。
     */
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AppTheme.current
    var focused by remember { mutableStateOf(false) }

    /*
     * ★ 2026-09-30 改：**底色只给焦点，不给"当前页"**。
     *
     * 依据是设计原型 A 的一条硬规则（`docs/32` §四）：
     * > **整个界面唯一有底色的东西，就是你正看着的那个。**
     *
     * 原来这里是"选中就铺一块灰底（`navSelectedFill`）"，等于**常驻一块底色** ——
     * 屏幕上同时有两个"有底色的东西"，那条规则就废了。
     * 现在改成：
     * ```
     * 常态      无底色，图标 navText
     * 当前页    无底色，图标 textPrimary（更亮）
     * 焦点      有底色(focusFill) + 图标 primary + 放大 1.07
     * ```
     * 三个状态靠**图标明度 + 焦点底色**区分，一个底色都不多给。
     */
    val fill by animateColorAsState(
        targetValue = if (focused) theme.focusFill else Color.Transparent,
        label = "navItemFill",
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            focused -> theme.primary
            selected -> theme.textPrimary
            else -> theme.navText
        },
        label = "navItemIcon",
    )

    Box(
        modifier = modifier
            .size(RAIL_ITEM_SIZE)
            .clip(RoundedCornerShape(RAIL_ITEM_CORNER))
            .background(fill)
            // 三态变色（选中 > 焦点 > 常态）用不了 focusRing 的"垫色+描边"表达，
            // 但焦点同步走统一入口 observeFocus，不再手抄 onFocusChanged。
            .observeFocus { focused = it }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = iconColor,
            modifier = Modifier.size(RAIL_ICON_SIZE),
        )
    }
}

/** 方块圆角。14dp 对 44dp 的方块 ≈ 32% —— 和卡片圆角同一个视觉家族。 */
private val RAIL_ITEM_CORNER = 14.dp

/**
 * 还没做的页面 —— **2026-09-29 已删除**。
 *
 * 它原来给侧栏里还没落地的 Tab 兜底，说一句「这一页排在后面几轮」。
 * 侧栏 9 项现在全部落地了，所以它没有调用方了。
 *
 * 为什么不留着"以防万一"：留着就意味着 `when (tab)` 里还得写一个 `else`，
 * 而那个 `else` 会把"你加了新 Tab 但忘了做页面"这件事**藏起来** ——
 * 用户会看到一个空白页（遥控器上等于死键），而编译器一声不吭。
 * 删掉之后新增 Tab 会直接编译不过，这是我们要的效果。
 */
