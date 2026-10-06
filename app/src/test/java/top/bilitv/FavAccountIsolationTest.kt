package top.bilitv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.model.FavFolder
import top.bilitv.data.model.FavResourcePage
import top.bilitv.data.model.FeedItem
import top.bilitv.ui.fav.FavAccount
import top.bilitv.ui.fav.FavLoadPlan
import top.bilitv.ui.fav.FavSession
import top.bilitv.ui.fav.FavState

/**
 * 收藏页的**账号缓存隔离**回归（2026-10-04）。
 *
 * ## 为什么钉这一组
 *
 * 收藏页的 `FavViewModel` 是 Activity 作用域的：登录页弹掉回到收藏页，还是同一个实例，
 * 里面装着**上一个账号**的收藏夹和内容。修复前的真实缺陷是两类：
 *
 * 1. `load()` 的闸门（`inFlight`）和"夹列表非空就复用"的缓存检查都排在账号检测**之前** ——
 *    上一个账号的夹会原样端给下一个账号；旧请求还在飞时，新账号永远停在转圈。
 * 2. 本机凭证换了以后，旧账号的响应回来照写状态 —— 出现"夹列表是 A 的、内容是 B 的"。
 *
 * ## 这一组测的是真代码，不是镜像
 *
 * `AndroidViewModel` 在 JVM 单测里造不出来（要真的 `Application`），所以这次把状态机拆成了
 * 纯 Kotlin 的 [FavSession]（网络调用由 `FavViewModel` 以 lambda 传入，没有抽象整个网络层）。
 * 下面每一条都直接驱动这份真代码：`syncAccount` / `beginXxx` / `runFolders` / `runItems`，
 * 用 [CompletableDeferred] 卡住请求，在"请求还在飞"的时刻切账号，再放行 ——
 * 这是**受控的迟到响应**，不是源码字符串断言，也不是把逻辑抄一遍。
 *
 * 取舍（已记入 docs/111）：纯 [FavSession] 测试**不直接覆盖** `AndroidViewModel` 的
 * 协程取消接线（`FavViewModel.open` 里 `loadJob?.cancel()` 何时执行）与 Compose 重组 ——
 * 那段接线由 GPT 代码审查验证，真机/模拟器路径兜底；这里覆盖的是"结果能不能落状态"的判据。
 *
 * 所有"请求已发出但结果迟到"的用例都用 `launch(start = CoroutineStart.UNDISPATCHED)`
 * 起步，并在切账号/离页前断言 fetch 已经进入（[CompletableDeferred] 标记）且 gate 尚未放行 ——
 * 否则协程可能还在排队、根本没走到阻塞点，"迟到"就无从谈起。全程不用 sleep。
 */
class FavAccountIsolationTest {

    private val accountA = FavAccount(loggedIn = true, mid = 100L)
    private val accountB = FavAccount(loggedIn = true, mid = 200L)
    private val loggedOut = FavAccount(loggedIn = false, mid = 0L)

    private fun folder(id: Long, title: String) = FavFolder(id = id, title = title, count = 1)

    private fun video(bvid: String) =
        FeedItem(bvid = bvid, title = bvid, cover = "", ownerName = "UP", durationSec = 60, viewCount = 1)

    private fun page(vararg bvids: String, hasMore: Boolean = false) =
        FavResourcePage(bvids.map(::video), hasMore)

    private fun foldersOf(vararg f: FavFolder): suspend (Long) -> List<FavFolder>? = { f.toList() }

    private fun itemPageOf(p: FavResourcePage?): suspend (Long, Int) -> FavResourcePage? = { _, _ -> p }

    private val noInfo: (String) -> Unit = {}
    private val noWarn: (String) -> Unit = {}

    // ------------------------------------------------------------------ 换账号

    @Test
    fun 换账号必须清空旧夹旧内容并重新要求加载() {
        val s = FavSession()
        assertTrue("第一次观察到的账号也算变化，否则登出会停在 LOADING", s.syncAccount(accountA))
        assertEquals(FavState.LOADING, s.state)

        val reqA = s.beginFolders()!!
        runBlocking {
            s.runFolders(reqA, live = { accountA }, profileMid = { 100L },
                fetch = foldersOf(folder(11L, "A的夹")), info = noInfo, warn = noWarn)
        }
        assertEquals(FavState.FOLDERS, s.state)
        assertEquals(listOf(11L), s.folders.map { it.id })
        assertFalse(s.inFlight)

        // A → B
        assertTrue(s.syncAccount(accountB))
        assertEquals(FavState.LOADING, s.state)
        assertTrue("旧账号的夹必须清掉", s.folders.isEmpty())
        assertNull(s.opened)
        assertTrue("旧账号的内容必须清掉", s.items.isEmpty())
        assertEquals(0, s.page)
        assertTrue(s.hasMore)
        assertNull(s.error)
        assertFalse("闸门必须放掉，否则新账号的加载会被挡回去", s.inFlight)
        assertFalse(s.loadingMore)
        assertTrue("新账号必须真的发一次请求", s.needsFoldersReload())
        assertEquals(FavLoadPlan.LOAD_FOLDERS, s.planLoad())

        val reqB = s.beginFolders()!!
        runBlocking {
            s.runFolders(reqB, live = { accountB }, profileMid = { 200L },
                fetch = foldersOf(folder(22L, "B的夹")), info = noInfo, warn = noWarn)
        }
        assertEquals(listOf(22L), s.folders.map { it.id })
    }

    @Test
    fun 登出进NEED_LOGIN而不是显示空夹列表() {
        val s = FavSession()
        s.syncAccount(accountA)
        runBlocking {
            s.runFolders(s.beginFolders()!!, live = { accountA }, profileMid = { 100L },
                fetch = foldersOf(folder(11L, "A的夹")), info = noInfo, warn = noWarn)
        }
        val aFolder = s.folders.single()
        runBlocking {
            s.runItems(s.beginFirstPage(aFolder)!!, live = { accountA },
                fetch = itemPageOf(page("BV_A")), info = noInfo, warn = noWarn)
        }
        assertEquals(FavState.ITEMS, s.state)

        assertTrue(s.syncAccount(loggedOut))
        assertEquals(FavState.NEED_LOGIN, s.state)
        assertTrue(s.folders.isEmpty())
        assertTrue(s.items.isEmpty())
        assertNull(s.opened)
        assertFalse("登出不是'该发请求的 LOADING'", s.needsFoldersReload())
        assertEquals(FavLoadPlan.NONE, s.planLoad())
        assertNull("登出后发不出请求", s.beginFolders())
        assertNull("旧夹更不该能打开", s.beginFirstPage(aFolder))
    }

    // ------------------------------------------------------- 迟到的旧响应/旧异常

    @Test
    fun 迟到的旧账号夹列表不得写进新账号() = runBlocking {
        val s = FavSession()
        s.syncAccount(accountA)
        val reqA = s.beginFolders()!!
        var live = accountA
        val gate = CompletableDeferred<Unit>()
        val fetchStarted = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            s.runFolders(reqA, live = { live }, profileMid = { 100L },
                fetch = { fetchStarted.complete(Unit); gate.await(); listOf(folder(11L, "A的夹")) },
                info = noInfo, warn = noWarn)
        }
        assertTrue("请求必须真的已经发出去、正阻塞在 fetch", fetchStarted.isCompleted)
        assertFalse("gate 还没放行，这时才谈得上'迟到'", gate.isCompleted)

        // 请求还在飞的这一刻换了账号：真实 VM 的 onEntry() 做的就是这一步
        live = accountB
        assertTrue(s.syncAccount(live))
        gate.complete(Unit)
        job.join()

        assertTrue("迟到的旧夹列表不许落到新账号", s.folders.isEmpty())
        assertEquals(FavState.LOADING, s.state)
        assertNull(s.error)
        assertFalse(s.inFlight)
        assertTrue("旧请求作废后，新账号还得有一次请求可发", s.needsFoldersReload())
        assertNotNull(s.beginFolders())
    }

    @Test
    fun 入口没被调到时_请求体内的账号核对同样丢弃旧夹() = runBlocking {
        val s = FavSession()
        s.syncAccount(accountA)
        val reqA = s.beginFolders()!!
        var live = accountA
        val gate = CompletableDeferred<Unit>()
        val fetchStarted = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            s.runFolders(reqA, live = { live }, profileMid = { 100L },
                fetch = { fetchStarted.complete(Unit); gate.await(); listOf(folder(11L, "A的夹")) },
                info = noInfo, warn = noWarn)
        }
        assertTrue("请求必须真的已经发出去、正阻塞在 fetch", fetchStarted.isCompleted)
        assertFalse("gate 还没放行，这时才谈得上'迟到'", gate.isCompleted)

        // 故意**不**调 syncAccount：只有请求体里那次实时账号核对能挡住它
        live = accountB
        gate.complete(Unit)
        job.join()

        assertTrue(s.folders.isEmpty())
        assertEquals(FavState.LOADING, s.state)
        assertTrue(s.needsFoldersReload())
    }

    @Test
    fun 迟到的旧异常不得把新账号打成ERROR() = runBlocking {
        val s = FavSession()
        s.syncAccount(accountA)
        val reqA = s.beginFolders()!!
        var live = accountA
        val gate = CompletableDeferred<Unit>()
        val fetchStarted = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            s.runFolders(reqA, live = { live }, profileMid = { 100L },
                fetch = { fetchStarted.complete(Unit); gate.await(); throw java.io.IOException("boom") },
                info = noInfo, warn = noWarn)
        }
        assertTrue("请求必须真的已经发出去、正阻塞在 fetch", fetchStarted.isCompleted)
        assertFalse("gate 还没放行，这时才谈得上'迟到'", gate.isCompleted)

        live = accountB
        gate.complete(Unit)
        job.join()

        assertEquals("旧账号的异常不许把新账号写成 ERROR", FavState.LOADING, s.state)
        assertNull(s.error)
        assertTrue(s.folders.isEmpty())
        assertTrue(s.needsFoldersReload())
    }

    @Test
    fun 迟到的旧夹内容不得混进新账号() = runBlocking {
        val s = FavSession()
        s.syncAccount(accountA)
        s.runFolders(s.beginFolders()!!, live = { accountA }, profileMid = { 100L },
            fetch = foldersOf(folder(11L, "A的夹")), info = noInfo, warn = noWarn)
        val aFolder = s.folders.single()

        val reqItems = s.beginFirstPage(aFolder)!!
        assertEquals(FavState.ITEMS_LOADING, s.state)
        var live = accountA
        val gate = CompletableDeferred<Unit>()
        val fetchStarted = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            s.runItems(reqItems, live = { live },
                fetch = { _, _ -> fetchStarted.complete(Unit); gate.await(); page("BV_A1") },
                info = noInfo, warn = noWarn)
        }
        assertTrue("请求必须真的已经发出去、正阻塞在 fetch", fetchStarted.isCompleted)
        assertFalse("gate 还没放行，这时才谈得上'迟到'", gate.isCompleted)

        live = accountB
        s.syncAccount(live)
        gate.complete(Unit)
        job.join()

        assertTrue("旧账号的夹内容不许落到新账号", s.items.isEmpty())
        assertNull(s.opened)
        assertEquals(FavState.LOADING, s.state)
        assertTrue(s.needsFoldersReload())
    }

    // ----------------------------------------------- 同账号重复打开（inFlight 闸门）

    /**
     * 同账号重复 open：第二下被 [FavSession.beginFirstPage] 的 `inFlight` 闸门拒绝，
     * 不能连累第一下那个**正在飞**的合法请求 —— 它必须照常跑完并落状态。
     *
     * ★ 边界：这条只钉 `FavSession` 的闸门语义（**拒绝 ≠ 撤销在途请求**）。
     * `FavViewModel.open` 里"先 beginFirstPage，被接受才 cancel 旧 loadJob"的那段
     * 取消接线建在 `AndroidViewModel`/`viewModelScope` 上，JVM 单测造不出来，
     * 由 GPT 代码审查验证；这里不镜像、也不源码字符串断言那段接线。
     */
    @Test
    fun 同账号重复open被拒_在途请求照常完成且闸门最终放掉() = runBlocking {
        val s = FavSession()
        s.syncAccount(accountA)
        s.runFolders(s.beginFolders()!!, live = { accountA }, profileMid = { 100L },
            fetch = foldersOf(folder(11L, "夹")), info = noInfo, warn = noWarn)
        val f = s.folders.single()

        // 第一下：真的发出去，闸门立起来。
        val first = s.beginFirstPage(f)!!
        assertEquals(FavState.ITEMS_LOADING, s.state)
        assertTrue(s.inFlight)

        val gate = CompletableDeferred<Unit>()
        val fetchStarted = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            s.runItems(first, live = { accountA },
                fetch = { _, _ -> fetchStarted.complete(Unit); gate.await(); page("BV_OK") },
                info = noInfo, warn = noWarn)
        }
        assertTrue("第一下的请求必须真的已经发出去、正阻塞在 fetch", fetchStarted.isCompleted)
        assertFalse("gate 还没放行", gate.isCompleted)

        // 原请求已阻塞在 fetch，再模拟第二下迟到的点击。
        assertNull("已有请求在飞时第二次 open 必须被拒绝", s.beginFirstPage(f))
        assertTrue("被拒不能放掉第一下的闸门", s.inFlight)
        assertEquals("被拒不能把第一下的 ITEMS_LOADING 改掉", FavState.ITEMS_LOADING, s.state)

        gate.complete(Unit)
        job.join()

        assertEquals("原请求照常完成", FavState.ITEMS, s.state)
        assertFalse("完成后闸门必须放掉，否则后续请求永远发不出", s.inFlight)
        assertFalse(s.loadingMore)
        assertEquals(listOf("BV_OK"), s.items.map { it.bvid })
    }

    // ------------------------------------------------------------------ 旧夹

    @Test
    fun 换账号后旧收藏夹打不开且会重新拉新账号的夹() {
        val s = FavSession()
        s.syncAccount(accountA)
        runBlocking {
            s.runFolders(s.beginFolders()!!, live = { accountA }, profileMid = { 100L },
                fetch = foldersOf(folder(11L, "A的夹")), info = noInfo, warn = noWarn)
        }
        val aFolder = s.folders.single()

        assertTrue(s.syncAccount(accountB))
        assertNull("迟到的旧夹不许在新账号下打开", s.beginFirstPage(aFolder))
        assertNull(s.opened)
        assertEquals(0, s.page)
        assertEquals(FavState.LOADING, s.state)
        assertTrue("被拒之后必须补一次新账号的夹列表", s.needsFoldersReload())

        val reqB = s.beginFolders()!!
        runBlocking {
            s.runFolders(reqB, live = { accountB }, profileMid = { 200L },
                fetch = foldersOf(folder(22L, "B的夹")), info = noInfo, warn = noWarn)
        }
        assertEquals(listOf(22L), s.folders.map { it.id })
        assertNotNull("新账号自己的夹能正常打开", s.beginFirstPage(s.folders.single()))
    }

    @Test
    fun 换账号后不允许出现没有请求的LOADING() {
        val s = FavSession()
        s.syncAccount(accountA)
        runBlocking {
            s.runFolders(s.beginFolders()!!, live = { accountA }, profileMid = { 100L },
                fetch = foldersOf(folder(11L, "A的夹")), info = noInfo, warn = noWarn)
        }

        s.syncAccount(accountB)
        assertEquals(FavState.LOADING, s.state)
        assertFalse(s.inFlight)
        assertTrue("FavViewModel.startFoldersIfPending 靠它补请求", s.needsFoldersReload())
        assertEquals(FavLoadPlan.LOAD_FOLDERS, s.planLoad())

        // 登出是合法的终点，不是"卡住的 LOADING"
        s.syncAccount(loggedOut)
        assertEquals(FavState.NEED_LOGIN, s.state)
        assertFalse(s.needsFoldersReload())
    }

    // ------------------------------------------------- 同账号缓存/分页/重试/离页

    @Test
    fun 同账号返回用缓存不重复请求() {
        val s = FavSession()
        s.syncAccount(accountA)
        runBlocking {
            s.runFolders(s.beginFolders()!!, live = { accountA }, profileMid = { 100L },
                fetch = foldersOf(folder(11L, "A的夹")), info = noInfo, warn = noWarn)
        }

        assertFalse("同账号不是变化", s.syncAccount(accountA))
        assertEquals(FavState.FOLDERS, s.state)
        assertEquals(FavLoadPlan.NONE, s.planLoad())
    }

    @Test
    fun 同账号分页失败重试保留已有内容() {
        val s = FavSession()
        s.syncAccount(accountA)
        runBlocking {
            s.runFolders(s.beginFolders()!!, live = { accountA }, profileMid = { 100L },
                fetch = foldersOf(folder(11L, "夹")), info = noInfo, warn = noWarn)
        }
        val f = s.folders.single()

        val firstPage = (1..20).map { "BV$it" }.toTypedArray()
        runBlocking {
            s.runItems(s.beginFirstPage(f)!!, live = { accountA },
                fetch = itemPageOf(page(*firstPage, hasMore = true)), info = noInfo, warn = noWarn)
        }
        assertEquals(FavState.ITEMS, s.state)
        assertEquals(1, s.page)
        assertEquals(20, s.items.size)
        assertTrue(s.hasMore)

        val second = s.beginNextPage()!!
        assertEquals(2, second.page)
        assertFalse(second.first)
        assertTrue(s.loadingMore)
        runBlocking {
            s.runItems(second, live = { accountA },
                fetch = itemPageOf(page("BV21", "BV22", hasMore = true)), info = noInfo, warn = noWarn)
        }
        assertEquals(2, s.page)
        assertEquals(22, s.items.size)
        assertFalse(s.loadingMore)

        // 第三页失败：保留已拿到的内容，页码不推进，页面仍是内容态（不是"夹是空的"）
        val third = s.beginNextPage()!!
        assertEquals(3, third.page)
        runBlocking {
            s.runItems(third, live = { accountA }, fetch = itemPageOf(null), info = noInfo, warn = noWarn)
        }
        assertEquals(FavState.ITEMS, s.state)
        assertEquals(22, s.items.size)
        assertEquals(2, s.page)
        assertNotNull(s.error)
        assertFalse(s.inFlight)
        assertFalse(s.loadingMore)
        assertNull("上次失败还没重试，不许继续翻页", s.beginNextPage())

        val retry = s.beginRetry()!!
        assertEquals("重试的是失败的那一页，不推进页码", 3, retry.page)
        assertFalse(retry.first)
        assertNull(s.error)
        runBlocking {
            s.runItems(retry, live = { accountA },
                fetch = itemPageOf(page("BV23", hasMore = false)), info = noInfo, warn = noWarn)
        }
        assertEquals(3, s.page)
        assertEquals(23, s.items.size)
        assertNull(s.error)
        assertFalse(s.hasMore)
        assertNull(s.beginNextPage())
    }

    @Test
    fun 离页取消保留内容与页码_返回时不再请求() {
        val s = FavSession()
        s.syncAccount(accountA)
        runBlocking {
            s.runFolders(s.beginFolders()!!, live = { accountA }, profileMid = { 100L },
                fetch = foldersOf(folder(11L, "夹")), info = noInfo, warn = noWarn)
            val f = s.folders.single()
            s.runItems(s.beginFirstPage(f)!!, live = { accountA },
                fetch = itemPageOf(page("BV1", "BV2", hasMore = true)), info = noInfo, warn = noWarn)
        }
        assertEquals(FavState.ITEMS, s.state)

        s.stopLoading()
        assertEquals("已经就绪的内容不该被离页打成 ERROR", FavState.ITEMS, s.state)
        assertEquals(1, s.page)
        assertEquals(2, s.items.size)
        assertNotNull(s.opened)
        assertFalse(s.inFlight)

        assertFalse(s.syncAccount(accountA))
        assertEquals("回来时内容和夹都还在，不重新请求", FavLoadPlan.NONE, s.planLoad())
    }

    @Test
    fun 第一页途中离页_迟到的结果作废且返回后能重试() = runBlocking {
        val s = FavSession()
        s.syncAccount(accountA)
        s.runFolders(s.beginFolders()!!, live = { accountA }, profileMid = { 100L },
            fetch = foldersOf(folder(11L, "夹")), info = noInfo, warn = noWarn)
        val f = s.folders.single()

        val req = s.beginFirstPage(f)!!
        val gate = CompletableDeferred<Unit>()
        val fetchStarted = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            s.runItems(req, live = { accountA },
                fetch = { _, _ -> fetchStarted.complete(Unit); gate.await(); page("BV_LATE") },
                info = noInfo, warn = noWarn)
        }
        assertTrue("请求必须真的已经发出去、正阻塞在 fetch", fetchStarted.isCompleted)
        assertFalse("gate 还没放行，这时才谈得上'迟到'", gate.isCompleted)

        s.stopLoading()
        assertEquals(FavState.ERROR, s.state)
        assertFalse(s.inFlight)

        gate.complete(Unit)
        job.join()
        assertTrue("离页后迟到的第一页结果必须作废", s.items.isEmpty())
        assertEquals(FavState.ERROR, s.state)
        assertNull(s.error)

        assertEquals(FavLoadPlan.RETRY_ITEMS, s.planLoad())
        val retry = s.beginRetry()!!
        assertEquals(1, retry.page)
        assertTrue(retry.first)
        s.runItems(retry, live = { accountA }, fetch = itemPageOf(page("BV_OK")),
            info = noInfo, warn = noWarn)
        assertEquals(FavState.ITEMS, s.state)
        assertEquals(listOf("BV_OK"), s.items.map { it.bvid })
    }
}
