package top.bilitv.ui.dynamic

import top.bilitv.ui.components.verticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.rememberScrollState
import top.bilitv.ui.components.scrollWithScrollbar
import androidx.compose.ui.res.stringResource
import top.bilitv.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.bilitv.data.model.CODE_NOT_LOGGED_IN
import top.bilitv.data.model.DynamicItem
import top.bilitv.ui.components.FilledActionButton
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.theme.gridCells
import top.bilitv.ui.components.LoadFeedback
import top.bilitv.ui.theme.nearEnd
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import top.bilitv.ui.components.FeedCard
import top.bilitv.data.model.FeedItem

/**
 * 动态 —— 关注的人发的**能播的**东西，一条一条往下排。
 *
 * ```
 * ┌──────────────────────────────────────────────────────────────┐
 * │ 动态                                                          │
 * │ 已加载 12 条 · 图文动态不在这里（只留能播的）                 │
 * ├──────────────────────────────────────────────────────────────┤
 * │ [◯] 名字  投稿了视频 · 3小时前                                │
 * │ ┌────────┐ 标题标题标题标题标题标题                           │
 * │ │ 缩略图 │ 正文正文正文……                                     │
 * │ │  12:34 │ 播放 12.3万 · 弹幕 46 · 点赞 1.2千                │
 * │ └────────┘                                                   │
 * └──────────────────────────────────────────────────────────────┘
 * ```
 *
 * ## ⛔ 这是本 App 里**唯一一个登录才能看的页面**，而且现在也验不了
 *
 * 实测（`tools/probe_dynamic.py`）：动态流这一族接口游客态**全部** `-101`
 * （`/feed/all`、`type=video`、`/all/update`、桌面路径、以及**带 WBI 签名的对照组**
 * —— 结果一模一样）。所以：
 *
 * - 少爷（2026-09-29）明确说过「所有需要登陆的部分，可以先做出来，不用验证，
 *   等我明天醒了再登陆测试」，这一页就是照这个做的；
 * - 也因此**解析层对缺失字段更保守**（见 `DynamicItem` 的说明），
 *   宁可少显示一条也不编值；
 * - 登录后第一件事：`python tools/probe_dynamic.py --cookie`，
 *   拿真实响应核 `Parsers.parseDynamicFeed` 的每个字段名。
 *
 * ## 为什么是"竖排大行"而不是照首页那样做网格
 *
 * 首页那种 4 列网格适合**只有封面和标题**的内容。动态多两样东西：
 * 一段正文明文（转发语/简介）和一行计数 —— 塞进网格格子里就只能砍掉，
 * 而"这一条说了什么"恰恰是动态和普通视频列表的区别所在。
 * 参考的三款客户端里，动态也都是竖排的。
 *
 * ## 三种"没内容"要说三句（外加登录过期，是第四句）
 *
 * | 情况 | 判据 | 说什么 | 按钮 |
 * |---|---|---|---|
 * | 没登录 | `isLoggedIn()` 为假（**并且不发请求**） | 动态要登录 | 「去登录」 |
 * | 登录过期 | 发了请求，服务端回 `-101` | 登录过期了 | 「重新登录」 |
 * | 真没有能播的 | `code==0` 但一条视频都没解析出来 | 最近没有能播的 | 「去首页看看」 |
 * | 请求失败 | 传输异常 / 结构不认识 | 拿不到动态 | 「重新加载」 |
 *
 * ★ 第 2 行是这一页**比别的列表页多出来的一种**：`isLoggedIn()` 为真
 * 但服务端回 `-101`（凭证被吊销/过期）是真实存在的状态，
 * 而它和"网络不通"要做的操作完全不同。判断它只能靠**响应码** ——
 * 这就是 [top.bilitv.data.model.DynamicFeed.code] 必须带回来的理由。
 *
 * @param onOpenVideo 点一条 → 进详情页。**只传 bvid**：
 *   动态接口的 `major.archive` **没有 `cid`**（官方文档确认），
 *   而详情页会自己用 `view` 接口补上，所以这里不需要它。
 * @param onNeedLogin 去扫码登录。
 * @param onGoHome 空列表时那个按钮的去处。**它同时是这一页空着时唯一的焦点落点**
 *   （`docs/11`：历史页出现过的死键事故 —— 空态没有任何可聚焦元素，遥控器全废）。
 */
@Composable
fun DynamicScreen(
    onOpenVideo: (String) -> Unit,
    onNeedLogin: () -> Unit,
    onGoHome: () -> Unit,
) {
    val vm: DynamicViewModel = viewModel()
    val theme = AppTheme.current
    LaunchedEffect(Unit) { vm.load() }
    DisposableEffect(vm) { onDispose { vm.stopLoading() } }
    top.bilitv.ui.OnRefreshRequest { if (!vm.loading) vm.reload() }

    val firstRow = remember { FocusRequester() }
    val emptyButton = remember { FocusRequester() }
    val list = vm.items
    val listState = rememberLazyGridState()

    /*
     * 滚到底部附近就自动要下一页。
     *
     * `index >= 总数 - 3` 留了三行的提前量 —— 等真正滚到最后一行才发请求，
     * 用户会在最后一行停住等网络往返。
     *
     * ## ⛔ 这里踩过一个坑，写下来免得再犯
     *
     * 第一版写的是 `remember { derivedStateOf { last >= list.size - 3 } }` ——
     * **`list` 是外面那个 `val list = vm.items`，被 `remember` 的 lambda 捕获了初值**。
     * 首帧 `items` 是空的，于是 `list.size` 永远等于 0，
     * `last >= -3` 恒真 → 每翻一页 `list.size` 变化就再触发一次
     * → **一路把服务端所有页都拉完**。单测看不出来（它是 UI 逻辑），
     * 编译也不会报错，只有真机上会发现"网络一直在转"。
     *
     * 所以这里**只读 `listState.layoutInfo`**（它本身就是 State，能被正确追踪），
     * 不捕获任何一次性求值的变量。
     */
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && info.nearEnd(info.totalItemsCount)
        }
    }
    // 把 `list.size` 放进 key：每次成功追加一页就重新判一次，
    // 否则"已经很靠底"这个状态不变时 effect 不会重跑，翻页会在第二页卡住。
    LaunchedEffect(shouldLoadMore, list.size) { if (shouldLoadMore) vm.loadMore() }

    Column(modifier = Modifier.fillMaxSize()) {
        DynamicHeader(loaded = list.size, ready = vm.state == DynamicState.READY)

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                vm.loading && list.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                list.isEmpty() -> DynamicNotice(
                    state = vm.state,
                    olderAvailable = vm.hasMore && vm.state == DynamicState.READY,
                    loadingMore = vm.loadingMore,
                    failedPage = vm.moreError != null || vm.refreshError != null,
                    onAction = {
                        when {
                            vm.state == DynamicState.NEED_LOGIN || vm.state == DynamicState.EXPIRED -> onNeedLogin()
                            vm.refreshError != null || vm.state == DynamicState.ERROR -> vm.reload()
                            vm.hasMore -> vm.loadMore()
                            else -> onGoHome()
                        }
                    },
                    actionLabel = when {
                        vm.loadingMore -> stringResource(R.string.loading)
                        vm.state == DynamicState.NEED_LOGIN -> stringResource(R.string.action_sign_in)
                        vm.state == DynamicState.EXPIRED -> stringResource(R.string.action_sign_in_again)
                        vm.refreshError != null || vm.state == DynamicState.ERROR -> stringResource(R.string.action_reload)
                        vm.moreError != null -> stringResource(R.string.dynamic_retry_older)
                        vm.hasMore -> stringResource(R.string.dynamic_load_older)
                        else -> stringResource(R.string.action_browse_home)
                    },
                    requester = emptyButton,
                )

                /*
                 * ★ 2026-09-30 少爷要求：「动态/历史里的视频也应该用类似首页的视频卡展示」。
                 * 所以从"一行一条的大行"改成**和首页同一个网格**（`FeedCard`）。
                 */
                else -> LazyVerticalGrid(
                    columns = theme.gridCells(),
                    state = listState,
                    contentPadding = PaddingValues(theme.screenPadding),
                    horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
                    verticalArrangement = Arrangement.spacedBy(theme.rowGap),
                    modifier = Modifier.fillMaxSize().verticalScrollbar(listState),
                ) {
                    // 合并时已按稳定标识去重，追加旧动态时保留现有卡片与焦点。
                    gridItemsIndexed(list, key = { _, item -> item.id.ifBlank { item.bvid } }) { index, item ->
                        FeedCard(
                            item = item.toFeedItem(),
                            onClick = { onOpenVideo(item.bvid) },
                            modifier = Modifier.fillMaxWidth(),
                            // 焦点挂点**挂在第 0 条**上 —— 它永远在视口里、永远被组合，
                            // 不会重演设置页"挂点被顶出视口 → 连试 30 次全失败"那个 P0。
                            focusRequester = firstRow.takeIf { index == 0 },
                        )
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val theme0 = AppTheme.current
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 22.dp),
                        ) {
                            when {
                                vm.loading || vm.refreshError != null -> LoadFeedback(vm.loading, vm.refreshError, vm::reload)
                                vm.loadingMore -> {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.dynamic_loading_more),
                                        style = TextStyle(fontSize = AppType.Caption),
                                        color = theme0.textTertiary,
                                    )
                                }
                                vm.state == DynamicState.EXPIRED -> FilledActionButton(
                                    text = stringResource(R.string.dynamic_expired_action), onClick = onNeedLogin,
                                )
                                vm.moreError != null -> FilledActionButton(
                                    text = stringResource(R.string.dynamic_older_failed_action), onClick = { vm.loadMore() },
                                )
                                vm.hasMore -> FilledActionButton(
                                    text = stringResource(R.string.dynamic_load_older), onClick = { vm.loadMore() },
                                )
                                // 条数少的时候不报stringResource(R.string.end_of_list) —— 那反而像个错误提示
                                !vm.hasMore && list.size >= 8 -> Text(
                                    text = stringResource(R.string.end_of_list),
                                    style = TextStyle(fontSize = AppType.Caption),
                                    color = theme0.textTertiary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    RequestFocusOnAppear(firstRow, list.firstOrNull()?.bvid)
    RequestFocusOnAppear(emptyButton, !vm.loading && list.isEmpty())
}

@Composable
private fun DynamicHeader(loaded: Int, ready: Boolean) {
    val theme = AppTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = theme.screenPadding,
                end = theme.screenPadding,
                top = 8.dp,
                bottom = 12.dp,
            ),
    ) {
        Text(
            text = stringResource(R.string.nav_dynamic),
            style = TextStyle(fontSize = AppType.H1, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        /*
         * 这行小字不是装饰，是**解释"缺了什么"**。
         *
         * 我们把图文/纯文字动态全滤掉了（遥控器上点它们没反应）。
         * 不明说的话，用户看到"我明明发了动态，这里怎么没有"，会以为 App 坏了。
         * 明确告诉他"这是有意的"，比让他自己去猜强。
         */
        Text(
            text = if (ready) {
                stringResource(R.string.dynamic_loaded, loaded)
            } else {
                stringResource(R.string.dynamic_header_hint)
            },
            style = TextStyle(fontSize = AppType.Caption),
            color = theme.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 「没内容」时的说明。四种原因四句话（见 [DynamicScreen] 那张表）。
 */
@Composable
private fun DynamicNotice(
    state: DynamicState,
    olderAvailable: Boolean,
    loadingMore: Boolean,
    failedPage: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    requester: FocusRequester,
) {
    val theme = AppTheme.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp).scrollWithScrollbar(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (loadingMore) stringResource(R.string.dynamic_loading_older) else if (failedPage) stringResource(R.string.dynamic_failed)
                else if (olderAvailable) stringResource(R.string.dynamic_older_empty) else when (state) {
                DynamicState.NEED_LOGIN -> stringResource(R.string.dynamic_need_login)
                DynamicState.EXPIRED -> stringResource(R.string.dynamic_expired)
                DynamicState.EMPTY -> stringResource(R.string.dynamic_empty)
                DynamicState.ERROR -> stringResource(R.string.dynamic_unavailable)
                // 这两个**不该走到这里** —— 调用方只在"列表空且不在加载中"时才渲染这一屏。
                // 当表达式写 `when` 必须穷尽，所以留一句话而不是留空白：
                // 万一日后哪里改错了，屏幕上至少有一行字能指认现场。
                DynamicState.LOADING, DynamicState.READY -> stringResource(R.string.dynamic_reopen)
            },
            style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        Text(
            text = if (olderAvailable || failedPage) stringResource(R.string.dynamic_older_hint) else when (state) {
                DynamicState.NEED_LOGIN ->
                    stringResource(R.string.dynamic_need_login_hint)
                DynamicState.EXPIRED ->
                    stringResource(R.string.dynamic_expired_hint)
                DynamicState.EMPTY ->
                    stringResource(R.string.dynamic_empty_hint)
                DynamicState.ERROR ->
                    stringResource(R.string.dynamic_error_hint)
                DynamicState.LOADING, DynamicState.READY ->
                    stringResource(R.string.dynamic_reopen_hint)
            },
            style = TextStyle(fontSize = AppType.Body3, lineHeight = 22.sp),
            color = theme.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )

        /*
         * ★ 这个按钮不只是"方便"，它是**这一页空着时唯一的焦点落点**。
         * 历史页曾经因为空态没有任何可聚焦元素，导致遥控器全废（`docs/11`）。
         */
        FilledActionButton(
            text = actionLabel,
            onClick = onAction,
            modifier = Modifier
                .focusRequester(requester)
                .padding(top = 24.dp),
        )
    }
}

/**
 * 动态页的五种状态。
 *
 * 比关注页多一个 [EXPIRED]（登录过期）—— 见 `DynamicScreen` 的说明：
 * 那个状态只能靠**响应码**判，别的列表接口没有这一档。
 */
enum class DynamicState { LOADING, NEED_LOGIN, EXPIRED, EMPTY, ERROR, READY }

/**
 * 把一条动态转成一张**首页那样的卡**。
 *
 * ## 两个要当心的地方
 *
 * 1. **时长是字符串**（`durationText` = "04:18"），卡片要的是秒。
 *    所以要解析 —— 解析不出来就**给 0**（卡片会自动不画那个药丸），
 *    **不要编一个假的时长**（那正是 `docs/99` §C 那类事故）。
 * 2. **播放/弹幕可能是 null**（接口没给），转成 0 表示"没有这个数"，
 *    卡片会自己决定画不画。
 */
private fun DynamicItem.toFeedItem(): FeedItem = FeedItem(
    bvid = bvid,
    badge = badge,
    title = title,
    cover = cover,
    ownerName = ownerName,
    durationSec = parseDurationText(durationText),
    viewCount = play ?: 0L,
    danmakuCount = danmaku ?: 0L,
    pubDateSec = pubTs ?: 0L,
)

/**
 * "04:18" / "1:02:33" → 秒。解析不出来返回 0。
 *
 * 分段从右往左乘 60 的幂 —— 这样 `MM:SS` 和 `HH:MM:SS` 一套代码都能吃。
 */
private fun parseDurationText(text: String): Int {
    val parts = text.trim().split(':')
    if (parts.isEmpty() || parts.size > 3) return 0
    var sec = 0L
    var mul = 1L
    for (p in parts.reversed()) {
        val v = p.toIntOrNull() ?: return 0
        sec += v * mul
        mul *= 60
    }
    return sec.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
