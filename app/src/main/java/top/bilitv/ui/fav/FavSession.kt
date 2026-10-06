package top.bilitv.ui.fav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.bilitv.data.model.FavFolder
import top.bilitv.data.model.FavResourcePage
import top.bilitv.data.model.FeedItem

/**
 * 账号快照：只看"登没登录 + 是哪个 mid"。
 *
 * ★ 收藏夹列表接口要显式传自己的 mid，夹内资源接口按 media_id 走 —— 两个请求都属于
 * **某一个具体账号**。所以不能只看 mid：登出以后 mid 可能还留着旧值，必须连登录标志一起记。
 */
internal data class FavAccount(val loggedIn: Boolean, val mid: Long)

/** [FavSession.planLoad] 的结论。 */
internal enum class FavLoadPlan {
    /** 同账号的缓存还能用 / 有请求在飞 / 没登录 —— 什么都不用做。 */
    NONE,

    /** 重新拉收藏夹列表（首次进入、换账号、上次失败、离页回来时被打断的 LOADING）。 */
    LOAD_FOLDERS,

    /** 人在夹里但第一页失败或被打断 —— 重试当前夹的内容，别把用户甩回夹列表。 */
    RETRY_ITEMS,
}

/** 一次请求结果的处置：[APPLIED] 落状态、[FAILED] 当前请求失败、[STALE] 已过期什么都不碰。 */
internal enum class FavApply { APPLIED, FAILED, STALE }

/**
 * 一次请求的凭据（发起那一刻定下来）。
 *
 * 结果只有在 **[generation] 仍是最新**、**[account] 仍是当前账号**、夹内请求的 **[folderId]**
 * 仍对得上当前打开的夹时，才允许写状态。三个条件缺一不可 —— 只看代次挡不住
 * "换了账号但代次没变"的路径（入口没被调到的时候），只看账号挡不住同一个账号里的并发请求。
 */
internal class FavRequest(
    val generation: Int,
    val account: FavAccount,
    val folderId: Long,
    val page: Int,
    val first: Boolean,
)

/**
 * 收藏页的状态机（2026-10-04 账号缓存隔离）。
 *
 * ## 为什么单独一个类
 *
 * 原来这堆状态和方法都长在 `FavViewModel` 里。收藏页两个接口都要登录态，而
 * `AndroidViewModel` 在 JVM 单测里造不出来（要真的 `Application`），于是"换账号"
 * 这条路径一直没有回归验证 —— 而它正好有两个真问题：
 *
 * 1. `load()` 里 `inFlight` 闸门和"夹列表非空就复用"的缓存检查都在账号检测之前早退：
 *    上一个账号的收藏夹会原样端给下一个账号；旧请求还在飞时新账号只会一直转圈；
 * 2. 本地凭证换了以后，旧账号的响应回来照样往状态上写，出现"夹列表是 A 的、
 *    夹内容是 B 的"这种混合状态。
 *
 * 修法是把状态机搬进这个**纯 Kotlin 类**，网络调用仍留在 `FavViewModel` 里、
 * 以三个 lambda 传进来（取 mid / 取夹列表 / 取夹内容）。没有抽象整个网络层，
 * 却让 JVM 单测能直接驱动真实的换账号、迟到响应、离页重入路径。
 *
 * ## 三条不变量
 *
 * | 不变量 | 在哪保证 |
 * |---|---|
 * | 账号一变：旧数据全清、旧请求作废、闸门放掉 | [syncAccount] |
 * | 异步结果（含异常分支）只作用于它捕获的账号/代次/夹 | [runFolders]、[runItems] 里的 [isCurrent]/[isCurrentItems] |
 * | 已登录时绝不停在"LOADING 却没有请求" | [needsFoldersReload] + 调用方在入口补齐请求 |
 */
internal class FavSession {

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

    /** 已成功加载到第几页（0 = 还没成功过一页）。 */
    var page = 0
        private set

    var hasMore by mutableStateOf(true)
        private set

    /**
     * 有请求在飞。
     *
     * ★ 这是闸门：换账号/离页时必须一并放掉，否则它会**把新账号的加载挡回去**，
     * 界面就停在"转圈但没有任何请求"。
     */
    var inFlight = false
        private set

    /** 每发起一次请求 +1。旧代次的结果与异常一律丢弃。 */
    private var generation = 0

    /** 上一次观察到的账号。null = 还没观察过。 */
    private var account: FavAccount? = null

    val loggedIn: Boolean get() = account?.loggedIn == true

    val mid: Long get() = account?.mid ?: 0L

    // ------------------------------------------------------------------ 账号

    /**
     * 按"实时账号"刷新快照。
     *
     * 变了就作废旧请求（代次 +1）、放掉闸门、把旧账号的夹/内容/页码/错误全部清掉，
     * 状态落到 **LOADING**（已登录，等着真的发一次请求）或 **NEED_LOGIN**（登出）。
     *
     * @return true = 确实换了账号，调用方必须保证接着为新账号起一次请求。
     */
    fun syncAccount(live: FavAccount): Boolean {
        if (live == account) return false
        account = live
        ++generation
        inFlight = false
        loadingMore = false
        opened = null
        folders = emptyList()
        items = emptyList()
        page = 0
        hasMore = true
        error = null
        state = if (live.loggedIn) FavState.LOADING else FavState.NEED_LOGIN
        return true
    }

    /**
     * 换了已登录账号后，唯一不能停下来的状态就是"LOADING 但没有任何请求"。
     *
     * [syncAccount] 换账号时会把状态置成 LOADING，所以公共入口只要看到它，
     * 就必须真的发一次夹列表请求（见 `FavViewModel.startFoldersIfPending`）。
     */
    fun needsFoldersReload(): Boolean =
        loggedIn && opened == null && !inFlight && state == FavState.LOADING

    // ------------------------------------------------------------ 入口决策

    /**
     * "进页面时调一次"的那次判断，顺序与修复前完全一致（账号检测在闸门与缓存之前）。
     *
     * | 情况 | 结论 |
     * |---|---|
     * | 没登录 | [FavLoadPlan.NONE]（[syncAccount] 已经把状态落成 NEED_LOGIN） |
     * | 有请求在飞 | [FavLoadPlan.NONE] |
     * | 在夹里 + 失败/第一页被打断 | [FavLoadPlan.RETRY_ITEMS] |
     * | 在夹里 + 有内容 | [FavLoadPlan.NONE] —— 同账号返回时保留内容与页码 |
     * | 夹列表已有内容 | [FavLoadPlan.NONE] —— 同账号返回时用缓存，不重复请求 |
     * | 其余 | [FavLoadPlan.LOAD_FOLDERS] |
     */
    fun planLoad(): FavLoadPlan = when {
        !loggedIn -> FavLoadPlan.NONE
        inFlight -> FavLoadPlan.NONE
        opened != null ->
            if (state == FavState.ERROR || state == FavState.ITEMS_LOADING) FavLoadPlan.RETRY_ITEMS
            else FavLoadPlan.NONE
        state == FavState.FOLDERS && folders.isNotEmpty() -> FavLoadPlan.NONE
        else -> FavLoadPlan.LOAD_FOLDERS
    }

    // ------------------------------------------------------------ 发起请求

    /** 拉收藏夹列表。返回 null = 不该发（没登录 / 已有请求在飞）。 */
    fun beginFolders(): FavRequest? {
        val acc = account ?: return null
        if (!acc.loggedIn || inFlight) return null
        inFlight = true
        state = FavState.LOADING
        return FavRequest(++generation, acc, folderId = 0L, page = 1, first = true)
    }

    /**
     * 打开一个夹（第一页）。
     *
     * 返回 null = 拒绝：
     * - 没登录；
     * - **这个夹不在当前账号的夹列表里** —— 换账号后 `folders` 已经清空，
     *   所以上一个账号那份列表上点下来的"迟到的旧夹"一定落在这里，不会被新账号打开；
     * - 已有请求在飞（夹列表还在加载时不该能点到夹，防御）。
     */
    fun beginFirstPage(folder: FavFolder): FavRequest? {
        val acc = account ?: return null
        if (!acc.loggedIn) return null
        if (folder !in folders) return null
        if (inFlight) return null
        ++generation
        inFlight = true
        loadingMore = false
        error = null
        opened = folder
        page = 0
        hasMore = true
        items = emptyList()
        state = FavState.ITEMS_LOADING
        return FavRequest(generation, acc, folder.id, page = 1, first = true)
    }

    /** 夹内往下翻。返回 null = 不该翻（没登录/在飞/还在追加/没下一页/上次失败还没重试）。 */
    fun beginNextPage(): FavRequest? {
        val acc = account ?: return null
        val folder = opened ?: return null
        if (!acc.loggedIn || inFlight || loadingMore || !hasMore || error != null) return null
        inFlight = true
        loadingMore = true
        return FavRequest(generation, acc, folder.id, page = page + 1, first = false)
    }

    /** 失败后的重试：页 0 失败 = 重试第一页，隔页失败 = 重试那一页（不推进页码）。 */
    fun beginRetry(): FavRequest? {
        val acc = account ?: return null
        val folder = opened ?: return null
        if (!acc.loggedIn || inFlight) return null
        val first = page == 0
        inFlight = true
        loadingMore = !first
        error = null
        if (first) state = FavState.ITEMS_LOADING
        return FavRequest(generation, acc, folder.id, page = if (first) 1 else page + 1, first = first)
    }

    // ------------------------------------------------------------ 异步执行

    /**
     * 跑一次"收藏夹列表"请求。
     *
     * [live] 是**实时**账号快照（每次重新读本机凭证）。取 mid 之后、拿到列表之后各核一次：
     * 账号换了就作废本次请求直接返回，绝不把结果写上状态。
     */
    suspend fun runFolders(
        req: FavRequest,
        live: () -> FavAccount,
        profileMid: suspend () -> Long,
        fetch: suspend (Long) -> List<FavFolder>?,
        info: (String) -> Unit,
        warn: (String) -> Unit,
    ) {
        try {
            val mid = profileMid()
            currentCoroutineContext().ensureActive()
            if (syncAccount(live())) return
            if (mid <= 0L) {
                if (failFolders(req)) warn("拿不到自己的 mid，收藏夹列表查不了")
                return
            }
            val result = fetch(mid)
            currentCoroutineContext().ensureActive()
            if (syncAccount(live())) return
            when (applyFolders(req, result)) {
                FavApply.APPLIED -> info("收藏夹 ${folders.size} 个")
                // ★ 拿不到 ≠ 没有。"还没有收藏夹"会让有收藏夹的人以为收藏丢了。
                FavApply.FAILED -> warn("收藏夹列表拿不到（网络或接口问题）")
                FavApply.STALE -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ★ 异常分支同样过账号/代次这一关：旧账号的异常不许把新账号打成 ERROR。
            if (syncAccount(live())) return
            if (failFolders(req)) warn("收藏夹读取失败：${e.javaClass.simpleName}")
        }
    }

    /** 跑一次夹内资源请求。约束与 [runFolders] 相同，另加"还停在同一个夹"这一条。 */
    suspend fun runItems(
        req: FavRequest,
        live: () -> FavAccount,
        fetch: suspend (Long, Int) -> FavResourcePage?,
        info: (String) -> Unit,
        warn: (String) -> Unit,
        /** 第一页失败时的提示（由 ViewModel 按当前语言取好，纯状态机不碰资源）。 */
        errorFirst: String = "收藏加载失败，请重试",
        /** 翻页失败时保留已有内容，只提示（同上）。 */
        errorKeep: String = "收藏加载失败，已有内容仍保留，请重试",
    ) {
        try {
            val result = fetch(req.folderId, req.page)
            currentCoroutineContext().ensureActive()
            if (syncAccount(live())) return
            /*
             * ★ `null` = **请求失败**，和"这个夹真的没有内容"是两件事。
             *
             * 2026-09-30 修：原来接口失败时返回空列表，界面就分不清，于是网络一断、
             * 标题上写着「这个收藏夹是空的」—— 而那个夹里其实有 318 条。
             * **用户会以为自己的收藏全丢了。**
             *
             * 现在第一页失败 → 进 ERROR 态（文案说"拿不到、可以重试"）；
             * 翻页失败 → 保留已经拿到的内容，什么都不改（下次滚动会再试）。
             */
            if (result == null) {
                val message = if (items.isEmpty()) errorFirst else errorKeep
                if (failItems(req, message)) warn("${opened?.title.orEmpty()} 第 ${req.page} 页拿不到（网络或接口问题）")
                return
            }
            if (applyItems(req, result)) {
                info("${opened?.title.orEmpty()} 第 $page 页 ${result.items.size} 条（累计 ${items.size}），还有页=$hasMore")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (syncAccount(live())) return
            if (failItems(req, errorFirst)) warn("收藏内容读取失败：${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ 结果落地

    private fun applyFolders(req: FavRequest, result: List<FavFolder>?): FavApply {
        if (!isCurrent(req)) return FavApply.STALE
        inFlight = false
        if (result == null) {
            state = FavState.ERROR
            return FavApply.FAILED
        }
        folders = result
        state = FavState.FOLDERS
        return FavApply.APPLIED
    }

    private fun failFolders(req: FavRequest): Boolean {
        if (!isCurrent(req)) return false
        inFlight = false
        state = FavState.ERROR
        return true
    }

    private fun applyItems(req: FavRequest, result: FavResourcePage): Boolean {
        if (!isCurrentItems(req)) return false
        items = ((if (req.first) emptyList() else items) + result.items).distinctBy { it.bvid }
        page = req.page
        error = null
        hasMore = result.hasMore
        state = FavState.ITEMS
        inFlight = false
        loadingMore = false
        return true
    }

    private fun failItems(req: FavRequest, message: String): Boolean {
        if (!isCurrentItems(req)) return false
        error = message
        if (req.first) state = FavState.ERROR
        inFlight = false
        loadingMore = false
        return true
    }

    private fun isCurrent(req: FavRequest): Boolean =
        req.generation == generation && req.account == account

    private fun isCurrentItems(req: FavRequest): Boolean =
        isCurrent(req) && opened?.id == req.folderId

    // ------------------------------------------------------------ 离页 / 返回

    /** 页面离开就取消网络，返回时保留已有内容和成功页码。 */
    fun stopLoading() {
        ++generation
        inFlight = false
        loadingMore = false
        if (state == FavState.LOADING || state == FavState.ITEMS_LOADING) state = FavState.ERROR
    }

    /** 回到夹列表。登出时不许落成 FOLDERS，否则界面上会写着"还没有收藏夹"。 */
    fun backToFolders() {
        ++generation
        error = null
        inFlight = false
        loadingMore = false
        opened = null
        items = emptyList()
        state = if (loggedIn) FavState.FOLDERS else FavState.NEED_LOGIN
    }
}
