package top.bilitv.ui.up

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.bilitv.BiliTvApp
import top.bilitv.data.model.FeedItem
import top.bilitv.data.model.UpProfile
import top.bilitv.util.AppLog

class UpSpaceViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as BiliTvApp
    var items by mutableStateOf<List<FeedItem>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var total by mutableStateOf(0L); private set
    var state by mutableStateOf(UpState.LOADING); private set
    var profile by mutableStateOf<UpProfile?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var moreLoading by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set
    var canLoadMore by mutableStateOf(false); private set
    val loggedIn: Boolean get() = graph.api.isLoggedIn()
    private var relationBusy = false
    private var page = 0
    private var generation = 0
    private var loadedMid = 0L
    private var failedMore = false
    private var requestJob: Job? = null
    private var profileJob: Job? = null

    fun retry() { if (failedMore) more() else reload() }

    fun stopLoading() {
        requestJob?.cancel(); profileJob?.cancel(); generation++
        requestJob = null; profileJob = null
        loading = false; moreLoading = false
        if (state == UpState.LOADING) state = if (page > 0) UpState.READY else UpState.ERROR
    }

    fun refreshProfile() {
        val mid = loadedMid; val g = generation
        if (mid <= 0) return
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            try {
                val result = graph.api.upProfile(mid)
                if (g == generation && mid == loadedMid) profile = result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (g == generation) notice = "UP资料暂时加载失败，可重试"
                AppLog.w("UpSpace", e.javaClass.simpleName)
            }
        }
    }

    fun changeRelation(action: Int) {
        val p = profile ?: return
        if (relationBusy || p.relation == null) return
        if (p.blocked && action == 1) { notice = "先解除拉黑后再关注"; return }
        val g = generation
        relationBusy = true; notice = "正在更新…"
        viewModelScope.launch {
            try {
                // Disposal cancels reads only. Never retry a social write automatically.
                graph.api.changeUpRelation(p.mid, action)
                if (g != generation || p.mid != loadedMid) return@launch
                profile = null
                refreshProfile()
                notice = "操作已提交，正在读取最新关系状态"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (g == generation) { notice = "关系更新未确认，请刷新资料后检查"; refreshProfile() }
            } finally { relationBusy = false }
        }
    }

    fun load(mid: Long) {
        if (mid <= 0) return
        if (mid == loadedMid && loggedIn && (loading || state == UpState.READY || state == UpState.EMPTY)) return
        fetch(mid)
    }
    fun reload() { if (loadedMid > 0) fetch(loadedMid) }

    private fun fetch(mid: Long) {
        stopLoading()
        if (loadedMid != mid) { items = emptyList(); total = 0; page = 0; profile = null; notice = null }
        loadedMid = mid
        failedMore = false; loadError = null
        if (!loggedIn) {
            items = emptyList(); profile = null; total = 0; page = 0; canLoadMore = false
            state = UpState.NEED_LOGIN
            return
        }
        loading = true
        if (items.isEmpty()) state = UpState.LOADING
        refreshProfile()
        request(first = true)
    }

    fun more() {
        if (loading || moreLoading || !canLoadMore) return
        if (!loggedIn) { fetch(loadedMid); return }
        moreLoading = true; loadError = null
        request(first = false)
    }

    private fun request(first: Boolean) {
        val mid = loadedMid; val g = generation
        val requestedPage = if (first) 1 else page + 1
        requestJob = viewModelScope.launch {
            try {
                val result = graph.api.upVideos(mid, pn = requestedPage)
                    ?: throw java.io.IOException("投稿加载失败，请重试")
                if (g != generation || mid != loadedMid) return@launch
                items = ((if (first) emptyList() else items) + result.items).distinctBy { it.bvid }
                total = result.total; page = requestedPage; canLoadMore = result.hasMore
                failedMore = false; loadError = null
                state = if (items.isNotEmpty() || canLoadMore) UpState.READY else UpState.EMPTY
                AppLog.i("UpSpace", "mid=$mid 页=$page 投稿${items.size}/$total，还有页=$canLoadMore")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (g == generation) {
                    failedMore = !first
                    loadError = if (items.isEmpty()) "投稿加载失败，请重试" else "投稿加载失败，已有内容仍保留，请重试"
                    if (first) state = UpState.ERROR
                    AppLog.w("UpSpace", "读取投稿失败：${e.javaClass.simpleName}")
                }
            } finally { if (g == generation) { loading = false; moreLoading = false } }
        }
    }
}
