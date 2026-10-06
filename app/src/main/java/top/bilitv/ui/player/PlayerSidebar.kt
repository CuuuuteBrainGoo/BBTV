package top.bilitv.ui.player

import top.bilitv.ui.components.verticalScrollbar
import top.bilitv.ui.components.fixedScheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import top.bilitv.R
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
            TvCard(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(closeFocus), focusedScale = 1f, contentDescription = stringResource(R.string.player_close_sidebar_description)) {
                Text(stringResource(R.string.player_close_back), color = theme.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            TvCard(onClick = onSettings, modifier = Modifier.weight(1f).heightIn(min = 48.dp), focusedScale = 1f, contentDescription = stringResource(R.string.player_danmaku_settings)) {
                Text(stringResource(R.string.player_danmaku_settings), color = theme.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.sideParts.isNotEmpty()) {
                Column(Modifier.weight(.8f)) {
                    Text(stringResource(R.string.detail_parts), color = theme.primary, style = MaterialTheme.typography.titleMedium)
                    SidebarItems(vm.sideParts, vm, Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f)) {
                Text(vm.sideTitle, color = theme.primary, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.fillMaxSize().verticalScrollbar(listState), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(vm.sideItems, key = { "${it.bvid}/${it.epId}/${it.seasonId}" }) { item -> SidebarItem(item, vm, showCover = true) }
                    item {
                        if (vm.sideLoading) CircularProgressIndicator(color = theme.primary, modifier = Modifier.size(26.dp))
                        else if (vm.sideError != null) {
                            Text(vm.sideError.orEmpty(), color = theme.textSecondary)
                            SidebarAction(stringResource(R.string.action_reload), vm::loadSidebarMore)
                        } else if (vm.sideHasMore) SidebarAction(stringResource(R.string.action_load_more), vm::loadSidebarMore)
                        else Text(if (vm.sideItems.isEmpty()) stringResource(R.string.player_unavailable_videos) else stringResource(R.string.player_all_shown), color = theme.textSecondary)
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
        // 分P 列表不显示封面，只保留文字行（封面位在窄栏里会挤掉标题）。
        items(items, key = { it.cid }) { SidebarItem(it, vm, showCover = false) }
    }
}

/** 侧栏缩略图显示尺寸（16:9）。 */
private val SidebarCoverWidth = 84.dp
private val SidebarCoverHeight = 47.25f.dp

/** 分P 保留文字行；其他视频列表使用按显示尺寸解码的封面。 */
@Composable
private fun SidebarItem(item: NextTarget, vm: PlayerViewModel, showCover: Boolean) {
    val theme = AppTheme.current
    val current = vm.sideItemCurrent(item)
    val cover = item.cover
    val coverRequest = if (showCover && cover.isNotBlank()) {
        val density = LocalDensity.current
        val widthPx = with(density) { SidebarCoverWidth.roundToPx() }
        val heightPx = with(density) { SidebarCoverHeight.roundToPx() }
        ImageRequest.Builder(LocalContext.current)
            .data(cover.fixedScheme())
            .size(widthPx, heightPx)
            .build()
    } else null
    TvCard(onClick = { vm.playSideItem(item) }, modifier = Modifier.fillMaxWidth(), focusedScale = 1f,
        contentDescription = stringResource(if (current) R.string.player_now_playing_description else R.string.player_play_description, item.title)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (coverRequest != null) {
                AsyncImage(
                    model = coverRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(SidebarCoverWidth).height(SidebarCoverHeight),
                )
            }
            Text((if (current) "▶ " else "") + item.title, color = if (current) theme.primary else theme.textPrimary,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SidebarAction(label: String, action: () -> Unit) {
    TvCard(onClick = action, focusedScale = 1f, contentDescription = label) {
        Text(label, color = AppTheme.current.textPrimary, modifier = Modifier.padding(12.dp))
    }
}
