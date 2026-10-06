package top.bilitv.ui.fav

import top.bilitv.ui.theme.pageBackground

import top.bilitv.ui.components.verticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.bilitv.R
import top.bilitv.data.model.FavFolder
import top.bilitv.ui.components.BackChip
import top.bilitv.ui.components.FeedCard
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.focusRing
import top.bilitv.ui.theme.gridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import top.bilitv.ui.components.LoadFeedback
import top.bilitv.ui.theme.nearEnd
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType

/**
 * 收藏页。
 *
 * ## ⚠️ 整页**未在真机实测**（要登录，写代码时手上没有登录环境）
 *
 * 两个接口都要登录态。所以：
 * - 字段名与形状按公开文档 + 同类项目写；
 * - **配套探针 `tools/probe_fav.py`**，少爷登录后跑一次核对；
 * - 界面上"取不到"分四种说法（见 [FavState]），**不合并成一句"没有内容"** ——
 *   那正是 `docs/99` §C 那类"看起来正常其实没用"的温床。
 *
 * ## 两层：先选夹、再看内容
 *
 * 收藏夹可能有很多个，直接铺内容会不知道在看哪个夹。按返回键从"夹内"退到"夹列表"。
 */
@Composable
fun FavScreen(onBack: () -> Unit, onOpenVideo: (String, Long, String) -> Unit, onNeedLogin: () -> Unit) {
    val vm: FavViewModel = viewModel()
    val theme = AppTheme.current

    LaunchedEffect(Unit) { vm.load() }
    DisposableEffect(vm) { onDispose { vm.stopLoading() } }
    top.bilitv.ui.OnRefreshRequest { vm.reload() }

    var focusKick by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    // 换层（夹列表 ↔ 夹内）时把焦点送回第一项 —— 否则焦点会留在已经不存在的那一项上
    LaunchedEffect(vm.state) { focusKick++ }

    Column(modifier = Modifier.fillMaxSize().background(theme.pageBackground)) {
        BackChip(
            // 在夹内按「返回」先退回夹列表，再按才退出整页 —— 和用户的心理模型一致
            onBack = { if (vm.opened != null) vm.backToFolders() else onBack() },
            modifier = Modifier.padding(theme.screenPadding),
        )

        Row(
            modifier = Modifier.padding(horizontal = theme.screenPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = vm.opened?.title ?: stringResource(R.string.fav_title),
                style = TextStyle(fontSize = AppType.H1, fontWeight = FontWeight.Bold),
                color = theme.textPrimary,
            )
            if (vm.opened != null) {
                Text(
                    /*
                     * ★ 显示的是**收藏夹的真实总数**，不是"已经拉到多少条"。
                     *
                     * 2026-09-30 真机实测踩到：默认收藏夹有 **318 条**，
                     * 而这里原来写的是 `vm.items.size` —— 第一页只拉 20 条，
                     * 于是标题上写着「**19 条**」。用户会以为自己的收藏丢了。
                     *
                     * 分页是有的（列表往下滚会继续加载），所以正确说法是
                     * 「已加载 X / 共 Y 条」，扫一眼就知道还有没有。
                     * 拿不到总数（`count` 缺失或为 0）时退回显示已加载数 —— 不编一个假的。
                     */
                    text = run {
                        val total = vm.opened?.count ?: 0
                        when {
                            total <= 0 -> stringResource(R.string.fav_count, vm.items.size)
                            vm.items.size >= total -> stringResource(R.string.fav_count, total)
                            else -> stringResource(R.string.fav_count_loaded, vm.items.size, total)
                        }
                    },
                    style = TextStyle(fontSize = AppType.Meta),
                    color = theme.textTertiary,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (vm.state) {
                FavState.LOADING, FavState.ITEMS_LOADING ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                FavState.NEED_LOGIN -> FavNotice(
                    text = stringResource(R.string.fav_need_login),
                    actionLabel = stringResource(R.string.action_sign_in),
                    onAction = onNeedLogin,
                    requester = firstFocus,
                )

                FavState.ERROR -> FavNotice(
                    // 在夹里失败要说"拿不到这个夹的内容"，不能说"拿不到收藏夹" ——
                    // 用户会以为整个收藏功能坏了（而且两种失败的"重试"目标也不一样）
                    text = if (vm.opened != null) {
                        stringResource(R.string.fav_error_items)
                    } else {
                        stringResource(R.string.fav_error_folders)
                    },
                    actionLabel = stringResource(R.string.action_reload),
                    // ★ 走 retry()：在夹里失败就重试这个夹，而不是把用户甩回夹列表
                    onAction = { vm.retry() },
                    requester = firstFocus,
                )

                FavState.FOLDERS -> if (vm.folders.isEmpty()) {
                    FavNotice(
                        text = stringResource(R.string.fav_empty_folders),
                        actionLabel = stringResource(R.string.action_browse_home),
                        onAction = onBack,
                        requester = firstFocus,
                    )
                } else {
                    FolderList(vm.folders, firstFocus) { vm.open(it) }
                }

                FavState.ITEMS -> if (vm.items.isEmpty()) {
                    FavNotice(
                        text = if (vm.loadingMore) stringResource(R.string.fav_loading_items) else vm.error ?: if (vm.hasMore) stringResource(R.string.fav_page_empty) else stringResource(R.string.fav_empty_items),
                        actionLabel = if (vm.loadingMore) stringResource(R.string.action_loading) else if (vm.error != null) stringResource(R.string.action_reload) else if (vm.hasMore) stringResource(R.string.action_load_more) else stringResource(R.string.fav_back_folders),
                        onAction = { if (vm.error != null) vm.retry() else if (vm.hasMore) vm.loadMore() else vm.backToFolders() },
                        requester = firstFocus,
                    )
                } else {
                    ItemGrid(vm, firstFocus, onOpenVideo)
                }
            }
        }
    }

    RequestFocusOnAppear(firstFocus, "fav-$focusKick")
}

@Composable
private fun FolderList(
    folders: List<FavFolder>,
    requester: FocusRequester,
    onOpen: (FavFolder) -> Unit,
) {
    val theme = AppTheme.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(state = listState,
        modifier = Modifier.fillMaxSize().verticalScrollbar(listState),
        contentPadding = PaddingValues(
            start = theme.screenPadding, end = theme.screenPadding,
            top = 14.dp, bottom = 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(folders, key = { it.id }) { f ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (f == folders.firstOrNull()) Modifier.focusRequester(requester) else Modifier)
                    .focusRing(
                        contentDescription = stringResource(R.string.fav_folder_description, f.title, f.count),
                        restFill = theme.surface,
                        elevateOnFocus = true,
                        onClick = { onOpen(f) },
                    )
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = f.title.ifBlank { stringResource(R.string.fav_unnamed_folder) },
                    style = TextStyle(fontSize = AppType.CardTitle),
                    color = theme.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.fav_count, f.count),
                    style = TextStyle(fontSize = AppType.Meta),
                    color = theme.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun ItemGrid(
    vm: FavViewModel,
    requester: FocusRequester,
    onOpenVideo: (String, Long, String) -> Unit,
) {
    val theme = AppTheme.current
    val gridState = rememberLazyGridState()

    // 往下滚加载下一批。和首页同一套做法（用 snapshotFlow 观察最后可见下标）。
    LaunchedEffect(gridState, vm.items.size) {
        snapshotFlow { gridState.layoutInfo.nearEnd(vm.items.size) }
            .collect { near -> if (near) vm.loadMore() }
    }

    LazyVerticalGrid(
        columns = theme.gridCells(),
        state = gridState,
        contentPadding = PaddingValues(theme.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
        verticalArrangement = Arrangement.spacedBy(theme.rowGap),
        modifier = Modifier.fillMaxSize().verticalScrollbar(gridState),
    ) {
        gridItems(vm.items, key = { it.bvid }) { item ->
            FeedCard(
                item = item,
                onClick = { onOpenVideo(item.bvid, vm.opened?.id ?: 0L, vm.opened?.title.orEmpty()) },
                modifier = Modifier.fillMaxWidth(),
                focusRequester = requester.takeIf { item.bvid == vm.items.firstOrNull()?.bvid },
            )
        }
        if (vm.loadingMore || vm.error != null) item(span = { GridItemSpan(maxLineSpan) }) {
            LoadFeedback(vm.loadingMore, vm.error, vm::retry)
        }
        else if (vm.hasMore) item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().focusRing(contentDescription = stringResource(R.string.fav_load_more_description), onClick = vm::loadMore).padding(16.dp)) {
                Text(stringResource(R.string.action_load_more), color = theme.textPrimary)
            }
        }
    }
}

/** 拿不到内容时的那一块 —— 一句话 + 一个可聚焦的动作。**内容是空的也要有焦点落点。** */
@Composable
private fun FavNotice(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    requester: FocusRequester,
) {
    val theme = AppTheme.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, style = TextStyle(fontSize = AppType.Body1), color = theme.textSecondary)
        Row(
            modifier = Modifier
                .padding(top = 18.dp)
                .focusRequester(requester)
                .focusRing(contentDescription = actionLabel, onClick = onAction)
                .padding(horizontal = 26.dp, vertical = 14.dp),
        ) {
            Text(text = actionLabel, style = TextStyle(fontSize = AppType.Body2), color = theme.textPrimary)
        }
    }
}
