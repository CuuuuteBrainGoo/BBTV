package top.bilitv.ui.history

import top.bilitv.ui.components.verticalScrollbar
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import top.bilitv.BiliTvApp
import top.bilitv.data.history.HistoryEntry
import top.bilitv.data.model.FeedItem
import top.bilitv.ui.components.FeedCard
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.theme.gridCells
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType

/**
 * 观看记录（历史）。
 *
 * ## 数据从哪来：**本机**，不是 B 站的云端历史
 *
 * 三个理由，按重要性排：
 *
 * 1. **续播必须本地**。云端历史要登录、有网络延迟，而"接着看"这个动作对延迟
 *    是零容忍的 —— 详见 `HistoryEntry` 的说明。
 * 2. **未登录也能用**。本项目的默认状态就是游客（广告跳过能用、首页能用），
 *    历史没道理反而要登录。
 * 3. **不上传**。这份记录只写在本机，谁都不发。三款参考客户端里 blbl 的
 *    `noCookies` 就是这个立场（不把 B 站凭证给第三方），我们更进一步：
 *    **连"看了什么"都不给别人**。设置页里那句「只存在这台设备上」不是装饰。
 *
 * ## 点一条 = 直接接着播
 *
 * 不绕详情页。用户点历史里的条目，要的是"接着看"，不是"再看一遍介绍页"。
 * 播放页自己会从本机记录里读位置（`PlayerViewModel.resumeIfNeeded`）。
 *
 * ## 卡片形态：和首页同一个（少爷 2026-09-30 要求）
 *
 * > 动态和历史里的视频也应该用类似首页的视频卡展示，同时历史里的视频应该能读到
 * > 历史播放进度，可以在视频卡上做一个符合主题色进度条示意给用户看。
 *
 * 所以这一页是 `LazyVerticalGrid` + `FeedCard(progress = ...)`，不再是自绘的行。
 * 数据本来就有（`HistoryEntry.fraction`），只是从来没画出来过。
 *
 * ## 单条删除：为什么是「管理模式」而不是长按
 *
 * 改成网格之后，原来每行右侧那个「移除」按钮没有位置了。补回来的方式是
 * **顶部一个「管理」开关**：打开之后每张卡蒙一层暗罩 + 右上角一个 ✕，
 * 这时按 OK 就是删掉这一条。
 *
 * ⛔ **不用长按**，理由是硬的：`combinedClickable` 的长按在 Compose 里
 * **只对触摸生效，方向键按住确定键不会触发**（`docs/99` §D 记过同类问题）。
 * 而且长按在电视上没有任何视觉提示 —— 用户不可能发现。
 * BT 的配置界面同样避开了拖拽/长按（`docs/33` §4.3）。
 *
 * 为什么不是"每张卡上一个常驻的 ✕"：那会让每张卡里多一个焦点目标，
 * 卡片网格的左右方向键要在"卡片"和"卡片的删除键"之间跳，
 * 手感会变得很难预测。**模式化之后，网格里永远只有卡片一个焦点目标。**
 *
 * ## 为什么不按天分组
 *
 * B 站的网页版历史是按天分组的。这里不分：本机记录最多 200 条，
 * 而电视上的列表越长越难用 —— 每条自带「3 小时前」这种相对时间，
 * 信息已经够了，分组的标题栏反而占掉一屏的三分之一。
 */
@Composable
fun HistoryScreen(
    onResume: (HistoryEntry) -> Unit,
    /** 空列表时那个「去看看首页」按钮要去哪。用它把焦点交回一级导航 */
    onGoHome: () -> Unit,
) {
    val vm: HistoryViewModel = viewModel()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val theme = AppTheme.current
    LaunchedEffect(Unit) { vm.load() }
    DisposableEffect(vm) { onDispose { vm.stopLoading() } }
    top.bilitv.ui.OnRefreshRequest { vm.load() }

    val firstItem = remember { FocusRequester() }
    val clearButton = remember { FocusRequester() }
    val manageButton = remember { FocusRequester() }
    val emptyButton = remember { FocusRequester() }
    val list = vm.items

    /*
     * 管理模式。**只在这一页活着**（跟着 `remember` 走）——
     * 切走再回来就回到正常模式，不会出现"用户忘了自己开着管理模式、
     * 点一下视频结果删了"这种事。
     */
    var manageMode by remember { mutableStateOf(false) }

    /*
     * 列表空了就自动退出管理模式。
     *
     * 不退出的话，用户删到最后一条时会卡在一个"没有东西可管"的模式里 ——
     * 满屏只有顶部一个「完成」，而他刚才是为了删东西进来的，认知上很别扭。
     */
    LaunchedEffect(list.isEmpty()) {
        if (list.isEmpty()) manageMode = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HistoryHeader(
            count = list.size,
            manageMode = manageMode,
            onToggleManage = { manageMode = !manageMode },
            onClear = {
                vm.clear()
                manageMode = false
            },
            manageRequester = manageButton.takeIf { list.isNotEmpty() },
            clearRequester = clearButton.takeIf { list.isNotEmpty() },
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (list.isEmpty()) {
                EmptyState(onGoHome = onGoHome, requester = emptyButton)
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = theme.gridCells(),
                    contentPadding = PaddingValues(theme.screenPadding),
                    horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
                    verticalArrangement = Arrangement.spacedBy(theme.rowGap),
                    modifier = Modifier.fillMaxSize().verticalScrollbar(gridState),
                ) {
                    gridItems(list, key = { it.key }) { entry ->
                        val isFirst = entry.key == list.first().key
                        FeedCard(
                            item = entry.toFeedItem(),
                            onClick = {
                                // ★ 管理模式里 OK = 删掉这条；正常模式里 OK = 接着看
                                if (manageMode) vm.remove(entry.key) else onResume(entry)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                /*
                                 * ★ 从**第一张卡**按「上」必须能回到头部的「管理 / 清空记录」。
                                 *
                                 * ## 为什么需要显式指定（2026-09-30 实测踩到）
                                 *
                                 * 这一页改成 4 列网格之后，第一行的卡片**只有 1/4 屏宽**，
                                 * 而 Compose 的「上」是**在当前卡片宽度的"束"里**往上找节点的 ——
                                 * 头部那两个按钮在最右边（x≈1690~1910），**完全在束的外面**。
                                 * 找不到候选就退化成"找最近的可聚焦节点"，于是
                                 * **按「上」直接跳到了侧栏的「动态」**（实测：焦点从历史页
                                 * 第一张卡跳到了侧栏，再按 OK 就进了动态页）。
                                 *
                                 * 后果：**「管理」和「清空记录」变得几乎够不到** ——
                                 * 只有第一行最右边那张卡按「上」才碰得到（做过这行的只有两张）。
                                 * 而它们是这一页**唯一能删东西的入口**。
                                 *
                                 * 修法就是显式钉一条：第一张卡的「上」= 头部按钮。
                                 * 只钉第一张，是因为**用户从网格往上走时手里一定是第一张**
                                 * （进页面默认焦点也在它上面）—— 钉多了反而制造意外。
                                 *
                                 * ⚠️ `focusProperties` 必须写在 `FeedCard` 传进来的 `modifier` 里
                                 * （它在 `focusRing` 的 focusable **之前**），写在后面挂不上。
                                 */
                                .then(
                                    if (isFirst && !manageMode) {
                                        Modifier.focusProperties { up = manageButton }
                                    } else {
                                        Modifier
                                    }
                                ),
                            // ★ 进度条：这就是少爷要的那一条
                            progress = entry.fraction,
                            manageMode = manageMode,
                            focusRequester = firstItem.takeIf { isFirst },
                        )
                    }
                }
            }
        }
    }

    /*
     * 进页面自动落焦点。
     *
     * ## 三种落点，一个都不能少
     *
     * | 情况 | 落点 |
     * |---|---|
     * | 有记录 | 第一条 |
     * | 空列表 | 「去看看首页」按钮 |
     * | 刚删掉一条 | 重新落回第一条 |
     *
     * ### 为什么"空列表"和"刚删掉一条"要单独说
     *
     * ⚠️ **它们曾经是一个真实的死键事故**：清空/删完之后，这一页上
     * 一个可聚焦的东西都不剩，遥控器按方向键毫无反应 ——
     * 而这一页在 `AppShell` 里被标成"自己管焦点"，侧栏**不会**代收。
     * 表现就是"历史页清空之后整个 App 卡死了"，实际上进程活得好好的。
     *
     * 这就是 `docs/13` §2.1 说的那类问题：**能聚焦的元素数量会随数据变化**，
     * 所以"这一页有没有焦点"不是页面级的静态属性，得跟着数据一起判断。
     *
     * `generation` 参与 key：删掉一条之后 `key` 变了 → effect 重跑 → 焦点落回第一条。
     * 不这么做的话，删的是当前焦点那一张，Compose 只能自己找邻居，
     * 落点不可预测（可能落到侧栏、也可能哪儿都不落）。
     */
    RequestFocusOnAppear(firstItem, if (list.isEmpty()) null else "${list.first().key}@${vm.generation}")
    RequestFocusOnAppear(emptyButton, list.isEmpty())
}

/** 顶部：标题 + 隐私说明 + 管理 / 清空 */
@Composable
private fun HistoryHeader(
    count: Int,
    manageMode: Boolean,
    onToggleManage: () -> Unit,
    onClear: () -> Unit,
    manageRequester: FocusRequester?,
    clearRequester: FocusRequester?,
) {
    val theme = AppTheme.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = theme.screenPadding,
                end = theme.screenPadding,
                top = 8.dp,
                bottom = 12.dp,
            ),
    ) {
        Column {
            Text(
                text = stringResource(R.string.nav_history),
                style = TextStyle(fontSize = AppType.H1, fontWeight = FontWeight.SemiBold),
                color = theme.textPrimary,
            )
            Text(
                text = (if (count > 0) stringResource(R.string.history_count, count) else "") +
                    stringResource(R.string.history_local_hint) +
                    if (manageMode) stringResource(R.string.history_manage_hint) else "",
                style = TextStyle(fontSize = AppType.Caption),
                color = if (manageMode) theme.primary else theme.textTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        /*
         * 两个按钮**只在有记录时给**。
         *
         * 空列表上摆一个点了没反应的按钮，是电视应用里最容易让人以为"卡死了"的东西之一
         * —— 按下去界面上什么都不变。空的时候它们直接不存在。
         */
        if (count > 0) {
            TvCard(
                onClick = onToggleManage,
                modifier = Modifier.then(
                    // focusRequester 必须挂在 clickable 之前（focusRequester 认的是后面最近的焦点目标）
                    if (manageRequester != null) Modifier.focusRequester(manageRequester) else Modifier
                ),
                background = if (manageMode) theme.primary else theme.surfaceHigh,
                contentDescription = if (manageMode) stringResource(R.string.history_manage_exit) else stringResource(R.string.history_manage_enter),
            ) {
                Text(
                    text = if (manageMode) stringResource(R.string.action_done) else stringResource(R.string.action_manage),
                    style = TextStyle(fontSize = AppType.Body3, fontWeight = FontWeight.Medium),
                    color = if (manageMode) theme.onPrimary else theme.textPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                )
            }

            Spacer(Modifier.width(10.dp))

            TvCard(
                onClick = onClear,
                modifier = Modifier.then(
                    if (clearRequester != null) Modifier.focusRequester(clearRequester) else Modifier
                ),
                background = theme.surfaceHigh,
                contentDescription = stringResource(R.string.history_clear_description),
            ) {
                Text(
                    text = stringResource(R.string.history_clear),
                    style = TextStyle(fontSize = AppType.Body3),
                    color = theme.textPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onGoHome: () -> Unit, requester: FocusRequester) {
    val theme = AppTheme.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.history_empty),
                style = TextStyle(fontSize = AppType.H4, fontWeight = FontWeight.Medium),
                color = theme.textPrimary,
            )
            Text(
                text = stringResource(R.string.history_empty_hint),
                style = TextStyle(fontSize = AppType.Meta),
                color = theme.textSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
            /*
             * ★ 这个按钮不只是"方便"，它是**这一页空着时的唯一焦点落点**。
             * 没有它，清空之后整个页面没有一个可聚焦元素，遥控器就彻底按不动了
             * （详见 [HistoryScreen] 里的说明）。
             */
            TvCard(
                onClick = onGoHome,
                modifier = Modifier
                    .focusRequester(requester)
                    .padding(top = 24.dp),
                background = theme.surfaceHigh,
                contentDescription = stringResource(R.string.action_go_home),
            ) {
                Text(
                    text = stringResource(R.string.action_browse_home),
                    style = TextStyle(fontSize = AppType.Body2),
                    color = theme.textPrimary,
                    modifier = Modifier.padding(horizontal = 26.dp, vertical = 13.dp),
                )
            }
        }
    }
}

/**
 * 历史页的数据。
 *
 * 逻辑薄到几乎只是转发 —— 这**是刻意的**：记录本身的去重/排序/截断全在
 * `History` 的纯函数里（那样才能单测），这里只负责"读出来给界面、改完再读一遍"。
 * 一个把所有规则都塞进 ViewModel 的写法，会让这些规则只能靠手点来验证。
 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp
    private var repairJob: Job? = null

    var items by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    /**
     * 每次增删都 +1。
     *
     * 它唯一的用途是当 `RequestFocusOnAppear` 的 key —— 删掉一条之后
     * 焦点要**重新落回第一条**，而"条目列表变了"这件事本身不是个可比较的 key
     * （`items` 每次 `load()` 都是新对象，但值可能一模一样）。
     * 用一个单调递增的计数最直白，也绝不会漏。
     */
    var generation by mutableStateOf(0)
        private set

    fun load() {
        items = graph.history.all()
        stopLoading()
        val stale = items.filter { it.isPgc && it.title.startsWith("第 ") && it.title.endsWith(" 集") }
        if (stale.isEmpty()) return
        repairJob = viewModelScope.launch {
            for (entry in stale) {
                try {
                    val detail = graph.api.pgcDetail(0L, entry.epId)
                    coroutineContext.ensureActive()
                    val episode = detail?.episodes?.firstOrNull { it.epId == entry.epId }
                    if (episode != null && graph.history.get(entry.key)?.title == entry.title) {
                        graph.history.updateTitle(entry.key, detail.playbackTitle(episode))
                        items = graph.history.all()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* 本地历史仍可用，下一次打开重试。 */ }
                delay(1200L)
            }
        }
    }

    fun stopLoading() {
        repairJob?.cancel()
        repairJob = null
    }

    fun remove(key: String) {
        graph.history.remove(key)
        generation++
        load()
    }

    fun clear() {
        graph.history.clear()
        generation++
        load()
    }
}

/**
 * 把一条观看记录转成一张**首页那样的卡**。
 *
 * 2026-09-30 少爷要求历史和动态都用首页那种视频卡，所以这里有这个映射 ——
 * 不这么做就要为历史页单独写一套卡片，而"卡片长什么样"应该是**一个组件说了算**。
 *
 * - `durationSec`：记录里存的是**毫秒**（`durationMs`），卡片的药丸要秒
 * - `pubDateSec`：用 `updatedAtSec`（最后一次观看时间）—— 历史页上"什么时候看的"
 *   比"什么时候发布的"有用
 * - `viewCount` 留 0：历史记录里没有播放量，**不编一个假的**
 */
private fun HistoryEntry.toFeedItem(): FeedItem = FeedItem(
    bvid = bvid,
    badge = badge,
    title = title,
    cover = cover,
    ownerName = owner,
    durationSec = (durationMs / 1000L).toInt(),
    viewCount = 0L,          // 历史记录里没有播放量 —— 不编一个假的（卡片会自动不画那个药丸）
    pubDateSec = updatedAtSec,
)
