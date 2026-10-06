package top.bilitv.ui.detail

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import top.bilitv.BiliTvApp
import top.bilitv.R
import top.bilitv.data.model.VideoDetail

/*
 * 视频详情的状态与数据源。
 *
 * 2026-09-29 从 `DetailScreen.kt` 拆出来：重做详情页之后那个文件逼近 300 行上限
 * （`docs/audit/A9` §7.3 的"单文件别超 300 行"）。拆的边界是**"界面"和"状态"**。
 * 两者同一个包（`top.bilitv.ui.detail`），调用方一行 import 都不用改。
 */

class DetailViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp
    private val api = graph.api

    var detail by mutableStateOf<VideoDetail?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var loadedBvid: String? = null
    private var loadedContext: Triple<top.bilitv.data.settings.VideoApiSource, Boolean, Long>? = null
    private var generation = 0
    private var requestJob: Job? = null

    fun stopLoading() {
        generation++
        requestJob?.cancel(); requestJob = null
        loading = false
    }

    fun load(bvid: String, force: Boolean = false) {
        val context = Triple(graph.settings.videoApiSource, api.isLoggedIn(), api.myMid())
        if (!force && loadedBvid == bvid && loadedContext == context && (loading || detail != null)) return
        stopLoading()
        if (loadedBvid != bvid || loadedContext != context) detail = null
        loadedBvid = bvid
        loadedContext = context
        loading = true
        // 重试前先清掉上一条错误，否则出错文案会在转圈时还挂着
        error = null
        val g = generation
        requestJob = viewModelScope.launch {
            try {
                val d = api.videoDetail(bvid)?.takeIf { it.bvid == bvid }
                    ?: throw java.io.IOException("详情不可用")
                currentCoroutineContext().ensureActive()
                if (g != generation || loadedBvid != bvid) return@launch
                detail = d; error = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (g == generation) error = graph.getString(R.string.detail_load_failed)
            } finally { if (g == generation) loading = false }
        }
    }

    /** 「重试」用。必须 force —— 否则会被 `loadedBvid == bvid` 直接挡回去，按了没反应。 */
    fun retry() {
        loadedBvid?.let { load(it, force = true) }
    }
}
