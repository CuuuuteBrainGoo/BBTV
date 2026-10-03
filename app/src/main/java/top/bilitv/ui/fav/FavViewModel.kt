package top.bilitv.ui.fav

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import top.bilitv.BiliTvApp
import top.bilitv.data.model.FavFolder
import top.bilitv.data.model.FeedItem
import top.bilitv.util.AppLog
import top.bilitv.data.model.MyProfileResult
import top.bilitv.data.model.MyProfile

/**
 * 收藏页的状态。
 *
 * ## ⚠️ 这一页**全部路径都未在真机实测过**
 *
 * 两个接口都要登录态，而写这段代码时手上没有登录环境。
 * 所以：
 * - 字段名按公开文档与同类项目写；
 * - **配套探针 `tools/probe_fav.py`** —— 少爷登录后跑一次核对（照 `probe_dynamic.py` 的套路）；
 * - 界面上凡是"取不到"的地方都**说清是哪一种取不到**，不合并成一句"没有内容"。
 *
 * ## 两层结构：先选夹、再看内容
 *
 * 收藏夹可能有很多个（默认收藏夹 + 用户自建），直接铺内容会不知道在看哪个夹。
 * 所以先列夹、选中再进内容。这是 B 站自己网页版的层次。
 */
class FavViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp

    /** 收藏夹列表。空 + [state]=FOLDERS 时表示"真的一个夹都没有"。 */
    var folders by mutableStateOf<List<FavFolder>>(emptyList())
        private set

    /** 当前打开的收藏夹。null = 还在夹列表这一层。 */
    var opened by mutableStateOf<FavFolder?>(null)
        private set

    /** 夹内的视频。 */
    var items by mutableStateOf<List<FeedItem>>(emptyList())
        private set

    var state by mutableStateOf(FavState.LOADING)
        private set

    /** 正在追加下一页。 */
    var loadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var page = 0
    var hasMore by mutableStateOf(true)
        private set
    private var inFlight = false
    private var gen = 0
    private var loadJob: Job? = null

    /** 进页面时调一次（带闸门，理由同 FollowViewModel）。 */
    fun load() {
        if (inFlight) return
        if (!graph.api.isLoggedIn()) {
            stopLoading()
            opened = null; items = emptyList(); folders = emptyList()
            state = FavState.NEED_LOGIN
            return
        }
        if (opened != null) {
            if (state == FavState.ERROR || state == FavState.ITEMS_LOADING) retry()
            return
        }
        if (state == FavState.FOLDERS && folders.isNotEmpty()) return
        val g = ++gen
        inFlight = true
        loadJob = viewModelScope.launch {
          try {
            state = FavState.LOADING
            // 收藏夹列表要显式传自己的 mid —— 少这个参数接口报 -400，
            // 那个错**看起来像接口坏了**（docs/21 记过这次误判）。
            val mid = graph.api.myProfile().let { if (it is MyProfileResult.Ok) it.profile.mid else 0L }
            if (g != gen) return@launch
            if (mid <= 0L) {
                state = FavState.ERROR
                AppLog.w("Fav", "拿不到自己的 mid，收藏夹列表查不了")
                return@launch
            }
            val foldersOrNull = graph.api.favFolders(mid)
            if (g != gen) return@launch
            if (foldersOrNull == null) {
                // ★ 拿不到 ≠ 没有。说"还没有收藏夹"会让有收藏夹的人以为收藏丢了。
                state = FavState.ERROR
                AppLog.w("Fav", "收藏夹列表拿不到（网络或接口问题）")
                return@launch
            }
            folders = foldersOrNull
            state = FavState.FOLDERS
            AppLog.i("Fav", "收藏夹 ${folders.size} 个")
          } catch (e: CancellationException) { throw e }
          catch (e: Exception) { if (g == gen) { state = FavState.ERROR; AppLog.w("Fav", "收藏夹读取失败：${e.javaClass.simpleName}") } }
          finally { if (g == gen) inFlight = false }
        }
    }

    /** 打开一个夹。 */
    fun open(folder: FavFolder) {
        loadJob?.cancel()
        gen++; error = null
        opened = folder
        page = 0
        hasMore = true
        items = emptyList()
        state = FavState.ITEMS_LOADING
        fetchItems(first = true)
    }

    /** 回到夹列表。 */
    fun backToFolders() {
        loadJob?.cancel()
        gen++; error = null; inFlight = false; loadingMore = false
        opened = null
        items = emptyList()
        state = FavState.FOLDERS
    }

    /** 页面离开就取消网络，返回时保留已有内容和成功页码。 */
    fun stopLoading() {
        loadJob?.cancel(); gen++
        inFlight = false; loadingMore = false
        if (state == FavState.LOADING || state == FavState.ITEMS_LOADING) state = FavState.ERROR
    }

    /** 夹内往下翻。 */
    fun loadMore() {
        if (inFlight || loadingMore || !hasMore || opened == null || error != null) return
        fetchItems(first = false)
    }

    private fun fetchItems(first: Boolean) {
        val folder = opened ?: return
        val g = gen
        val requestPage = if (first) 1 else page + 1
        inFlight = true
        loadingMore = !first
        if (first) state = FavState.ITEMS_LOADING
        loadJob = viewModelScope.launch {
          try {
            val result = graph.api.favResourcePage(folder.id, pn = requestPage, ps = PAGE_SIZE)
            if (g != gen || opened?.id != folder.id) return@launch
            /*
             * ★ `null` = **请求失败**，和"这个夹真的没有内容"是两件事。
             *
             * 2026-09-30 修：原来接口失败时返回空列表，界面就分不清，
             * 于是网络一断、标题上写着「这个收藏夹是空的」——
             * 而那个夹里其实有 318 条。**用户会以为自己的收藏全丢了。**
             *
             * 现在第一页失败 → 进 ERROR 态（文案说"拿不到、可以重试"）；
             * 翻页失败 → 保留已经拿到的内容，什么都不改（下次滚动会再试）。
             */
            if (result == null) {
                error = if (items.isEmpty()) "收藏加载失败，请重试" else "收藏加载失败，已有内容仍保留，请重试"
                if (first) state = FavState.ERROR
                AppLog.w("Fav", "${folder.title} 第 $requestPage 页拿不到（网络或接口问题）")
                return@launch
            }
            items = ((if (first) emptyList() else items) + result.items).distinctBy { it.bvid }
            page = requestPage; error = null
            hasMore = result.hasMore
            state = FavState.ITEMS
            AppLog.i("Fav", "${folder.title} 第 $page 页 ${result.items.size} 条（累计 ${items.size}），还有页=$hasMore")
          } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            if (g == gen) {
                error = "收藏加载失败，请重试"
                if (first) state = FavState.ERROR
                AppLog.w("Fav", "收藏内容读取失败：${e.javaClass.simpleName}")
            }
          } finally { if (g == gen) { inFlight = false; loadingMore = false } }
        }
    }

    /**
     * ERROR 态下那颗「重新加载」的去处。
     *
     * 分两种情况 —— **在夹里失败要重试"这个夹的内容"，而不是把用户甩回夹列表**：
     * 原来那颗按钮直接调 [load]（重新拉夹列表），用户点完会莫名其妙回到上一层。
     */
    fun retry() {
        if (opened != null) {
            if (inFlight) return
            error = null
            fetchItems(first = page == 0)
        } else {
            load()
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}

/** 收藏页的四种状态。**分开是为了让每种"没有内容"说的话不一样**（见 FavScreen 的说明）。 */
enum class FavState {
    LOADING,
    NEED_LOGIN,
    ERROR,

    /** 在夹列表这一层（可能为空 = 一个夹都没有）。 */
    FOLDERS,

    /** 在某个夹里，正在拉第一页。 */
    ITEMS_LOADING,

    /** 在某个夹里，内容就绪（可能为空 = 这个夹是空的）。 */
    ITEMS,
}
