package top.bilitv.ui.home

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import top.bilitv.BiliTvApp
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

    private var hasMore = true

    /**
     * 请求代数。**防止乱序覆盖**：切分区/刷新会 +1，
     * 迟到的旧响应回来看见代数变了就自己丢掉 —— 否则慢请求会把新内容盖回旧的。
     */
    private var gen = 0
    private var inFlight = false

    // ------------------------------------------------------------------ 对外动作

    /**
     * 重新读一次用户配置。
     *
     * 界面每次**重新进入组合**时调（切走再切回来）—— 用户在设置页改了分区，
     * 回来就该看到新的。VM 本身活得比页面久，所以不能只在构造时读一次。
     */
    fun syncSections() {
        val next = HomeSection.parse(graph.settings.homeSections)
        if (next == sections) return
        sections = next
        // 当前分区被关掉了 → 回到第一个
        if (section !in next) {
            section = next.first()
            refresh()
        }
    }

    /** 切到某个分区。**每次都重新加载**（不做跨分区缓存）。 */
    fun show(next: HomeSection) {
        section = next
        refresh()
    }

    /** 重新推荐一批 / 重新拉这一页。 */
    fun refresh() {
        val g = ++gen
        inFlight = true
        loading = true
        loadingMore = false
        viewModelScope.launch {
            val result = load(section, first = true)
            if (g != gen) return@launch          // 这次请求已经过期
            items = result
            hasMore = section.pageable && result.isNotEmpty()
            error = if (result.isEmpty()) section.emptyHint else null
            loading = false
            inFlight = false
            refreshCount++
            AppLog.i("Home", "${section.label} 刷新 ${result.size} 条（hasMore=$hasMore）")
        }
    }

    /**
     * 往下翻到底时追加一批。
     *
     * 可以放心连调 —— 这里有三道闸：正在飞、首屏还在加载、已经没有了。
     */
    fun loadMore() {
        if (inFlight || loading || loadingMore || !hasMore) return
        val g = gen
        inFlight = true
        loadingMore = true
        viewModelScope.launch {
            val more = load(section, first = false)
            if (g != gen) return@launch
            if (more.isEmpty()) {
                hasMore = false
            } else {
                // 去重：推荐流的相邻批次会重叠，不去重会让 LazyGrid 的 key 直接崩。
                // PGC 没有 bvid，用 seasonId 当兜底 key。
                val seen = items.mapTo(HashSet()) { it.identity() }
                items = items + more.filter { seen.add(it.identity()) }
            }
            loadingMore = false
            inFlight = false
            AppLog.i("Home", "${section.label} 追加 ${more.size} 条（累计 ${items.size}）")
        }
    }

    // ------------------------------------------------------------------ 取数

    private suspend fun load(which: HomeSection, first: Boolean): List<FeedItem> {
        val raw: List<FeedItem> = when {
            which.pgcType != null -> {
                page = if (first) 1 else page + 1
                graph.api.pgcIndex(which.pgcType, page = page, ps = HOME_PAGE_SIZE)
                    .map { it.toFeedItem() }
            }

            which.regionId != 0 -> {
                page = if (first) 1 else page + 1
                graph.api.regionNewList(which.regionId, pn = page, ps = HOME_PAGE_SIZE)
            }

            which == HomeSection.RECOMMEND -> {
                recommendIdx += 1
                graph.api.feedRecommend(freshIdx = recommendIdx)
            }

            which == HomeSection.POPULAR -> {
                page = if (first) 1 else page + 1
                graph.api.popular(pn = page, ps = HOME_PAGE_SIZE)
            }

            // 不能翻页；"追加"时直接返回空，让 loadMore 把 hasMore 置 false
            else -> if (first) graph.api.weeklyOne() else emptyList()
        }
        // 解析层已剔除 goto=ad 的卡片，这里只是留一道闸。
        // ⚠️ PGC 卡的 bvid 是空的（它走 seasonId），所以判据是"两个 id 至少有一个"
        return if (graph.settings.filterUiAds) {
            raw.filter { it.bvid.isNotBlank() || it.seasonId > 0L }
        } else {
            raw
        }
    }
}

/** 去重用的稳定标识：UGC 用 bvid，PGC 用 seasonId。 */
private fun FeedItem.identity(): String =
    if (bvid.isNotBlank()) "b:$bvid" else "s:$seasonId"

/** 一页抓多少条。推荐流的页大小由接口自己定（它给 12），不在这里管。 */
private const val HOME_PAGE_SIZE = 20
