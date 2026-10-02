package top.bilitv.ui.fav

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.bilitv.data.model.FavFolder
import top.bilitv.ui.components.BackChip
import top.bilitv.ui.components.FeedCard
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.focusRing
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

    var focusKick by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    // 换层（夹列表 ↔ 夹内）时把焦点送回第一项 —— 否则焦点会留在已经不存在的那一项上
    LaunchedEffect(vm.state) { focusKick++ }

    Column(modifier = Modifier.fillMaxSize().background(theme.background)) {
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
                text = vm.opened?.title ?: "收藏",
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
                            total <= 0 -> "${vm.items.size} 条"
                            vm.items.size >= total -> "$total 条"
                            else -> "已加载 ${vm.items.size} / 共 $total 条"
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
                    text = "收藏要登录才能看",
                    actionLabel = "去登录",
                    onAction = onNeedLogin,
                    requester = firstFocus,
                )

                FavState.ERROR -> FavNotice(
                    // 在夹里失败要说"拿不到这个夹的内容"，不能说"拿不到收藏夹" ——
                    // 用户会以为整个收藏功能坏了（而且两种失败的"重试"目标也不一样）
                    text = if (vm.opened != null) {
                        "拿不到这个收藏夹的内容 —— 可能是网络不通"
                    } else {
                        "拿不到收藏夹 —— 可能是接口变了或网络不通"
                    },
                    actionLabel = "重新加载",
                    // ★ 走 retry()：在夹里失败就重试这个夹，而不是把用户甩回夹列表
                    onAction = { vm.retry() },
                    requester = firstFocus,
                )

                FavState.FOLDERS -> if (vm.folders.isEmpty()) {
                    FavNotice(
                        text = "还没有收藏夹",
                        actionLabel = "去首页看看",
                        onAction = onBack,
                        requester = firstFocus,
                    )
                } else {
                    FolderList(vm.folders, firstFocus) { vm.open(it) }
                }

                FavState.ITEMS -> if (vm.items.isEmpty()) {
                    FavNotice(
                        text = "这个收藏夹是空的",
                        actionLabel = "返回收藏夹",
                        onAction = { vm.backToFolders() },
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
                        contentDescription = "${f.title}，${f.count} 条",
                        restFill = theme.surface,
                        elevateOnFocus = true,
                        onClick = { onOpen(f) },
                    )
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = f.title.ifBlank { "未命名收藏夹" },
                    style = TextStyle(fontSize = AppType.CardTitle),
                    color = theme.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${f.count} 条",
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
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last -> if (last >= vm.items.size - 8) vm.loadMore() }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(theme.cardColumns),
        state = gridState,
        contentPadding = PaddingValues(theme.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
        verticalArrangement = Arrangement.spacedBy(theme.rowGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(vm.items, key = { it.bvid }) { item ->
            FeedCard(
                item = item,
                onClick = { onOpenVideo(item.bvid, vm.opened?.id ?: 0L, vm.opened?.title.orEmpty()) },
                modifier = Modifier.fillMaxWidth(),
                focusRequester = requester.takeIf { item.bvid == vm.items.firstOrNull()?.bvid },
            )
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
