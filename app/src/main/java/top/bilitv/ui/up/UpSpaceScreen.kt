package top.bilitv.ui.up

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.rememberScrollState
import top.bilitv.ui.components.scrollWithScrollbar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import top.bilitv.R
import top.bilitv.ui.components.BackChip
import top.bilitv.ui.components.FeedCard
import top.bilitv.ui.components.FilledActionButton
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.fixedScheme
import top.bilitv.ui.theme.gridCells
import top.bilitv.ui.components.LoadFeedback
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType

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
    val gridState = rememberLazyGridState()
    val theme = AppTheme.current

    LaunchedEffect(mid) { vm.load(mid) }
    DisposableEffect(vm) { onDispose { vm.stopLoading() } }
    top.bilitv.ui.OnRefreshRequest { if (!vm.loading && !vm.moreLoading) vm.reload() }

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
                    Text(listOfNotNull(profile.level?.let { "LV$it" }, profile.fans?.let { stringResource(R.string.up_fans, it) }).joinToString(" · "),
                        color = theme.primary, style = TextStyle(fontSize = AppType.Caption))
                    if (profile.sign.isNotBlank()) Text(profile.sign, color = theme.textSecondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = AppType.Small))
                }
                if (profile.relation != null) {
                    TvCard(onClick = { vm.changeRelation(if (profile.followed) 2 else 1) }, focusedScale = 1f,
                        contentDescription = if (profile.followed) stringResource(R.string.up_unfollow) else stringResource(R.string.up_follow)) {
                        Text(if (profile.followed) stringResource(R.string.up_followed) else stringResource(R.string.up_follow), color = theme.primary, modifier = Modifier.padding(12.dp))
                    }
                    TvCard(onClick = { vm.changeRelation(if (profile.blocked) 6 else 5) }, focusedScale = 1f,
                        contentDescription = if (profile.blocked) stringResource(R.string.up_unblock) else stringResource(R.string.up_block)) {
                        Text(if (profile.blocked) stringResource(R.string.up_unblock) else stringResource(R.string.up_block), color = theme.textPrimary, modifier = Modifier.padding(12.dp))
                    }
                } else TvCard(onClick = { vm.refreshProfile(); if (!vm.loggedIn) onNeedLogin() }, focusedScale = 1f,
                    contentDescription = if (vm.loggedIn) stringResource(R.string.up_refresh_relation_description) else stringResource(R.string.up_follow_after_login_description)) {
                    Text(if (vm.loggedIn) stringResource(R.string.up_refresh_profile) else stringResource(R.string.action_login), color = theme.primary, modifier = Modifier.padding(12.dp))
                }
            }
        }
        vm.notice?.let { Text(it, color = theme.textSecondary, modifier = Modifier.padding(horizontal = theme.screenPadding)) }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                vm.loading && list.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                list.isEmpty() -> UpNotice(
                    state = vm.state,
                    filteredPage = vm.state == UpState.READY && vm.canLoadMore,
                    pagingError = vm.loadError,
                    loadingMore = vm.moreLoading,
                    actionLabel = when {
                        vm.moreLoading -> stringResource(R.string.action_loading)
                        vm.loadError != null -> stringResource(R.string.action_reload)
                        vm.canLoadMore -> stringResource(R.string.action_load_more)
                        vm.state == UpState.NEED_LOGIN -> stringResource(R.string.action_sign_in)
                        vm.state == UpState.ERROR -> stringResource(R.string.action_reload)
                        else -> stringResource(R.string.action_back)
                    },
                    onAction = {
                        when {
                            vm.loadError != null -> vm.retry()
                            vm.canLoadMore -> vm.more()
                            vm.state == UpState.NEED_LOGIN -> onNeedLogin()
                            vm.state == UpState.ERROR -> vm.reload()
                            else -> onBack()
                        }
                    },
                    requester = retryButton,
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
                    gridItems(list, key = { it.bvid }) { item ->
                        FeedCard(
                            item = item,
                            onClick = { onOpen(item.bvid) },
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = firstCard.takeIf { item.bvid == list.first().bvid },
                        )
                    }
                    if (vm.loading || vm.loadError != null) item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadFeedback(vm.loading, vm.loadError, vm::retry)
                    }
                    if (vm.canLoadMore && vm.loadError == null) item(span = { GridItemSpan(maxLineSpan) }) {
                        TvCard(onClick = { vm.more() }, focusedScale = 1f, contentDescription = stringResource(R.string.up_load_more_description)) {
                            Text(if (vm.moreLoading) stringResource(R.string.loading) else stringResource(R.string.action_load_more), color = theme.primary, modifier = Modifier.padding(16.dp))
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

        Column(Modifier.weight(1f)) {
            Text(
                text = name.ifBlank { stringResource(R.string.player_creator) },
                style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
                color = theme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (count > 0) stringResource(R.string.up_submission_count, count) else stringResource(R.string.up_submission_list),
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
    filteredPage: Boolean,
    pagingError: String?,
    loadingMore: Boolean,
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
            text = if (loadingMore) stringResource(R.string.up_loading_submissions) else if (pagingError != null) pagingError
                else if (filteredPage) stringResource(R.string.up_page_empty) else when (state) {
                UpState.NEED_LOGIN -> stringResource(R.string.up_need_login)
                UpState.EMPTY -> stringResource(R.string.up_empty)
                UpState.ERROR -> stringResource(R.string.up_error)
                // 不该走到这里（调用方只在"列表空且不在加载中"时渲染这一屏）。
                // 写出来是因为 `when` 当表达式用必须穷尽，留一句话而不是留空白。
                UpState.LOADING, UpState.READY -> stringResource(R.string.up_state_error)
            },
            style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (filteredPage || pagingError != null) stringResource(R.string.up_paging_hint) else when (state) {
                UpState.NEED_LOGIN ->
                    stringResource(R.string.up_need_login_hint)
                UpState.EMPTY ->
                    stringResource(R.string.up_empty_hint)
                UpState.ERROR ->
                    stringResource(R.string.up_error_hint)
                UpState.LOADING, UpState.READY ->
                    stringResource(R.string.up_state_error_hint)
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
