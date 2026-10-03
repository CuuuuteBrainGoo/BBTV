package top.bilitv.ui.home

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.bilitv.BiliTvApp
import top.bilitv.R
import top.bilitv.data.model.FeedItem
import top.bilitv.data.model.toFeedItem
import top.bilitv.util.AppLog

/**
 * 首页数据。
 *
 * ## 分区从哪来
 *
 * [sections] 是**用户配置的**（`SettingsStore.homeSections` → [HomeSection.parse]）。
 * 少爷要求"顺序可自定义、可关某项"，见 `docs/33` §四的模型：
 * **一份有序 id 名单，顺序即显示顺序，不在名单里即隐藏。**
 *
 * ## ⛔ 取消了跨标签缓存（2026-09-30 少爷定的）
 *
 * > 如果去到别的选项卡再进来，那就不用记了，**直接刷新重新加载这个选项卡所有的视频卡片**。
 *
 * 所以切分区 = 重新加载，[show] 一律走 [refresh]。
 * ⚠️ 但"进播放页再回来"是另一回事：数据还在内存、图片还在缓存，
 * **不该重新加载** —— 那由 `Nav.kt` 的 `rememberSaveableStateHolder` 保住整个页面状态。
 *
 * ## 三个源/三类源的"下一页"机制完全不同（别想当然）
 *
 * | 类型 | 翻页方式 |
 * |---|---|
 * | 推荐 | **`fresh_idx`** —— 加一就是"再推荐一批"，**这也是 B 站网页自己的做法** |
 * | 热门 / UGC 分区 | `pn` 真分页 |
 * | PGC 分类 | `page` 真分页 |
 * | 每周必看 | 一期一个固定名单，**不能翻** |
 *
 * 所以"刷新"的语义也不同：只有推荐流是**真的换一批**（`fresh_idx` 前进）；
 * 热门是个榜单、每周必看是当期名单，重新拉还是同一份 —— 那是**数据源的性质**，不是 bug。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp
    val autoRefresh: Boolean get() = graph.settings.autoRefresh
    private var recommendPolicy = graph.settings.recommendSource to graph.settings.personalizedRecommendations
    var recommendNotice by mutableStateOf<String?>(null)
        private set
    val canBacktrack: Boolean get() = section == HomeSection.RECOMMEND && graph.settings.showRecommendBacktrack && previous != null
    private var previous by mutableStateOf<RecommendationSnapshot?>(null)
    var restoredViewport by mutableStateOf<HomeViewport?>(null)
        private set
    var restoreCount by mutableIntStateOf(0)
        private set

    /** 用户配置的分区（顺序即显示顺序）。 */
    var sections by mutableStateOf(HomeSection.parse(graph.settings.homeSections))
        private set

    var items by mutableStateOf<List<FeedItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    /** 正在追加下一页（底部转圈用）。和 [loading] 分开：首屏加载和追加的界面表现不一样。 */
    var loadingMore by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 当前分区。 */
    var section by mutableStateOf(sections.firstOrNull() ?: HomeSection.RECOMMEND)
        private set

    /**
     * 第几次"整页刷新"。**只升不降**，用来让界面知道"该把焦点搬回第一格了"。
     *
     * 为什么需要它：`RequestFocusOnAppear(requester, key)` 只在 **key 变化**时重新要焦点。
     * 刷新前后"列表非空"这个布尔值没变，焦点就不会被搬回去 —— 而少爷要求
     * **刷新后焦点回到第一行第一列**。
     */
    var refreshCount by mutableIntStateOf(0)
        private set

    // ------------------------------------------------------------------ 翻页游标

    /** 推荐流的 `fresh_idx`。0 起步，用之前先 +1，所以第一次请求是 1。 */
    private var recommendIdx = 0

    /** 真分页的页码（热门 / UGC 分区 / PGC 分类共用这一个）。 */
    private var page = 1

    var hasMore by mutableStateOf(true); private set
    private var initialized = false
    val needsFirstPage: Boolean get() = !initialized
    private var requestJob: Job? = null

    /**
     * 请求代数。**防止乱序覆盖**：切分区/刷新会 +1，
     * 迟到的旧响应回来看见代数变了就自己丢掉 —— 否则慢请求会把新内容盖回旧的。
     */
    private var gen = 0
    private var inFlight = false
    private var failedMore = false

    fun stopLoading() {
        gen++
        requestJob?.cancel(); requestJob = null
        loading = false; loadingMore = false; inFlight = false
    }

    // ------------------------------------------------------------------ 对外动作

    /**
     * 重新读一次用户配置。
     *
     * 界面每次**重新进入组合**时调（切走再切回来）—— 用户在设置页改了分区，
     * 回来就该看到新的。VM 本身活得比页面久，所以不能只在构造时读一次。
     */
    fun syncSections(): Boolean {
        if (!graph.settings.showRecommendBacktrack) previous = null
        val policy = graph.settings.recommendSource to graph.settings.personalizedRecommendations
        val changed = policy != recommendPolicy && section == HomeSection.RECOMMEND
        if (policy != recommendPolicy) {
            recommendPolicy = policy; previous = null; recommendNotice = null
            if (changed) items = emptyList()
        }
        val next = HomeSection.parse(graph.settings.homeSections)
        var hidden = false
        if (next != sections) {
            sections = next
            if (section !in next) {
                stopLoading(); section = next.first(); initialized = false
                items = emptyList(); previous = null; recommendNotice = null
                hidden = true
            }
        }
        return changed || hidden
    }

    /** 切到某个分区。**每次都重新加载**（不做跨分区缓存）。 */
    fun show(next: HomeSection) {
        if (next != section) { items = emptyList(); initialized = false; previous = null; restoredViewport = null; recommendNotice = null }
        section = next
        refresh()
    }

    /** 重新推荐一批 / 重新拉这一页。 */
    fun refresh(viewport: HomeViewport = HomeViewport()) {
        val prior = if (graph.settings.showRecommendBacktrack && section == HomeSection.RECOMMEND && items.isNotEmpty())
            RecommendationSnapshot(items, page, hasMore, error, failedMore, viewport, recommendNotice) else null
        stopLoading()
        val g = gen
        inFlight = true
        loading = true
        loadingMore = false
        error = null; failedMore = false
        val which = section; val index = recommendIdx + 1
        requestJob = viewModelScope.launch {
            try {
                val result = load(which, 1, index)
                currentCoroutineContext().ensureActive()
                if (g != gen) return@launch
                if (which == HomeSection.RECOMMEND) { if (result.items.isNotEmpty()) previous = prior; recommendNotice = result.notice }
                items = result.items.distinctBy { it.identity() }
                page = 1; recommendIdx = index
                hasMore = which.pageable && (result.hasMore ?: result.items.isNotEmpty())
                initialized = true
                error = if (result.items.isEmpty() && !hasMore) graph.getString(R.string.home_empty, graph.getString(which.labelRes)) else null
                refreshCount++
                AppLog.i("Home", "${which.id} 刷新 ${result.items.size} 条（hasMore=$hasMore）")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (g == gen) { error = if (items.isEmpty()) "加载失败，请重试" else "加载失败，已保留现有内容，请重试"; AppLog.w("Home", e.javaClass.simpleName) }
            } finally { if (g == gen) { loading = false; inFlight = false } }
        }
    }

    /**
     * 往下翻到底时追加一批。
     *
     * 可以放心连调 —— 这里有三道闸：正在飞、首屏还在加载、已经没有了。
     */
    fun loadMore() {
        if (inFlight || loading || loadingMore || !hasMore || error != null) return
        val g = gen
        inFlight = true
        loadingMore = true
        val which = section; val nextPage = page + 1; val index = recommendIdx + 1
        requestJob = viewModelScope.launch {
            try {
                val more = load(which, nextPage, index)
                currentCoroutineContext().ensureActive()
                if (g != gen) return@launch
                if (which == HomeSection.RECOMMEND) recommendNotice = more.notice
                hasMore = more.hasMore ?: more.items.isNotEmpty()
                val seen = items.mapTo(HashSet()) { it.identity() }
                items = items + more.items.filter { seen.add(it.identity()) }
                page = nextPage; recommendIdx = index
                AppLog.i("Home", "${which.id} 追加 ${more.items.size} 条（累计 ${items.size}）")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (g == gen) { failedMore = true; error = "加载更多失败，已有内容仍可观看，请重试"; AppLog.w("Home", e.javaClass.simpleName) }
            } finally { if (g == gen) { loadingMore = false; inFlight = false } }
        }
    }

    fun backtrack() {
        if (!canBacktrack) return
        val snapshot = previous ?: return
        stopLoading() // Also closes the actual old network call.
        items = snapshot.items; page = snapshot.page; hasMore = snapshot.hasMore
        error = snapshot.error; failedMore = snapshot.failedMore; recommendNotice = snapshot.notice
        loading = false; loadingMore = false; inFlight = false
        previous = null; restoredViewport = snapshot.viewport; restoreCount++
        // fresh_idx stays monotonic: restored recommendations are not a server-side page cursor.
    }

    fun retry() { if (failedMore) { error = null; loadMore() } else refresh() }

    // ------------------------------------------------------------------ 取数

    private suspend fun load(which: HomeSection, requestPage: Int, index: Int): top.bilitv.data.api.RecommendPage {
        if (which == HomeSection.RECOMMEND) return graph.api.recommendPage(index, recommendPolicy.first, recommendPolicy.second)
        if (which.pgcType != null) {
            val result = graph.api.pgcIndexPage(which.pgcType, page = requestPage, ps = HOME_PAGE_SIZE)
            return top.bilitv.data.api.RecommendPage(result.items.map { it.toFeedItem() }, hasMore = result.hasMore)
        }
        val raw: List<FeedItem> = when {
            which.regionId != 0 -> {
                graph.api.regionNewList(which.regionId, pn = requestPage, ps = HOME_PAGE_SIZE, strict = true)
            }

            which == HomeSection.POPULAR -> {
                graph.api.popular(pn = requestPage, ps = HOME_PAGE_SIZE, strict = true)
            }

            // 不能翻页；"追加"时直接返回空，让 loadMore 把 hasMore 置 false
            else -> if (requestPage == 1) graph.api.weeklyOne(strict = true) else emptyList()
        }
        // 解析层已剔除 goto=ad 的卡片，这里只是留一道闸。
        // ⚠️ PGC 卡的 bvid 是空的（它走 seasonId），所以判据是"两个 id 至少有一个"
        return top.bilitv.data.api.RecommendPage(if (graph.settings.filterUiAds) {
            raw.filter { it.bvid.isNotBlank() || it.seasonId > 0L }
        } else {
            raw
        })
    }
}

/** 去重用的稳定标识：UGC 用 bvid，PGC 用 seasonId。 */
private fun FeedItem.identity(): String =
    if (bvid.isNotBlank()) "b:$bvid" else "s:$seasonId"

/** 一页抓多少条。推荐流的页大小由接口自己定（它给 12），不在这里管。 */
private const val HOME_PAGE_SIZE = 20

/** One previous recommendation batch; bitmaps remain in the existing bounded image cache. */
private data class RecommendationSnapshot(val items: List<FeedItem>, val page: Int, val hasMore: Boolean,
    val error: String?, val failedMore: Boolean, val viewport: HomeViewport, val notice: String?)

data class HomeViewport(val index: Int = 0, val offset: Int = 0, val focusedKey: String? = null)
