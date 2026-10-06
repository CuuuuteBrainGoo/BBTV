package top.bilitv.ui.player

import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.bilitv.R
import top.bilitv.data.api.BiliApi
import top.bilitv.data.model.CommentPage
import top.bilitv.data.model.VideoComment
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.components.verticalScrollbar
import top.bilitv.ui.theme.AppTheme

/** Playback owns this state. No network work before open; closing/switching invalidates old requests. */
class PlayerComments(private val context: Context, private val api: BiliApi, private val scope: CoroutineScope) {
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
                    ?: throw java.io.IOException(context.getString(R.string.player_comments_no_oid))
                if (request != generation) return@launch
                oid = id
                val response: CommentPage = api.comments(id, requestedNewest, requestedOffset)
                if (request != generation) return@launch
                roots = if (replace) response.items else (roots + response.items).distinctBy { it.id }
                total = response.total
                more = response.hasMore && response.nextOffset != requestedOffset
                offset = response.nextOffset.orEmpty()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (request == generation) error = e.message ?: context.getString(R.string.player_comments_failed) }
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
                contentDescription = stringResource(R.string.player_comments_close_description)) { Text(stringResource(R.string.player_close_back), color = theme.textPrimary, modifier = Modifier.padding(12.dp)) }
            Text(stringResource(R.string.player_comments_title, state.total), color = theme.primary,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = if (compactHeight) 4.dp else 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val hot = stringResource(R.string.player_comments_hot)
                val newest = stringResource(R.string.player_comments_new)
                CommentAction(if (!state.newest) stringResource(R.string.player_comments_selected, hot) else hot, modifier = Modifier.weight(1f)) { state.order(false) }
                CommentAction(if (state.newest) stringResource(R.string.player_comments_selected, newest) else newest, modifier = Modifier.weight(1f)) { state.order(true) }
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
                            CommentAction(stringResource(R.string.action_retry), action = state::retry)
                        }
                        state.more -> CommentAction(stringResource(R.string.action_load_more), action = state::loadMore)
                        else -> Text(if (state.total == 0L) stringResource(R.string.player_comments_empty) else stringResource(R.string.player_comments_all_shown), color = theme.textSecondary)
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
        modifier = Modifier.fillMaxWidth(), contentDescription = stringResource(R.string.player_comments_card_description, comment.author, stringResource(if (expanded) R.string.player_comments_collapse_description else R.string.player_comments_expand_description))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (comment.pinned) stringResource(R.string.player_comments_pinned, comment.author) else comment.author, color = theme.primary)
            Text(comment.message, color = theme.textPrimary, maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(listOf(comment.time, stringResource(R.string.player_comments_likes, comment.likes), stringResource(R.string.player_comments_replies, comment.replyCount)).filter { it.isNotBlank() }.joinToString(" · "),
                color = theme.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(if (expanded) stringResource(R.string.player_comments_collapse) else stringResource(R.string.player_comments_expand), color = theme.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
