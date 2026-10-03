package top.bilitv.ui.pgc

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
import top.bilitv.data.model.PgcDetail
import top.bilitv.util.AppLog

class PgcDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as BiliTvApp
    private val api = graph.api
    var detail by mutableStateOf<PgcDetail?>(null); private set
    var loading by mutableStateOf(true); private set
    var message by mutableStateOf(""); private set
    var loggedIn by mutableStateOf(false); private set
    private var loadedId = 0L
    private var generation = 0
    private var requestJob: Job? = null

    fun stopLoading() {
        generation++
        requestJob?.cancel(); requestJob = null
        loading = false
    }

    fun retry() { if (loadedId > 0) load(loadedId, force = true) }

    fun load(seasonId: Long, force: Boolean = false) {
        val account = api.isLoggedIn()
        if (!force && loadedId == seasonId && loggedIn == account && (loading || detail != null)) return
        stopLoading()
        if (loadedId != seasonId || loggedIn != account) detail = null
        loadedId = seasonId; loggedIn = account
        loading = true; message = ""
        val g = generation
        requestJob = viewModelScope.launch {
            try {
                val d = api.pgcDetail(seasonId)?.takeIf { it.seasonId == seasonId }
                    ?: throw java.io.IOException("剧集详情不可用")
                currentCoroutineContext().ensureActive()
                if (g != generation || loadedId != seasonId) return@launch
                detail = d
                AppLog.i("PgcDetail", "season=$seasonId 读取${d.episodes.size}集")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (g == generation) {
                    message = graph.getString(if (account) R.string.cinema_detail_retry else R.string.cinema_detail_sign_in_retry)
                    AppLog.w("PgcDetail", "读取失败：${e.javaClass.simpleName}")
                }
            } finally { if (g == generation) loading = false }
        }
    }
}
