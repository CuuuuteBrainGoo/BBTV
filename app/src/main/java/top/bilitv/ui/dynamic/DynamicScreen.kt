package top.bilitv.ui.dynamic

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import top.bilitv.BiliTvApp
import top.bilitv.data.model.CODE_NOT_LOGGED_IN
import top.bilitv.data.model.DynamicItem
import top.bilitv.data.model.mergeDynamicItems
import top.bilitv.ui.components.FilledActionButton
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.dynamicAuthorSuffix
import top.bilitv.ui.components.dynamicStatLine
import top.bilitv.ui.components.fixedScheme
import top.bilitv.ui.components.focusRing
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.util.AppLog
import androidx.compose.foundation.lazy.grid.GridCells
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
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    // 把 `list.size` 放进 key：每次成功追加一页就重新判一次，
    // 否则"已经很靠底"这个状态不变时 effect 不会重跑，翻页会在第二页卡住。
    LaunchedEffect(shouldLoadMore, list.size) { if (shouldLoadMore) vm.loadMore() }

    Column(modifier = Modifier.fillMaxSize()) {
        DynamicHeader(loaded = list.size, ready = vm.state == DynamicState.READY)

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                list.isEmpty() -> DynamicNotice(
                    state = vm.state,
                    onAction = when (vm.state) {
                        DynamicState.NEED_LOGIN, DynamicState.EXPIRED -> onNeedLogin
                        DynamicState.ERROR -> { { vm.reload() } }
                        else -> onGoHome
                    },
                    actionLabel = when (vm.state) {
                        DynamicState.NEED_LOGIN -> "去登录"
                        DynamicState.EXPIRED -> "重新登录"
                        DynamicState.ERROR -> "重新加载"
                        else -> "去首页看看"
                    },
                    requester = emptyButton,
                )

                /*
                 * ★ 2026-09-30 少爷要求：「动态/历史里的视频也应该用类似首页的视频卡展示」。
                 * 所以从"一行一条的大行"改成**和首页同一个网格**（`FeedCard`）。
                 */
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(theme.cardColumns),
                    state = listState,
                    contentPadding = PaddingValues(theme.screenPadding),
                    horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
                    verticalArrangement = Arrangement.spacedBy(theme.rowGap),
                    modifier = Modifier.fillMaxSize(),
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
                                vm.loadingMore -> {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = "正在取更多…",
                                        style = TextStyle(fontSize = AppType.Caption),
                                        color = theme0.textTertiary,
                                    )
                                }
                                vm.state == DynamicState.EXPIRED -> FilledActionButton(
                                    text = "登录已过期，重新登录", onClick = onNeedLogin,
                                )
                                vm.moreError != null -> FilledActionButton(
                                    text = "加载失败，重试更早动态", onClick = { vm.loadMore() },
                                )
                                vm.hasMore -> FilledActionButton(
                                    text = "加载更早动态", onClick = { vm.loadMore() },
                                )
                                // 条数少的时候不报"没有更多了" —— 那反而像个错误提示
                                !vm.hasMore && list.size >= 8 -> Text(
                                    text = "没有更多了",
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
            text = "动态",
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
                "已加载 $loaded 条 · 只显示能播的，图文动态不在这里"
            } else {
                "关注的人发了什么能播的东西，都在这里"
            },
            style = TextStyle(fontSize = AppType.Caption),
            color = theme.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 一条动态。
 *
 * 焦点态照 BT：垫一层半透明主色 + 主色描边，**不放大**。
 * 这一行有 180dp 高、横跨整个屏幕宽度，放大一圈两边都会被裁掉。
 */
@Composable
private fun DynamicRow(
    item: DynamicItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val theme = AppTheme.current
    val context = LocalContext.current

    val suffix = dynamicAuthorSuffix(item)
    val statLine = dynamicStatLine(item)
    val desc = buildList {
        add(item.ownerName)
        if (suffix.isNotBlank()) add(suffix)
        add(item.title)
        if (item.caption.isNotBlank()) add(item.caption)
        if (statLine.isNotBlank()) add(statLine)
    }.joinToString("，")

    Row(
        modifier = modifier
            // focusRequester 必须挂在 focusRing **之前**（它认的是后面最近的焦点目标）
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )
            .focusRing(contentDescription = desc, onClick = onClick)
            .padding(8.dp),
    ) {
        Box {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.cover.fixedScheme())
                    // 按实际显示尺寸解码，绝不原图解码（低内存的生命线）
                    .size(THUMB_W, THUMB_H)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(THUMB_W.dp, THUMB_H.dp)
                    .clip(RoundedCornerShape(theme.cardCorner))
                    .background(theme.surfaceHigh),
            )

            // 时长角标：接口没给就不画（不写 00:00）
            if (item.durationText.isNotBlank()) {
                Text(
                    text = item.durationText,
                    style = TextStyle(fontSize = AppType.Tiny),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.66f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }

            /*
             * 「转发」角标。
             *
             * 为什么必须有：转发的作者是**转发者**，但视频是**原作者**的 ——
             * 不标一下，用户会以为这个视频是转发者发的。
             */
            if (item.forwarded) {
                Text(
                    text = "转发",
                    style = TextStyle(fontSize = AppType.Tiny, fontWeight = FontWeight.Medium),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(theme.primary.copy(alpha = 0.88f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.ownerFace.fixedScheme())
                        .size(AVATAR_PX, AVATAR_PX)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(AVATAR)
                        .clip(CircleShape)
                        .background(theme.surfaceHigh),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.ownerName,
                    style = TextStyle(fontSize = AppType.Meta, fontWeight = FontWeight.Medium),
                    color = theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (suffix.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = suffix,
                        style = TextStyle(fontSize = AppType.Small),
                        color = theme.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = item.title,
                style = TextStyle(fontSize = AppType.CardTitle, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
                color = theme.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (item.caption.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.caption,
                    style = TextStyle(fontSize = AppType.Caption, lineHeight = 19.sp),
                    color = theme.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ★ 五个计数一个都没拿到时整行不画（`dynamicStatLine` 返回空串），见那个函数的说明
            if (statLine.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = statLine,
                    style = TextStyle(fontSize = AppType.Small),
                    color = theme.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 「没内容」时的说明。四种原因四句话（见 [DynamicScreen] 那张表）。
 */
@Composable
private fun DynamicNotice(
    state: DynamicState,
    actionLabel: String,
    onAction: () -> Unit,
    requester: FocusRequester,
) {
    val theme = AppTheme.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 130.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = when (state) {
                DynamicState.NEED_LOGIN -> "动态需要登录"
                DynamicState.EXPIRED -> "登录已经过期"
                DynamicState.EMPTY -> "最近没有能播的动态"
                DynamicState.ERROR -> "拿不到动态"
                // 这两个**不该走到这里** —— 调用方只在"列表空且不在加载中"时才渲染这一屏。
                // 当表达式写 `when` 必须穷尽，所以留一句话而不是留空白：
                // 万一日后哪里改错了，屏幕上至少有一行字能指认现场。
                DynamicState.LOADING, DynamicState.READY -> "状态异常，回到侧栏再进一次这一页"
            },
            style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        Text(
            text = when (state) {
                DynamicState.NEED_LOGIN ->
                    "动态是你关注的人发的东西，存在账号里，不登录读不到。扫码登录一次就行。"
                DynamicState.EXPIRED ->
                    "服务端说这张凭证不认了（换了密码、或者太久没用）。重新扫一次码就好，本机的记录不会丢。"
                DynamicState.EMPTY ->
                    "关注的 UP 主最近发的动态里没有视频。图文和纯文字动态不会出现在这里 —— " +
                            "这是有意的：遥控器上点它们没有任何反应，那比空着更让人恼火。"
                DynamicState.ERROR ->
                    "接口可能变了或网络不通。已经登录了还是这样，就是服务端的问题，看日志能有线索。"
                DynamicState.LOADING, DynamicState.READY ->
                    "这一屏本来不该出现，麻烦把日志发我。"
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

/** 缩略图按 16:9 解码。300×169 在 1080p 画布上够清楚，解码开销也小 */
private const val THUMB_W = 300
private const val THUMB_H = 169

/** 头像显示的 dp 尺寸 / 按它解码的像素尺寸（2 倍够 320dpi 的屏用） */
private val AVATAR = 28.dp
private const val AVATAR_PX = 56

/**
 * 动态页的五种状态。
 *
 * 比关注页多一个 [EXPIRED]（登录过期）—— 见 `DynamicScreen` 的说明：
 * 那个状态只能靠**响应码**判，别的列表接口没有这一档。
 */
enum class DynamicState { LOADING, NEED_LOGIN, EXPIRED, EMPTY, ERROR, READY }

/**
 * 动态页数据。
 *
 * ## 首屏要"多翻几页"才够看
 *
 * 一页 20 条动态里，视频投稿可能只占两三条（其余是图文/纯文字/专栏）。
 * 只取一页的话，用户进来看见的是一片稀稀拉拉 —— 所以首屏**自动往下翻**，
 * 直到攒够 [MIN_FIRST_PAGE] 条或者服务端说没有下一页，最多翻 [MAX_ROUNDS] 轮。
 *
 * 这是"解析层把非视频条目丢掉"的**配套代价**，不是额外优化：
 * 筛选在本地做，翻页就得自己管。
 */
class DynamicViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp

    var items by mutableStateOf<List<DynamicItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var moreError by mutableStateOf<Int?>(null)
        private set
    var state by mutableStateOf(DynamicState.LOADING)
        private set

    private var offset = ""

    /** 有没有请求在飞。首屏和翻页共用一个闸 —— 两边同时跑会把列表拼乱。 */
    private var busy = false
    private var generation = 0

    /**
     * 进页面时调用。
     *
     * 闸门的理由和关注页一样：侧栏切走再切回来会重新进组合，
     * `LaunchedEffect(Unit)` 就再跑一次。但**不能无条件跳过** ——
     * 用户可能刚在设置页登录/退出，那种情况下必须重取。
     */
    fun load() {
        if (state == DynamicState.READY && graph.api.isLoggedIn()) return
        fetchFirst()
    }

    /** 「重新加载」用。**必须绕开上面的闸**，否则按了没反应。 */
    fun reload() {
        busy = false
        fetchFirst()
    }

    private fun fetchFirst() {
        if (busy) return
        val requestGeneration = ++generation
        busy = true
        loading = true
        items = emptyList()
        offset = ""
        hasMore = false
        moreError = null

        viewModelScope.launch {
            if (!graph.api.isLoggedIn()) {
                // 没登录就**不要发请求** —— 发了也是 -101，白等一个网络往返。
                // 但要**留一条日志**（和「我的」页一个口径）：否则排查"这一页为什么空着"时，
                // 日志里既没有请求记录也没有这条说明，只能靠猜。
                AppLog.i("Dynamic", "未登录，不发任何请求")
                state = DynamicState.NEED_LOGIN
                loading = false
                busy = false
                return@launch
            }

            var page = graph.api.dynamicFeed("")
            if (requestGeneration != generation) return@launch
            var collected = mergeDynamicItems(emptyList(), page.items)
            var rounds = 1
            var cursorAdvanced = true
            while (
                collected.size < MIN_FIRST_PAGE &&
                page.hasMore &&
                page.code == 0 &&
                rounds < MAX_ROUNDS
            ) {
                val previousOffset = page.nextOffset
                if (previousOffset.isBlank()) break
                val nextPage = graph.api.dynamicFeed(previousOffset)
                if (requestGeneration != generation) return@launch
                if (nextPage.code != 0) {
                    moreError = nextPage.code
                    break // 保留最后成功页的游标，重试不能跳过丢失的一页。
                }
                page = nextPage
                collected = mergeDynamicItems(collected, page.items)
                rounds++
                if (page.nextOffset == previousOffset) {
                    cursorAdvanced = false
                    break
                }
            }

            items = collected
            offset = page.nextOffset
            hasMore = page.hasMore && page.nextOffset.isNotBlank() && cursorAdvanced
            state = when {
                moreError == CODE_NOT_LOGGED_IN -> DynamicState.EXPIRED
                collected.isNotEmpty() -> DynamicState.READY
                // 服务端明说没登录 —— 本机存着凭证但服务端不认 = 登录过期
                page.code == CODE_NOT_LOGGED_IN -> DynamicState.EXPIRED
                // 服务端说"成功"，那就真是没有能播的（而不是我们坏了）
                page.code == 0 -> DynamicState.EMPTY
                else -> DynamicState.ERROR
            }
            loading = false
            busy = false
            AppLog.i(
                "Dynamic",
                "动态 ${collected.size} 条（翻了 $rounds 页，hasMore=$hasMore，" +
                        "code=${page.code}，状态=$state）",
            )
        }
    }

    /** 滚到底部时调用。重复调用是安全的（`busy` / `hasMore` 两道闸）。 */
    fun loadMore() {
        if (busy || !hasMore || state != DynamicState.READY) return
        val requestGeneration = generation
        busy = true
        loadingMore = true
        moreError = null

        viewModelScope.launch {
            val before = items.size
            var rounds = 0
            do {
                val page = graph.api.dynamicFeed(offset)
                if (requestGeneration != generation) return@launch
                rounds++
                if (page.code != 0) {
                    moreError = page.code
                    if (page.code == CODE_NOT_LOGGED_IN) state = DynamicState.EXPIRED
                    AppLog.w("Dynamic", "更早的动态加载失败，保留已有内容；code=${page.code}")
                    break
                }
                items = mergeDynamicItems(items, page.items)
                /*
                 * ★ 游标没往前走就**必须停**。
                 *
                 * 否则（服务端字段改名、或给了空串）下一轮会拿同一个 offset
                 * 再问一次，拿回同一批数据 —— 表现是列表底部无限重复同一批卡片，
                 * 而且请求会一直发下去。宁可少一页，也不能这样。
                 */
                val next = page.nextOffset
                if (next.isBlank() || next == offset) {
                    hasMore = false
                } else {
                    offset = next
                    hasMore = page.hasMore
                }
                // 图文页或重叠页仍推进游标；最多自动跨 3 页，余下由底部按钮继续。
            } while (items.size == before && hasMore && rounds < MAX_ROUNDS)
            loadingMore = false
            busy = false
            AppLog.i("Dynamic", "翻 $rounds 页 → 共 ${items.size} 条（hasMore=$hasMore，" +
                "最新=${items.firstOrNull()?.pubTs}，最早=${items.lastOrNull()?.pubTs}）")
        }
    }

    private companion object {
        /** 首屏至少攒这么多条。一页 20 条里视频通常只占几条，所以 12 是个"看起来像列表"的数 */
        const val MIN_FIRST_PAGE = 12

        /** 首屏最多自动翻几页。防住"服务端一直说有下一页但都给非视频"这种最坏情况 */
        const val MAX_ROUNDS = 3
    }
}


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
