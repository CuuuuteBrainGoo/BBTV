package top.bilitv.ui.home

import top.bilitv.ui.components.verticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import top.bilitv.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.bilitv.ui.components.FeedCard
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.SectionTabBar
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.components.feedMetaLine
import top.bilitv.ui.theme.gridCells
import top.bilitv.ui.components.LoadFeedback
import top.bilitv.ui.theme.nearEnd
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.ui.RefreshBus
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import top.bilitv.data.model.FeedItem

/**
 * 首页 —— **纯缩略图网格**（2026-09-29 深夜，按少爷要求第二次改回纯网格）。
 *
 * ```
 * ┌──────────────────────────────────────────────────────┐
 * │ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢                  ← 侧栏（AppShell，纯图标）│
 * ├──────────────────────────────────────────────────────┤
 * │ [推荐] 热门  每周必看            ← 子标签栏（本文件）   │
 * ├──────────────────────────────────────────────────────┤
 * │ ┌────┐ ┌────┐ ┌────┐ ┌────┐   ← 4 列网格，缩略图直贴   │
 * │ └────┘ └────┘ └────┘ └────┘     无卡片底、无外边距      │
 * └──────────────────────────────────────────────────────┘
 * ```
 *
 * ## ★ 2026-09-29 深夜：大封面**已删除**（这是第二次"加进去又拿掉"）
 *
 * 时间线要记清楚，免得第三次又有人加回来：
 *
 * 1. 最初是"Banner + 横滑 + 网格"三段式 → 砍成**纯网格**
 *    （理由：遥控器用户要一眼看到足够多的东西，不该被一张大图挡住）；
 * 2. 本轮早些时候又加回一件主视觉（理由：少爷说首页"灰扑扑"）；
 * 3. **当晚少爷看到真机后明确要求删掉** ——
 *    原话：「**首页里那个大封面不要了，首页里只要视频卡片就行了**」。
 *
 * **所以现在就是纯网格，别再"借影院语言"加回来了。**
 * 第 2 步那条理由（"灰扑扑"）已经被第 3 步覆盖：
 * 少爷看过带主视觉的实物之后选择不要 —— **实物反馈 > 文字描述**。
 * 影院语言（大图 + 双层渐变）仍然用在**影视页和详情页**，那两处没被否。
 *
 * ## 卡片仍是「缩略图直贴」
 *
 * 没有卡片底色、没有外边距，播放量和时长压在图上的小药丸里，一屏 4 列。
 *
 * ## "热门"仍是子标签
 *
 * 它从侧栏一级入口降级成首页的子标签，并新增"每周必看"。三个数据源都是探针实测过的（见 `docs/15`）。
 *
 * 数据和缓存搬到了同包的 `HomeViewModel.kt`（本文件因此保持在 300 行以内）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    /** 点一张 PGC 卡（番剧/影视）→ 去剧集详情页。**PGC 没有 bvid，走 seasonId**。 */
    onOpenSeason: (Long) -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    DisposableEffect(vm) { onDispose { vm.stopLoading() } }
    val theme = AppTheme.current

    val sections = vm.sections
    val tab = vm.section
    val tabIndex = sections.indexOf(tab).coerceAtLeast(0)

    /*
     * 进页面时：**重新读一次用户配置**，再加载。
     *
     * 为什么要 sync：用户在设置页改了分区顺序/开关，回到首页要立刻看到新的。
     * VM 活得比页面久（切 Tab 不会重建它），所以不能只在构造时读一次配置。
     * `LaunchedEffect(Unit)` 会在"切走再切回来"时重新跑 —— 因为那一页会离开组合。
     */
    val gridState = rememberLazyGridState()
    val firstCard = remember { FocusRequester() }
    val retryButton = remember { FocusRequester() }

    /*
     * 每个标签各自记住"上次焦点在哪张卡"，回来时还落回它。
     *
     * ★ 存 **bvid 不存下标**：推荐流每次刷新顺序会变，下标会错位到别的视频上。
     * ★ 按标签分开存：从"热门"切到"推荐"再切回来，焦点应该回到上次那一条，
     *   而不是被另一个标签的记忆盖掉。
     */
    val lastFocused = rememberSaveable(saver = mapSaver(
        save = { it.toMap() },
        restore = { values -> mutableStateMapOf<String, String>().apply {
            values.forEach { (key, value) -> if (value is String) put(key, value) }
        } },
    )) { mutableStateMapOf<String, String>() }
    fun viewport(): HomeViewport {
        val focused = lastFocused[tab.id]?.takeIf { key -> gridState.layoutInfo.visibleItemsInfo.any { it.key == key } }
        return HomeViewport(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset,
            focused ?: vm.items.getOrNull(gridState.firstVisibleItemIndex)?.cardKey())
    }
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val sourceChanged = vm.syncSections()
        if (!entered) {
            entered = true
            vm.refresh(viewport())
        } else if (vm.autoRefresh || sourceChanged || vm.needsFirstPage) vm.refresh(viewport())
    }

    /*
     * 遥控器菜单键刷新。
     *
     * 外壳（`AppShell`）只负责"喊一声"（`RefreshBus.request()`），
     * 谁在被显示谁自己听着 —— 外壳不该知道每个页面怎么刷新。
     * `tick == 0` 是初始值，不刷；切成别的 Tab 时这一页会离开组合，正好不会再响应。
     */
    var lastRefreshRequest by remember { mutableIntStateOf(RefreshBus.tick) }
    LaunchedEffect(RefreshBus.tick) {
        if (RefreshBus.tick != lastRefreshRequest) {
            lastRefreshRequest = RefreshBus.tick
            vm.refresh(viewport())
        }
    }


    val list = vm.items

    /*
     * 无限加载：焦点/滚动快到底时自动追加下一批。
     *
     * ★ 用 `snapshotFlow` 观察"最后一个可见项的下标"，**不是** `remember { derivedStateOf }`。
     *   `docs/99` §C 记过那个坑：`derivedStateOf` 里捕获一次性求值的变量 =
     *   得到一个永远不变的值。这里读的是 `gridState.layoutInfo`（每次布局都变），
     *   而且放在 flow 的 lambda 里读，所以每次滚动都会被重新求值。
     */
    LaunchedEffect(gridState, list.size) {
        snapshotFlow { gridState.layoutInfo.nearEnd(list.size) }
            .collect { near ->
                if (near) vm.loadMore()
            }
    }

    /*
     * ★ 2026-09-29 少爷要求：**「首页里那个大封面不要了，首页里只要视频卡片就行了」**。
     *
     * 所以这里**不再抽第一条做影院主视觉** —— 整个列表都是卡片。
     * 连带收益：少一层"第一张卡从哪开始"的心智负担，也没有 `drop(1)` 错位的风险。
     * 主视觉相关的高度预算（`heroHeight` 那些）从这一页整体移除，不再参与折线计算。
     */
    val cards = list

    /*
     * 记住的是**这一条卡的稳定标识**：UGC 用 bvid、PGC 用 seasonId。
     * ⚠️ 不能统一用 bvid —— PGC 卡的 bvid 是空串，那样所有 PGC 卡会共用同一个 key。
     */
    val remembered = lastFocused[tab.id]?.takeIf { key -> cards.any { it.cardKey() == key } }
    val defaultKey = remembered ?: cards.firstOrNull()?.cardKey()

    Column(modifier = Modifier.fillMaxSize()) {
        SectionTabBar(
            // ★ 选项卡来自**用户配置的分区名单**（顺序即显示顺序）。见 HomeSection 的说明。
            labels = sections.map { stringResource(it.labelRes) },
            selectedIndex = tabIndex,
            contentFocusRequester = if (list.isNotEmpty()) firstCard else retryButton.takeIf { !vm.loading },
            /*
             * ★ 2026-09-30 少爷的规则：
             * - 点的就是**当前已选中**的那个 → **刷新**（重新推荐一批）
             * - 点的是**别的**那个 → 正常切过去（会重新加载该标签）
             *
             * 为什么"点当前项"要单独分一支：切标签和"重新推荐一批"是两件事，
             * 前者换数据源、后者换批次。合成一个动作就有一个说不清：
             * 点当前项到底该不该重新请求。
             */
            onSelect = { i ->
                if (i == tabIndex) vm.refresh(viewport()) else vm.show(sections[i])
            },
            modifier = Modifier.padding(
                start = theme.screenPadding,
                end = theme.screenPadding,
                top = 8.dp,
                bottom = 10.dp,
            ),
        )

        /*
         * 下拉刷新。
         *
         * ⚠️ 电视遥控器上**够不到**这个手势（没有触摸），它是给**手机**用的。
         * 但成本极低（material3 自带），而少爷在用手机测，所以留着。
         * `isRefreshing` 只在"已经有内容"时才驱动那个转圈 ——
         * 首屏本来是空的，那时全屏转圈由下面的 `vm.loading` 分支负责，两个转圈会打架。
         */
        if (tab == HomeSection.RECOMMEND && vm.recommendNotice != null) Text(vm.recommendNotice!!,
            color = theme.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = theme.screenPadding, vertical = 4.dp))
        PullToRefreshBox(
            isRefreshing = vm.loading && list.isNotEmpty(),
            onRefresh = { vm.refresh(viewport()) },
            modifier = Modifier.fillMaxSize(),
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                (vm.loading || vm.loadingMore) && list.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                list.isEmpty() -> EmptyState(
                    message = vm.error ?: stringResource(if (vm.hasMore) R.string.home_empty_page else R.string.empty_content),
                    onRetry = { if (vm.hasMore && vm.error == null) vm.loadMore() else vm.retry() },
                    retryRequester = retryButton,
                    action = stringResource(if (vm.hasMore && vm.error == null) R.string.action_continue_loading else R.string.action_reload),
                )

                else -> LazyVerticalGrid(
                    columns = theme.gridCells(),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = theme.screenPadding,
                        end = theme.screenPadding,
                        bottom = theme.screenPadding,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
                    verticalArrangement = Arrangement.spacedBy(theme.rowGap),
                    modifier = Modifier.fillMaxSize().verticalScrollbar(gridState),
                ) {
                    /*
                     * ⛔ key 必须用 cardKey()，**不能用 bvid**：
                     * PGC 卡的 bvid 是空串，20 张卡的 key 会全是 "" →
                     * `LazyGrid` 抛 `IllegalArgumentException: Key "" was already used`
                     * 直接闪退（2026-09-30 实测踩到，番剧分区一进去就崩）。
                     */
                    gridItems(cards, key = { it.cardKey() }) { item ->
                        FeedCard(
                            item = item,
                            /*
                             * PGC 卡（番剧/影视）**没有 bvid** —— 它的去处是剧集详情页，
                             * 走 seasonId。这是"一个网格里装两种内容"的唯一分岔点，
                             * 放在点击这一处比在数据层分两套类型便宜得多（见 FeedItem.seasonId 的说明）。
                             */
                            onClick = {
                                if (item.seasonId > 0L) onOpenSeason(item.seasonId) else onOpen(item.bvid)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            onFocused = { if (it) lastFocused[tab.id] = item.cardKey() },
                            focusRequester = firstCard.takeIf { item.cardKey() == defaultKey },
                        )
                    }
                    if (vm.loadingMore || vm.error != null) item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadFeedback(vm.loadingMore, vm.error, vm::retry)
                    }
                    else if (vm.hasMore) item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                        TvCard(onClick = vm::loadMore, focusedScale = 1f, contentDescription = stringResource(R.string.action_load_more),
                            modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_load_more), color = theme.primary, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }
            if (vm.canBacktrack) TvCard(onClick = { vm.backtrack() }, focusedScale = 1f,
                contentDescription = stringResource(R.string.action_previous_recommendations),
                modifier = Modifier.align(Alignment.BottomEnd).padding(theme.screenPadding)) {
                Text(stringResource(R.string.action_previous_batch), color = theme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
        }
    }

    /*
     * ★ 下拉刷新之后**再补一次焦点复位**（2026-09-30 实测补的）。
     *
     * 实测现象：**菜单键刷新后焦点正确落在第一格**，但**下拉刷新后焦点是空的**，
     * 要按一下方向键才回来。
     *
     * 真因：下拉是**触摸手势**，手指接触屏幕会把当前焦点清掉；
     * 而下面那次 `RequestFocusOnAppear` 在"数据回来"的瞬间就执行了 ——
     * **那时手指还没离开**，所以刚要到手的焦点又被触摸清掉。
     *
     * 修法：等触摸结束（约 350ms，够手势收尾和指示器回弹）再要一次。
     * ⚠️ 只在 `refreshCount` 变化时触发 —— **不能挂在 `list.size` 上**，
     * 否则每次"往下滚加载更多"都会把焦点拽回第一格，那是灾难性的。
     */
    var focusKick by remember { mutableIntStateOf(0) }
    var handledRefresh by rememberSaveable { mutableIntStateOf(vm.refreshCount) }
    LaunchedEffect(vm.refreshCount) {
        if (vm.refreshCount != handledRefresh) {
            handledRefresh = vm.refreshCount
            lastFocused.remove(tab.id)
            delay(TOUCH_SETTLE_MS)
            // 回到顶部：少爷要求"刷新后焦点回到**第一行第一列**"，
            // 所以刷新语义上就是"回到最上面重新看"，滚动位置也一并归零。
            runCatching { gridState.scrollToItem(0) }
            focusKick++
        }
    }

    /*
     * 内容就绪就把焦点送到第一张卡；空态则送到「重试」。
     *
     * ★ key 里带上 `vm.refreshCount` + `focusKick`：**每次刷新都换一个新 key**，
     *   `RequestFocusOnAppear` 才会重新要一次焦点。
     *   只写 `list.isNotEmpty()` 的话，刷新前后这个布尔值没变、焦点不会被搬回去，
     *   而少爷要求 **刷新后焦点回到第一行第一列**。
     */
    /*
 * ⛔ 2026-09-30：key 里**去掉了 `tab.id`**。原来带上它，切分区时 key 一变就重新抢焦点 ——
 * 那正是少爷反馈的"一点 OK 焦点就跳到中间区"。
 * 现在只在**刷新**（`refreshCount` / `focusKick` 变化）时抢，那是他明确要过的：
 * "刷新后焦点回到第一行第一列"。
 */
    var handledRestore by rememberSaveable { mutableIntStateOf(vm.restoreCount) }
    LaunchedEffect(vm.restoreCount) {
        if (handledRestore == vm.restoreCount) return@LaunchedEffect
        handledRestore = vm.restoreCount
        vm.restoredViewport?.let { saved ->
            val index = saved.index.coerceIn(0, (cards.size - 1).coerceAtLeast(0))
            val key = saved.focusedKey?.takeIf { wanted -> cards.any { it.cardKey() == wanted } }
                ?: cards.getOrNull(index)?.cardKey()
            if (key != null) lastFocused[tab.id] = key
            gridState.scrollToItem(index, saved.offset.coerceAtLeast(0))
            androidx.compose.runtime.withFrameNanos { }
            runCatching { firstCard.requestFocus() }
        }
    }
    RequestFocusOnAppear(firstCard, "${vm.refreshCount}-$focusKick")
    if (!vm.loading && list.isEmpty()) RequestFocusOnAppear(retryButton, vm.error)
}

/**
 * 一条卡的**稳定标识**：UGC 用 bvid，PGC 用 seasonId。
 *
 * 为什么不能用 bvid 一把梭：**PGC 卡的 bvid 是空串**（见 `FeedItem.seasonId`），
 * 拿它当 key 的话所有 PGC 卡会撞在一起 —— 焦点记忆会错位，`LazyGrid` 的 key 也会崩。
 */
private fun FeedItem.cardKey(): String =
    if (bvid.isNotBlank()) "b:$bvid" else "s:$seasonId"

/** 下拉刷新后、补一次焦点复位前要等多久（让触摸手势真正结束）。 */
private const val TOUCH_SETTLE_MS = 600L

/**
 * 空态：一句话 + 一个「重新加载」。
 *
 * 空态点明当前分区；成功的空列表不推断为网络或接口故障。
 * "谁空了"决定了用户该不该重试、还是该换个标签。
 */
@Composable
private fun EmptyState(
    message: String,
    onRetry: () -> Unit,
    retryRequester: FocusRequester,
    action: String,
) {
    val theme = AppTheme.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = TextStyle(fontSize = AppType.Body2),
            color = theme.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TvCard(
            onClick = onRetry,
            modifier = Modifier
                .focusRequester(retryRequester)
                .padding(top = 24.dp),
            background = theme.surfaceHigh,
            contentDescription = action,
        ) {
            Text(
                text = action,
                style = TextStyle(fontSize = AppType.Body2),
                color = theme.textPrimary,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
            )
        }
    }
}
