package top.bilitv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.bilitv.data.api.BiliApi
import top.bilitv.data.model.CommentPage
import top.bilitv.data.model.VideoComment
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.components.verticalScrollbar
import top.bilitv.ui.theme.AppTheme

/** Playback owns this state. No network work before open; closing/switching invalidates old requests. */
class PlayerComments(private val api: BiliApi, private val scope: CoroutineScope) {
    var open by mutableStateOf(false); private set
    var newest by mutableStateOf(false); private set
    var roots by mutableStateOf<List<VideoComment>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var total by mutableStateOf(0L); private set
    var more by mutableStateOf(false); private set
    private var offset = ""
    private var oid = 0L
    private var generation = 0
    private var job: Job? = null
    private var resolve: (suspend () -> Long)? = null

    private fun cancel() { ++generation; job?.cancel(); job = null; loading = false; error = null }
    fun reset() {
        close(); oid = 0L; roots = emptyList()
        offset = ""; more = false; total = 0; resolve = null
    }
    fun show(resolveOid: suspend () -> Long) {
        open = true; resolve = resolveOid
        if (roots.isEmpty() && !loading) fetch(replace = true)
    }
    fun close() { cancel(); open = false }
    fun back() = close()
    fun order(byTime: Boolean) {
        if (byTime == newest) return
        cancel(); newest = byTime; roots = emptyList(); more = false; total = 0
        offset = ""; fetch(replace = true)
    }
    fun retry() = fetch(replace = roots.isEmpty())
    fun loadMore() { if (more) fetch(replace = false) }
    private fun fetch(replace: Boolean) {
        if (loading || !open) return
        loading = true; error = null
        val request = ++generation
        val requestedOffset = if (replace) "" else offset
        val requestedNewest = newest
        job = scope.launch {
            try {
                val id = oid.takeIf { it > 0 } ?: resolve?.invoke()?.takeIf { it > 0 }
                    ?: throw java.io.IOException("当前视频没有可用的评论编号，请稍后重试")
                if (request != generation) return@launch
                oid = id
                val response: CommentPage = api.comments(id, requestedNewest, requestedOffset)
                if (request != generation) return@launch
                roots = if (replace) response.items else (roots + response.items).distinctBy { it.id }
                total = response.total
                more = response.hasMore && response.nextOffset != requestedOffset
                offset = response.nextOffset.orEmpty()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (request == generation) error = e.message ?: "评论加载失败，请重试" }
            finally { if (request == generation) loading = false }
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun PlayerCommentSidebar(state: PlayerComments, requestedWidth: Float, modifier: Modifier = Modifier) {
    val theme = AppTheme.current
    val first = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(state.newest) { list.scrollToItem(0) }
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val compactHeight = maxHeight < 320.dp
        Column(Modifier.fillMaxHeight().width((maxWidth * (requestedWidth.takeIf { it > 0 } ?: .48f))
            .coerceIn(260.dp.coerceAtMost(maxWidth), maxWidth)).background(theme.surface)
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()
            .padding(if (compactHeight) 8.dp else 16.dp)) {
            TvCard(onClick = state::close, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(first), focusedScale = 1f,
                contentDescription = "关闭评论侧栏，返回播放") { Text("关闭 · 返回播放", color = theme.textPrimary, modifier = Modifier.padding(12.dp)) }
            Text("评论 · ${state.total}", color = theme.primary,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = if (compactHeight) 4.dp else 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommentAction(if (!state.newest) "热度 ✓" else "热度", modifier = Modifier.weight(1f)) { state.order(false) }
                CommentAction(if (state.newest) "最新 ✓" else "最新", modifier = Modifier.weight(1f)) { state.order(true) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().verticalScrollbar(list), state = list,
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(state.roots, key = { it.id }) { comment ->
                    CommentCard(comment)
                }
                item {
                    when {
                        state.loading -> CircularProgressIndicator(color = theme.primary, modifier = Modifier.size(26.dp))
                        state.error != null -> {
                            Text(state.error.orEmpty(), color = theme.textSecondary)
                            CommentAction("重试", action = state::retry)
                        }
                        state.more -> CommentAction("加载更多", action = state::loadMore)
                        else -> Text(if (state.total == 0L) "暂无评论" else "已显示当前可读评论", color = theme.textSecondary)
                    }
                }
            }
        }
    }
    RequestFocusOnAppear(first, true)
}

@Composable
private fun CommentAction(label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    TvCard(onClick = action, modifier = modifier.heightIn(min = 48.dp), focusedScale = 1f, contentDescription = label) {
        Text(label, color = AppTheme.current.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun CommentCard(comment: VideoComment) {
    val theme = AppTheme.current
    var expanded by remember(comment.id) { mutableStateOf(false) }
    TvCard(onClick = { expanded = !expanded }, focusedScale = 1f,
        modifier = Modifier.fillMaxWidth(), contentDescription = "${comment.author}，${if (expanded) "收起评论" else "展开评论"}") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text((if (comment.pinned) "置顶 · " else "") + comment.author, color = theme.primary)
            Text(comment.message, color = theme.textPrimary, maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(listOf(comment.time, "${comment.likes}赞", "${comment.replyCount}回复").filter { it.isNotBlank() }.joinToString(" · "),
                color = theme.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(if (expanded) "点击收起" else "点击展开", color = theme.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
