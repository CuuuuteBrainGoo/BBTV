package top.bilitv.ui.fav

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.bilitv.BiliTvApp
import top.bilitv.R
import top.bilitv.data.model.FavFolder
import top.bilitv.data.model.FeedItem
import top.bilitv.data.model.MyProfileResult
import top.bilitv.util.AppLog

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
 *
 * ## 账号隔离（2026-10-04）
 *
 * 这个 VM 是 Activity 作用域的：登录页弹掉、再回到收藏页时**还是同一个实例**，
 * 里面的收藏夹和内容都是上一个账号的。所以：
 *
 * - 状态与账号隔离规则都住在 [FavSession]（纯 Kotlin，可 JVM 单测）；
 * - 本类只做异步接线：**每个公共入口第一件事**是按实时凭证同步账号快照，
 *   换过账号就取消旧请求，然后保证新账号真的发得出去请求；
 * - 所有网络结果（含异常分支）只允许写回它捕获的那个账号/代次/夹。
 */
class FavViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp

    private val session = FavSession()

    /** 收藏夹列表。空 + [state]=FOLDERS 时表示"真的一个夹都没有"。 */
    val folders: List<FavFolder> get() = session.folders

    /** 当前打开的收藏夹。null = 还在夹列表这一层。 */
    val opened: FavFolder? get() = session.opened

    /** 夹内的视频。 */
    val items: List<FeedItem> get() = session.items

    val state: FavState get() = session.state

    /** 正在追加下一页。 */
    val loadingMore: Boolean get() = session.loadingMore

    val error: String? get() = session.error

    val hasMore: Boolean get() = session.hasMore

    private var loadJob: Job? = null

    private fun liveAccount() = FavAccount(graph.api.isLoggedIn(), graph.api.myMid())

    /**
     * 公共入口统一开头：先按**实时**本机凭证刷新账号快照。
     *
     * 换过账号就取消还在飞的旧请求 —— 旧数据和闸门由 [FavSession.syncAccount] 一并清掉，
     * 所以这一步必须发生在任何"缓存命中/闸门早退"之前。
     *
     * @return true = 确实换了账号（状态已落到 LOADING 或 NEED_LOGIN）。
     */
    private fun onEntry(): Boolean {
        val changed = session.syncAccount(liveAccount())
        if (changed) {
            loadJob?.cancel()
            loadJob = null
        }
        return changed
    }

    /**
     * 补一次夹列表请求。
     *
     * 只有换账号才会留下"已登录 + LOADING + 没有请求"这个状态（见 [FavSession.needsFoldersReload]）。
     * 没有这一步，新账号的收藏页就是一个永远转的圈。
     */
    private fun startFoldersIfPending() {
        if (session.needsFoldersReload()) startFolders()
    }

    /** 进页面时调一次（带闸门，理由同 FollowViewModel）。 */
    fun load() {
        onEntry()
        when (session.planLoad()) {
            FavLoadPlan.NONE -> Unit
            FavLoadPlan.LOAD_FOLDERS -> startFolders()
            FavLoadPlan.RETRY_ITEMS -> retryItems()
        }
    }

    /** 菜单刷新保留当前收藏夹，重新读取第一页。 */
    fun reload() {
        onEntry()
        if (session.inFlight || !session.loggedIn) return
        val folder = session.opened
        if (folder == null) startFolders() else open(folder)
    }

    /**
     * 打开一个夹。
     *
     * [FavSession.beginFirstPage] 会拒绝"不在当前账号夹列表里"的夹 ——
     * 换账号后旧列表上点下来的迟到点击打不开上一个账号的夹；这时补一次夹列表请求，
     * 不会停在没请求的 LOADING。
     *
     * ★ 顺序：先同步账号、先问 [FavSession.beginFirstPage] 收不收这个请求，
     * **只有被接受**才取消上一个 `loadJob`。反过来的话，重复点击/迟到点击会被
     * `inFlight` 闸门拒绝（返回 null），可旧请求已经被掐掉，闸门与 ITEMS_LOADING
     * 却留在原地 —— 同账号那个合法在途请求既发不出也回不来。换账号那条路径仍由
     * [onEntry] 负责取消，不受这里影响。
     */
    fun open(folder: FavFolder) {
        onEntry()
        val req = session.beginFirstPage(folder)
        if (req == null) {
            // 被拒（不在当前账号列表 / 已有请求在飞）不能动 loadJob：
            // 正在飞的那次请求可能就是这个夹的合法请求。
            startFoldersIfPending()
            return
        }
        // 只有确实接受了新请求，才让旧的让位。
        loadJob?.cancel()
        startItems(req)
    }

    /** 回到夹列表。 */
    fun backToFolders() {
        // 换过账号就不能直接落成"夹列表"：那时候夹列表还是空的，
        // 界面会写着"还没有收藏夹"—— 而新账号可能有一堆夹。要重新拉。
        if (onEntry()) {
            startFolders()
            return
        }
        loadJob?.cancel()
        loadJob = null
        session.backToFolders()
    }

    /** 页面离开就取消网络，返回时保留已有内容和成功页码。 */
    fun stopLoading() {
        loadJob?.cancel()
        loadJob = null
        session.stopLoading()
    }

    /** 夹内往下翻。 */
    fun loadMore() {
        onEntry()
        val req = session.beginNextPage()
        if (req == null) {
            startFoldersIfPending()
            return
        }
        startItems(req)
    }

    /**
     * ERROR 态下那颗「重新加载」的去处。
     *
     * 分两种情况 —— **在夹里失败要重试"这个夹的内容"，而不是把用户甩回夹列表**：
     * 原来那颗按钮直接调 [load]（重新拉夹列表），用户点完会莫名其妙回到上一层。
     */
    fun retry() {
        onEntry()
        if (session.opened == null) {
            load()
            return
        }
        retryItems()
    }

    private fun retryItems() {
        val req = session.beginRetry()
        if (req == null) {
            startFoldersIfPending()
            return
        }
        startItems(req)
    }

    private fun startFolders() {
        val req = session.beginFolders() ?: return
        loadJob = viewModelScope.launch {
            session.runFolders(
                req,
                live = ::liveAccount,
                // 收藏夹列表要显式传自己的 mid —— 少这个参数接口报 -400，
                // 那个错**看起来像接口坏了**（docs/21 记过这次误判）。
                profileMid = { graph.api.myProfile().let { if (it is MyProfileResult.Ok) it.profile.mid else 0L } },
                fetch = { mid -> graph.api.favFolders(mid) },
                info = { AppLog.i(TAG, it) },
                warn = { AppLog.w(TAG, it) },
            )
            // 请求途中账号被换掉时，状态会停在"已登录的 LOADING 却没有请求"。
            // 这里为新账号补一次，别让用户看着一个永远转的圈；页面已经离开（协程被取消）时什么都不做。
            if (isActive && session.needsFoldersReload()) startFolders()
        }
    }

    private fun startItems(req: FavRequest) {
        loadJob = viewModelScope.launch {
            session.runItems(
                req,
                live = ::liveAccount,
                fetch = { folderId, page -> graph.api.favResourcePage(folderId, pn = page, ps = PAGE_SIZE) },
                info = { AppLog.i(TAG, it) },
                warn = { AppLog.w(TAG, it) },
                errorFirst = graph.getString(R.string.fav_items_failed),
                errorKeep = graph.getString(R.string.fav_items_failed_keep),
            )
            if (isActive && session.needsFoldersReload()) startFolders()
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val TAG = "Fav"
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
