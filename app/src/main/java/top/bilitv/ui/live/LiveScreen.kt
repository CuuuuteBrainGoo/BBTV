package top.bilitv.ui.live

import top.bilitv.R
import top.bilitv.data.settings.uiLocale
import androidx.compose.ui.res.stringResource

import top.bilitv.ui.theme.pageBackground

import top.bilitv.ui.components.verticalScrollbar
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalDensity
import top.bilitv.ui.components.cappedCover
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import top.bilitv.BiliTvApp
import top.bilitv.data.model.LiveArea
import top.bilitv.data.model.LiveRoom
import top.bilitv.data.model.LiveStatus
import top.bilitv.ui.components.FilledActionButton
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.SectionTabBar
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.components.fixedScheme
import top.bilitv.ui.components.formatCount
import top.bilitv.ui.theme.gridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import top.bilitv.ui.components.LoadFeedback
import kotlinx.coroutines.CancellationException
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.util.AppLog

/**
 * 直播 —— 房间列表。
 *
 * ```
 * ┌──────────────────────────────────────────────────────────┐
 * │ 直播                                                      │
 * │ 选一个直播间进去看                                        │
 * │ [推荐] [网游] [手游] [娱乐] [电台] …          ← 可横向滚动 │
 * ├──────────────────────────────────────────────────────────┤
 * │ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐              │
 * │ │ 封面16:9│ │        │ │        │ │        │              │
 * │ │  🔴直播中│ │        │ │        │ │        │              │
 * │ ├────────┤ └────────┘ └────────┘ └────────┘              │
 * │ │ 标题    │                                               │
 * │ │ 主播·1.2万人气                                           │
 * │ └────────┘                                               │
 * └──────────────────────────────────────────────────────────┘
 * ```
 *
 * ## 和「关注」页的形态差别
 *
 * 关注页是**人物索引**（圆形头像、纯为人）；直播页是**内容索引**
 * （16:9 封面、看的是画面）。所以这里用视频卡片的形态，
 * 而不是把人做成主角 —— 点进去是"看这场直播"，不是"看这个人"。
 *
 * ## ★ 这一页用的是**老接口**
 *
 * 新的 `/xlive/web-interface/v1/second/getList` 在游客态稳定 `-352 风控校验失败`。
 * 老接口 `/room/v1/room/get_user_recommend` 和 `/room/v1/Area/getRoomList`
 * 都活着。**别按"名字里没有 v2 就是过时"的直觉去改** ——
 * 完整对照表见 `data/api/Parsers.kt` 里"直播"那一节。
 *
 * @param onOpen 点一个房间 → 直接进播放页。三个参数（roomId / 标题 / 封面）
 *   一起带过去：列表接口的同一个响应里本来就有，播放页就不用再问一次
 *   —— 而且播放页拿到就能立刻把顶部信息栏填上，不用等接口。
 */
@Composable
fun LiveScreen(onOpen: (LiveRoom) -> Unit) {
    val vm: LiveViewModel = viewModel()
    val gridState = rememberLazyGridState()
    val theme = AppTheme.current
    LaunchedEffect(Unit) { vm.load() }
    DisposableEffect(vm) { onDispose { vm.stopLoading() } }
    top.bilitv.ui.OnRefreshRequest { if (!vm.loading) vm.reload() }

    val firstTile = remember { FocusRequester() }
    val retryButton = remember { FocusRequester() }
    val rooms = vm.rooms

    Column(modifier = Modifier.fillMaxSize().background(theme.pageBackground)) {
        LiveHeader(
            total = rooms.size,
            living = rooms.count { it.isLiving },
            showCount = vm.state == LiveState.READY,
        )

        SectionTabBar(
            labels = vm.tabLabels,
            selectedIndex = vm.tabIndex,
            contentFocusRequester = if (rooms.isNotEmpty()) firstTile else retryButton.takeIf { !vm.loading },
            onSelect = { vm.selectTab(it) },
            modifier = Modifier.padding(
                start = theme.screenPadding,
                end = theme.screenPadding,
                bottom = 10.dp,
            ),
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Header, tabs and system insets have already consumed their actual space.
            val textHeight = with(LocalDensity.current) { 24.sp.toDp() + AppType.Small.toDp() * 1.4f + 14.dp }
            val coverLimit = (maxHeight - 6.dp - theme.screenPadding - textHeight).coerceAtLeast(1.dp)
            when {
                vm.loading && rooms.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                rooms.isEmpty() && vm.error != null -> LoadFeedback(false, vm.error, vm::reload,
                    Modifier.align(Alignment.Center).padding(24.dp).focusRequester(retryButton))

                rooms.isEmpty() -> LiveNotice(
                    state = vm.state,
                    onRetry = { vm.reload() },
                    requester = retryButton,
                )

                else -> LazyVerticalGrid(
                    columns = theme.gridCells(),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = theme.screenPadding,
                        end = theme.screenPadding,
                        top = 6.dp,
                        bottom = theme.screenPadding,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
                    verticalArrangement = Arrangement.spacedBy(theme.rowGap),
                    modifier = Modifier.fillMaxSize().verticalScrollbar(gridState),
                ) {
                    gridItems(rooms, key = { it.roomId }) { room ->
                        LiveTile(
                            room = room,
                            coverLimit = coverLimit,
                            onClick = { onOpen(room) },
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = firstTile.takeIf { room.roomId == rooms.first().roomId },
                        )
                    }
                    if (vm.loading || vm.error != null) item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadFeedback(vm.loading, vm.error, vm::reload)
                    }
                }
            }
        }
    }

    // 焦点：有列表就给第一张卡；列表空就给「重新加载」那颗按钮
    RequestFocusOnAppear(firstTile, if (rooms.isEmpty()) null else rooms.first().roomId)
    RequestFocusOnAppear(retryButton, !vm.loading && rooms.isEmpty())
}

/**
 * 页头。
 *
 * ★ 文案里那个数字是**在播的房间数**，不是列表长度 —— 两者本来不一样：
 * 列表里可能混着轮播 / 未开播。写"正在直播 30 个房间"而下面挂着「未开播」
 * 的角标，是自相矛盾。一个在播都数不出来时（全是"不知道"）就改说"共 N 个"，
 * 不硬凑一个 0 出来。
 */
@Composable
private fun LiveHeader(total: Int, living: Int, showCount: Boolean) {
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
            text = stringResource(R.string.nav_live),
            style = TextStyle(fontSize = AppType.H1, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        Text(
            text = when {
                !showCount -> stringResource(R.string.live_header_hint)
                living > 0 -> stringResource(R.string.live_living_rooms, living)
                else -> stringResource(R.string.live_total_rooms, total)
            },
            style = TextStyle(fontSize = AppType.Caption),
            color = theme.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 一个直播房间卡片。
 *
 * ## 封面上那个角标为什么重要
 *
 * 列表里混着"正在直播"和"没开播"的房间。用户点进去才发现没开播的体验很差，
 * 所以**必须在列表上就能看出来**。
 *
 * ## ★ 角标读的不是一个字段，是一条规则
 *
 * ⚠️ **列表接口压根不返回 `live_status`**（实测：推荐流 30 条全是 `null`）。
 * 所以"在播没有"是 [top.bilitv.data.api.liveStatusOf] 推断出来的，
 * 不是照抄的。推断规则和它的三条理由都写在那个函数上，别在这儿再写一遍 if。
 *
 * 四种状态给三种画法：
 *
 * | 状态 | 角标 | 颜色 |
 * |---|---|---|
 * | 直播中 | 直播中 | 红（和关注页的"直播中"同一个色值） |
 * | 轮播（放录像） | 轮播 | 灰 —— 能看，但不是直播 |
 * | 未开播 | 未开播 | 更暗的灰 |
 * | 不知道 | **不画** | —— 拿不到就别替接口下结论 |
 *
 * 最后一行是 2026-09-29 补的。第一版没有它，老写法 `optInt(..., 0)` 兜底成
 * "未开播"，于是**每一张卡都挂着「未开播」** —— 有封面有人气能点进去播，
 * 就是角标在撒谎。这种"不报错、不崩、测试还全绿"的毛病只能靠模拟器实测抓。
 */
@Composable
private fun LiveTile(
    room: LiveRoom,
    coverLimit: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val theme = AppTheme.current
    val context = LocalContext.current

    val desc = buildString {
        append(room.title)
        append("，").append(room.uname)
        when (room.liveStatus) {
            LiveStatus.LIVING -> append(context.getString(R.string.live_living_description))
            LiveStatus.RERUN -> append(context.getString(R.string.live_rerun_description))
            LiveStatus.OFFLINE -> append(context.getString(R.string.live_offline_description))
            // 不知道就什么都不说。读屏多一句"开播状态未知"是噪音，
            // 念一句"未开播"则是撒谎
            else -> Unit
        }
        if (room.online > 0) append(context.getString(R.string.live_popularity_description, formatCount(room.online, context.uiLocale)))
    }

    TvCard(
        onClick = onClick,
        modifier = if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier,
        contentDescription = desc,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .cappedCover(16f / 9f, coverLimit)
                    .clip(RoundedCornerShape(theme.cardCorner))
                    .background(theme.surfaceHigh),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(room.cover.fixedScheme())
                        // 按实际显示尺寸解码，绝不原图解码（低内存的生命线）
                        .size(480, 270)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                LiveBadge(
                    status = room.liveStatus,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )

                if (room.online > 0) {
                    Text(
                        text = stringResource(R.string.live_popularity, formatCount(room.online, context.uiLocale)),
                        style = TextStyle(fontSize = AppType.Tiny, fontWeight = FontWeight.Medium),
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x99000000))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(7.dp))

            Text(
                text = room.title,
                style = TextStyle(fontSize = AppType.Meta, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
                color = theme.textPrimary,
                // 2026-09-29 视觉改造：直播是**密排网格**，标题允许两行会让卡片高度参差，
                // 一排看过去高低不齐。收成一行 + 省略号，对齐优先。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(3.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    text = room.uname,
                    style = TextStyle(fontSize = AppType.Small, lineHeight = AppType.Small * 1.4f),
                    color = theme.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 分区名：只在有内容时才加那一段 —— 空的名字会显示成「主播 · 」
                val area = listOf(room.parentAreaName, room.areaName)
                    .filter { it.isNotBlank() }
                    .joinToString("·")
                if (area.isNotBlank()) {
                    Text(
                        text = " · $area",
                        style = TextStyle(fontSize = AppType.Small, lineHeight = AppType.Small * 1.4f),
                        color = theme.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(.65f, fill = false),
                    )
                }
            }
        }
    }
}

/**
 * 封面角标。
 *
 * 状态取值见 [LiveStatus]。**`UNKNOWN` 直接不画** —— 这是这里唯一一处
 * "什么都不输出"的正当理由：拿不到数据的时候，画一个「未开播」是在
 * 替接口下一个它没下的结论，而且这个结论会把能看的房间挡在门外。
 */
@Composable
private fun LiveBadge(status: Int, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        LiveStatus.LIVING -> R.string.live_living to LIVE_RED
        LiveStatus.RERUN -> R.string.live_rerun to Color(0xCC55555F)
        LiveStatus.OFFLINE -> R.string.live_offline to Color(0xAA2A2A33)
        else -> return
    }
    Text(
        text = stringResource(label),
        style = TextStyle(fontSize = AppType.Tiny, fontWeight = FontWeight.Medium),
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 「列表空」时的说明。
 *
 * 直播列表**不需要登录**，所以这里没有"去登录"那条分支 —— 空就是两种可能：
 * 接口出问题，或者这个分区暂时没有人在播。后者在冷门分区是**正常现象**，
 * 不是错误，所以分开说。
 */
@Composable
private fun LiveNotice(state: LiveState, onRetry: () -> Unit, requester: FocusRequester) {
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
                LiveState.ERROR -> stringResource(R.string.live_unavailable)
                LiveState.EMPTY -> stringResource(R.string.live_empty)
                else -> stringResource(R.string.live_state_unavailable)
            },
            style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        Text(
            text = when (state) {
                LiveState.EMPTY -> stringResource(R.string.live_empty_hint)
                else -> stringResource(R.string.live_reload_hint)
            },
            style = TextStyle(fontSize = AppType.Body3, lineHeight = 22.sp),
            color = theme.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )

        /*
         * ★ 这颗按钮同时是**这一页空着时唯一的焦点落点**。
         *
         * `AppShell` 把直播页标成"自己管焦点"，侧栏不代收。没有它，
         * 空列表上按遥控器方向键毫无反应（历史页在 2026-09-29 踩过一次，见 `docs/11`）。
         */
        FilledActionButton(
            text = stringResource(R.string.action_reload),
            onClick = onRetry,
            modifier = Modifier
                .focusRequester(requester)
                .padding(top = 24.dp),
        )
    }
}

/** 直播中角标用的红。和关注页保持同一个色值，免得两处红不一样 */
private val LIVE_RED = Color(0xFFE0534F)

/** 直播页的三种状态。**没有 NEED_LOGIN** —— 直播列表本来就游客可用 */
enum class LiveState { LOADING, EMPTY, ERROR, READY }

/**
 * 直播页数据。
 *
 * ## 两个请求的关系
 *
 * 「分区表」（12 个大区）和「房间列表」是**两次独立请求**：
 * 分区表一次就够（它在整个 App 生命周期里不会变），房间列表每换一个分区要重取。
 * 所以分开加载、分开失败 —— 分区表拿不到不该挡住推荐流，
 * 那只会让用户在"一个空白的页面"和"有 30 个房间但没有分区标签的页面"之间
 * 选了更差的那个。
 */
class LiveViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp

    var rooms by mutableStateOf<List<LiveRoom>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var state by mutableStateOf(LiveState.LOADING)
        private set

    /**
     * 分区：**下标 0 恒为「关注」、1 恒为「推荐」**，2 往后对应 [areas] 里的大区。
     *
     * ★ 2026-09-30 少爷（截图批注）：「直播第一个是关注，第二个是推荐」。
     * 原来只有「推荐」开头，现在把「关注」插到最前面。
     */
    var tabIndex by mutableStateOf(0)
        private set
    var areas by mutableStateOf<List<LiveArea>>(emptyList())
        private set

    /** 标签文字。前两个固定是「关注」「推荐」，后面是大区 */
    val tabLabels: List<String> get() = listOf(graph.getString(R.string.live_following), graph.getString(R.string.section_recommend)) + areas.map { it.name }

    private var fetchJob: Job? = null
    private var initialized = false
    private var loadedLogin: Boolean? = null
    private var areasLoaded = false
    private var generation = 0
    var error by mutableStateOf<String?>(null); private set

    /** 当前选中的分区下标 → 给"重新加载"用的。`reload()` 必须知道重取哪一页 */
    fun load() {
        val loggedIn = graph.api.isLoggedIn()
        if (loadedLogin != loggedIn) {
            rooms = emptyList(); initialized = false; loadedLogin = loggedIn
        }
        if (!initialized && fetchJob?.isActive != true) fetch()
    }

    fun stopLoading() {
        ++generation
        fetchJob?.cancel(); fetchJob = null; loading = false
    }

    /** Explicit refresh cancels the previous read while keeping already loaded rooms. */
    fun reload() {
        fetch()
    }

    fun selectTab(index: Int) {
        if (index !in tabLabels.indices) return
        if (index == tabIndex) return
        tabIndex = index
        rooms = emptyList()
        initialized = false
        fetch()
    }

    private fun fetch() {
        fetchJob?.cancel()
        loading = true; error = null
        val g = ++generation
        val index = tabIndex
        fetchJob = viewModelScope.launch {
            try {
            // Keep a successful area table; an unavailable table does not block the room list.
            if (!areasLoaded) {
                val a = graph.api.liveAreas()
                coroutineContext.ensureActive()
                if (g != generation) return@launch
                if (a.isNotEmpty()) {
                    areas = a
                    areasLoaded = true
                    AppLog.i("Live", "分区表 ${a.size} 个大区")
                } else {
                    AppLog.w("Live", "分区表取不到（不影响推荐流）")
                }
            }

            val list = if (index == 0) {
                // 「关注」——没登录时接口给 -101，列表为空，界面按"空"处理
                graph.api.liveFollowing(strict = true)
            } else if (index == 1) {
                graph.api.liveRecommend(strict = true)
            } else {
                // 下标 0/1 是「关注」「推荐」，所以大区下标要减 2
                val area = areas.getOrNull(index - 2)
                if (area == null) {
                    AppLog.w("Live", "分区下标 $index 越界（只有 ${areas.size} 个大区）")
                    emptyList()
                } else {
                    graph.api.liveAreaRooms(parentAreaId = area.id, strict = true)
                }
            }

            coroutineContext.ensureActive()
            if (g != generation) return@launch
            rooms = list.distinctBy { it.roomId }
            state = when {
                list.isNotEmpty() -> LiveState.READY
                else -> LiveState.EMPTY
            }
            initialized = true
            AppLog.i("Live", "分区[$index] 房间 ${list.size} 个（状态=$state）")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (g == generation) {
                    error = graph.getString(if (index == 0 && !graph.api.isLoggedIn()) R.string.live_following_sign_in else R.string.live_load_failed)
                    state = if (rooms.isEmpty()) LiveState.ERROR else LiveState.READY
                    AppLog.w("Live", e.javaClass.simpleName)
                }
            } finally { if (g == generation) loading = false }
        }
    }
}
