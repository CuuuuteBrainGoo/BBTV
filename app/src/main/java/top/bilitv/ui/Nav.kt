package top.bilitv.ui

import androidx.activity.compose.BackHandler
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import top.bilitv.BiliTvApp
import top.bilitv.data.settings.ThemeSkin
import top.bilitv.ui.detail.DetailScreen
import top.bilitv.ui.fav.FavScreen
import top.bilitv.ui.follow.FollowScreen
import top.bilitv.ui.login.LoginScreen
import top.bilitv.ui.pgc.PgcDetailScreen
import top.bilitv.ui.player.PlayerScreen
import top.bilitv.ui.up.UpSpaceScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import top.bilitv.ui.components.AppToast
import top.bilitv.ui.components.rememberBackConfirm
import androidx.compose.ui.unit.dp

/**
 * **二级**页面。
 *
 * 一级页面（首页 / 影视 / 搜索 / 设置 …）不在这里 —— 它们是 [AppShell] 内部的 Tab 状态。
 * 这个栈**空着就表示"停在一级界面"**，非空表示"压在某个二级页面下面"。
 *
 * 为什么这么分：一级和二级的**返回语义不一样**。
 * - 一级之间切换：按返回没用（用侧栏切），侧栏常驻
 * - 二级页面：全屏覆盖，按返回逐层弹出
 *
 * 混在一个栈里就得回答"按返回到底该弹到哪个 Tab"，那是个没有正确答案的问题。
 */
sealed interface Screen {
    /** UGC 视频详情 */
    data class Detail(val bvid: String, val playlistId: Long = 0L, val playlistTitle: String = "") : Screen

    /**
     * 播放页。
     *
     * @param bvid UGC 视频号。**PGC 内容这里是空的** —— 番剧/影视没有 bvid，
     *   播放要走 `ep_id`，所以下面单独给了 [epId]。
     * @param epId > 0 表示这是一次 PGC 播放，取流走 `pgc/player/web/playurl`。
     * @param titleHint PGC 路径下没有 `view` 接口可取标题（它会返回空），
     *   标题由调用方（剧集详情页）带进来，免得播放页顶部空着一行。
     * @param coverHint 同上，PGC 的封面也只能由调用方带进来 ——
     *   它的去处是观看记录列表（UGC 的封面播放页自己能拿到）。
     */
    data class Player(
        val bvid: String,
        val cid: Long,
        val epId: Long = 0L,
        val titleHint: String = "",
        val coverHint: String = "",
        /**
         * PGC 的剧集 ID。**只用于"自动连播找下一集"**（2026-09-29 新增）。
         *
         * 为什么必须带进来：播放页手里只有 `epId + cid` 两个标量，
         * **不知道《这部剧》还有哪些集** —— 而"下一集是谁"只能从全集列表里算。
         * 有了 `seasonId` 就能复用已有的 `pgcDetail` 接口（游客态可用）拿到 `episodes`。
         *
         * UGC（普通视频）走另一条：它的 `pages` 在 `load()` 拉详情时**本来就在响应里**，
         * 不需要额外参数（见 `PlayerViewModel.load` 的 UGC 分支）。
         *
         * 默认 0 = 非 PGC / 调用方没带 —— 此时自动连播对 PGC 不生效（不会误播第一集）。
         */
        val seasonId: Long = 0L,
        val playlistId: Long = 0L,
        val playlistTitle: String = "",
        val directResume: Boolean = false,
    ) : Screen

    /** 影视 / 番剧的剧集详情（PGC） */
    data class PgcDetail(val seasonId: Long) : Screen

    /**
     * UP 主主页（他的投稿列表）。
     *
     * [name] / [face] 是**从关注列表带过来的**：关注接口的同一个响应里
     * 本来就有这两个字段，不带着走就得在主页再问一次接口 ——
     * 而 `/x/space/acc/info` 在游客态是 `-401 非法访问`，根本拿不到。
     *
     * @param mid UP 的用户号。
     * @param name 昵称。空串表示调用方没带（顶部会显示「UP 主」）。
     * @param face 头像地址。空串表示不带（顶部就不画头像，不裂图）。
     */
    data class UpSpace(val mid: Long, val name: String, val face: String) : Screen

    /** 扫码登录 */
    data object Login : Screen

    /** 收藏页（从「我的」进）。两层：收藏夹列表 → 夹内视频。 */
    data object Fav : Screen

    /**
     * 关注列表（UP 主网格）。
     *
     * ★ 2026-09-29 从**侧栏一级项**降为**二级页**。少爷原话：
     * > 「关注」不要做在侧边栏，做到「我的」页面里。
     *
     * 所以它现在的去处是「我的」页里的一行，进去后按返回回到「我的」。
     * 顺带的好处：侧栏从 9 项变 8 项，竖向更宽松（`docs/99` §E 记过侧栏溢出的事故）。
     */
    data object Follow : Screen

    /**
     * 直播播放页。
     *
     * ## 为什么单独一个 Screen，不复用 [Player]
     *
     * [Player] 的两个必填参数是 `bvid` 和 `cid` —— 直播**两个都没有**：
     * 直播没有视频号，弹幕也不按 cid 分段。硬塞的话调用点会变成
     * `Screen.Player(bvid = "", cid = 0, liveRoomId = 12345)`，
     * 一眼看不出它其实是直播。
     *
     * 分成两个 Screen 之后，"哪条路"这件事在**导航层就定死了**，
     * 播放页只要看自己拿到的是哪一组参数。
     *
     * @param roomId 直播间号（长号）。
     * @param title 房间标题。列表接口给的，带过来省一次请求，也让顶部信息栏立刻有内容。
     * @param cover 封面。给"重连/退回列表"这类场景留的（直播不写观看记录，所以它不用于历史）。
     * @param uname 主播昵称。同上，省一次请求。
     */
    data class LivePlayer(
        val roomId: Long,
        val title: String,
        val cover: String,
        val uname: String,
    ) : Screen

    companion object {
        /**
         * **点一张 UGC 视频卡之后到底去哪一页。**
         *
         * 少爷原话（2026-09-30 实机反馈 7，逐字）：
         * > 把所有的详情页先关了，在设置里做成开关可选选项，设置里打开了再显示详情页，
         * > 平时点击视频卡直接进入播放。
         *
         * | `detailPageEnabled` | 结果 |
         * |---|---|
         * | `true`  | 先进 [Detail]（详情页），从那里点播放 |
         * | `false` | 直接进 [Player]，**`cid = 0`** |
         *
         * ## 为什么 `cid` 敢填 0
         *
         * 详情页存在的唯一"技术必要"就是它能给出 `cid`。但播放页自己也会拉一次 `view`：
         * `PlayerViewModel.load` 的 UGC 分支里有
         * `realCid = if (cid != 0L) cid else detail?.cid ?: 0L` ——
         * 也就是说 `cid = 0` 时它会**自己去取**，不是"等外部给"。
         *
         * 所以这一跳省掉的只是**一屏 UI**，请求次数并不增加（详情页那一屏本来也要拉 `view`），
         * 反而少了一次"详情页 → 播放页"的 `view` 重复请求。
         *
         * ## 为什么是一个纯函数
         *
         * 它是"设置 → 导航"的**唯一一处**判据。抽成不依赖 Compose 的纯函数，
         * 单测才能直接钉住它（见 `NavTargetTest`）—— 否则这个开关写反了
         * （开=跳过、关=进详情）在界面上完全看不出来，只有用户觉得"怎么还是老样子"。
         *
         * 三处调用点共用它：首页/影视的分区卡、收藏夹、UP 主主页。
         * ⛔ 新增"点视频卡"的入口时**必须走这个函数**，别直接写 [Detail]。
         *
         * ⚠️ 番剧 / 影视（PGC）不走这里：那里是"一部剧"不是"一个视频"，
         * 不选集没有 `epId`，详情页必须保留。
         */
        fun targetForVideoCard(detailPageEnabled: Boolean, bvid: String): Screen =
            if (detailPageEnabled) Detail(bvid) else Player(bvid = bvid, cid = 0L)

        fun targetAfterPlayback(returnDetails: Boolean, bvid: String, seasonId: Long,
            playlistId: Long = 0L, playlistTitle: String = ""): Screen? = when {
            !returnDetails -> null
            seasonId > 0L -> PgcDetail(seasonId)
            bvid.isNotBlank() -> Detail(bvid, playlistId, playlistTitle)
            else -> null
        }
    }
}

/**
 * 页面栈。
 *
 * 没有引入 Navigation Compose：页面数量还很少，一个 `mutableStateListOf` 当栈就够了。
 * 引入导航库要多一个依赖 + 一套 route 字符串约定，现在的收益是零。
 * 等需要深链或参数传递变复杂时再换（成本很低，页面本身与导航无关）。
 *
 * @param onSkinChange 换肤。一路透传到设置页 —— 皮肤状态住在 `MainActivity`
 *   （因为 `BiliTvTheme` 在那一层），设置页只负责发起请求。
 */
@Composable
fun BiliTvRoot(
    onSkinChange: (ThemeSkin) -> Unit,
    /** 侧栏显示哪些项、什么顺序。状态住在 `MainActivity`（见那里的说明）。 */
    railTabs: List<NavTab>,
    onRailTabsChange: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? BiliTvApp
    val stack = remember { mutableStateListOf<Screen>().apply {
        if (app?.settings?.startupPage == "FAV") add(Screen.Fav)
    } }

    /*
     * 点视频卡去哪一页（少爷反馈 7 的「视频详情页」开关）。
     *
     * ⛔ **必须在"点击的那一刻"现读设置，不能 `remember` 住。**
     * 这个开关和 `railTabs` 不一样：改侧栏要求**立刻**重排，所以它得是状态；
     * 而"去哪一页"只在点击时用一次。要是把它 `remember` 成一个值，
     * 用户在设置页把它关掉、返回首页再点卡，走的还是旧值 ——
     * 表现就是"开关点了没反应，重启才好"（本项目在别处真踩过这一类，见 `docs/99` §C）。
     *
     * 拿不到 App（预览 / 单测环境）时按 `false` —— 与 `SettingsStore` 的默认值一致。
     */
    fun videoTarget(bvid: String, playlistId: Long = 0L, playlistTitle: String = ""): Screen =
        if (app?.settings?.detailPageEnabled == true) Screen.Detail(bvid, playlistId, playlistTitle)
        else Screen.Player(bvid, 0L, playlistId = playlistId, playlistTitle = playlistTitle)

    /*
     * ★ 一级 Tab 的名字**提升到这一层**（2026-09-29 修 back 键退桌面）。
     *
     * ## 为什么必须提升
     *
     * 原实现在这里写的是 `BackHandler(enabled = stack.isNotEmpty()) { pop() }` ——
     * 只覆盖"压在二级页面上"的情况。**栈空着（停在一级页面）时它是禁用的**，
     * 按键直接穿透到 Activity 默认行为 = **退出 App 回桌面**。
     *
     * 实测复现：在「影视」页按返回 → 落到手机桌面（`docs/29` 决策 005，复现 3 次）。
     *
     * ## 正确的返回语义（用户预期）
     *
     * ```
     * 二级页面  → 逐层弹出              （原有行为，保留）
     * 非首页 Tab → 回「首页」            （新增）
     * 在首页    → 退出 App              （交给系统默认）
     * ```
     *
     * 这是所有内容类 App 的通行做法：**返回键先做"退到更上一级的内容"，
     * 只有退无可退时才退出应用**。原来那版把"一级 Tab 之间"当成了平级、
     * 按下返回就等于"没有上一级" → 直接退出，这不符合直觉
     * （尤其是从侧栏点进「设置」再想回去的时候）。
     *
     * ## 为什么提升而不是在 AppShell 里加 BackHandler
     *
     * `AppShell` 的返回键只在"它自己在组合里"的时候才生效 —— 而二级页面
     * 会把 `AppShell` **整个从组合里摘掉**。两个 BackHandler 谁生效取决于
     * 组合顺序，那是竞态。放在根节点一处处理，语义清晰且不会有重叠。
     */
    var tabName by rememberSaveable { mutableStateOf(NavTab.startup(app?.settings?.startupPage, railTabs).name) }
    var startupFocusPending by rememberSaveable { mutableStateOf(app?.settings?.startupPage != "FAV") }
    // 认不出来（枚举改名/删项）时回落；当前项被用户在设置里藏起来时也回落（落到侧栏第一项）
    val tab = railTabs.firstOrNull { it.name == tabName } ?: railTabs.firstOrNull() ?: NavTab.HOME

    /*
     * 给一级界面存一份"离开时的样子"。
     *
     * 这个 `when` 是互斥的：进详情页时 [AppShell] **整个从组合里被摘掉**，
     * 所有 `remember` 归零。2026-09-28 实测过：在首页往下滚 8 行、点进去、再返回，
     * 焦点**跳回第一张卡**、滚动位置也回到顶部，用户得重新滚一遍找刚才那张。
     *
     * `rememberSaveableStateHolder` 会把 `rememberSaveable` 存的值留住 ——
     * `rememberLazyGridState()` 内部就是用 `rememberSaveable` 的，
     * 所以滚动位置和焦点下标都跟着回来。
     */
    val stateHolder = rememberSaveableStateHolder()

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    /*
     * ★ 返回键一处收口（2026-09-29）—— 见上面 `tabName` 那段说明。
     *
     * 三档优先级，从内到外：
     *   1. 有二级页面  → 弹出（播放页自己也有 BackHandler，它会先吃掉）
     *   2. 不在首页    → 回首页
     *   3. 就在首页    → 不拦，交给系统退出 App
     *
     * `enabled = stack.isNotEmpty() || tab != NavTab.HOME` 把第 3 种情况
     * 显式排除掉 —— 这里**故意不用"永远 enable 然后自己判断退出"**，
     * 因为"退出 App"这件事该不该做、怎么做（要不要保留最近任务等）
     * 是系统的事，替它做决定只会跟各家的返回手势打架。
     */
    /*
     * ★ 2026-09-30（少爷反馈 4）：**在最外层再按一次才退出 App**。
     *
     * 原来这里 `enabled` 故意排除"就在首页"这一支，交给系统退出 —— 结果按错一下就回桌面上。
     * 现在改成**永远拦**，然后用一条 `when` 表达优先级：
     *
     * | 顺序 | 情况 | 结果 |
     * |---|---|---|
     * | ① | 有二级页面 | 弹出一层 |
     * | ② | 不在首页 | 回首页 |
     * | ③ | 在首页 + 两秒内按了第二次 | 真的退出 App |
     * | ④ | 在首页 + 第一次按 | 弹 2 秒提示「再按一下返回退出 APP」 |
     *
     * ⛔ 和原来那条注释的想法相反：**"退出 App 该怎么做是系统的事"在这里不成立** ——
     * 用户按错一下就没了，代价是重新冷启动 4.8 秒。所以由我们自己加一层确认。
     */
    val backConfirm = rememberBackConfirm()
    BackHandler(enabled = true) {
        when {
            stack.isNotEmpty() -> pop()
            tab != NavTab.HOME -> tabName = NavTab.HOME.name
            backConfirm.message != null -> {
                backConfirm.clear()
                (context as? Activity)?.finish()
            }
            else -> backConfirm.arm("再按一下返回退出 APP")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    when (val top = stack.lastOrNull()) {
        null -> stateHolder.SaveableStateProvider("shell") {
            AppShell(
                startupFocus = app?.settings?.startupFocus.takeIf { startupFocusPending },
                onStartupFocusHandled = { startupFocusPending = false },
                tabName = tabName,
                onSelectTab = { tabName = it.name },
                // ★ 详情页开关在这里生效（少爷反馈 7）。见 `Screen.targetForVideoCard` 的说明。
                onOpenVideo = { stack.add(videoTarget(it)) },
                onOpenSeason = { stack.add(Screen.PgcDetail(it)) },
                onOpenUp = { mid, name, face -> stack.add(Screen.UpSpace(mid, name, face)) },
                onOpenLogin = { stack.add(Screen.Login) },
                onOpenFollow = { stack.add(Screen.Follow) },
                onOpenFav = { stack.add(Screen.Fav) },
                onOpenLive = { room ->
                    stack.add(
                        Screen.LivePlayer(
                            roomId = room.roomId,
                            title = room.title,
                            cover = room.cover,
                            uname = room.uname,
                        )
                    )
                },
                onSkinChange = onSkinChange,
                railTabs = railTabs,
                onRailTabsChange = onRailTabsChange,
                /*
                 * 从观看记录点进来 = **直接续播**，不绕详情页。
                 *
                 * 这是"历史续播"这个功能的核心动作：用户点历史里的一条，
                 * 想要的是"接着看"，不是"再看一遍这个视频的介绍页"。
                 * 播放页自己会从本机记录里读上次的位置（`resumeIfNeeded`）。
                 */
                onResume = { e ->
                    stack.add(Screen.Player(e.bvid, e.cid, e.epId, e.title, e.cover, seasonId = e.seasonId, directResume = true))
                },
            )
        }

        is Screen.Detail -> DetailScreen(
            bvid = top.bvid,
            onBack = { pop() },
            onPlay = { stack.add(Screen.Player(top.bvid, it, playlistId = top.playlistId, playlistTitle = top.playlistTitle)) },
        )

        is Screen.PgcDetail -> PgcDetailScreen(
            seasonId = top.seasonId,
            onBack = { pop() },
            onPlayEpisode = { epId, cid, title, cover ->
                stack.add(
                    Screen.Player(
                        bvid = "",
                        cid = cid,
                        epId = epId,
                        titleHint = title,
                        coverHint = cover,
                        // 自动连播靠它去拉全集列表（见 Screen.Player.seasonId 的说明）
                        seasonId = top.seasonId,
                    )
                )
            },
            onNeedLogin = { stack.add(Screen.Login) },
        )

        is Screen.UpSpace -> UpSpaceScreen(
            mid = top.mid,
            nameHint = top.name,
            faceHint = top.face,
            onOpen = { stack.add(videoTarget(it)) },
            onBack = { pop() },
            onNeedLogin = { stack.add(Screen.Login) },
        )

        is Screen.Follow -> FollowScreen(
            onOpenUp = { mid, name, face -> stack.add(Screen.UpSpace(mid, name, face)) },
            onNeedLogin = { stack.add(Screen.Login) },
            // 二级页有返回键了，这个"去首页"只用于**空列表时给个落脚点**，
            // 所以它做的事是"退回上一级"，而不是跳 Tab —— 跳 Tab 会让用户莫名其妙回到首页。
            onGoHome = { pop() },
        )

        is Screen.Fav -> FavScreen(
            onBack = { pop() },
            onOpenVideo = { bvid, id, title -> stack.add(videoTarget(bvid, id, title)) },
            onNeedLogin = { stack.add(Screen.Login) },
        )

        is Screen.Login -> LoginScreen(
            onBack = { pop() },
            // 登录成功后把登录页弹掉，回到用户原来那一页。
            // 不主动刷新那一页的数据 —— [PgcDetailScreen] 的 VM 会重新取一次
            // （它按 seasonId 记忆，登录页弹掉后那一页会重新组合）。
            onLoggedIn = { pop() },
        )

        is Screen.Player -> PlayerScreen(
            bvid = top.bvid,
            cid = top.cid,
            epId = top.epId,
            titleHint = top.titleHint,
            coverHint = top.coverHint,
            seasonId = top.seasonId,
            playlistId = top.playlistId,
            playlistTitle = top.playlistTitle,
            directResume = top.directResume,
            onOpenUp = { mid, name, face -> stack.add(Screen.UpSpace(mid, name, face)) },
            onBack = { currentBvid, currentSeason ->
                val target = Screen.targetAfterPlayback(app?.settings?.returnDetailsOnExit == true,
                    currentBvid, currentSeason, top.playlistId, top.playlistTitle)
                pop()
                if (target != null && stack.lastOrNull() != target) {
                    // 替换旧详情，防止连播到新视频后返回链不断堆积介绍页。
                    if (stack.lastOrNull() is Screen.Detail || stack.lastOrNull() is Screen.PgcDetail) pop()
                    stack.add(target)
                }
            },
        )

        is Screen.LivePlayer -> PlayerScreen(
            // 直播没有 bvid / cid：这两条参数留着默认值，
            // 分支由 liveRoomId > 0 决定（见 PlayerScreen 的参数说明）
            bvid = "",
            cid = 0L,
            liveRoomId = top.roomId,
            titleHint = top.title,
            coverHint = top.cover,
            ownerHint = top.uname,
            onOpenUp = { mid, name, face -> stack.add(Screen.UpSpace(mid, name, face)) },
            onBack = { _, _ -> pop() },
        )
    }

        // 「再按一下返回退出 APP」的半透明提示（少爷反馈 4）
        backConfirm.message?.let {
            AppToast(
                text = it,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
            )
        }
    }
}
