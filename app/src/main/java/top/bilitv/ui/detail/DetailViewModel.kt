package top.bilitv.ui.detail

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import top.bilitv.BiliTvApp
import top.bilitv.data.model.VideoDetail

/*
 * 视频详情的状态与数据源。
 *
 * 2026-09-29 从 `DetailScreen.kt` 拆出来：重做详情页之后那个文件逼近 300 行上限
 * （`docs/audit/A9` §7.3 的"单文件别超 300 行"）。拆的边界是**"界面"和"状态"**。
 * 两者同一个包（`top.bilitv.ui.detail`），调用方一行 import 都不用改。
 */

class DetailViewModel(app: Application) : AndroidViewModel(app) {

    private val api = (app as BiliTvApp).api

    var detail by mutableStateOf<VideoDetail?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var loadedBvid: String? = null

    fun load(bvid: String, force: Boolean = false) {
        if (!force && loadedBvid == bvid && detail != null) return
        loadedBvid = bvid
        loading = true
        // 重试前先清掉上一条错误，否则出错文案会在转圈时还挂着
        error = null
        viewModelScope.launch {
            val d = api.videoDetail(bvid)
            detail = d
            error = if (d == null) "拿不到视频详情（可能已失效或接口变了）" else null
            loading = false
        }
    }

    /** 「重试」用。必须 force —— 否则会被 `loadedBvid == bvid` 直接挡回去，按了没反应。 */
    fun retry() {
        loadedBvid?.let { load(it, force = true) }
    }
}
