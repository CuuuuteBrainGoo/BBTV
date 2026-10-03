package top.bilitv.ui.dynamic

import android.app.Application
import top.bilitv.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.bilitv.BiliTvApp
import top.bilitv.data.model.CODE_NOT_LOGGED_IN
import top.bilitv.data.model.CODE_REQUEST_FAILED
import top.bilitv.data.model.DynamicItem
import top.bilitv.data.model.mergeDynamicItems
import top.bilitv.util.AppLog

/** First load and older pages share the actual cursor; each successful page is committed once. */
class DynamicViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as BiliTvApp
    var items by mutableStateOf<List<DynamicItem>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var loadingMore by mutableStateOf(false); private set
    var hasMore by mutableStateOf(false); private set
    var moreError by mutableStateOf<Int?>(null); private set
    var refreshError by mutableStateOf<String?>(null); private set
    var state by mutableStateOf(DynamicState.LOADING); private set
    private var offset = ""
    private var busy = false
    private var generation = 0
    private var loadJob: Job? = null

    fun load() {
        if ((state == DynamicState.READY || state == DynamicState.EMPTY) && graph.api.isLoggedIn()) return
        fetch(first = true)
    }
    fun reload() = fetch(first = true)
    fun loadMore() {
        if (busy || !hasMore || state != DynamicState.READY || refreshError != null) return
        fetch(first = false)
    }
    fun stopLoading() {
        loadJob?.cancel(); generation++
        loadJob = null; busy = false; loading = false; loadingMore = false
        if (state == DynamicState.LOADING) state = if (items.isEmpty()) DynamicState.ERROR else DynamicState.READY
    }

    private fun fetch(first: Boolean) {
        if (!first && busy) return
        loadJob?.cancel()
        val g = ++generation
        busy = true; loading = first; loadingMore = !first
        if (first) { refreshError = null; if (items.isEmpty()) state = DynamicState.LOADING }
        moreError = null
        loadJob = viewModelScope.launch {
            var succeeded = false
            try {
                if (!graph.api.isLoggedIn()) {
                    items = emptyList(); offset = ""; hasMore = false; state = DynamicState.NEED_LOGIN
                    return@launch
                }
                val before = if (first) 0 else items.size
                var collected = if (first) emptyList() else items
                var cursor = if (first) "" else offset
                var more = true
                var rounds = 0
                do {
                    val page = graph.api.dynamicFeed(cursor)
                    if (g != generation) return@launch
                    if (page.code != 0) {
                        failed(page.code, first && !succeeded)
                        break
                    }
                    collected = mergeDynamicItems(collected, page.items)
                    val next = page.nextOffset
                    more = page.hasMore && next.isNotBlank() && next != cursor
                    cursor = next
                    // Partial successes survive a later failure/cancellation; retry uses the last successful cursor.
                    items = collected; offset = cursor; hasMore = more
                    state = if (items.isNotEmpty() || hasMore) DynamicState.READY else DynamicState.EMPTY
                    succeeded = true; rounds++
                } while (more && rounds < 3 && if (first) collected.size < 12 else collected.size == before)
                AppLog.i("Dynamic", "动态成功页=$rounds，条数=" + items.size + "，还有页=" + hasMore)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (g == generation) {
                    failed(CODE_REQUEST_FAILED, first && !succeeded)
                    AppLog.w("Dynamic", "读取动态失败：${e.javaClass.simpleName}")
                }
            } finally { if (g == generation) { busy = false; loading = false; loadingMore = false } }
        }
    }

    private fun failed(code: Int, initial: Boolean) {
        if (code == CODE_NOT_LOGGED_IN) {
            if (initial) { items = emptyList(); offset = ""; hasMore = false }
            state = DynamicState.EXPIRED
        } else if (initial) {
            refreshError = if (items.isEmpty()) graph.getString(R.string.dynamic_failed) else graph.getString(R.string.dynamic_failed_keep)
            state = if (items.isEmpty()) DynamicState.ERROR else DynamicState.READY
        } else moreError = code
    }
}
