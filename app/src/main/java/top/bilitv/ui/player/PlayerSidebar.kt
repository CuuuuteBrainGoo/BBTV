package top.bilitv.ui.player

import top.bilitv.ui.components.verticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.theme.AppTheme

/** 列表只在按键呼出后加载；返回／关闭统一回播放，方向键不穿透到背后。 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun PlayerSidebar(vm: PlayerViewModel, onClose: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val theme = AppTheme.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val closeFocus = remember { FocusRequester() }
    BoxWithConstraints(modifier.fillMaxHeight()) {
    val requested = vm.danmakuSettings.playerSidebarWidth.takeIf { it > 0 } ?: if (vm.sideParts.isEmpty()) .48f else .78f
    val minimum = if (vm.sideParts.isEmpty()) 240.dp else 420.dp
    Column(Modifier.fillMaxHeight().width((maxWidth * requested).coerceIn(minimum.coerceAtMost(maxWidth), maxWidth))
        .background(Color(0xF20B0B10)).focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()
        .padding(if (maxHeight < 320.dp) 8.dp else 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvCard(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(closeFocus), focusedScale = 1f, contentDescription = "关闭侧栏，返回播放") {
                Text("关闭 · 返回播放", color = theme.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            TvCard(onClick = onSettings, modifier = Modifier.weight(1f).heightIn(min = 48.dp), focusedScale = 1f, contentDescription = "弹幕/字幕设置") {
                Text("弹幕/字幕设置", color = theme.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.sideParts.isNotEmpty()) {
                Column(Modifier.weight(.8f)) {
                    Text("分P", color = theme.primary, style = MaterialTheme.typography.titleMedium)
                    SidebarItems(vm.sideParts, vm, Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f)) {
                Text(vm.sideTitle, color = theme.primary, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.fillMaxSize().verticalScrollbar(listState), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(vm.sideItems, key = { "${it.bvid}/${it.epId}/${it.seasonId}" }) { item -> SidebarItem(item, vm) }
                    item {
                        if (vm.sideLoading) CircularProgressIndicator(color = theme.primary, modifier = Modifier.size(26.dp))
                        else if (vm.sideError != null) {
                            Text(vm.sideError.orEmpty(), color = theme.textSecondary)
                            SidebarAction("重新加载", vm::loadSidebarMore)
                        } else if (vm.sideHasMore) SidebarAction("加载更多", vm::loadSidebarMore)
                        else Text(if (vm.sideItems.isEmpty()) "暂无可用视频" else "已显示全部", color = theme.textSecondary)
                    }
                }
            }
        }
    }
    }
    RequestFocusOnAppear(closeFocus, true)
}

@Composable
private fun SidebarItems(items: List<NextTarget>, vm: PlayerViewModel, modifier: Modifier) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(modifier.verticalScrollbar(listState), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(items, key = { it.cid }) { SidebarItem(it, vm) }
    }
}

@Composable
private fun SidebarItem(item: NextTarget, vm: PlayerViewModel) {
    val theme = AppTheme.current
    val current = vm.sideItemCurrent(item)
    TvCard(onClick = { vm.playSideItem(item) }, modifier = Modifier.fillMaxWidth(), focusedScale = 1f,
        contentDescription = (if (current) "正在播放，" else "播放，") + item.title) {
        Text((if (current) "▶ " else "") + item.title, color = if (current) theme.primary else theme.textPrimary,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun SidebarAction(label: String, action: () -> Unit) {
    TvCard(onClick = action, focusedScale = 1f, contentDescription = label) {
        Text(label, color = AppTheme.current.textPrimary, modifier = Modifier.padding(12.dp))
    }
}
