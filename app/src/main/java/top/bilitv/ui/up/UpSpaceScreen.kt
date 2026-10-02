package top.bilitv.ui.up

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
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
import top.bilitv.data.model.FeedItem
import top.bilitv.ui.components.BackChip
import top.bilitv.ui.components.FeedCard
import top.bilitv.ui.components.FilledActionButton
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.fixedScheme
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.util.AppLog

/**
 * 一个 UP 主的主页 —— 他的投稿列表。
 *
 * ```
 * ┌──────────────────────────────────────────────────────┐
 * │ ← 返回   ◯ 名字  共 328 个投稿                         │
 * ├──────────────────────────────────────────────────────┤
 * │ ┌────┐ ┌────┐ ┌────┐ ┌────┐   ← 复用首页的视频卡片      │
 * │ └────┘ └────┘ └────┘ └────┘                          │
 * └──────────────────────────────────────────────────────┘
 * ```
 *
 * ## 二级页面，所以没有侧栏
 *
 * 全屏覆盖，左上角给一个常驻「← 返回」（[BackChip]）——
 * 和详情页、PGC 详情页同一个规矩：**屏幕上必须有一条看得见的退路**，
 * 不能只靠遥控器返回键（`docs/11`）。
 *
 * ## ⚠️ 这一页在游客态拿不到数据（接口限制，不是 bug）
 *
 * `/x/space/wbi/arc/search` 加了 buvid3、WBI 签名、`platform=web` 之后
 * 游客态仍然是 `-352 风控校验失败`，再试变 `-412 request was banned`。
 * 实测记录见 `tools/probe_follow.py` 与 `docs/18`。
 *
 * 所以四种状态分开写，用户能做的事各不相同：
 *
 * | 情况 | 说什么 | 给什么按钮 |
 * |---|---|---|
 * | 没登录 | 看 UP 主投稿要登录 | 「去登录」 |
 * | 登录了但空 | 他可能一个视频都没发 | 「返回关注列表」（**焦点落点**） |
 * | 请求失败 | 接口可能变了或网络不通 | 「重新加载」 |
 *
 * @param mid UP 的用户号。
 * @param nameHint 从关注页带过来的昵称。**先显示它**，免得加载期间顶部空一行 ——
 *   和播放页的 `titleHint` 是同一个理由。
 * @param faceHint 从关注页带过来的头像地址。同理。
 * @param onOpen 点一个视频 → 进详情页。
 * @param onBack 返回上一页（真的 pop 由 `Nav.kt` 负责）。
 * @param onNeedLogin 去扫码登录。
 */
@Composable
fun UpSpaceScreen(
    mid: Long,
    nameHint: String,
    faceHint: String,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onNeedLogin: () -> Unit,
) {
    val vm: UpSpaceViewModel = viewModel(key = "up-$mid")
    val theme = AppTheme.current

    LaunchedEffect(mid) { vm.load(mid) }

    val firstCard = remember { FocusRequester() }
    val retryButton = remember { FocusRequester() }
    val list = vm.items

    Column(modifier = Modifier.fillMaxSize()) {
        UpHeader(
            name = vm.profile?.name?.ifBlank { nameHint } ?: nameHint,
            face = vm.profile?.face?.ifBlank { faceHint } ?: faceHint,
            count = vm.total,
            onBack = onBack,
            // 列表空着时「返回」是这一页唯一能聚焦的东西，焦点必须给它
            backRequester = firstCard.takeIf { list.isEmpty() },
        )

        vm.profile?.let { profile ->
            Row(Modifier.fillMaxWidth().padding(horizontal = theme.screenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(listOfNotNull(profile.level?.let { "LV$it" }, profile.fans?.let { "$it 粉丝" }).joinToString(" · "),
                        color = theme.primary, style = TextStyle(fontSize = AppType.Caption))
                    if (profile.sign.isNotBlank()) Text(profile.sign, color = theme.textSecondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = AppType.Small))
                }
                if (profile.relation != null) {
                    TvCard(onClick = { vm.changeRelation(if (profile.followed) 2 else 1) }, focusedScale = 1f,
                        contentDescription = if (profile.followed) "取消关注" else "关注") {
                        Text(if (profile.followed) "已关注" else "关注", color = theme.primary, modifier = Modifier.padding(12.dp))
                    }
                    TvCard(onClick = { vm.changeRelation(if (profile.blocked) 6 else 5) }, focusedScale = 1f,
                        contentDescription = if (profile.blocked) "解除拉黑" else "拉黑") {
                        Text(if (profile.blocked) "解除拉黑" else "拉黑", color = theme.textPrimary, modifier = Modifier.padding(12.dp))
                    }
                } else TvCard(onClick = { vm.refreshProfile(); if (!vm.loggedIn) onNeedLogin() }, focusedScale = 1f,
                    contentDescription = if (vm.loggedIn) "重新读取关注关系" else "登录后关注") {
                    Text(if (vm.loggedIn) "刷新资料" else "登录", color = theme.primary, modifier = Modifier.padding(12.dp))
                }
            }
        }
        vm.notice?.let { Text(it, color = theme.textSecondary, modifier = Modifier.padding(horizontal = theme.screenPadding)) }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                list.isEmpty() -> UpNotice(
                    state = vm.state,
                    actionLabel = when (vm.state) {
                        UpState.NEED_LOGIN -> "去登录"
                        UpState.ERROR -> "重新加载"
                        else -> "返回关注列表"
                    },
                    onAction = when (vm.state) {
                        UpState.NEED_LOGIN -> onNeedLogin
                        UpState.ERROR -> { { vm.reload() } }
                        else -> onBack
                    },
                    requester = retryButton,
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(theme.cardColumns),
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
                    gridItems(list, key = { it.bvid }) { item ->
                        FeedCard(
                            item = item,
                            onClick = { onOpen(item.bvid) },
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = firstCard.takeIf { item.bvid == list.first().bvid },
                        )
                    }
                    if (list.size < vm.total) item(span = { GridItemSpan(maxLineSpan) }) {
                        TvCard(onClick = { vm.more() }, focusedScale = 1f, contentDescription = "加载更多投稿") {
                            Text(if (vm.moreLoading) "正在加载…" else "加载更多", color = theme.primary, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }

    RequestFocusOnAppear(firstCard, if (list.isEmpty()) null else list.first().bvid)
    RequestFocusOnAppear(retryButton, !vm.loading && list.isEmpty())
}

/** 顶部：返回 + 头像 + 名字 + 投稿数 */
@Composable
private fun UpHeader(
    name: String,
    face: String,
    count: Long,
    onBack: () -> Unit,
    backRequester: FocusRequester?,
) {
    val theme = AppTheme.current
    val context = LocalContext.current

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
        BackChip(
            onBack = onBack,
            modifier = Modifier.then(
                if (backRequester != null) Modifier.focusRequester(backRequester) else Modifier
            ),
        )

        Spacer(Modifier.size(18.dp))

        /*
         * 头像。地址是**从关注页带过来的**，不是这里再问一次接口 ——
         * 关注列表里本来就有 `face` 字段，同一个请求里已经拿到了，
         * 再为头像单独打一个接口在电视的低端芯片上是白花的钱。
         * 万一没带过来（比如以后从别处跳进来），就用一块表面色占位，不裂图。
         */
        if (face.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(face.fixedScheme())
                    // 按实际显示尺寸解码，绝不原图解码（低内存的生命线）
                    .size(200, 200)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(theme.surfaceHigh),
            )
            Spacer(Modifier.size(14.dp))
        }

        Column {
            Text(
                text = name.ifBlank { "UP 主" },
                style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
                color = theme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (count > 0) "共 $count 个投稿" else "投稿列表",
                style = TextStyle(fontSize = AppType.Caption),
                color = theme.textTertiary,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun UpNotice(
    state: UpState,
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
                UpState.NEED_LOGIN -> "看 UP 主的投稿需要登录"
                UpState.EMPTY -> "他没有公开的投稿"
                UpState.ERROR -> "拿不到他的投稿列表"
                // 不该走到这里（调用方只在"列表空且不在加载中"时渲染这一屏）。
                // 写出来是因为 `when` 当表达式用必须穷尽，留一句话而不是留空白。
                UpState.LOADING, UpState.READY -> "状态异常，按返回再进一次"
            },
            style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when (state) {
                UpState.NEED_LOGIN ->
                    "B 站对「UP 主投稿列表」这个接口有登录要求，没登录时它会直接拒绝，" +
                        "不是网络问题也不是我们的 bug。扫码登录一次就行。"
                UpState.EMPTY ->
                    "这个账号可能只发专栏或动态，没有视频投稿。回关注列表换一个看看。"
                UpState.ERROR ->
                    "接口可能变了或网络不通。已经登录了还是这样，就是服务端的问题，看日志能有线索。"
                UpState.LOADING, UpState.READY ->
                    "这一屏本来不该出现，麻烦把日志发我。"
            },
            style = TextStyle(fontSize = AppType.Body3, lineHeight = 22.sp),
            color = theme.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )

        /*
         * ★ 这个按钮不只是"方便"，它是**这一页空着时的焦点落点**之一
         * （另一个是左上角的「← 返回」）。见 `docs/11` §2.1。
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

/** UP 主页的四种状态。它决定"拿不到东西时该说什么话、给什么按钮"。 */
enum class UpState { LOADING, NEED_LOGIN, EMPTY, ERROR, READY }

/**
 * UP 主页的数据。
 *
 * 和关注页同一套判据：**"列表空"这件事本身没有含义**，
 * 必须配上"有没有登录"才能翻译成用户看得懂的一句话。
 */
class UpSpaceViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp

    var items by mutableStateOf<List<FeedItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var total by mutableStateOf(0L)
        private set
    var state by mutableStateOf(UpState.LOADING)
        private set
    var profile by mutableStateOf<top.bilitv.data.model.UpProfile?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var moreLoading by mutableStateOf(false)
        private set
    val loggedIn: Boolean get() = graph.api.isLoggedIn()
    private var relationBusy = false
    private var page = 1

    fun refreshProfile() {
        val mid = loadedMid
        if (mid <= 0) return
        viewModelScope.launch {
            try { profile = graph.api.upProfile(mid) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notice = "UP 资料暂时加载失败，可重试"; AppLog.w("UpSpace", e.javaClass.simpleName) }
        }
    }
    fun changeRelation(action: Int) {
        val p = profile ?: return
        if (relationBusy || p.relation == null) return
        if (p.blocked && action == 1) { notice = "先解除拉黑后再关注"; return }
        relationBusy = true; notice = "正在更新…"
        viewModelScope.launch {
            try {
                graph.api.changeUpRelation(p.mid, action)
                profile = null // 不以本地猜测替代写入后的账号关系。
                profile = graph.api.upProfile(p.mid)
                notice = "关系已更新"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notice = "关系更新未确认，请刷新资料后检查"; refreshProfile() }
            finally { relationBusy = false }
        }
    }
    fun more() {
        if (moreLoading || items.size >= total) return
        moreLoading = true
        viewModelScope.launch {
            try {
                val result = graph.api.upVideos(loadedMid, pn = page + 1)
                if (result == null || result.items.isEmpty()) notice = "未取得更多投稿，可以重试"
                else { items = (items + result.items).distinctBy { it.bvid }; total = result.total; page++ }
            } finally { moreLoading = false }
        }
    }

    private var loadedMid = 0L
    private var inFlight = false

    fun load(mid: Long) {
        if (mid <= 0L) return
        // 同一个 UP 且已经成功取到过 → 不重复请求（从详情页返回时页面会重新组合）
        if (mid == loadedMid && state == UpState.READY) return
        fetch(mid)
    }

    fun reload() {
        val m = loadedMid
        inFlight = false
        if (m > 0L) fetch(m)
    }

    private fun fetch(mid: Long) {
        if (inFlight) return
        inFlight = true
        loadedMid = mid
        page = 1
        refreshProfile()
        loading = true
        viewModelScope.launch {
            if (!graph.api.isLoggedIn()) {
                // 没登录就别发 —— 这个接口在游客态确定走不通（-352），
                // 发了只是白等一个往返 + 给日志添一条没有信息量的错误
                items = emptyList()
                state = UpState.NEED_LOGIN
                loading = false
                inFlight = false
                return@launch
            }

            val result = graph.api.upVideos(mid)
            if (result == null) {
                /*
                 * ★ 拿不到 ≠ 他没发过。2026-09-30 修：原来这里只能看到"空列表"，
                 * 于是把网络失败判成了 EMPTY，界面上写着「**他没有公开的投稿**」——
                 * 等于我们网络不通，却替那个 UP 主下了个结论。
                 *
                 * 现在走 ERROR 支：文案说「拿不到他的投稿列表」，并且给「重新加载」。
                 */
                state = UpState.ERROR
                loading = false
                inFlight = false
                AppLog.w("UpSpace", "mid=$mid 投稿拿不到（网络或接口问题）")
                return@launch
            }
            items = result.items
            total = result.total
            state = when {
                result.items.isNotEmpty() -> UpState.READY
                else -> UpState.EMPTY
            }
            loading = false
            inFlight = false
            AppLog.i("UpSpace", "mid=$mid 投稿 ${result.items.size}/${result.total} 条 状态=$state")
        }
    }
}
