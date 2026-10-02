package top.bilitv.ui.follow

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import top.bilitv.data.model.UpUser
import top.bilitv.ui.components.FilledActionButton
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.fixedScheme
import top.bilitv.ui.components.formatCount
import top.bilitv.ui.components.focusRing
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.util.AppLog

/**
 * 关注 —— 「我关注的 UP 主」网格。
 *
 * ```
 * ┌────────────────────────────────────────────────────────┐
 * │ 关注                                                    │
 * │ 共 128 个 UP 主                                          │
 * ├────────────────────────────────────────────────────────┤
 * │   ◯     ◯     ◯     ◯     ◯     ← 头像网格（圆形）      │
 * │  名字   名字   名字   名字   名字                        │
 * │  12万粉 直播中 …                                        │
 * └────────────────────────────────────────────────────────┘
 * ```
 *
 * ## 为什么不做成"关注的人的新投稿"时间流
 *
 * 那个更接近 B 站首页的"动态"，但有两个硬伤：
 *
 * 1. **接口在游客态完全走不通**（`polymer/web-dynamic/v1/feed/all` 未登录直接 `-101`），
 *    而且动态流里混着转发、纯文字、图片动态，要一层层剥才能拿到视频 ——
 *    对遥控器用户来说，那些非视频条目全是"点了没反应的坑"。
 * 2. **关注页的核心动作是"我想起某个 UP 了，去他的主页看看"**，
 *    而不是"被动刷一条时间流"。前者在电视上的操作路径应该是
 *    「侧栏 → 关注 → 找到人头像 → 确认」，两步。
 *
 * 所以这里做的是**人物索引**，不是时间流。
 *
 * ## ⚠️ 这一页必须登录（不是我们把接口写错了）
 *
 * `/x/relation/followings` 未登录返回 `code=-101 账号未登录`。
 * 所以三种"没内容"要分成三句话说，用户能做的事完全不同：
 *
 * | 情况 | 说什么 | 给什么按钮 |
 * |---|---|---|
 * | 没登录 | 关注列表要登录后才能看 | 「去登录」 |
 * | 登录了但还是空 | 你还没关注任何人 | 「去首页看看」（**焦点落点**） |
 * | 请求失败 | 接口可能变了或网络不通 | 「重新加载」 |
 *
 * @param onOpenUp 点一个 UP → 进他的主页（二级页面）。
 *   **三个参数一起带过去**（mid / 昵称 / 头像）：关注接口的同一个响应里本来就有这三样，
 *   主页那边就不用再问一次接口 —— 而 `/x/space/acc/info` 在游客态是 `-401`，问也问不到。
 * @param onNeedLogin 去扫码登录。
 * @param onGoHome 空列表时那个按钮要去哪。**它同时是这一页空着时唯一的焦点落点**，
 *   见 `docs/11`：没有它，空列表上遥控器按方向键毫无反应（历史页踩过一次）。
 */
@Composable
fun FollowScreen(
    onOpenUp: (Long, String, String) -> Unit,
    onNeedLogin: () -> Unit,
    onGoHome: () -> Unit,
) {
    val vm: FollowViewModel = viewModel()
    val theme = AppTheme.current
    LaunchedEffect(Unit) { vm.load() }

    val firstTile = remember { FocusRequester() }
    val emptyButton = remember { FocusRequester() }
    val list = vm.items

    Column(modifier = Modifier.fillMaxSize()) {
        FollowHeader(count = list.size, showCount = vm.loggedIn && list.isNotEmpty())

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                list.isEmpty() -> FollowNotice(
                    state = vm.state,
                    onAction = when (vm.state) {
                        FollowState.NEED_LOGIN -> onNeedLogin
                        FollowState.ERROR -> { { vm.reload() } }
                        else -> onGoHome
                    },
                    actionLabel = when (vm.state) {
                        FollowState.NEED_LOGIN -> "去登录"
                        FollowState.ERROR -> "重新加载"
                        else -> "去首页看看"
                    },
                    requester = emptyButton,
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(theme.cardColumns + 1),
                    state = rememberLazyGridState(),
                    contentPadding = PaddingValues(
                        start = theme.screenPadding,
                        end = theme.screenPadding,
                        bottom = theme.screenPadding,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
                    verticalArrangement = Arrangement.spacedBy(theme.rowGap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    gridItems(list, key = { it.mid }) { up ->
                        UpTile(
                            up = up,
                            onClick = { onOpenUp(up.mid, up.name, up.face) },
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = firstTile.takeIf { up.mid == list.first().mid },
                        )
                    }
                }
            }
        }
    }

    RequestFocusOnAppear(firstTile, if (list.isEmpty()) null else list.first().mid)
    RequestFocusOnAppear(emptyButton, !vm.loading && list.isEmpty())
}

@Composable
private fun FollowHeader(count: Int, showCount: Boolean) {
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
            text = "关注",
            style = TextStyle(fontSize = AppType.H1, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        Text(
            text = if (showCount) "共 $count 个 UP 主 · 选中一个看他的投稿" else "选中一个 UP 主看他的投稿",
            style = TextStyle(fontSize = AppType.Caption),
            color = theme.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 一个 UP 主格子。
 *
 * 头像用**圆形**，和视频卡片的 16:9 圆角矩形形成区分 ——
 * 这一页全是"人"，一眼就该看出来区别。
 *
 * 焦点态照 BT：垫一层半透明主色 + 主色描边，**不放大**。
 * 网格密排时放大一定会压到邻居，而"垫色 + 描边"已经足够显眼。
 */
@Composable
private fun UpTile(
    up: UpUser,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val theme = AppTheme.current
    val context = LocalContext.current

    val desc = buildString {
        append(up.name)
        if (up.liveRoomId > 0) append("，正在直播")
        if (up.fans > 0) append("，${formatCount(up.fans)}粉丝")
        if (up.sign.isNotBlank()) append("，${up.sign}")
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            // focusRequester 必须挂在 focusRing **之前**（它认的是后面最近的焦点目标）
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )
            .focusRing(contentDescription = desc, onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Box {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(up.face.fixedScheme())
                    // 按实际显示尺寸解码，绝不原图解码（低内存的生命线）
                    .size(240, 240)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .background(theme.surfaceHigh),
            )

            /*
             * 「直播中」角标。压在头像右下角。
             *
             * 为什么值得单独画：直播是**有时效**的信息 —— 用户看到这个角标才会点进去，
             * 而它和"粉丝数"这种静态信息的重要性不是一个量级。参考的三款客户端里
             * blbl 也在关注列表上标了直播状态。
             */
            if (up.liveRoomId > 0) {
                Text(
                    text = "直播中",
                    style = TextStyle(fontSize = AppType.Tiny, fontWeight = FontWeight.Medium),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(4.dp))
                        .background(LIVE_RED)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = up.name,
            style = TextStyle(fontSize = AppType.Body2, fontWeight = FontWeight.Medium),
            color = theme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        /*
         * 第三行只在**真的有内容**时才占位。
         *
         * 粉丝数拿不到时（接口没给）留空高，而不是写「0 粉丝」——
         * 那是句假话，而且比没有更糟。
         */
        val sub = when {
            up.officialDesc.isNotBlank() -> up.officialDesc
            up.fans > 0 -> "${formatCount(up.fans)}粉丝"
            else -> ""
        }
        if (sub.isNotBlank()) {
            Text(
                text = sub,
                style = TextStyle(fontSize = AppType.Small),
                color = theme.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/**
 * 「没内容」时的说明。
 *
 * 三种原因写三句话，因为用户能做的事完全不同（见 [FollowScreen] 的说明）。
 * 含混地显示一句"加载失败"，用户唯一能做的就是反复重试。
 */
@Composable
private fun FollowNotice(
    state: FollowState,
    actionLabel: String,
    onAction: () -> Unit,
    requester: FocusRequester,
) {
    val theme = AppTheme.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = when (state) {
                FollowState.NEED_LOGIN -> "关注列表需要登录"
                FollowState.EMPTY -> "还没有关注任何人"
                FollowState.ERROR -> "拿不到关注列表"
                // 下面两个**不该走到这里** —— 调用方只在"列表空且不在加载中"时才渲染这一屏。
                // 写出来是因为 `when` 当表达式用必须穷尽；留一句话而不是留空白，
                // 万一日后哪里改错了，屏幕上至少有一行字能指认现场。
                FollowState.LOADING, FollowState.READY -> "状态异常，回到侧栏再进一次这一页"
            },
            style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        Text(
            text = when (state) {
                FollowState.NEED_LOGIN ->
                    "关注的人存在你的账号里，不登录读不到。扫码登录一次就行，之后不用再登。"
                FollowState.EMPTY ->
                    "在手机或网页上关注几个 UP 主，这里就会出现。"
                FollowState.ERROR ->
                    "接口可能变了或网络不通。已经登录了还是这样，就是服务端的问题，看日志能有线索。"
                FollowState.LOADING, FollowState.READY ->
                    "这一屏本来不该出现，麻烦把日志发我。"
            },
            style = TextStyle(fontSize = AppType.Body3, lineHeight = 22.sp),
            color = theme.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )

        /*
         * ★ 这个按钮不只是"方便"，它是**这一页空着时唯一的焦点落点**。
         * `AppShell` 把关注页标成"自己管焦点"，侧栏不会代收 ——
         * 没有它，空列表上按方向键毫无反应（历史页踩过一次，见 docs/11）。
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

/** 头像直径。5 列 × 152dp 的格子里，104dp 的头像两侧还有余量给焦点描边 */
private val AVATAR_SIZE = 104.dp

/** 直播中角标用的红。B 站直播的红是 `#FA5A57` 那一档 */
private val LIVE_RED = Color(0xFFE0534F)

/** 关注页的四种状态。它决定"空的时候该说什么话、给什么按钮"。 */
enum class FollowState { LOADING, NEED_LOGIN, EMPTY, ERROR, READY }

/**
 * 关注页数据。
 *
 * ## 判据只有一条，但必须记三件事
 *
 * 服务端在"没登录"和"接口挂了"两种情况下都只会给空列表，
 * 所以**状态的判据是「列表空 + `isLoggedIn()`」这一对**，不能只看列表空不空。
 * 这和 PGC 详情页（`docs/15` §2.1）是同一类问题，只是那边的空值来自 `data=null`。
 */
class FollowViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp

    var items by mutableStateOf<List<UpUser>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var loggedIn by mutableStateOf(false)
        private set
    var state by mutableStateOf(FollowState.LOADING)
        private set

    private var inFlight = false

    /**
     * 进页面时调用。
     *
     * ## 为什么要带一个"已经取到过就别重取"的闸
     *
     * 侧栏切走再切回来，[FollowScreen] 会重新进入组合，`LaunchedEffect(Unit)` 就再跑一次。
     * 每次都真发请求的话，用户来回点几下侧栏就是好几个网络往返 + 好几次列表闪动。
     * 但**不能无条件跳过**：登录态可能刚变过（在设置页退出登录了、
     * 或者刚扫码登录成功），那种情况下必须重取，否则会一直显示上一次的结论。
     *
     * 所以闸门开在「上次成功了 **且** 登录态没变过」这两个条件上。
     */
    fun load() {
        if (state == FollowState.READY && loggedIn == graph.api.isLoggedIn()) return
        fetch()
    }

    /** 「重试」/ 「重新加载」用。**必须绕开上面的闸**，否则按了没反应。 */
    fun reload() {
        inFlight = false
        fetch()
    }

    private fun fetch() {
        if (inFlight) return
        inFlight = true
        loading = true
        viewModelScope.launch {
            val isLoggedIn = graph.api.isLoggedIn()
            loggedIn = isLoggedIn
            if (!isLoggedIn) {
                // 没登录就**不要发请求** —— 发了也是 -101，
                // 白等一个网络往返，而且日志里会多一条毫无信息量的错误
                items = emptyList()
                state = FollowState.NEED_LOGIN
                loading = false
                inFlight = false
                return@launch
            }

            val result = graph.api.followingList(vmid = graph.api.myMid())
            items = result
            /*
             * ★ 列表空的时候，**不能直接说"接口坏了"，也不能直接说"你没关注谁"**。
             *
             * 这两件事在列表上长得一模一样（都是空），但用户能做的事完全不同。
             * 所以空的时候去问一次 `/x/relation/stat`（游客态可用的公开计数），
             * 拿"关注数"当第二条证据：
             *
             * | 列表 | 关注数 | 结论 |
             * |---|---|---|
             * | 空 | 0 | 确实没关注任何人 → EMPTY |
             * | 空 | >0 | 接口那边出问题了 → ERROR |
             * | 空 | 取不到(null) | 证据不足，**按 ERROR 处理**（宁可让他重试，也别骗他说"你没关注"） |
             */
            state = when {
                result.isNotEmpty() -> FollowState.READY
                else -> {
                    val count = graph.api.followingCount(graph.api.myMid())
                    if (count == 0L) FollowState.EMPTY else FollowState.ERROR
                }
            }
            loading = false
            inFlight = false
            AppLog.i(
                "Follow",
                "关注 ${result.size} 个（已登录=$isLoggedIn 状态=$state）",
            )
        }
    }
}
