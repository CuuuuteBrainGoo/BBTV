package top.bilitv.ui.follow

import top.bilitv.ui.components.verticalScrollbar
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import top.bilitv.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import top.bilitv.BiliTvApp
import top.bilitv.data.model.UpUser
import top.bilitv.ui.components.FilledActionButton
import top.bilitv.ui.components.LoadFeedback
import top.bilitv.ui.components.BackChip
import top.bilitv.ui.components.scrollWithScrollbar
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.fixedScheme
import top.bilitv.ui.components.formatCount
import top.bilitv.ui.components.focusRing
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.ui.theme.CardGridCells
import top.bilitv.ui.theme.nearEnd
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
    val gridState = rememberLazyGridState()
    val theme = AppTheme.current
    LaunchedEffect(Unit) { vm.load() }
    DisposableEffect(vm) { onDispose { vm.stopLoading() } }
    LaunchedEffect(vm, gridState) {
        snapshotFlow { gridState.layoutInfo.nearEnd(vm.items.size) to vm.items.size }.collect { (near, size) ->
            if (near && size > 0 && vm.loadError == null) vm.more()
        }
    }

    val firstTile = remember { FocusRequester() }
    val emptyButton = remember { FocusRequester() }
    val list = vm.items

    Column(modifier = Modifier.fillMaxSize()) {
        BackChip(onBack = onGoHome, modifier = Modifier.padding(horizontal = theme.screenPadding, vertical = 8.dp))
        FollowHeader(count = list.size, total = vm.total, showCount = vm.loggedIn && list.isNotEmpty())

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                vm.loading && list.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                list.isEmpty() && !vm.canLoadMore -> FollowNotice(
                    state = vm.state,
                    onAction = when (vm.state) {
                        FollowState.NEED_LOGIN -> onNeedLogin
                        FollowState.ERROR -> { { vm.reload() } }
                        else -> onGoHome
                    },
                    actionLabel = when (vm.state) {
                        FollowState.NEED_LOGIN -> stringResource(R.string.action_sign_in)
                        FollowState.ERROR -> stringResource(R.string.action_reload)
                        else -> stringResource(R.string.action_browse_home)
                    },
                    requester = emptyButton,
                )

                else -> LazyVerticalGrid(
                    columns = CardGridCells(160.dp),
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
                    gridItems(list, key = { it.mid }) { up ->
                        UpTile(
                            up = up,
                            onClick = { onOpenUp(up.mid, up.name, up.face) },
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = firstTile.takeIf { up.mid == list.first().mid },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadFeedback(vm.loading || vm.moreLoading, vm.loadError, vm::retry,
                            if (list.isEmpty() && vm.loadError != null) Modifier.focusRequester(emptyButton) else Modifier)
                    }
                    if (vm.canLoadMore) item(span = { GridItemSpan(maxLineSpan) }) {
                        FilledActionButton(if (vm.moreLoading) stringResource(R.string.loading) else stringResource(R.string.follow_continue), vm::more,
                            modifier = Modifier.fillMaxWidth().then(
                                if (list.isEmpty() && vm.loadError == null) Modifier.focusRequester(emptyButton) else Modifier))
                    }
                }
            }
        }
    }

    RequestFocusOnAppear(firstTile, if (list.isEmpty()) null else list.first().mid)
    RequestFocusOnAppear(emptyButton, !vm.loading && list.isEmpty())
}

@Composable
private fun FollowHeader(count: Int, total: Long?, showCount: Boolean) {
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
            text = stringResource(R.string.account_following),
            style = TextStyle(fontSize = AppType.H1, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        Text(
            text = if (!showCount) stringResource(R.string.follow_header_hint)
                else if (total != null) stringResource(R.string.follow_count_total, count, total)
                else stringResource(R.string.follow_count, count),
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
        if (up.liveRoomId > 0) append(context.getString(R.string.live_living_description))
        if (up.fans > 0) append(context.getString(R.string.follow_fans_description, formatCount(up.fans)))
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
                    text = stringResource(R.string.live_living),
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
            up.fans > 0 -> stringResource(R.string.follow_fans, formatCount(up.fans))
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
            .scrollWithScrollbar(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = when (state) {
                FollowState.NEED_LOGIN -> stringResource(R.string.follow_need_login)
                FollowState.EMPTY -> stringResource(R.string.follow_empty)
                FollowState.ERROR -> stringResource(R.string.follow_unavailable)
                // 下面两个**不该走到这里** —— 调用方只在"列表空且不在加载中"时才渲染这一屏。
                // 写出来是因为 `when` 当表达式用必须穷尽；留一句话而不是留空白，
                // 万一日后哪里改错了，屏幕上至少有一行字能指认现场。
                FollowState.LOADING, FollowState.READY -> stringResource(R.string.dynamic_reopen)
            },
            style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        Text(
            text = when (state) {
                FollowState.NEED_LOGIN ->
                    stringResource(R.string.follow_need_login_hint)
                FollowState.EMPTY ->
                    stringResource(R.string.follow_empty_hint)
                FollowState.ERROR ->
                    stringResource(R.string.follow_error_hint)
                FollowState.LOADING, FollowState.READY ->
                    stringResource(R.string.dynamic_reopen_hint)
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

/** 只把成功响应的零关注显示为空；失败保持可重试。 */
enum class FollowState { LOADING, NEED_LOGIN, EMPTY, ERROR, READY }

/**
 * 关注页数据。
 *
 * total 和 list 来自同一次成功响应。按账号缓存已加载页，失败不推进页码，离页取消读取。
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

    var total by mutableStateOf<Long?>(null); private set
    var canLoadMore by mutableStateOf(false); private set
    var moreLoading by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set
    private var requestJob: Job? = null
    private var generation = 0
    private var page = 0
    private var loadedMid: Long? = null
    private var failedMore = false

    fun stopLoading() {
        ++generation; requestJob?.cancel(); requestJob = null
        loading = false; moreLoading = false
    }

    /** Successful empty pages and the current account are cached, interrupted first pages resume. */
    fun load() {
        val login = graph.api.isLoggedIn()
        val mid = graph.api.myMid()
        if (login == loggedIn && mid == loadedMid && (page > 0 || requestJob?.isActive == true)) return
        reload()
    }

    fun reload() {
        stopLoading()
        val mid = graph.api.myMid()
        val login = graph.api.isLoggedIn()
        if (mid != loadedMid || login != loggedIn) {
            items = emptyList(); page = 0; total = null; canLoadMore = false
        }
        loadedMid = mid; loggedIn = login
        loadError = null; failedMore = false
        if (!login) { state = FollowState.NEED_LOGIN; return }
        request(1)
    }

    fun retry() { if (failedMore) more() else reload() }

    fun more() {
        if (requestJob?.isActive == true || !canLoadMore) return
        if (!graph.api.isLoggedIn() || graph.api.myMid() != loadedMid) { reload(); return }
        request(page + 1)
    }

    private fun request(nextPage: Int) {
        val g = ++generation
        val mid = loadedMid ?: return
        loading = nextPage == 1; moreLoading = nextPage > 1; loadError = null
        if (loading && items.isEmpty()) state = FollowState.LOADING
        requestJob = viewModelScope.launch {
            try {
                val result = graph.api.followingPage(vmid = mid, pn = nextPage)
                coroutineContext.ensureActive()
                if (g != generation || loadedMid != mid) return@launch
                if (!graph.api.isLoggedIn() || graph.api.myMid() != mid) { reload(); return@launch }
                items = (if (nextPage == 1) result.items else items + result.items).distinctBy { it.mid }
                total = result.total; page = nextPage; canLoadMore = result.hasMore
                state = when {
                    items.isNotEmpty() || canLoadMore -> FollowState.READY
                    total == 0L -> FollowState.EMPTY
                    else -> FollowState.ERROR
                }
                AppLog.i("Follow", "关注第$page 页，累计 ${items.size} 个，更多=$canLoadMore")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (g == generation) {
                failedMore = nextPage > 1
                loadError = graph.getString(R.string.follow_failed)
                if (items.isEmpty() && !canLoadMore) state = FollowState.ERROR
                AppLog.w("Follow", e.javaClass.simpleName)
            } }
            finally { if (g == generation) { loading = false; moreLoading = false } }
        }
    }
}
