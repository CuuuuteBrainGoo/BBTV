package top.bilitv.ui.player

import top.bilitv.ui.components.scrollWithScrollbar
import top.bilitv.data.settings.PlayerKeyBindings
import top.bilitv.data.settings.RemoteAction
import top.bilitv.data.settings.RemoteKeyPress
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilitv.BiliTvApp
import top.bilitv.R
import top.bilitv.data.settings.uiLocale
import top.bilitv.data.auth.WebCookieMaintainer
import top.bilitv.data.danmaku.DanmakuItem
import top.bilitv.data.danmaku.danmakuSegmentCount
import top.bilitv.data.danmaku.danmakuSegmentIndex
import top.bilitv.data.danmaku.mergeDanmaku
import top.bilitv.data.history.History
import top.bilitv.data.history.HistoryEntry
import top.bilitv.data.model.DashStream
import top.bilitv.data.model.LiveStatus
import top.bilitv.data.model.PlayInfo
import top.bilitv.data.model.VideoDetail
import top.bilitv.data.model.PgcDetail
import top.bilitv.data.model.FeedItem
import top.bilitv.data.model.VideoRelation
import top.bilitv.data.model.FavFolder
import top.bilitv.data.model.coinsToAdd
import top.bilitv.data.settings.DanmakuTuning
import top.bilitv.data.settings.PlaybackTuning
import top.bilitv.data.settings.QualityOptions
import top.bilitv.data.sponsor.SkipPlanner
import top.bilitv.player.BiliPlayer
import top.bilitv.player.DecoderSupport
import top.bilitv.player.NextEpisode
import top.bilitv.player.PlaylistQueue
import top.bilitv.player.StreamSelector
import top.bilitv.player.audioMimeOf
import top.bilitv.ui.theme.AppTheme
import top.bilitv.player.videoMimeOf
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.components.RailIcons
import top.bilitv.ui.components.rememberBackConfirm
import top.bilitv.ui.components.formatCount
import top.bilitv.ui.components.formatDuration
import top.bilitv.util.AppLog

/**
 * 播放页。
 *
 * 界面取舍（Phase 1）：**不用 `PlayerView` 自带的控制器**，控制条自己用 Compose 写。
 * 原因是 Compose 与 Android View 的焦点互通一直是坑，而电视上"焦点在谁身上"比什么都重要；
 * 自己写则完全可控。控制条目前是**常驻半透明**的，自动隐藏留到界面设计阶段一起做
 * —— 现在先把"能播、能跳、能弹幕、能跳过广告"做实。
 */
@Composable
fun PlayerScreen(
    bvid: String,
    cid: Long,
    onBack: (String, Long) -> Unit,
    /**
     * > 0 表示这是一次 PGC（番剧/影视）播放，取流走 `pgc/player/web/playurl`。
     * 默认 0 让原来的 UGC 调用点一个字都不用改 —— 那条路径是已经验证过的，不动它。
     */
    epId: Long = 0L,
    /** PGC 路径下没有 `view` 接口能取标题，由调用方带进来 */
    titleHint: String = "",
    /**
     * 封面。**只有 PGC 需要**：UGC 的封面在 `view` 接口的响应里，
     * 而番剧/影视的详情接口拿不到（要登录），只能由剧集详情页带进来。
     * 它的唯一去处是观看记录列表 —— 少这一张图，历史页会多出一行灰块。
     */
    coverHint: String = "",
    /**
     * > 0 表示这是一次**直播**播放，取流走直播站的 `getRoomPlayInfo`。
     *
     * 直播和点播在播放层是**三条不同的路**（UGC / PGC / 直播），
     * 所以它又是一个独立参数而不是"塞进 cid"。
     * 三者的差别不止"接口不同"：直播**没有时长、不能拖进度、没有弹幕分段、
     * 不写观看记录**（`docs/19`）。合成一条路会让每一处都长出 `if (是直播)`。
     */
    liveRoomId: Long = 0L,
    /** 直播路径下主播昵称，由列表页带进来（省一次接口） */
    ownerHint: String = "",
    /**
     * PGC 的剧集 ID。**只用于"自动连播找下一集"**（2026-09-29 新增）。
     *
     * 播放页手里只有 epId + cid，不知道这部剧还有哪些集 —— 有了 seasonId
     * 就能复用 `pgcDetail` 拿全集列表。0 = 非 PGC（UGC 的 pages 在详情响应里，
     * 不需要这个参数）。
     */
    seasonId: Long = 0L,
    onOpenUp: ((Long, String, String) -> Unit)? = null,
    playlistId: Long = 0L,
    playlistTitle: String = "",
    directResume: Boolean = false,
) {
    val context = LocalContext.current
    // 播放 VM 跟随播放页；退出即释放解码器、缓冲和定时任务。
    val playerStore = remember { ViewModelStore() }
    val playerOwner = remember { object : ViewModelStoreOwner {
        override val viewModelStore = playerStore
    } }
    val vm: PlayerViewModel = viewModel(
        viewModelStoreOwner = playerOwner,
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application),
    )
    LaunchedEffect(bvid, cid, epId, liveRoomId) {
        vm.playlistId = playlistId
        vm.playlistTitle = playlistTitle
        if (liveRoomId > 0L) {
            vm.loadLive(liveRoomId, titleHint, coverHint, ownerHint)
        } else {
            vm.load(bvid, cid, epId, titleHint, coverHint, seasonId, directResume = directResume)
        }
    }

    // 播放中禁止进屏保（docs/05 §4 第 9 条）
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            vm.endTouchBoost()
            vm.pause()
            /*
             * 离开播放页时**补一次落盘**。
             *
             * 定时落盘是 5 秒一次，所以"看了 4 秒就退出"这一段本来会丢。
             * 用户对续播的预期是"接着刚才那一下"，丢几秒会让人觉得"没记住"。
             *
             * 顺序在 `pause()` 之后：先停住，再读位置，拿到的是一个稳定的值。
             */
            vm.persistProgress()
            playerStore.clear()
        }
    }

    var panelOpen by remember { mutableStateOf(false) }

    // 返回键统一由下面那一个 BackHandler 处理（合并成一条链，见那里的说明）

    // ------------------------------------------------------------ 控制条自动隐藏

    /*
     * 为什么必须做这件事：
     *
     * 控制条原来是**常驻半透明**的，横在画面下方挡着字幕和弹幕。
     * 对"看视频"这个主场景来说，它是纯干扰 —— 用户 99% 的时间不碰它。
     *
     * ## 隐藏 ≠ 消失
     *
     * 不能简单地把控制条从组合里删掉：那样焦点会**掉空**，
     * 用户按方向键什么都不会发生 —— 比挡着画面糟糕得多。
     * 所以隐藏时放一个**全屏透明的兜底焦点**接住按键，任何按键先唤出控制条。
     *
     * ## 什么时候不隐藏
     *
     * 暂停 / 出错 / 面板开着 —— 这几种情况用户**就是要操作**，
     * 藏起来只会让他以为"按了没反应"。
     */
    var controlsVisible by remember { mutableStateOf(!vm.danmakuSettings.hideControlsOnStart) }
    var lastInputAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    /*
     * ★ 2026-09-30（少爷反馈 6）：**返回键一条链，优先级写在 `when` 里**。
     *
     * | 顺序 | 当前状态 | 按一下返回 |
     * |---|---|---|
     * | ① | 日志面板开着 | 关面板 |
     * | ② | 有跳过片头倒计时 | 撤销跳过 |
     * | ③ | 控制栏**开着** | 只**收起控制栏**，不退出播放 |
     * | ④ | 开关「单击返回键退出」= 开 | 退出播放（BT 的默认行为） |
     * | ⑤ | 两秒内按了第二次 | 退出播放 |
     * | ⑥ | 第一次按 | 弹 2 秒提示「再按一下返回退出当前视频」 |
     *
     * ## ⛔ 为什么合并成一个，而不是挂三个 `BackHandler`
     *
     * 原来这里挂着两个独立的 `BackHandler`（跳过倒计时 / 日志面板）。
     * Compose 的 `BackHandler` 是"**后组合的先拿到**"，
     * 也就是说**优先级由代码位置隐式决定** —— 谁写在下面谁优先。
     * 加第三个（返回键主逻辑）时，它必须写在最上面才能当兜底，
     * 但它要用的 `controlsVisible` 又声明在下面 → **编译直接不过**。
     *
     * 与其靠移动声明顺序来凑，不如**把优先级写成显式的 `when` 顺序**：
     * 一眼能看出谁先谁后，改动时也不会被"谁写在下面"这种隐式规则坑到。
     */
    val scope = rememberCoroutineScope()
    val remotePress = remember { RemoteKeyPress() }
    var remoteJob by remember { mutableStateOf<Job?>(null) }
    val cancelRemote: () -> Unit = {
        remoteJob?.cancel(); remoteJob = null; remotePress.clear(); vm.endTouchBoost()
    }
    DisposableEffect(Unit) { onDispose { remoteJob?.cancel(); remotePress.clear() } }
    LaunchedEffect(bvid, cid, epId, liveRoomId) { cancelRemote() }
    val playerWindowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(playerWindowFocused) { if (!playerWindowFocused) cancelRemote() }
    val backConfirm = rememberBackConfirm()
    // 这些提示会从非组合的按键回调里发出去，先在组合作用域取好当前语言文本。
    val backConfirmText = stringResource(R.string.player_back_confirm)
    val noticeLiveNoSubtitle = stringResource(R.string.player_live_no_subtitle)
    val noticeLiveNoSpeed = stringResource(R.string.player_live_no_speed)
    val noticeNoUpSpace = stringResource(R.string.player_no_up_space)
    val noticeCannotBoost = stringResource(R.string.player_cannot_boost)
    BackHandler(enabled = true) {
        cancelRemote()
        when {
            vm.previewEnded -> onBack(vm.currentBvid, vm.currentSeasonId)
            vm.touchLocked -> { vm.touchLocked = false; controlsVisible = true }
            vm.comments.open -> vm.comments.back()
            vm.sideOpen -> vm.closeSidebar()
            panelOpen -> panelOpen = false
            vm.skipHint != null -> {
                vm.cancelSkip()
                panelOpen = false
            }
            controlsVisible -> controlsVisible = false
            vm.singleBackExit -> onBack(vm.currentBvid, vm.currentSeasonId)
            backConfirm.message != null -> {
                backConfirm.clear()
                onBack(vm.currentBvid, vm.currentSeasonId)
            }
            else -> backConfirm.arm(backConfirmText)
        }
    }

    /** 正在长按快进/快退。按住期间控制条必须钉住，否则跳到一半自己消失 */
    var seeking by remember { mutableStateOf(false) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var seekHintAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(seekHintAt, seeking) {
        if (!seeking && seekTarget != null) { delay(1_500L); seekTarget = null }
    }

    /*
     * ══════════════════════════════════════════════════════════════════════
     * 遥控器 ← / → 快退 / 快进（2026-09-30 新增，替代控制栏里那两颗按钮）
     * ══════════════════════════════════════════════════════════════════════
     *
     * 少爷原话（实机反馈 6）：
     * > 快进10秒和后退10秒不要做成图标在播放器控制栏，播放器控制栏的功能抄BT的，
     * > 快进快退的实现和按键逻辑也抄BT的。
     *
     * 依据：BT 的「按键设置（播放时）」（`docs/37` §4.4 / `tmp/shots/bt_keymap_01.png`）
     * —— **左键 = 快退 x 秒、右键 = 快进 x 秒，两项锁死不可改**；
     * 而同一个页面抓到的控制栏（`docs/37` §4.1）里**没有**快进快退。
     *
     * ★ 什么时候生效：**控制栏隐藏时**。
     *
     * 这不是偷懒，是照 BT 实测抄的。模拟器上在 BT 播放页做了 A/B：
     * - 控制栏**可见**时按 ←：时间 `01:40 → 01:42`（只是正常播放），
     *   同时**焦点高亮块往右移了一格**（`tmp/seek/montage2.png`）
     *   → 可见时 ← / → 是"在按钮行里走格子"，不是快进快退；
     * - 控制栏**隐藏**时按 ↓：控制栏出现（`tmp/shots/bt_seek_03.png`）
     *   → BT 的「下键 = 播放面板」得到验证。
     *
     * 于是两个状态各司其职、互不打架：**看视频时左右键归进度，调控制栏时左右键归焦点**。
     *
     * ⚠️ 「隐藏时按 ← 是否真的跳了 10 秒」这一条**没能量到**：
     * 控制栏一藏，BT 就不显示时间（它的「显示播放进度时间」默认关），
     * 而中间那次补测正好赶上模拟器播放卡住（前后两帧一字不差）。
     * 但结论是被**锁死的映射**唯一确定的 —— 左右键既然锁死是快退/快进，
     * 而可见态又确认了它们不干这个，那"隐藏态才生效"是唯一解。
     */

    var seekJob by remember { mutableStateOf<Job?>(null) }

    /**
     * 按下和长按只更新目标时间；松手才定位，不请求遥控器预览画面。
     *
     * 用自己数节拍而不是系统按键重复（`repeatCount`）：遥控器的"长按"在模拟器上
     * 拿不到稳定的重复事件，等于把功能建在一个看不见的东西上（2026-09-29 的教训）。
     */
    val startSeekHold: (Long) -> Unit = { delta ->
        val startedAt = SystemClock.elapsedRealtime()
        var count = 1
        seekTarget = (vm.positionMs + delta).coerceIn(0L, vm.durationMs.coerceAtLeast(0L))
        seekHintAt = startedAt
        AppLog.i("Player", "按键进度预览：目标=${seekTarget}ms")
        seeking = true
        seekJob?.cancel()
        seekJob = scope.launch {
            try {
                delay(LONG_PRESS_DELAY_MS)
                while (isActive) {
                    val accelerated = PlaybackTuning.holdSeekStep(delta, SystemClock.elapsedRealtime() - startedAt)
                    seekTarget = ((seekTarget ?: vm.positionMs) + accelerated).coerceIn(0L, vm.durationMs.coerceAtLeast(0L))
                    seekHintAt = SystemClock.elapsedRealtime()
                    count++
                    delay(REPEAT_MS)
                }
            } finally {
                // 记录预览次数和目标，不把预览误报成播放器实际seek次数。
                if (count > 1) {
                    AppLog.i(
                        "Player",
                        "长按快进快退：按住 ${SystemClock.elapsedRealtime() - startedAt}ms，预览 $count 次，目标 ${seekTarget?.div(1000)}s",
                    )
                }
                seeking = false
            }
        }
    }

    val stopSeekHold: () -> Unit = {
        // cancel() 会让上面 finally 里的 `seeking = false` 跑起来，这里再写一次是
        // 为了"抬手时立刻解除钉住"，不必等协程收尾。
        if (seekJob != null) seekTarget?.let { vm.seekTo(it) }
        seekJob?.cancel()
        seekJob = null
        seeking = false
    }

    val pinned = !vm.playing || vm.error != null || panelOpen || vm.comments.open || vm.sideOpen || seeking || vm.actionBusy || vm.qualityDialog || vm.liveLineDialog || vm.favoriteFolders != null
    LaunchedEffect(vm.actionBusy) { if (!vm.actionBusy) lastInputAt = SystemClock.elapsedRealtime() }

    val wakeupFocus = remember { FocusRequester() }
    val firstBarFocus = remember { FocusRequester() }
    val panelFirstFocus = remember { FocusRequester() }

    LaunchedEffect(pinned, controlsVisible) {
        if (pinned || !controlsVisible) return@LaunchedEffect
        while (true) {
            delay(HIDE_POLL_MS)
            if (SystemClock.elapsedRealtime() - lastInputAt >= HIDE_AFTER_MS) {
                controlsVisible = false
                return@LaunchedEffect
            }
        }
    }

    /*
     * 焦点归位：谁"当前该有焦点"就送给谁。
     *
     * 这是真机实测发现的第三个焦点缺陷 —— **面板打开后焦点还留在底下的控制条上**，
     * 用户在弹幕设置面板里按方向键，动的却是背后那条看不见的控制条。
     * 严格说不是"焦点丢了"，是"焦点去了用户看不见的地方"，更难察觉。
     *
     * 等一帧再请求：节点刚 compose 出来时还没 attach 到窗口，
     * 这时 requestFocus() 会抛 IllegalStateException（同 Tv.kt 里的说明）。
     */
    LaunchedEffect(controlsVisible, panelOpen, vm.comments.open, vm.sideOpen, vm.qualityDialog, vm.liveLineDialog, vm.favoriteFolders != null, vm.previewEnded) {
        if (vm.previewEnded) { cancelRemote(); return@LaunchedEffect }
        if (vm.comments.open || vm.sideOpen || vm.qualityDialog || vm.liveLineDialog || vm.favoriteFolders != null) return@LaunchedEffect
        withFrameNanos { }
        runCatching {
            when {
                panelOpen -> panelFirstFocus.requestFocus()
                controlsVisible -> firstBarFocus.requestFocus()
                else -> wakeupFocus.requestFocus()
            }
        }
    }

    val performRemote: (RemoteAction) -> Unit = { action ->
        when (action) {
            RemoteAction.NONE -> Unit
            RemoteAction.PLAY -> vm.togglePlay()
            RemoteAction.CONTROLS -> { vm.comments.close(); vm.closeSidebar(); panelOpen = false; controlsVisible = !controlsVisible }
            RemoteAction.SETTINGS -> { vm.comments.close(); vm.closeSidebar(); controlsVisible = false; panelOpen = true }
            RemoteAction.CATALOGUE -> vm.openSidebar(PlaybackTuning.SideAction.CATALOGUE)
            RemoteAction.RECOMMEND -> vm.openSidebar(PlaybackTuning.SideAction.RECOMMEND)
            RemoteAction.UP_LIST -> vm.openSidebar(PlaybackTuning.SideAction.UP_UPLOADS)
            RemoteAction.DANMAKU -> vm.setDanmakuOn(!vm.dmOn)
            RemoteAction.SUBTITLE -> if (!vm.live) vm.changeSubtitlesEnabled(!vm.subtitlesEnabled) else vm.showNotice(noticeLiveNoSubtitle)
            RemoteAction.SPEED -> if (!vm.live) vm.cycleSpeed() else vm.showNotice(noticeLiveNoSpeed)
            RemoteAction.QUALITY -> vm.openQuality()
            RemoteAction.NEXT -> vm.playAdjacent(1)
            RemoteAction.PREVIOUS -> vm.playAdjacent(-1)
            RemoteAction.COMMENTS -> { controlsVisible = false; panelOpen = false; vm.openComments() }
            RemoteAction.REFRESH -> vm.refreshStream()
            RemoteAction.LIKE -> vm.toggleLike()
            RemoteAction.COIN -> vm.coin()
            RemoteAction.FAVORITE -> vm.openFavorites()
            RemoteAction.TRIPLE -> vm.triple()
            RemoteAction.OPEN_UP -> if (vm.ownerMid > 0L && onOpenUp != null) onOpenUp(vm.ownerMid, vm.ownerName, "") else vm.showNotice(noticeNoUpSpace)
            RemoteAction.BOOST -> if (!vm.beginTouchBoost()) vm.showNotice(noticeCannotBoost)
        }
    }
    val latestRemoteAction by androidx.compose.runtime.rememberUpdatedState(performRemote)
    val handlePlayerKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = playerKeys@ { e ->
        val nativeCode = PlayerKeyBindings.canonical(e.nativeKeyEvent.keyCode)
        if (e.type == KeyEventType.KeyUp && remotePress.key == nativeCode) {
            remoteJob?.cancel(); remoteJob = null
            remotePress.release(nativeCode, e.nativeKeyEvent.isCanceled)?.let(latestRemoteAction)
            vm.endTouchBoost()
            return@playerKeys true
        }
        if (e.key == Key.Back) { cancelRemote(); return@playerKeys false }
        if (!PlayerKeyBindings.allowed(e.nativeKeyEvent.keyCode)) return@playerKeys false
        if (e.type == KeyEventType.KeyDown && remotePress.key == nativeCode) return@playerKeys true

        /*
         * ⚠️ KeyUp 不能被开头那句一刀切拦掉。
         *
         * 原来第一行是 `if (e.type != KeyDown) return false`，因为那时候
         * 没有任何"按住不放"的功能。现在 ← / → 要**长按连跳**，
         * 抬手必须能通知到我们取消那个循环 —— 拦掉了等于"按住撒不开手"。
         *
         * ★ 但**只接快进快退那两个键**的抬手。
         *
         * `onPreviewKeyEvent` 返回 `true` 会把事件吃掉，Activity 就再也看不到它 ——
         * 而返回键的 `BackHandler` 走的是 Activity 那条路（按下时按 KeyUp 触发）。
         * 要是在这里"顺手"把**所有** KeyUp 都吃掉，就会出现这种怪事：
         * **按着左键不放的时候，返回键失灵一次**。
         * 这正是少爷最讨厌的"按了没反应"，所以这里必须按 key 过滤。
         */
        if (e.type == KeyEventType.KeyUp) {
            if (seekJob != null && seekDeltaFor(e.key, vm.seekStepMs) != null) {
                stopSeekHold()
                return@playerKeys true
            }
            return@playerKeys false
        }
        if (e.type != KeyEventType.KeyDown) return@playerKeys false
        vm.touchLocked = false // The lock only protects touch; remote keys remain usable.
        lastInputAt = SystemClock.elapsedRealtime()

        if (!controlsVisible && !vm.comments.open && !vm.sideOpen && !panelOpen && !vm.qualityDialog &&
            !vm.liveLineDialog && vm.favoriteFolders == null && PlayerKeyBindings.editable(nativeCode, true)) {
            val settings = vm.danmakuSettings
            val shortOverride = if (PlayerKeyBindings.editable(nativeCode, false)) settings.playerKeyAction(nativeCode, false) else null
            val long = settings.playerKeyAction(nativeCode, true)
            if (shortOverride != null || long != null) {
                cancelRemote(); stopSeekHold()
                val enabled = when (nativeCode) { 19 -> settings.playerUpEnabled; 20 -> settings.playerDownEnabled; else -> true }
                val short = if (!enabled) RemoteAction.NONE else shortOverride ?: when (nativeCode) {
                    23 -> RemoteAction.PLAY; 82 -> RemoteAction.CONTROLS
                    19, 20 -> PlayerKeyBindings.side(vm.sideActionFor(nativeCode == 19))
                    else -> RemoteAction.NONE
                }
                val holdAction = long.takeIf { enabled && it != RemoteAction.NONE }
                remotePress.start(nativeCode, if (holdAction == null) null else short, holdAction)
                if (holdAction == null) latestRemoteAction(short)
                else remoteJob = scope.launch {
                    delay(PlayerKeyBindings.HOLD_MS)
                    remotePress.fireLong()?.let(latestRemoteAction)
                }
                return@playerKeys true
            }
        }

        // 用户新规则：菜单键开／关控制条；从侧栏切回控制条。
        if (e.key == Key.Menu) {
            if (e.nativeKeyEvent.repeatCount == 0) {
                stopSeekHold()
                panelOpen = false
                if (vm.comments.open) { vm.comments.close(); controlsVisible = true }
                else if (vm.sideOpen) { vm.closeSidebar(); controlsVisible = true }
                else controlsVisible = !controlsVisible
            }
            return@playerKeys true
        }

        /*
         * 返回键**在这里不处理**，交给上面的三级链条（BackHandler）：
         * 控制栏开着 → 收起；关着 → 提示/退出。
         *
         * ⚠️ 2026-09-30 改：原来这里写着"隐藏状态下按返回就该退出播放" ——
         * 那条已被少爷推翻（他要求"控制栏开着按返回只收起控制栏"）。
         * 而"控制栏开着"这一支仍然走上面的链条，所以这里一律放行。
         */
        if (e.key == Key.Back) return@playerKeys false
        if (vm.comments.open || vm.sideOpen || panelOpen || vm.qualityDialog || vm.liveLineDialog || vm.favoriteFolders != null) return@playerKeys false

        /*
         * 控制栏**开着**时，← / → 归焦点系统（在按钮行里走格子）。
         *
         * 这是 BT 实测出来的分界线（见上面 [startSeekHold] 的说明）：
         * 面板可见 → 左右键走焦点；面板隐藏 → 左右键跳进度。
         */
        if (controlsVisible) return@playerKeys false

        if (e.key == Key.DirectionUp || e.key == Key.DirectionDown) {
            if (e.nativeKeyEvent.repeatCount == 0) {
                stopSeekHold()
                seekTarget = null
                vm.sideActionFor(e.key == Key.DirectionUp)?.let(vm::openSidebar)
            }
            return@playerKeys true
        }

        /*
         * —— 以下都是「控制栏隐藏」状态，也就是"纯看视频"的主场景 ——
         *
         * ★ ← / → = 快退 / 快进（BT 锁死的映射）。这一下**不唤醒控制栏** ——
         * BT 那个状态下的时间显示是关的，跳了 10 秒画面自己会说话，
         * 弹一条控制栏出来反而是干扰。
         */
        if (e.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
            if (e.nativeKeyEvent.repeatCount == 0) vm.togglePlay()
            return@playerKeys true
        }
        val delta = seekDeltaFor(e.key, vm.seekStepMs)
        if (delta != null) {
            // 直播的时间轴不可回退，这个键在直播页就当没接到。
            if (vm.live || vm.durationMs <= 0L) return@playerKeys false
            /*
             * ★ 只在"还没在按着"的时候起循环。
             *
             * 遥控器按住方向键时，**系统自己会持续补发 KeyDown**（按键重复，
             * 间隔约 50~150ms）。不挡这一下的话，每来一个重复事件就
             * `seekJob?.cancel()` + 重新起协程 + **立刻再跳一次** ——
             * 等于把节拍交给了系统的重复速率，实测出来是 0.14~0.24 秒一跳。
             *
             * 而 2026-09-29 的实测结论是：跳得太密会把播放器的网络请求连续打断
             * （日志里一串 `HttpDataSourceException: InterruptedIOException`），
             * 松开之后要缓冲好几秒。所以节拍必须由我们自己按 [REPEAT_MS] 数。
             */
            if (seekJob == null) startSeekHold(delta)
            return@playerKeys true
        }

        // 其余按键：隐藏状态下这一下**只用来唤醒**，不顺手执行。
        // 理由：用户看不见焦点在哪一项，顺手执行等于让他盲按 ——
        // 想调音量结果按到"返回"的体验比多按一次差得多。
        // （BT 的「下键 = 播放面板」也是这个行为，见 `docs/37` §4.4。）
        controlsVisible = true
        true
    }
    val latestPlayerKey by androidx.compose.runtime.rememberUpdatedState(handlePlayerKey)
    val keyActivity = LocalContext.current.findActivity() as? top.bilitv.MainActivity
    DisposableEffect(keyActivity) {
        val fallback: (android.view.KeyEvent) -> Boolean = { event ->
            val code = PlayerKeyBindings.canonical(event.keyCode)
            if (code in listOf(19, 20, 21, 22, 23, 82) ||
                (PlayerKeyBindings.editable(code, true) && code in vm.danmakuSettings.configuredPlayerKeys))
                latestPlayerKey(androidx.compose.ui.input.key.KeyEvent(event))
            else false
        }
        keyActivity?.playerUnhandledKey = fallback
        onDispose { if (keyActivity?.playerUnhandledKey === fallback) keyActivity.playerUnhandledKey = null }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent(handlePlayerKey)
    ) {
        // 画面由 PlayerView 承载（它负责按比例留黑边），但**关掉自带控制器**：
        // 控制条自己用 Compose 写，避开 Compose 与 Android View 的焦点互通问题。
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false; isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    resizeMode = resizeModeOf(vm.aspectId)
                    player = vm.player.exo
                }
            },
            /*
             * `update` 里同步 resizeMode —— 这样用户在控制条上切画面比例**立刻生效**，
             * 不用退出重进（2026-09-29）。`vm.aspectId` 是 State，切档会触发这里重跑。
             * 顺带一提 `player` 也在这里同步，是原来的逻辑（换播放器实例时用）。
             */
            update = {
                it.player = vm.player.exo
                it.resizeMode = resizeModeOf(vm.aspectId)
            },
            modifier = Modifier.fillMaxSize(),
        )

        PlayerTouch(vm, Modifier.matchParentSize(), tap = { controlsVisible = !controlsVisible; lastInputAt = SystemClock.elapsedRealtime() },
            target = { seekTarget = it; seekHintAt = SystemClock.elapsedRealtime() }, dragging = { seeking = it },
            enabled = !vm.touchLocked && !vm.comments.open && !vm.sideOpen && !panelOpen &&
                !vm.qualityDialog && !vm.liveLineDialog && vm.favoriteFolders == null)

        if (vm.error == null && vm.buffering) {
            Column(Modifier.align(Alignment.Center).zIndex(2f).background(Color(0xB3000000), RoundedCornerShape(16.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(Modifier.size(48.dp), color = AppTheme.current.primary)
                Text(stringResource(R.string.action_loading), color = Color.White, fontSize = 22.sp)
            }
        } else if (vm.error == null && !vm.playWhenReady && !controlsVisible && !panelOpen && vm.danmakuSettings.showPauseIcon && !vm.touchLocked) {
            Box(Modifier.align(Alignment.Center).zIndex(2f).size(112.dp).background(Color(0x66000000), CircleShape), contentAlignment = Alignment.Center) {
                Icon(RailIcons.Pause, stringResource(R.string.player_paused), tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(68.dp))
            }
        }
        seekTarget?.let { target ->
            Column(Modifier.align(Alignment.BottomCenter).zIndex(2f).fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Text("${formatDuration((target / 1000).toInt())} / ${formatDuration((vm.durationMs / 1000).toInt())}",
                    color = Color.White, fontSize = 16.sp,
                    style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 4f)),
                    modifier = Modifier.padding(end = 14.dp, bottom = 6.dp))
                LinearProgressIndicator(progress = { if (vm.durationMs > 0) (target.toFloat() / vm.durationMs).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().height(3.dp), color = AppTheme.current.primary,
                    trackColor = Color.White.copy(alpha = .18f), strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
                    gapSize = 0.dp, drawStopIndicator = {})
            }
        }

        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides top.bilitv.ui.theme.LocalPlaybackDensity.current) {
        DanmakuLayer(
            items = vm.danmaku,
            // 注意：**不能**用 vm.positionMs —— 那个值 250ms 才刷新一次，
            // 弹幕按它画就是一秒只动 4 次、中间十几帧钉在原地。见 danmakuPosition()
            position = { vm.danmakuPosition() },
            playing = vm.playing,
            // 直播接收器和点播分段统一在此渲染，开关也停止直播连接／新点播请求。
            enabled = vm.dmOn,
            alpha = vm.dmAlpha,
            scale = vm.dmScale,
            maxLines = vm.dmLines,
            scrollDurationMs = vm.dmSpeedMs,
            merge = vm.dmMerge,
            areaFifths = vm.dmArea,
            overlap = vm.dmOverlap,
            outlineWidth = vm.dmOutline,
            outlineMinAlpha = vm.dmOutlineAlpha,
            trackHeight = vm.dmTrackHeight,
            performance = vm.player.performanceMode,
            modifier = Modifier.fillMaxSize(),
        )

        if (!vm.live && vm.subtitlesEnabled) SubtitleLayer(vm.subtitleText, vm.subtitlesEnabled, vm.subtitleStyle, Modifier.fillMaxSize())
        if (vm.statsVisible) PlaybackStats(vm,
            Modifier.align(Alignment.TopStart).padding(12.dp).zIndex(2f))
        }

        vm.error?.let { err ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = err.title,
                    color = Color(0xFFFF7A7A),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (err.detail.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = err.detail,
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        /*
         * 提示条统一挂在信息栏下面。
         *
         * 原来是三个各自 `align(TopCenter)` + 写死 top 偏移的 Box。
         * 加了顶部信息栏之后，48dp 那一条会**压在标题上**。
         * 收拢成一列、偏移跟着信息栏走，以后再加提示也不用重新算坐标。
         */
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (controlsVisible) 136.dp else 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 广告跳过提示
            vm.skipHint?.let { hint -> Toast(stringResource(R.string.player_skip_cancel, hint)) }

            if (vm.muted && vm.error == null) {
                Toast(stringResource(R.string.player_audio_muted), alpha = 0.8f)
            }

            // 临时提示：换编码重播这类"自动救回来了"的动作要让用户看得见，
            // 否则画面突然重来一遍会让人以为是 bug
            vm.notice?.let { msg -> Toast(msg, color = Color(0xFF7FD8A0)) }

            // 返回键的两秒确认（少爷反馈 6）：控制栏已收起后，第一次按只提示
            backConfirm.message?.let { Toast(it) }
        }

        if (vm.comments.open) PlayerCommentSidebar(vm.comments, vm.danmakuSettings.playerSidebarWidth, Modifier.align(Alignment.CenterEnd).zIndex(3f))

        if (vm.sideOpen) {
            PlayerSidebar(vm, onClose = vm::closeSidebar, onSettings = { vm.closeSidebar(); panelOpen = true },
                modifier = Modifier.align(Alignment.CenterEnd).zIndex(3f))
        }

        if (panelOpen) {
            DanmakuPanel(
                vm = vm,
                firstFocus = panelFirstFocus,
                modifier = Modifier.align(Alignment.CenterEnd).padding(horizontal = 8.dp).zIndex(3f),
                onLock = { vm.lockTouch(); panelOpen = false; controlsVisible = false; seekTarget = null },
                onClose = { panelOpen = false; controlsVisible = false },
            )
        }

        if (controlsVisible) {
            // 顶部信息栏：只在控制条唤出时出现、跟着控制条一起消失。
            // blbl 和 mytvb 都是这个时机 —— 它是"我现在在控制播放"的一部分，不是常驻装饰。
            TopInfoBar(vm = vm, modifier = Modifier.align(Alignment.TopCenter))

            ControlBar(
                vm = vm,
                firstFocus = firstBarFocus,
                onComments = { controlsVisible = false; panelOpen = false; vm.openComments() },
                onCatalogue = { controlsVisible = false; vm.openSidebar(PlaybackTuning.SideAction.CATALOGUE) },
                onRecommend = { controlsVisible = false; vm.openSidebar(PlaybackTuning.SideAction.RECOMMEND) },
                onOpenUp = onOpenUp,
                onDanmakuSettings = { vm.closeSidebar(); panelOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            /*
             * 兜底焦点。全屏、透明、不可见，唯一职责是**接住遥控器按键**。
             *
             * 只在控制条隐藏时存在 —— 它要是常驻，用户导航时焦点会莫名其妙
             * 落到这块什么都看不见的区域上。
             *
             * 语义名照 mytvb 抄：它那个兜底节点叫 `Show player controls`。
             * 光有一个没标签的全屏可聚焦区，读屏只会念一句"未加标签"，
             * 排查时也看不出这是什么东西（`docs/11` §6.2）。
             */
            val wakeupDescription = if (!vm.playWhenReady) stringResource(R.string.player_paused_description) else stringResource(R.string.player_playing_description)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(wakeupFocus)
                    .focusable()
                    .semantics { contentDescription = wakeupDescription },
            )
        }
        if (!controlsVisible && !vm.live && !vm.touchLocked && seekTarget == null && vm.danmakuSettings.showProgressTime) {
            Text("${formatDuration((vm.positionMs / 1000).toInt())} / ${formatDuration((vm.durationMs / 1000).toInt())}",
                color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
        PlayerActionDialogs(vm, onExit = { onBack(vm.currentBvid, vm.currentSeasonId) }) {
            lastInputAt = SystemClock.elapsedRealtime(); controlsVisible = true
        }
        if (vm.previewActive && !vm.previewEnded && vm.error == null) {
            Text(stringResource(R.string.player_preview_badge), color = AppTheme.current.primary, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        if (vm.touchLocked) {
            PlayerIconButton(stringResource(R.string.player_touch_locked_hint), RailIcons.Lock,
                Modifier.align(Alignment.CenterStart).padding(start = 20.dp).zIndex(4f),
                onLongClick = { vm.touchLocked = false; controlsVisible = true }) { vm.lockTouch() }
        }
    }
}

/** 播放中、没人碰遥控器多久之后把控制条收起来 */
private const val HIDE_AFTER_MS = 4_000L

/** 检查间隔。不必太密：这是个"用户已经不动了"的判断，半个秒粒度足够 */
private const val HIDE_POLL_MS = 500L

/**
 * 播放页顶部的信息栏（标题 / UP主 / 播放量 / 弹幕数）。
 *
 * ## 为什么要有
 *
 * 三家参考 App 里 blbl 和 mytvb 都有：blbl 是「返回箭头 + 标题 + `41人正在观看` + UP 头像和关注」，
 * mytvb 是「标题 + `UP主 · 172.5万播放 · 日期`」。`docs/11` §6.4 把它列成了我们缺的一项。
 *
 * 它解决的是一个很实际的问题：**从首页一路点进来，画面一开就不知道自己在看哪个视频了**，
 * 尤其是分P 连播、或者从推荐流点进来的时候。要确认一下只能退出去看 —— 那太蠢了。
 *
 * ## 为什么跟着控制条一起出现
 *
 * 它是"我正在控制播放"这件事的一部分，不是常驻装饰。常驻的话会一直压着弹幕顶部。
 * 渐变蒙层也是这个用途：压住弹幕但不像纯黑条那样硬切一刀。
 */
@Composable
private fun TopInfoBar(vm: PlayerViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    /*
     * 直播和点播的"第二行"信息不是同一回事：
     * 点播是「UP主 · 12万播放 · 340弹幕」，直播是「主播 · 5.1万人气」。
     * 直播里的 `viewCount` 装的是**在线人数**（同一个字段位，不同含义），
     * 所以不能让它走"播放"那个词 —— 那会把在线人数说成播放量。
     */
    val meta = if (vm.live) {
        buildList {
            vm.ownerName.takeIf { it.isNotBlank() }?.let { add(it) }
            if (vm.viewCount > 0) add(stringResource(R.string.live_popularity, formatCount(vm.viewCount, context.uiLocale)))
            vm.areaName.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
    } else {
        buildList {
            vm.ownerName.takeIf { it.isNotBlank() }?.let { add(it) }
            if (vm.viewCount > 0) add(stringResource(R.string.detail_views, formatCount(vm.viewCount, context.uiLocale)))
            if (vm.danmakuCount > 0) add(stringResource(R.string.detail_danmaku_count, formatCount(vm.danmakuCount, context.uiLocale)))
        }.joinToString(" · ")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xCC000000), Color(0x00000000))
                )
            )
            .padding(start = 36.dp, end = 36.dp, top = 22.dp, bottom = 34.dp),
    ) {
        Text(
            text = vm.title.ifBlank { stringResource(R.string.loading) },
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 屏幕上方的一枚胶囊提示。抽出来是因为播放页有三处提示长得一模一样 */
@Composable
private fun Toast(
    text: String,
    color: Color = Color.White,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xCC1A1A22))
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            color = color.copy(alpha = alpha),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ControlBar(vm: PlayerViewModel, firstFocus: FocusRequester, onOpenUp: ((Long, String, String) -> Unit)?, onDanmakuSettings: () -> Unit, onComments: () -> Unit, onCatalogue: () -> Unit, onRecommend: () -> Unit, modifier: Modifier = Modifier) {
    val visible = vm.barButtons.filter { when (it) {
        PlayerBarButton.SPEED, PlayerBarButton.SUBTITLE, PlayerBarButton.COMMENTS,
        PlayerBarButton.CATALOGUE, PlayerBarButton.RECOMMEND, PlayerBarButton.PREVIOUS,
        PlayerBarButton.NEXT, PlayerBarButton.LOOP -> !vm.live
        PlayerBarButton.LINE -> vm.live
        PlayerBarButton.UP -> vm.ownerMid > 0 && onOpenUp != null
        PlayerBarButton.LIKE, PlayerBarButton.COIN, PlayerBarButton.FAVORITE -> vm.canInteract
        else -> true
    } }
    Column(modifier.fillMaxWidth().background(Color(0xB2000000))) {
        if (!vm.live && vm.danmakuSettings.showPlayerProgress) TouchProgress(vm)
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                visible.forEachIndexed { index, button ->
                    val icon = when (button) {
                        PlayerBarButton.PLAY -> if (vm.error != null) RailIcons.Retry else if (vm.playWhenReady) RailIcons.Pause else RailIcons.Play
                        PlayerBarButton.SPEED, PlayerBarButton.QUALITY, PlayerBarButton.DANMAKU -> null
                        PlayerBarButton.SUBTITLE -> RailIcons.Subtitle
                        PlayerBarButton.SETTINGS -> RailIcons.Settings
                        PlayerBarButton.COMMENTS -> RailIcons.Comments
                        PlayerBarButton.CATALOGUE -> RailIcons.Catalogue
                        PlayerBarButton.RECOMMEND -> RailIcons.Recommend
                        PlayerBarButton.PREVIOUS -> RailIcons.Previous
                        PlayerBarButton.NEXT -> RailIcons.Next
                        PlayerBarButton.REFRESH -> RailIcons.Retry
                        PlayerBarButton.LOOP -> RailIcons.Loop
                        PlayerBarButton.LIKE -> RailIcons.Like
                        PlayerBarButton.COIN -> RailIcons.Coin
                        PlayerBarButton.FAVORITE -> RailIcons.Star
                        PlayerBarButton.UP -> RailIcons.Up
                        PlayerBarButton.LINE -> RailIcons.Line
                    }
                    val active = when (button) {
                        PlayerBarButton.DANMAKU -> vm.dmOn
                        PlayerBarButton.LOOP -> vm.loopEnabled
                        PlayerBarButton.SUBTITLE -> vm.subtitlesEnabled
                        PlayerBarButton.LIKE -> vm.relation?.liked == true
                        PlayerBarButton.COIN -> (vm.relation?.coins ?: 0) > 0
                        PlayerBarButton.FAVORITE -> vm.relation?.favorited == true
                        else -> false
                    }
                    val label = when (button) {
                        PlayerBarButton.PLAY -> if (vm.error != null) stringResource(R.string.player_retry_play) else if (vm.playWhenReady) stringResource(R.string.player_pause) else stringResource(R.string.action_play)
                        PlayerBarButton.SPEED -> stringResource(R.string.player_speed_label, vm.speedLabel)
                        PlayerBarButton.QUALITY -> stringResource(R.string.player_quality_label, vm.qualityLabel)
                        PlayerBarButton.DANMAKU -> stringResource(R.string.player_danmaku_state, stringResource(if (vm.dmOn) R.string.value_on else R.string.value_off))
                        PlayerBarButton.SUBTITLE -> stringResource(R.string.player_subtitle_state, stringResource(if (vm.subtitlesEnabled) R.string.value_on else R.string.value_off))
                        PlayerBarButton.LIKE -> stringResource(R.string.player_like_state, if (active) stringResource(R.string.player_like_liked) else stringResource(R.string.player_like_hint))
                        PlayerBarButton.COIN -> vm.relation?.let { stringResource(R.string.player_coin_state, it.coins) } ?: stringResource(R.string.player_coin_unknown)
                        PlayerBarButton.FAVORITE -> if (active) stringResource(R.string.player_favorite_saved) else stringResource(R.string.player_favorite_choose)
                        PlayerBarButton.UP -> stringResource(R.string.player_up_label, vm.ownerName)
                        PlayerBarButton.LINE -> stringResource(R.string.player_live_source)
                        PlayerBarButton.SETTINGS -> stringResource(R.string.setting_playback)
                        PlayerBarButton.COMMENTS -> stringResource(R.string.player_video_comments)
                        PlayerBarButton.CATALOGUE -> stringResource(R.string.remote_catalogue)
                        PlayerBarButton.RECOMMEND -> stringResource(R.string.remote_recommend)
                        PlayerBarButton.PREVIOUS -> stringResource(R.string.player_previous_video)
                        PlayerBarButton.NEXT -> stringResource(R.string.player_next_video)
                        PlayerBarButton.REFRESH -> stringResource(R.string.player_refresh_hint)
                        PlayerBarButton.LOOP -> stringResource(R.string.player_loop_state, stringResource(if (vm.loopEnabled) R.string.value_on else R.string.value_off))
                    }
                    PlayerIconButton(label, icon, Modifier.then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                        active = active,
                        buttonScale = vm.danmakuSettings.playerButtonScale,
                        value = when (button) { PlayerBarButton.QUALITY -> vm.qualityLabel; PlayerBarButton.SPEED -> vm.speedLabel; else -> null },
                        danmakuGlyph = button == PlayerBarButton.DANMAKU,
                        disabledIcon = button == PlayerBarButton.SUBTITLE && !vm.subtitlesEnabled,
                        onLongClick = when (button) { PlayerBarButton.LIKE -> ({ vm.triple() }); PlayerBarButton.DANMAKU -> onDanmakuSettings; else -> null }) {
                        when (button) {
                            PlayerBarButton.PLAY -> vm.togglePlay()
                            PlayerBarButton.SPEED -> vm.cycleSpeed()
                            PlayerBarButton.QUALITY -> vm.openQuality()
                            PlayerBarButton.DANMAKU -> vm.setDanmakuOn(!vm.dmOn)
                            PlayerBarButton.SUBTITLE -> vm.changeSubtitlesEnabled(!vm.subtitlesEnabled)
                            PlayerBarButton.LIKE -> vm.toggleLike()
                            PlayerBarButton.COIN -> vm.coin()
                            PlayerBarButton.FAVORITE -> vm.openFavorites()
                            PlayerBarButton.UP -> onOpenUp?.invoke(vm.ownerMid, vm.ownerName, "")
                            PlayerBarButton.LINE -> vm.openLiveLines()
                            PlayerBarButton.SETTINGS -> onDanmakuSettings()
                            PlayerBarButton.COMMENTS -> onComments()
                            PlayerBarButton.CATALOGUE -> onCatalogue()
                            PlayerBarButton.RECOMMEND -> onRecommend()
                            PlayerBarButton.PREVIOUS -> vm.playAdjacent(-1)
                            PlayerBarButton.NEXT -> vm.playAdjacent(1)
                            PlayerBarButton.REFRESH -> vm.refreshStream()
                            PlayerBarButton.LOOP -> vm.toggleLoop()
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (vm.live) { if (vm.living) stringResource(R.string.player_live_badge) else stringResource(R.string.player_not_live) }
                    else "${formatDuration((vm.positionMs / 1000).toInt())} / ${formatDuration((vm.durationMs / 1000).toInt())}",
                    color = Color.White, style = MaterialTheme.typography.bodyMedium)
                if (vm.codecLabel.isNotBlank()) Text(vm.codecLabel, color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 进度条该填充多少（`0f` ~ `1f`）。
 *
 * ## 为什么抽成纯函数
 *
 * 它有三个"看起来没事、画出来很难看"的边界，编译器和界面都发现不了：
 *
 * | 输入 | 不处理会怎样 |
 * |---|---|
 * | `duration <= 0`（时长还没拿到 / 直播残留） | 除零 → `Infinity`/`NaN` → 填充宽度变成非数值，进度条整条消失或铺满 |
 * | `position > duration`（拖到末尾那几秒 / 换线路后时间轴重置） | `fraction > 1` → 进度条**画出容器外**（真的会溢出，`fillMaxWidth(1.4f)` 不是被夹住） |
 * | `position < 0`（`currentPosition` 在过渡态返回 `C.TIME_UNSET` 附近的负值） | 负数填充 → 视觉上"倒退一格"闪一下 |
 *
 * ⚠️ 时长未知时返回 `0f`（空条）而不是 `1f`（满条）：满条等于告诉用户"播完了"。
 *
 * 单测：`ProgressLineTest`。
 */
internal fun progressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * 控制栏顶上那条**全宽进度条**（少爷实机反馈 6）。
 *
 * 少爷原话（逐字）：
 * > 播放器控制栏怎么没有做进度条？
 *
 * ## 照 BT 抓到的东西做（`docs/37` §四，取证截图 `tmp/shots/bt_ctrl_zoom.png`）
 *
 * BT 的样子：一条**横贯整屏**的细线贴着控制栏上沿，填充色是主题色，
 * **左端带一个圆形手柄**；它**不在按钮行里** —— 所以不占焦点、不参与方向键导航，
 * 用户走按钮时不会莫名其妙停在一条线上。
 *
 * ## 为什么不做成"可聚焦的拖动条"
 *
 * 遥控器上没有指针，"聚焦一条线再左右拖"要两段式操作（先聚焦、再左右改值），
 * 而**左右键在同一条链上有更自然的岗位**（快进快退 / 在按钮间移动）。
 * 让这条线**只显示、不接焦点**，是电视播放器最不打架的做法
 * —— BT 也是这么做的。
 *
 * ## 颜色跟着皮肤走
 *
 * 用 `AppTheme.current.primary`，不写死 BT 那个绿：我们的皮肤系统约定是
 * "换皮肤 = 换一组数值，组件里一行 `if (皮肤==…)` 都不许有"（`docs/13`）。
 */
@Composable
private fun ProgressLine(positionMs: Long, durationMs: Long) {
    val fraction = progressFraction(positionMs, durationMs)
    val accent = AppTheme.current.primary

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            // 比线本身高一点，给手柄留出上下空间 —— 手柄贴着容器边缘会被裁掉半边
            .height(THUMB_SIZE),
    ) {
        // 轨道
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .background(Color.White.copy(alpha = 0.22f)),
        )

        // 已播部分
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction)
                .height(TRACK_HEIGHT)
                .background(accent),
        )

        // 手柄。`offset` 要把圆点**居中**在填充末端，所以再左移半个直径。
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * fraction - THUMB_SIZE / 2)
                .size(THUMB_SIZE)
                .clip(CircleShape)
                .background(accent),
        )
    }
}

/** 进度条轨道高度 */
private val TRACK_HEIGHT = 3.dp

/** 进度条手柄直径。取 11dp ≈ 电视 3 米外还能看出一颗点，又不至于挡住画面 */
private val THUMB_SIZE = 11.dp

@Composable
private fun BarButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvCard(
        onClick = onClick,
        focusedScale = 1f,
        modifier = modifier,
        // 语义名 = 按钮文字。控制条按钮是排查时最常看的焦点落点，
        // 没有语义名的话 `ui_probe tree` 只能给出一串坐标，分不清哪个是哪个。
        contentDescription = label,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/**
 * 遥控器 ← / → 该跳多少毫秒（`null` = 这个键不管快进快退）。
 *
 * ## 为什么要抽成纯函数
 *
 * 这是**唯一**决定"左键是快退、右键是快进"的地方，而**符号写反**是
 * 编译器和界面都发现不了的那类错 —— 按左键画面往前走，看起来像"网络卡了"。
 * 抽出来之后单测能直接钉住符号（`PlayerKeySeekTest`）。
 *
 * ## 依据是 BT，不是猜的（2026-09-30）
 *
 * `docs/37` §4.4 + 截图 `tmp/shots/bt_keymap_01.png`：BT 的「按键设置（播放时）」里
 * **左键 = 快退 x 秒、右键 = 快进 x 秒**，而且这两项**锁死不可改**。
 * 步长用 BT 的默认值「快进快退秒数 = 10 秒」。
 */
internal fun seekDeltaFor(key: Key, stepMs: Long = SEEK_STEP_MS): Long? = when (key) {
    Key.DirectionLeft -> -stepMs
    Key.DirectionRight -> stepMs
    else -> null
}

/**
 * 一次跳多少。对齐 chinasoul.bt 的 `Seek step = 10 seconds`
 * （`docs/37` §4.4 的「快进快退秒数 = 10 秒」，`docs/11` §6.3）
 */
private const val SEEK_STEP_MS = 10_000L

/** 按住多久算长按。比它短的按一下只跳一次 */
private const val LONG_PRESS_DELAY_MS = 350L

/**
 * 长按后每隔多久跳一次。
 *
 * 原来定的 150ms（≈6.7 次/秒）。2026-09-29 实测：这个速率会把播放器的网络请求
 * 连续打断（日志里一串 `HttpDataSourceException: InterruptedIOException`），
 * 松开之后要缓冲好几秒才恢复。300ms ≈ 3.3 次/秒，落在正常拖动进度条的区间里，
 * 每秒跳 33 秒也够用。
 */
private const val REPEAT_MS = 300L

/**
 * 运行日志面板。
 *
 * ## 存在的理由
 * App 跑在电视和手机上，**两边都没有 adb 可以接**。出问题时用户能做的只有截图，
 * 而截图装不下整条异常链和播放器内部状态。
 *
 * 这个面板把日志摊在屏幕上，并能一键分享出去 ——
 * 排查成本从「来回猜」降到「看一眼」。这不是给终端用户的功能，是给排障用的。
 */
@Composable
internal fun LogPanel(
    onClose: () -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(AppLog.snapshot()) }
    var toast by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    /*
     * 字号档位。
     *
     * 原来写死 11sp —— 在电视上从沙发看根本看不清，
     * 而"看不清"直接堵死了最后的兜底：**拍照发给别人看**。
     * 屏幕上放得下不重要，能拍清楚才重要。
     *
     * 默认「中」18sp（一屏约 25 条，正常阅读够用）；
     * 要拍照时按一下调到「大」28sp（一屏约 15 条，但字足够大）。
     * 有用的恰好就是最后十几条。
     */
    var sizeIdx by remember { mutableStateOf(1) }
    val sizes = remember { listOf(12, 18, 28) }
    val sizeNames = listOf(stringResource(R.string.value_small), stringResource(R.string.value_medium), stringResource(R.string.value_large))
    val scroll = rememberScrollState()

    // 新日志进来时自动滚到底 —— 最关心的永远是「最后几条」
    LaunchedEffect(text) {
        runCatching { scroll.scrollTo(scroll.maxValue) }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF20A0A10))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_logs),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(16.dp))
            /*
             * 路径可压缩，右边的操作按钮保持单行。
             *
             * 原来是「路径 + weight(1f) 的空白」—— 空白先占满，行宽不够时
             * 就只能去挤按钮，结果「清空」被压成了竖排的「清 / 空」。
             * 改成让路径自己吃掉剩余空间并且超长省略，按钮就能保持单行。
             */
            Text(
                text = AppLog.exportPath() ?: stringResource(R.string.player_log_file_pending),
                color = Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(16.dp))

            BarButton(stringResource(R.string.player_log_refresh), Modifier.focusRequester(firstFocus)) {
                text = AppLog.snapshot()
                toast = null
            }
            Spacer(Modifier.width(10.dp))
            BarButton(stringResource(R.string.player_log_font_size, sizeNames[sizeIdx])) {
                sizeIdx = (sizeIdx + 1) % sizes.size
                toast = context.getString(R.string.player_log_font_toast, sizeNames[sizeIdx])
            }
            Spacer(Modifier.width(10.dp))
            BarButton(stringResource(R.string.player_log_clear)) {
                AppLog.clear()
                text = AppLog.snapshot()
                toast = context.getString(R.string.player_log_cleared)
            }
            Spacer(Modifier.width(10.dp))
            BarButton(stringResource(R.string.action_close)) { onClose() }
        }

        toast?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFF7FD8A0), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = text.ifBlank { stringResource(R.string.player_log_empty) },
            color = Color(0xFFCFD8E3),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = sizes[sizeIdx].sp,
                /*
                 * 行高必须跟着字号一起变。
                 *
                 * bodySmall 自带 lineHeight = 16sp，只改 fontSize 不改 lineHeight 的话，
                 * 28sp 的字会被塞进 16sp 的行距里 —— 字直接叠在一起，
                 * 「大字模式」拍出来反而是一坨黑的，等于白做。
                 * 1.45 倍是等宽字体下既不挤也不散的常用值。
                 */
                lineHeight = (sizes[sizeIdx] * 1.45f).sp,
            ),
            modifier = Modifier
                .fillMaxSize()
                .scrollWithScrollbar(scroll),
        )
    }
}

/**
 * 弹幕设置面板。
 *
 * 与设置页共用遥控器选项行，修改后立即更新播放器。
 */
@Composable
private fun DanmakuPanel(vm: PlayerViewModel, firstFocus: FocusRequester, modifier: Modifier = Modifier, onLock: () -> Unit, onClose: () -> Unit) {
    PlaybackSettingsPanel(vm, firstFocus, modifier, onLock, onClose)

}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ------------------------------------------------------------------ 错误描述

/**
 * 播放失败的展示内容。
 *
 * [detail] 不是给用户看的漂亮话，是**给排查用的原始线索** —— 异常链 + 播放器内部状态。
 * 出问题时少爷截一张图，根因就在里面，不用来回猜（`docs/03` §7.4）。
 */
data class PlayError(val title: String, val detail: String = "")

/**
 * 自动连播的"下一个该播什么"（2026-09-29）。
 *
 * ## 为什么是一个独立的小数据类，而不是直接用 `Screen.Player`
 *
 * [Screen.Player] 带 `onBack` 之类的导航侧信息，而且它没有"让我播下一集"这个语义
 * （它是一个**位置**，不是一个**动作**）。这里要的是"播完这个，接着播那个"，
 * 字段只需要 `load()` 认的那几个。
 *
 * ## 字段和 `Screen.Player` 的对应关系
 *
 * | 这里 | `Screen.Player` | 说明 |
 * |---|---|---|
 * | `bvid` | `bvid` | UGC 有、PGC 空 |
 * | `cid` | `cid` | 两者都有 |
 * | `epId` | `epId` | PGC 的集号，UGC 为 0 |
 * | `title` | `titleHint` | 顶部标题，省一次请求 |
 * | `cover` | `coverHint` | 观看记录封面 |
 *
 * `seasonId` 标识目标剧集；本剧分集未提供时沿用当前剧集，跨作品不得沿用。
 */
internal data class NextTarget(
    val bvid: String,
    /** UGC 的显示标题（分P名）/ PGC 的集名 */
    val title: String,
    val cid: Long,
    val epId: Long,
    val cover: String,
    val seasonId: Long = 0L,
)

/**
 * 画面比例档位 id → Media3 的 `resizeMode` 常量（2026-09-29）。
 *
 * ## 为什么中间隔一层 id
 *
 * 直接存 Media3 的 int 常量会在**库升级时错位**（常量值属于库的内部实现）。
 * 所以 [PlaybackTuning] 定义自己的字符串 id，落盘存 id，渲染时才翻成常量。
 * 翻译表**只在这里**有，改 Media3 常量时只需要改这一处。
 */
private fun resizeModeOf(aspectId: String): Int = when (PlaybackTuning.aspectOf(aspectId).id) {
    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/**
 * 把异常链摊平成可读文本：`InvalidResponseCodeException: Response code: 403` 这种。
 *
 * **最后补上根因的调用栈前几帧** —— 这一步是拿真机轮次换来的（见 `AppLog` 里 `MAX_FRAMES` 的说明）。
 * 光有消息 `IndexOutOfBoundsException: index (0) must be less than size (0)` 完全无法定位，
 * 而 `HevcConfig.parseImpl:160` 一行就够破案。
 */
private fun describeThrows(e: Throwable): String = buildString {
    generateSequence(e) { it.cause }
        .take(6)
        .forEach { t ->
            append("· ").append(t.javaClass.simpleName)
            t.message?.let { append(": ").append(it.take(180)) }
            append('\n')
        }
    val frames = AppLog.rootFrames(e)
    if (frames.isNotEmpty()) {
        append("  └ 崩在 ")
        append(frames.take(3).joinToString("\n     "))
    }
}

/** `hev1.1.6.L120.90` → `HEVC`。给用户看的短名字，也给排查用 */
private fun codecFamily(codecs: String): String = when {
    codecs.startsWith("hev", true) || codecs.startsWith("hvc", true) -> "HEVC"
    codecs.startsWith("av01", true) -> "AV1"
    codecs.startsWith("avc", true) -> "AVC"
    else -> codecs.substringBefore('.').uppercase().ifBlank { "?" }
}

// ------------------------------------------------------------------ ViewModel

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp
    val comments = PlayerComments(app, graph.api, viewModelScope)
    val player = BiliPlayer(app)

    var subtitleTracks by mutableStateOf<List<top.bilitv.data.model.SubtitleTrack>>(emptyList())
        private set
    var subtitleText by mutableStateOf("")
        private set
    var statsVisible by mutableStateOf(graph.settings.advancedMode && graph.settings.showPlaybackStats)
        private set
    var endAction by mutableStateOf(graph.settings.playbackEndAction)
        private set
    private var lastNonLoopAction = endAction.takeUnless { it == PlaybackTuning.EndAction.LOOP } ?: PlaybackTuning.EndAction.PAUSE
    val loopEnabled: Boolean get() = endAction == PlaybackTuning.EndAction.LOOP && !previewActive && !live

    private fun applyEndAction() {
        endAction = graph.settings.playbackEndAction
        if (endAction != PlaybackTuning.EndAction.LOOP) lastNonLoopAction = endAction
        player.exo.repeatMode = if (loopEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    fun changeEndAction(action: PlaybackTuning.EndAction) {
        graph.settings.playbackEndAction = action
        applyEndAction()
        showNotice(if (previewActive && action == PlaybackTuning.EndAction.LOOP) graph.getString(R.string.player_end_action_saved_preview)
            else graph.getString(R.string.player_end_action, graph.getString(action.labelRes())))
    }
    fun toggleLoop() = changeEndAction(if (endAction == PlaybackTuning.EndAction.LOOP) lastNonLoopAction else PlaybackTuning.EndAction.LOOP)
    var subtitleStyle by mutableStateOf(graph.settings.subtitleStyle)
        private set
    var subtitlesEnabled by mutableStateOf(graph.settings.subtitleEnabled)
        private set
    private var subtitleError by mutableStateOf<String?>(null)
    private var subtitleMetadataLoading by mutableStateOf(false)
    private var subtitleLoading by mutableStateOf(false)
    private var subtitleMetadataLoaded = false
    private var subtitleMetadataJob: Job? = null
    private var subtitleJob: Job? = null
    private var subtitleGeneration = 0
    private var subtitleLoadGeneration = 0
    private var subtitleLanguage = graph.settings.subtitleLanguage
    private var subtitleTimeline: top.bilitv.data.model.SubtitleTimeline? = null
    private var subtitleTrack by mutableStateOf<top.bilitv.data.model.SubtitleTrack?>(null)
    val subtitleStatus: String get() = when {
        subtitleMetadataLoading -> graph.getString(R.string.player_subtitle_reading)
        subtitleLoading -> graph.getString(R.string.player_subtitle_loading, subtitleTrack?.label.orEmpty())
        subtitleError != null -> subtitleError!!
        !subtitleMetadataLoaded -> graph.getString(R.string.player_subtitle_after_play)
        subtitleTracks.isEmpty() -> graph.getString(R.string.player_subtitle_none)
        else -> graph.getString(R.string.player_subtitle_languages, subtitleTracks.size) + (subtitleTrack?.let { " · ${it.label}" } ?: "")
    }

    private fun resetSubtitles() {
        subtitleGeneration++; subtitleLoadGeneration++; subtitleMetadataJob?.cancel(); subtitleJob?.cancel()
        subtitleMetadataLoaded = false; subtitleMetadataLoading = false; subtitleLoading = false
        subtitleTracks = emptyList(); subtitleTrack = null; subtitleTimeline = null; subtitleText = ""; subtitleError = null
        subtitlesEnabled = graph.settings.subtitleEnabled; subtitleStyle = graph.settings.subtitleStyle
        subtitleLanguage = graph.settings.subtitleLanguage
    }

    fun loadSubtitleMetadata(force: Boolean = false) {
        if (live || cid <= 0 || (!force && (subtitleMetadataLoaded || subtitleMetadataLoading))) return
        if (force) {
            subtitleMetadataJob?.cancel(); subtitleJob?.cancel(); subtitleGeneration++; subtitleLoadGeneration++
            subtitleTimeline = null; subtitleText = ""; subtitleTrack = null; subtitleTracks = emptyList(); subtitleLoading = false
        }
        subtitleMetadataLoading = true; subtitleError = null
        val myToken = token; val generation = subtitleGeneration
        val video = bvid; val targetCid = cid; val episode = epId
        subtitleMetadataJob = viewModelScope.launch {
            try {
                val pgc = if (episode > 0) cataloguePgc ?: graph.api.pgcDetail(seasonId, episode) else null
                val ep = pgc?.episodes?.firstOrNull { it.epId == episode && it.cid == targetCid }
                val tracks = graph.api.subtitleTracks(if (episode > 0) ep?.bvid.orEmpty() else video, targetCid, ep?.aid ?: aid)
                if (myToken != token || generation != subtitleGeneration) return@launch
                subtitleTracks = tracks; subtitleMetadataLoaded = true
                AppLog.i("Subtitle", "语言已加载 cid=$targetCid 数量=${tracks.size} " + tracks.joinToString { it.language })
                selectSubtitle()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (myToken == token && generation == subtitleGeneration) {
                    subtitleError = e.message ?: graph.getString(R.string.player_subtitle_meta_failed)
                    AppLog.w("Subtitle", "字幕信息失败 cid=$targetCid：${e.javaClass.simpleName}")
                }
            } finally { if (myToken == token && generation == subtitleGeneration) subtitleMetadataLoading = false }
        }
    }

    fun changeSubtitlesEnabled(on: Boolean) {
        graph.settings.subtitleEnabled = on
        subtitlesEnabled = on
        if (!live) selectSubtitle()
        AppLog.i("Subtitle", "开关=${if (on) "开" else "关"} cid=$cid")
    }

    private fun selectSubtitle() {
        if (!subtitlesEnabled) { subtitleLoadGeneration++; subtitleJob?.cancel(); subtitleLoading = false; subtitleText = ""; return }
        val chosen = top.bilitv.data.model.preferredSubtitle(subtitleTracks, subtitleLanguage)
        if (chosen == null) { if (!subtitleMetadataLoaded) loadSubtitleMetadata(); return }
        if (chosen == subtitleTrack && subtitleTimeline != null) { subtitleText = subtitleTimeline!!.textAt(player.exo.currentPosition); return }
        if (chosen == subtitleTrack && subtitleLoading) return
        subtitleJob?.cancel(); subtitleTimeline = null; subtitleText = ""; subtitleTrack = chosen
        subtitleLoading = true; subtitleError = null
        val generation = ++subtitleLoadGeneration; val myToken = token
        subtitleJob = viewModelScope.launch {
            try {
                val timeline = graph.api.subtitleBody(chosen)
                if (myToken != token || generation != subtitleLoadGeneration || !subtitlesEnabled) return@launch
                subtitleTimeline = timeline; subtitleText = timeline.textAt(player.exo.currentPosition)
                AppLog.i("Subtitle", "正文已加载 cid=$cid 语言=${chosen.language} 条数=${timeline.cues.size}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (myToken == token && generation == subtitleLoadGeneration) {
                    subtitleError = e.message ?: graph.getString(R.string.player_subtitle_load_failed)
                    AppLog.w("Subtitle", "字幕正文失败：${e.javaClass.simpleName}")
                }
            } finally { if (myToken == token && generation == subtitleLoadGeneration) subtitleLoading = false }
        }
    }

    internal var sideOpen by mutableStateOf(false)
        private set
    internal var sideParts by mutableStateOf<List<NextTarget>>(emptyList())
        private set
    internal var sideItems by mutableStateOf<List<NextTarget>>(emptyList())
        private set
    var sideTitle by mutableStateOf("")
        private set
    var sideLoading by mutableStateOf(false)
        private set
    var sideError by mutableStateOf<String?>(null)
        private set
    var sideHasMore by mutableStateOf(false)
        private set
    var playlistId = 0L
    var playlistTitle = ""
    private var catalogueDetail: VideoDetail? = null
    private var cataloguePgc: PgcDetail? = null
    private val playlistQueue = PlaylistQueue()
    private var adjacentJob: Job? = null
    private var sideJob: Job? = null
    private var sidePage = 0
    private var sideAction = PlaybackTuning.SideAction.CATALOGUE
    private var sideSource = ""
    private var sideGeneration = 0

    fun sideActionFor(up: Boolean): PlaybackTuning.SideAction? {
        val s = graph.settings
        return if (live || !(if (up) s.playerUpEnabled else s.playerDownEnabled)) null
        else if (up) s.playerUpAction else s.playerDownAction
    }

    fun openComments() {
        if (live) { showNotice(graph.getString(R.string.player_live_no_comments)); return }
        closeSidebar()
        val requestedEp = epId; val requestedSeason = seasonId; val requestedToken = token
        comments.show {
            if (requestedToken != token) throw CancellationException()
            if (requestedEp <= 0L) aid
            else cataloguePgc?.episodes?.firstOrNull { it.epId == requestedEp }?.aid?.takeIf { it > 0L }
                ?: graph.api.pgcDetail(requestedSeason, requestedEp)?.episodes?.firstOrNull { it.epId == requestedEp }?.aid ?: 0L
        }
    }

    fun closeSidebar() {
        adjacentJob?.cancel(); adjacentJob = null
        sideOpen = false
        sideGeneration++
        sideJob?.cancel()
        sideLoading = false
    }

    fun openSidebar(action: PlaybackTuning.SideAction) {
        comments.close()
        closeSidebar()
        sideAction = action
        sideOpen = true
        sideParts = if (action == PlaybackTuning.SideAction.CATALOGUE) catalogueDetail?.pages.orEmpty()
            .takeIf { it.size > 1 }.orEmpty().map { NextTarget(bvid, "P${it.index} · ${it.title}", it.cid, 0, cover) }
            else emptyList()
        sideItems = emptyList()
        sideHasMore = false
        sidePage = 0
        sideError = null
        sideTitle = graph.getString(action.labelRes())
        sideSource = when {
            action == PlaybackTuning.SideAction.RECOMMEND -> "recommend"
            action == PlaybackTuning.SideAction.UP_UPLOADS -> "up"
            epId > 0 -> "pgc"
            playlistId > 0 -> "playlist"
            catalogueDetail?.collection?.isNotEmpty() == true -> "collection"
            else -> "up"
        }
        loadSidebarMore()
    }

    private fun FeedItem.sideTarget() = NextTarget(bvid, title, 0, 0, cover, seasonId)

    fun loadSidebarMore() {
        if (!sideOpen || sideLoading || (sidePage > 0 && !sideHasMore)) return
        val generation = sideGeneration
        val myToken = token
        val page = sidePage + 1
        sideLoading = true
        sideError = null
        sideJob = viewModelScope.launch {
            try {
                val items: List<NextTarget>
                var more = false
                var playlistPage: top.bilitv.data.model.FavResourcePage? = null
                val label: String
                when (sideSource) {
                    "recommend" -> {
                        val feed = if (epId > 0) {
                            val d = cataloguePgc ?: graph.api.pgcDetail(seasonId, epId)
                                ?: error(graph.getString(R.string.player_side_no_work))
                            graph.api.relatedSeasons(d.seasonId)
                        } else graph.api.relatedVideos(bvid)
                        items = (feed ?: error(graph.getString(R.string.player_side_recommend_failed))).map { it.sideTarget() }
                        label = graph.getString(R.string.section_recommend)
                    }
                    "pgc" -> {
                        val d = cataloguePgc ?: graph.api.pgcDetail(seasonId, epId) ?: error(graph.getString(R.string.player_side_episodes_failed))
                        items = d.episodes.filter { it.cid > 0 }.map { NextTarget("", d.playbackTitle(it), it.cid, it.epId, it.cover, d.seasonId) }
                        label = graph.getString(R.string.player_side_episodes_label, d.title)
                    }
                    "collection" -> {
                        val d = catalogueDetail ?: error(graph.getString(R.string.player_side_collection_failed))
                        items = d.collection.map { NextTarget(it.bvid, it.title, it.cid, 0, it.cover) }
                        label = graph.getString(R.string.player_side_collection_label, d.collectionTitle)
                    }
                    "playlist" -> {
                        val result = graph.api.favResourcePage(playlistId, page) ?: error(graph.getString(R.string.player_side_playlist_failed))
                        playlistPage = result
                        items = result.items.map { it.sideTarget() }
                        more = result.hasMore
                        label = graph.getString(R.string.player_side_playlist_label, playlistTitle)
                    }
                    else -> {
                        if (ownerMid <= 0) error(graph.getString(R.string.player_side_no_up_list))
                        val d = graph.api.upVideos(ownerMid, page) ?: error(graph.getString(R.string.player_side_up_failed))
                        items = d.items.map { it.sideTarget() }
                        more = d.hasMore
                        label = graph.getString(R.string.player_side_up_label, ownerName)
                    }
                }
                if (myToken != token || generation != sideGeneration || !sideOpen) return@launch
                sideTitle = label
                sideItems = (sideItems + items).distinctBy { if (it.epId > 0) "ep${it.epId}" else if (it.seasonId > 0) "ss${it.seasonId}" else it.bvid }
                if (sideSource == "playlist") {
                    playlistQueue.record(playlistId, page, playlistPage!!)
                }
                sidePage = page
                sideHasMore = more
                AppLog.i("PlayerSidebar", "$sideSource 第$page 页 ${items.size} 条，累计 ${sideItems.size} 条，分P ${sideParts.size} 条")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (myToken == token && generation == sideGeneration && sideOpen) sideError = e.message?.take(120) ?: graph.getString(R.string.player_side_load_failed)
            } finally {
                if (generation == sideGeneration) sideLoading = false
            }
        }
    }

    internal fun sideItemCurrent(item: NextTarget) = if (item.epId > 0) item.epId == epId
        else item.bvid.isNotBlank() && item.bvid == bvid && (item.cid == 0L || item.cid == cid)

    internal fun playSideItem(item: NextTarget) {
        if (sideItemCurrent(item)) { closeSidebar(); return }
        val fromPlaylist = sideSource == "playlist"
        val myToken = token
        closeSidebar()
        val generation = sideGeneration
        viewModelScope.launch {
            try {
                val target = if (item.seasonId > 0 && item.epId == 0L) {
                    val d = graph.api.pgcDetail(item.seasonId) ?: error(graph.getString(R.string.player_next_no_episodes))
                    val e = d.episodes.firstOrNull { it.cid > 0 } ?: error(graph.getString(R.string.player_next_no_playable))
                    NextTarget("", d.playbackTitle(e), e.cid, e.epId, e.cover, d.seasonId)
                } else item
                if (myToken != token || generation != sideGeneration) return@launch
                persistProgress()
                if (!fromPlaylist) { playlistId = 0L; playlistTitle = "" }
                load(target.bvid, target.cid, target.epId, target.title, target.cover, target.seasonId, keepSpeed = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { if (myToken == token) showNotice(e.message ?: graph.getString(R.string.player_open_video_failed)) }
        }
    }

    var title by mutableStateOf("")
        private set

    /*
     * 顶部信息栏用的三个字段。
     *
     * `load()` 里本来就会拉一次 `videoDetail`（为了拿 cid），这三个值就在同一个响应里，
     * **不额外发请求**。blbl / mytvb 的顶部信息栏也都是"标题 + UP主 + 播放量"这一档信息。
     */
    var ownerName by mutableStateOf("")
        private set
    var ownerMid by mutableStateOf(0L)
        private set
    var viewCount by mutableStateOf(0L)
        private set
    var danmakuCount by mutableStateOf(0L)
        private set

    var error by mutableStateOf<PlayError?>(null)
        private set
    var previewActive by mutableStateOf(false)
        private set
    var previewEnded by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set

    /**
     * 弹幕用的连续位置时钟（低频采样 + 按墙钟外推）。
     *
     * 它是普通字段不是 State —— 每帧都要读，不能挂在重组上。
     * 设计说明见 [PositionClock]。
     */
    private val posClock = PositionClock { SystemClock.elapsedRealtime() }

    var durationMs by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set
    var playWhenReady by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(true)
        private set
    val seekStepMs = graph.settings.seekSeconds * 1_000L
    var danmaku by mutableStateOf<List<DanmakuItem>>(emptyList())
        private set

    /** 非空 = 正在倒计时跳过某段广告 */
    var skipHint by mutableStateOf<String?>(null)
        private set

    /** 音频无论如何拿不到，已降级为静音播视频 */
    var muted by mutableStateOf(false)
        private set

    /** 一次性提示（几秒后自动消失），比如「已自动切到 AVC」 */
    var notice by mutableStateOf<String?>(null)
        private set
    var touchLocked by mutableStateOf(false)
    private var autoQualityFrom: Int? = null
    fun lockTouch() { touchLocked = true; showNotice(graph.getString(R.string.player_touch_locked_notice)) }

    /** 当前在播的编码 + 清晰度，如 `HEVC 480P`。既给用户看，也是截图排查的线索 */
    var codecLabel by mutableStateOf("")
        private set

    /**
     * 这一路是不是**直播**。
     *
     * ★ 它是"分支开关"，不是"信息"。播放页里有 5 处行为被它分流：
     * 进度条 / 快进 / 弹幕 / 广告跳过 / 观看记录。
     * 每一处**必须**单独想一遍 —— 这正是直播不能硬塞进点播那条路的原因。
     */
    var live by mutableStateOf(false)
        private set

    /** 直播是否处于"真的在播"状态（取流接口给的 `live_status==1`） */
    var living by mutableStateOf(false)
        private set

    /** 直播分区名，如「网游·吃鸡行动」。只给顶部信息栏用 */
    var areaName by mutableStateOf("")
        private set

    var dmOn by mutableStateOf(graph.settings.danmakuEnabled)
        private set
    var dmAlpha by mutableStateOf(graph.settings.danmakuAlpha)
        private set
    var dmScale by mutableStateOf(graph.settings.danmakuScale)
        private set
    var dmLines by mutableStateOf(graph.settings.danmakuMaxLines)
        private set
    var dmSpeedMs by mutableStateOf(graph.settings.danmakuDurationMs)
        private set
    var dmMerge by mutableStateOf(graph.settings.danmakuMerge)
        private set
    val danmakuSettings get() = graph.settings
    var dmArea by mutableStateOf(graph.settings.danmakuArea)
        private set
    var dmOverlap by mutableStateOf(graph.settings.danmakuOverlap)
        private set
    var dmOutline by mutableStateOf(graph.settings.danmakuOutline)
        private set
    var dmOutlineAlpha by mutableStateOf(graph.settings.danmakuOutlineAlpha)
        private set
    var dmTrackHeight by mutableStateOf(graph.settings.danmakuTrackHeight)
        private set
    private var dmFilter = graph.settings.danmakuFilter.copy(hideRepeated = graph.settings.danmakuHideRepeated)
    private var rawDanmaku = emptyList<DanmakuItem>()
    private var localDmRules = top.bilitv.data.danmaku.DanmakuRules(
        graph.settings.danmakuKeywords, graph.settings.danmakuRegexes, graph.settings.danmakuBlockedUsers)
    private var cloudDmRules = top.bilitv.data.danmaku.DanmakuRules()
    private var cloudDmLevel = 0
    private var cloudDmLoaded = false
    private var dmMetadata: top.bilitv.data.danmaku.DanmakuCloudProfile? = null
    private var dmMetadataJob: Job? = null

    fun reloadDanmakuSettings() {
        val s = graph.settings
        statsVisible = s.advancedMode && s.showPlaybackStats
        applyEndAction()
        val subtitleSelectionChanged = subtitlesEnabled != s.subtitleEnabled || subtitleLanguage != s.subtitleLanguage
        subtitleLanguage = s.subtitleLanguage
        subtitleStyle = s.subtitleStyle; subtitlesEnabled = s.subtitleEnabled
        if (!live && subtitleSelectionChanged) selectSubtitle()
        dmOn = s.danmakuEnabled; dmAlpha = s.danmakuAlpha; dmScale = s.danmakuScale
        dmLines = s.danmakuMaxLines; dmSpeedMs = s.danmakuDurationMs; dmMerge = s.danmakuMerge
        dmArea = s.danmakuArea; dmOverlap = s.danmakuOverlap; dmOutline = s.danmakuOutline
        dmOutlineAlpha = s.danmakuOutlineAlpha; dmTrackHeight = s.danmakuTrackHeight
        dmFilter = s.danmakuFilter.copy(hideRepeated = s.danmakuHideRepeated)
        localDmRules = top.bilitv.data.danmaku.DanmakuRules(s.danmakuKeywords, s.danmakuRegexes, s.danmakuBlockedUsers)
        val myToken = token
        if (live) {
            if (dmOn) startLiveDanmaku(myToken) else { liveDmJob?.cancel(); liveDmJob = null; danmaku = emptyList() }
        } else {
            viewModelScope.launch { danmakuMergeMutex.withLock { applyDanmakuFilter(myToken) } }
            loadDanmakuMetadata(myToken)
        }
    }

    private suspend fun applyDanmakuFilter(myToken: Int) {
        if (myToken != token) return
        val options = dmFilter
        val raw = rawDanmaku
        val metadata = dmMetadata
        val local = localDmRules; val cloud = cloudDmRules; val level = cloudDmLevel
        val filtered = withContext(Dispatchers.Default) {
            top.bilitv.data.danmaku.filterDanmaku(mergeDanmaku(raw, metadata?.interactions.orEmpty()), options, level, cloud, local)
        }
        if (myToken != token || options != dmFilter || local !== localDmRules || cloud !== cloudDmRules || metadata !== dmMetadata) return
        danmaku = filtered
        AppLog.i("Danmaku", "筛选等级=${options.level} 原始=${raw.size} 保留=${filtered.size} 云=${options.cloud}")
        val rejected = local.rejectedRegexes + if (options.cloud) cloud.rejectedRegexes else 0
        if (rejected > 0) showNotice(graph.getString(R.string.player_danmaku_regex_skipped, rejected))
    }

    private fun loadDanmakuMetadata(myToken: Int) {
        if (live || !dmOn || (!dmFilter.cloud && !dmFilter.allowInteraction)) {
            dmMetadataJob?.cancel()
            return
        }
        if (cid <= 0L || dmMetadataJob?.isActive == true || (dmMetadata != null && (!dmFilter.cloud || cloudDmLoaded))) return
        val targetCid = cid; val targetAid = aid
        dmMetadataJob = viewModelScope.launch {
            try {
                if (dmMetadata == null) {
                    try {
                        val profile = graph.api.danmakuMetadata(targetCid, targetAid)
                        if (myToken != token || !dmOn) return@launch
                        dmMetadata = profile; cloudDmLevel = profile.level
                        AppLog.i("Danmaku", "元数据已加载，互动文字=${profile.interactions.size} 服务端等级=${profile.level}")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        AppLog.w("Danmaku", "元数据未加载（${e.javaClass.simpleName}），普通弹幕和账号规则仍可用")
                        if (myToken == token && dmFilter.allowInteraction) showNotice(graph.getString(R.string.player_interaction_failed))
                    }
                }
                if (myToken != token || !dmOn) return@launch
                danmakuMergeMutex.withLock { applyDanmakuFilter(myToken) }
                if (dmFilter.cloud && !cloudDmLoaded) {
                    val rules = graph.api.danmakuCloud(dmMetadata ?: top.bilitv.data.danmaku.DanmakuCloudProfile())
                    if (myToken != token || !dmOn) return@launch
                    cloudDmRules = rules; cloudDmLoaded = true
                    AppLog.i("Danmaku", "云规则已加载，服务端等级=$cloudDmLevel")
                    danmakuMergeMutex.withLock { applyDanmakuFilter(myToken) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (myToken == token) {
                    val reason = if (e is IllegalStateException) e.message else graph.getString(R.string.player_cloud_fallback)
                    AppLog.w("Danmaku", "云屏蔽未加载：$reason（${e.javaClass.simpleName}）")
                    showNotice(graph.getString(R.string.player_cloud_failed, reason))
                }
            }
        }
    }

    /**
     * 控制栏上要画哪些按钮、什么顺序（少爷第 8 条：**控制栏也能配**）。
     *
     * ★ **在 VM 构造时读一次就够，故意不做成 `State`**：
     * 用户改这个设置时**一定不在播放页**（设置页在侧栏，播放页是全屏二级页，
     * 两者不可能同屏）。所以"进播放页时读一次"和"实时跟随"在行为上完全等价，
     * 而前者少一份要同步的状态 —— `docs/99` 那条"两份状态"的判据就是问
     * "另一份谁同步"，这里答案是"根本不需要第二份"。
     *
     * 反过来，倍速/比例那两项**必须是 State**，因为播放页里自己就要改它们。
     */
    val barButtons: List<PlayerBarButton> = PlayerBarButton.parse(graph.settings.playerButtons)

    // ------------------------------------------------------------ 三连增强（2026-09-29）

    /**
     * 当前倍速下标（表见 [PlaybackTuning.SPEEDS]）。
     *
     * 它是 **State** 而不只是读设置 —— 播放页要能临时切倍速（不落盘），
     * 用户下次进设置改的是默认值。初值来自设置。
     */
    var speedIndex by mutableIntStateOf(graph.settings.playbackSpeedIndex)
        private set

    /** 当前倍速的显示文本，如 `1.25x` */
    val speedLabel: String get() = if (boostSpeed != null) "2.0x" else PlaybackTuning.speedLabel(speedIndex)

    /**
     * 当前画面比例档位 id（`fit`/`fill`/`zoom`）。初值来自设置。
     *
     * ★ 它是 **State**，界面里 `PlayerView.resizeMode` 直接读它 ——
     * 所以切档**立刻生效**，不用重进播放页。
     */
    var aspectId by mutableStateOf(graph.settings.aspectMode)
        private set

    /**
     * 「单击返回键退出」开关（少爷反馈 6）。
     *
     * ⚠️ **故意不做成 State**：它在"按下返回键那一刻"取值就够了，不需要触发重组。
     * 做成 State 反而要维护同步（用户在设置页改了、播放页要跟着变）。
     *
     * ⛔ 插入位置注意：上面那个 `var` 带 `private set`，
     * 新属性**不能插在这两行之间** —— 否则 setter 会挂到新属性上（编译不过，实测踩过）。
     */
    val singleBackExit: Boolean get() = graph.settings.singleBackExit

    private val planner = SkipPlanner()
    private val loadedSegments = HashSet<Int>()
    private var ticker: Job? = null
    private var loadJob: Job? = null

    /**
     * 本机观看记录。
     *
     * ★ 它住在应用级（`BiliTvApp.history`），**不是这个 ViewModel 的成员** ——
     * 播放页一退，VM 就 `onCleared()` 了，续播位置必须活得比它久。
     */
    private val history = graph.history

    /** 距离上次落盘过了多少毫秒。到 [History.SAVE_INTERVAL_MS] 才写一次 */
    private var sinceSaveMs = 0L

    private var bvid = ""

    /** 当前播放是不是 PGC（>0）。重试时必须带回去，否则会退回 UGC 取流路径 */
    private var epId = 0L

    /** PGC 的标题。重试时不用再问调用方要一遍 */
    private var titleHint = ""

    /**
     * 封面 —— 只给观看记录用。
     *
     * 两条来源：UGC 从 `view` 接口的响应里拿（[cover]），PGC 由调用方带进来（[coverHint]）。
     * 不合并成一个字段，是因为**它们的生命周期不一样**：PGC 的那份在 `load()` 一开始就有，
     * UGC 的那份要等接口回来。合并的话 `load()` 里的重置逻辑就会把调用方给的值抹掉。
     */
    private var cover = ""
    private var videoBadge = ""
    private var coverHint = ""

    /**
     * 上次落盘的位置。
     *
     * 用来做两件事：**避免每 5 秒重复写同一个值**（暂停不动时位置不变），
     * 以及 `persistProgress()` 里判断"这次有没有必要写"。
     */
    private var lastSavedMs = -1L

    private var cid = 0L

    /**
     * 令牌：每次 [load] 自增。
     * 网络回来得晚的旧请求会被令牌拦下，防止"切了视频、上一集的弹幕才刷进来"。
     */
    private var token = 0

    private var durationHintMs = 0L
    private var sponsorRequested = false
    private var sponsorJob: Job? = null
    private var communitySkipSegments = emptyList<top.bilitv.data.sponsor.SponsorSegment>()
    private var skipPolicy: Triple<Boolean, Boolean, Set<top.bilitv.data.sponsor.SponsorCategory>>? = null
    private var renderedFirstFrame = false
    private var loadedKey: String? = null
    private var liveRoomId = 0L

    /** PGC 的 seasonId。自动连播拿全集列表要用（0 = 非 PGC） */
    private var seasonId = 0L
    val currentBvid: String get() = bvid
    val currentEpId: Long get() = epId
    val currentLiveRoomId: Long get() = if (live) liveRoomId else 0L
    val currentSeasonId: Long get() = if (epId > 0L) seasonId else 0L
    var resumeChoiceMs by mutableStateOf<Long?>(null)
        private set

    /** 已有详情算出的下一分P/分集兜底；合集与收藏列表共用playAdjacent的实际顺序。 */
    private var nextTarget: NextTarget? = null

    /**
     * 本次拿到的**完整**播放信息。
     *
     * 之前是 `load()` 里的局部变量 —— 但降级换编码时必须能重新选流，
     * 只留一条 `Selection` 不够用。播放地址是接口一次给全套的，留着不花额外成本。
     */
    private var playInfo by mutableStateOf<PlayInfo?>(null)
    private var adaptiveQuality: Int? = null
    private var lastAdaptiveChange = 0L

    /** 已经试过"换 AVC 重播"了吗。只试一次，否则 AVC 也失败时会来回打转 */
    private var triedAvcFallback = false

    /**
     * 直播已经试过"重新取流"了吗。同样只试一次。
     *
     * 和 [triedAvcFallback] 分开两个字段，虽然它们语义都是"只试一次" ——
     * 但触发条件完全不同（一个在点播的解析失败路径上，一个在直播的断流路径上），
     * 共用一个的话，点播失败后换到直播会带着"已经试过"的状态进来。
     */
    private var triedLiveRefetch = false

    /**
     * 点播已经试过"去掉 P2P 节点重开"了吗。只试一次。
     *
     * 单独一个字段的理由同 [triedLiveRefetch]：三种退路的触发条件各不相同，
     * 共用一个 flag 会让"刚换过地址"的播放器带着"已经试过"的状态去播下一个视频。
     */
    private var triedP2pRetry = false

    private val stopSkipSettingsObserver = graph.settings.observeChanges { refreshSkipSegments() }

    init {
        player.onPlaybackStutter = { dropped, elapsed, fps -> lowerQualityIfNeeded(dropped, elapsed, fps) }
        /*
         * 播放器说"我没办法了"，这里做最后两级决策。
         *
         * ## 为什么换编码这件事必须放在这一层
         * 换编码 = 重新选流。而选流要看清晰度偏好、解码能力、可用编码，
         * 这些全是 ViewModel 才知道的事。BiliPlayer 只负责"判断该不该换"
         * （见 isUnparsable），不该越界去管怎么选。
         *
         * ## 触发条件的三种可能
         *  1. 音频关了、视频还失败 → 多半是网络，但换 AVC 值得一试（代价小）
         *  2. 判定为解析类错误（数据到手、播放器解不了）→ **这就是换编码的正主**
         *  3. AVC 也失败 → 老老实实报错，别再转圈
         */
        player.onUnrecoverable = { e: androidx.media3.common.PlaybackException ->
            /*
             * ★ 直播的容灾是"**重新取流**"，不是"换编码"。
             *
             * 播放器那边已经为直播短路掉了关音频/换编码那套降级（见 `BiliPlayer.handleError`）。
             * 这里要做的只有一件事：**再问一次接口拿新地址**。
             *
             * 为什么必须有这一步：直播播放地址带时效签名（实测有效期 1 小时），
             * 而且主播一断线重连，CDN 路径就整个换掉了。一个还在播的直播间，
             * 用户看到的却是"播放失败"，十有八九就是手里那个地址过期了。
             *
             * 只重取一次 —— 再失败就说明不是地址的问题（房间关了 / 网络断了），
             * 循环重取只会让画面反复闪黑。
             */
            if (!live && pendingQuality != null) {
                restorePreviousQuality()
            } else if (live) {
                if (!triedLiveRefetch) {
                    triedLiveRefetch = true
                    AppLog.w("VM", "直播失败（${e.errorCodeName}）-> 重新取流一次")
                    showNotice(graph.getString(R.string.player_live_reconnecting))
                    error = null
                    val myToken = token
                    viewModelScope.launch {
                        delay(FALLBACK_DELAY_MS)
                        if (myToken != token) return@launch
                        val line = fetchLiveLine()
                        if (myToken != token || line == null) return@launch
                        AppLog.i("VM", "重连：${line.protocol}/${line.format}/${line.codec}")
                        codecLabel = graph.getString(R.string.player_live_codec, line.codec.uppercase())
                        player.playLive(line.urls.first(), line.isHls)
                    }
                } else {
                    error = PlayError(
                        graph.getString(R.string.player_live_broken),
                        graph.getString(R.string.player_live_broken_desc) + "\n\n" +
                            describeThrows(e) + "\n\n" + player.describeState(),
                    )
                }
                /*
                 * 注意这里**不能用 `return@`**：`onUnrecoverable` 是个**属性**，
                 * 而 `return@label` 的隐式标签只在"把 lambda 传给函数"时才生成
                 * （`x = { … }` 这种赋值形式没有标签）。所以直播那一支用 if/else 收口。
                 */
            } else if (!triedP2pRetry && graph.settings.autoRetryWithoutP2p && player.canRetryWithoutP2p()) {
                /*
                 * ── 退路 0：去掉 P2P 节点原地重开（2026-09-29 新增，「容错」分组）──
                 *
                 * 顺序摆在"换编码"**之前**，因为它更便宜也更可能命中：
                 *
                 *  - 换编码要重新选流、重走一遍起始（几百毫秒黑屏）；
                 *    换地址只是把候选表重排，手上这份带签名的地址还在有效期内
                 *  - 这类失败的真实原因十有八九就是"前面几条 P2P 地址连不通"，
                 *    换地址是正解，换编码是白费 —— 播放器根本没碰到编码那一步
                 *
                 * 只在**网络类**失败时走这条（`canRetryWithoutP2p` 内部会挡住
                 * 直播 / 已经滤过一轮 / 没有可选地址这三种情况）。
                 */
                tryP2pRetry(e)
            } else {
                continueToAvcOrFail(e)
            }
        }
        player.onAudioDisabled = {
            muted = true
            AppLog.w("VM", "已降级为静音播放")
        }
        player.exo.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                this@PlayerViewModel.playWhenReady = playWhenReady
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) { planner.onUserSeek(); skipHint = null }
            }

            override fun onRenderedFirstFrame() {
                renderedFirstFrame = true
                if (live) pendingLiveLine?.let { line ->
                    activeLiveLine = line; pendingLiveLine = null
                    codecLabel = graph.getString(R.string.player_live_codec, line.codec.uppercase())
                    AppLog.i("Player", "直播实际画质=$qualityLabel（首帧确认）")
                }
                val actual = player.currentQuality()
                val format = player.exo.videoFormat
                if (!live && actual != null && format?.width == actual.width && format.height == actual.height) {
                    activeQuality = actual.qualityId
                    pendingQuality = null
                    autoQualityFrom?.let { previous ->
                        val from = compactQualityLabel(previous, playInfo?.qualityLabels?.get(previous).orEmpty())
                        showNotice(if (previous != actual.qualityId) graph.getString(R.string.player_quality_auto_lowered, from, qualityLabel)
                            else graph.getString(R.string.player_quality_not_applied, qualityLabel))
                        autoQualityFrom = null
                    }
                    AppLog.i("Player", "实际画质=$qualityLabel（首帧确认）")
                }
                // 首帧出来之后才去查广告片段：不能挡着起播（docs/03 §3.2 第 11 条）
                loadSponsor()
                if (!live && subtitlesEnabled) loadSubtitleMetadata()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                /*
                 * 自动连播的**唯一正确触发点**（2026-09-29）。
                 *
                 * 为什么不猜 `position >= duration`：用户把进度条拖到最后 3 秒时
                 * 那个条件同样成立，会在"还没放完"时就跳走。`STATE_ENDED` 是
                 * 播放器真的走到流末尾才给的信号。
                 */
                if (playbackState == Player.STATE_ENDED) {
                    continueToNext()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // 用户自己拖了进度条 → 丢掉跳过倒计时，别把人踢走
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    planner.onUserSeek()
                    skipHint = null
                    subtitleText = if (subtitlesEnabled) subtitleTimeline?.textAt(newPosition.positionMs).orEmpty() else ""
                }
            }
        })
    }

    fun load(
        bvid: String,
        cid: Long,
        epId: Long = 0L,
        titleHint: String = "",
        coverHint: String = "",
        seasonId: Long = 0L,
        /**
         * 保留当前倍速（不重置为设置默认值）。**只有自动连播传 true**。
         *
         * 理由：用户手调到 1.5x 说明"我想快看"，连播下一集时把它打回 1.0x
         * 是反直觉的（他刚才是刻意调的）。而**用户主动点开另一个视频**时应该
         * 回到默认倍速 —— 那是"换了个片子看"，不是"继续快看"。
         */
        keepSpeed: Boolean = false,
        directResume: Boolean = false,
        refreshAtMs: Long? = null,
        refreshPaused: Boolean = false,
    ) {
        /*
         * key 里带上 epId：同一部剧切集时 bvid 是空的、cid 会变，但万一两集的 cid
         * 相同（花絮/PV 有可能），只按 "$bvid#$cid" 记忆就会"点了没反应"。
         *
         * ★ 这个 key 和观看记录的 key **必须是同一套规则**（都用 [History.keyOf]）——
         * 两处各写一份字符串拼接，改一处漏一处只是时间问题。
         */
        val key = History.keyOf(bvid, cid, epId)
        if (loadedKey == key && refreshAtMs == null) {
            // 离开播放页时暂停了，回来接着播（ExoPlayer 实例是复用的，进度不丢）
            player.exo.playWhenReady = resumeChoiceMs == null && !previewEnded
            return
        }
        finishTouchSeek(cancel = true); endTouchBoost()
        resumeChoiceMs = null
        previewActive = false; previewEnded = false
        loadedKey = key

        loadJob?.cancel()
        ticker?.cancel()
        resetSubtitles()
        player.stop()
        buffering = true
        durationMs = 0L
        closeSidebar()
        comments.reset()
        catalogueDetail = null
        cataloguePgc = null
        sideParts = emptyList()
        sideItems = emptyList()

        if (refreshAtMs == null) {
            enteredPreferredQuality = graph.settings.preferredQuality
            enteredPreferredAudio = graph.settings.preferredAudioQuality.id
            adaptiveQuality = null
        } else adaptiveQuality = activeQuality.takeIf { it > 0 } ?: adaptiveQuality
        autoQualityFrom = null
        touchLocked = false
        activeQuality = 0
        qualityDialog = false
        pendingQuality = null
        activeQuality = 0
        playInfo = null
        aid = 0L
        copyright = 0
        relation = null
        favoriteFolders = null
        actionBusy = false
        socialGeneration++
        lastAdaptiveChange = 0L

        this.bvid = bvid
        this.epId = epId
        this.titleHint = titleHint
        this.coverHint = coverHint
        this.seasonId = seasonId
        // 连播目标必须清 —— 否则切到没有下一集的视频时会带着上一部的目标
        nextTarget = null
        /*
         * 倍速：用户主动换视频 → 回到设置里的默认值；自动连播 → 沿用当前。
         * 见 [keepSpeed] 参数说明。
         */
        if (!keepSpeed) speedIndex = graph.settings.playbackSpeedIndex
        /*
         * 画面比例同理：主动换视频回到默认，连播沿用。
         * （老 4:3 片源用户切到"拉伸"后连播下一集，大概率还是老片，沿用更合理）
         */
        if (!keepSpeed) aspectId = graph.settings.aspectMode
        token++
        val myToken = token
        error = null
        danmaku = emptyList()
        rawDanmaku = emptyList()
        cloudDmLoaded = false; cloudDmLevel = 0
        dmMetadataJob?.cancel(); dmMetadataJob = null; dmMetadata = null
        cloudDmRules = top.bilitv.data.danmaku.DanmakuRules()
        loadedSegments.clear()
        planner.reset()
        sponsorJob?.cancel(); sponsorJob = null
        communitySkipSegments = emptyList(); skipPolicy = null; renderedFirstFrame = false
        sponsorRequested = false
        skipHint = null
        muted = false
        notice = null
        codecLabel = ""
        // 元信息也要清 —— 否则切下一个视频时，顶部信息栏会先短暂显示上一个的 UP主
        title = ""
        ownerName = ""
        ownerMid = 0L
        liveDmJob?.cancel(); liveDmJob = null
        cover = ""
        videoBadge = ""
        viewCount = 0L
        danmakuCount = 0L
        triedAvcFallback = false
        triedP2pRetry = false
        // 基准置空：新视频第一次 tick 采样之前，danmakuPosition() 一律返回 0
        posClock.reset()
        positionMs = 0L
        lastSavedMs = -1L
        sinceSaveMs = 0L
        /*
         * ★ 必须是 `false`，不能只靠"新建 VM 时默认 false"。
         *
         * 点播页和直播页共用同一个 `PlayerViewModel`（同一个 Composable 位置），
         * 所以从这个直播间退出、又打开一个点播视频时，VM 是**复用**的。
         * 不还原的话，点播页会带着 `live=true` 进来 ——
         * 表现是"没有进度条、按快进没反应、还不写观看记录"，而且很难联想到原因。
         */
        live = false
        living = false
        areaName = ""
        liveRoomId = 0L

        loadJob = viewModelScope.launch {
            AppLog.i("VM", "开始加载 bvid=$bvid cid=$cid epId=$epId")

            /*
             * ★ 起播前风控体检（2026-09-29 新增）。
             *
             * 三件套：Web 指纹（buvid3/4 + b_nut）→ buvid 激活 → bili_ticket。
             * 内部按天去重，正常情况只有首次进播放页会真发请求，之后都是直接返回。
             *
             * **为什么放在"取详情之前"而不是"取流之前"**：
             * 详情接口（`view`）不严，取流接口（`playurl`）才严。
             * 但维护本身要发 3 个请求，越早启动越能和详情请求并行。
             * 与无需签名的视频详情并行，只在取流前等待维护完成。
             *
             * ⚠️ 整体 runCatching：维护失败**绝不能挡住播放**。
             *    最坏情况就是回到"没有维护"的状态（也就是改造前的行为）。
             */
            val webHealth = async {
                runCatching { WebCookieMaintainer.ensureHealthyForPlay() }
                    .onFailure {
                        if (it is CancellationException) throw it
                        AppLog.w("VM", "起播前风控体检失败（不影响播放）：${it.javaClass.simpleName}")
                    }
            }

            /*
             * 取详情 + 取流，两条路径：
             *
             * - **UGC**（epId=0）：`view` 取标题/UP 主/真实 cid → `playurl` 取流。
             *   这条路径 2026-09-28 已经在真机上验证过，**一行都不动**。
             * - **PGC**（epId>0）：番剧/影视没有 bvid，`view` 接口对它无效；
             *   标题由剧集详情页带进来，cid 也是剧集接口给好的，直接取流。
             *
             * ⚠️ PGC 的播放地址需要登录。未登录时它返回 `code=0` + 空 data，
             * 解析出来就是 null —— 这里的提示要说明"需要登录"，
             * 不能笼统地说"接口失败"（用户能做的事完全不同）。
             */
            val realCid: Long
            val play: PlayInfo?
            if (epId > 0L) {
                title = titleHint
                realCid = cid
                if (realCid == 0L) {
                    AppLog.e("VM", "PGC 没有 cid")
                    error = PlayError(graph.getString(R.string.player_pgc_no_cid))
                    return@launch
                }
                this@PlayerViewModel.cid = realCid
                webHealth.await()
                play = graph.api.pgcPlayInfo(epId, realCid, accepts = { pickSelection(it) != null })
                if (play == null) {
                    AppLog.e("VM", "PGC playurl 返回空（未登录时属于正常现象，已登录=${graph.api.isLoggedIn()}）")
                    error = PlayError(
                        if (graph.api.isLoggedIn()) graph.getString(R.string.player_no_play_url) else graph.getString(R.string.player_pgc_need_login),
                        if (graph.api.isLoggedIn()) {
                            graph.getString(R.string.player_play_url_empty_desc)
                        } else {
                            graph.getString(R.string.player_pgc_need_login_desc)
                        },
                    )
                    return@launch
                }

            } else {
                val detail = graph.api.videoDetail(bvid)
                if (myToken != token) return@launch
                catalogueDetail = detail
                aid = detail?.aid ?: 0L
                copyright = detail?.copyright ?: 0
                videoBadge = detail?.badge.orEmpty()
                title = detail?.title.orEmpty()
                ownerName = detail?.ownerName.orEmpty()
                ownerMid = detail?.ownerMid ?: 0L
                if (!keepSpeed) speedIndex = graph.settings.speedForUp(ownerMid)
                cover = detail?.cover.orEmpty()
                viewCount = detail?.viewCount ?: 0L
                danmakuCount = detail?.danmakuCount ?: 0L
                AppLog.i("VM", "取得详情：$title")

                /*
                 * 自动连播：多 P 的「下一 P」。
                 *
                 * ★ 零额外请求 —— `pages` 就在上面这个 `videoDetail` 的响应里，
                 * 之前只是拿到了没存。用 `NextEpisode.nextPage` 按 cid 找下一项，
                 * 最后一 P / 找不到都返回 null（不会回绕重播）。
                 */
                val nextPage = NextEpisode.nextPage(detail?.pages.orEmpty(), cid.takeIf { it > 0 } ?: detail?.cid ?: 0)
                nextTarget = nextPage?.let {
                    NextTarget(bvid = bvid, cid = it.cid, epId = 0L, title = it.title, cover = cover)
                }
                AppLog.i(
                    "VM",
                    "连播目标：" + (nextTarget?.let { "下一P ${it.title}" } ?: "无（最后一P或单P）")
                )

                realCid = if (cid != 0L) cid else detail?.cid ?: 0L
                if (realCid == 0L) {
                    AppLog.e("VM", "详情里拿不到 cid")
                    error = PlayError(graph.getString(R.string.player_no_cid))
                    return@launch
                }
                this@PlayerViewModel.cid = realCid
                val previewCheck = if (detail?.chargingExclusive == true || detail?.paidContent == true)
                    async { graph.api.previewState(bvid, realCid) } else null
                webHealth.await()
                val result = graph.api.playInfo(bvid, realCid, aid, accepts = { pickSelection(it) != null })
                if (result == null) previewCheck?.cancel()
                play = result?.copy(isPreview = result.isPreview || previewCheck?.await() == true)
            }

            if (myToken != token) return@launch
            if (play == null) {
                AppLog.e("VM", "playurl 返回空或解析失败")
                val charged = catalogueDetail?.chargingExclusive == true
                error = PlayError(if (charged) graph.getString(R.string.player_charged_unavailable) else graph.getString(R.string.player_no_play_url),
                    if (charged) graph.getString(R.string.player_charged_desc)
                    else graph.getString(R.string.player_play_url_empty_desc))
                return@launch
            }

            try { top.bilitv.data.model.PlaybackPreview.requireBound(play) }
            catch (_: java.io.IOException) {
                error = PlayError(graph.getString(R.string.player_preview_unsafe), graph.getString(R.string.player_preview_unsafe_desc))
                return@launch
            }

            val resolvedKey = History.keyOf(bvid, realCid, epId)
            loadedKey = resolvedKey

            durationHintMs = top.bilitv.data.model.PlaybackPreview.duration(play.isPreview, 0, play.durationMs, play.previewLimitMs)
            play.notice?.let(::showNotice)
            AppLog.i(
                "VM",
                "playurl 返回：视频 ${play.videos.size} 条 / 音频 ${play.audios.size} 条 / 时长 ${play.durationMs}ms"
            )
            play.videos.forEach {
                AppLog.i(
                    "VM",
                    "  · q=${it.qualityId} ${it.codecs} ${it.width}x${it.height} bw=${it.bandwidth}"
                )
            }

            playInfo = play
            previewActive = play.isPreview
            refreshSkipSegments(force = true)

            val selection = pickSelection(play)
            if (selection == null) {
                AppLog.e("VM", "${play.videos.size} 条视频流的编码/分辨率全部不被本机解码器接受")
                error = PlayError(
                    graph.getString(R.string.player_no_decodable),
                    graph.getString(R.string.player_no_decodable_desc, play.videos.size),
                )
                return@launch
            }

            startPlayback(selection)
            if (refreshAtMs != null) {
                val target = refreshAtMs.coerceIn(0, durationHintMs.takeIf { it > 0 } ?: refreshAtMs.coerceAtLeast(0))
                player.exo.seekTo(target); samplePosition(target)
                if (refreshPaused) player.exo.pause()
                showNotice(graph.getString(R.string.player_url_refreshed))
            } else if (!previewActive) resumeIfNeeded(resolvedKey, play.durationMs, ask = graph.settings.askResume && !directResume && !keepSpeed)
            else showNotice(graph.getString(R.string.player_preview_playing))
            startTicker(myToken)
            val socialToken = socialGeneration
            if (canInteract && graph.api.canWriteVideoActions()) launch {
                val state = runCatching { graph.api.videoRelation(aid) }
                    .onFailure { if (it is CancellationException) throw it }.getOrNull()
                if (myToken == token && socialToken == socialGeneration) relation = state
            }
            loadSegment(danmakuSegmentIndex(positionMs), myToken)
            loadDanmakuMetadata(myToken)
            // 连播元信息不阻塞首帧；离页／换集后丢弃迟到响应。
            if (epId > 0L) launch {
                val next = runCatching {
                    run {
                        val d = graph.api.pgcDetail(seasonId, epId)
                        if (myToken != token) null
                        else {
                            cataloguePgc = d
                            d?.episodes?.firstOrNull { it.epId == epId }?.let { current ->
                                this@PlayerViewModel.seasonId = d.seasonId
                                title = d.playbackTitle(current)
                            }
                            NextEpisode.nextPgc(d?.episodes.orEmpty(), epId)?.let {
                            NextTarget(
                                bvid = "",
                                cid = it.cid,
                                epId = it.epId,
                                title = d!!.playbackTitle(it),
                                cover = it.cover,
                            )
                            }
                        }
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    AppLog.w("VM", "连播：取剧集列表失败（不影响本集）：${it.javaClass.simpleName}")
                }
                    .getOrNull()
                if (myToken == token) nextTarget = next
            }
        }
    }

    // ------------------------------------------------------------ 直播

    /**
     * 进直播间。
     *
     * ## 直播和点播在这一层差在哪（每一处都单独想过）
     *
     * | | 点播 [load] | 直播 |
     * |---|---|---|
     * | 取流 | `playurl`（DASH，音画分离） | `getRoomPlayInfo`（HLS，单条） |
     * | 时长 | 有 | **没有** → 不设 `durationHintMs`，进度条不画 |
     * | 断点续播 | 有 | **没有** —— 直播没有"上次看到哪"这回事 |
     * | 观看记录 | 写 | **不写**，见 [persistProgress] |
     * | 弹幕 | 分段拉取 | 走 WebSocket，**这一轮没做** |
     * | 广告跳过 | 查 SponsorBlock | 不查（`bvid` 为空，查了也是无效请求） |
     *
     * ## 为什么要先问一次房间信息
     *
     * 列表页带过来的 `live_status` **不可靠**（实测推荐流给过 `1` 但房间没开播）。
     * 而"开播中"和"没开播"要给用户的话完全不同 —— 前者是画面，
     * 后者是"主播还没开播，晚点再来"。多一次请求换来一句准确的话，值。
     *
     * 顺带拿到**人气**和**分区名**，这两个正是顶部信息栏要显示的。
     *
     * @param roomId 直播间号（长号）。
     */
    fun loadLive(roomId: Long, titleHint: String = "", coverHint: String = "", ownerHint: String = "", resumePlaying: Boolean = true, qualityHint: Int = 10000) {
        player.exo.repeatMode = Player.REPEAT_MODE_OFF
        touchLocked = false
        autoQualityFrom = null
        val key = "live#$roomId"
        if (loadedKey == key) {
            // 从直播间退出去又进来（ExoPlayer 实例复用），接着播
            player.exo.playWhenReady = resumeChoiceMs == null && !previewEnded
            return
        }
        finishTouchSeek(cancel = true); endTouchBoost()
        resumeChoiceMs = null
        previewActive = false; previewEnded = false
        loadedKey = key

        loadJob?.cancel()
        ticker?.cancel()
        resetSubtitles()
        closeSidebar()
        comments.reset()
        catalogueDetail = null
        cataloguePgc = null
        player.stop()
        buffering = true

        this.liveRoomId = roomId
        activeLiveLine = null; pendingLiveLine = null; livePlayInfo = null; requestedLiveQuality = qualityHint
        liveDmJob?.cancel(); liveDmJob = null
        this.bvid = ""
        this.cid = 0L
        this.epId = 0L
        this.titleHint = titleHint
        this.coverHint = coverHint
        token++
        val myToken = token

        error = null
        danmaku = emptyList()
        rawDanmaku = emptyList()
        cloudDmLoaded = false; cloudDmLevel = 0
        dmMetadataJob?.cancel(); dmMetadataJob = null; dmMetadata = null
        cloudDmRules = top.bilitv.data.danmaku.DanmakuRules()
        loadedSegments.clear()
        planner.reset()
        sponsorJob?.cancel(); sponsorJob = null
        communitySkipSegments = emptyList(); skipPolicy = null; renderedFirstFrame = false
        sponsorRequested = true          // 直播不查广告，直接标记"已处理"
        skipHint = null
        muted = false
        notice = null
        codecLabel = ""
        title = titleHint
        ownerName = ownerHint
        ownerMid = 0L
        cover = coverHint
        viewCount = 0L
        danmakuCount = 0L
        areaName = ""
        living = false
        triedAvcFallback = false
        triedLiveRefetch = false
        triedP2pRetry = false
        posClock.reset()
        positionMs = 0L
        lastSavedMs = -1L
        sinceSaveMs = 0L
        durationHintMs = 0L
        durationMs = 0L
        // ★ 到这里才置 true：上面的重置逻辑都是按点播写的，先让它们按老规矩跑完
        live = true

        loadJob = viewModelScope.launch {
            AppLog.i("VM", "进入直播间 room=$roomId（$titleHint）")

            /*
             * 房间信息。失败**不阻断**播放 —— 它在界面上只影响顶部信息栏，
             * 标题已经由调用方带进来了。为了它放弃整场直播是本末倒置。
             */
            val info = graph.api.liveRoomInfo(roomId)
            if (myToken != token) return@launch
            if (info != null) {
                if (info.title.isNotBlank()) title = info.title
                if (info.uname.isNotBlank()) ownerName = info.uname
                ownerMid = info.uid
                if (info.cover.isNotBlank()) cover = info.cover
                viewCount = info.online
                areaName = listOf(info.parentAreaName, info.areaName)
                    .filter { it.isNotBlank() }
                    .joinToString("·")
                living = info.isLiving
                AppLog.i("VM", "房间信息：${info.title} / $ownerName / 人气=${info.online} / 状态=${info.liveStatus}")
            } else {
                AppLog.w("VM", "房间信息取不到（不影响播放）")
            }

            val line = fetchLiveLine()
            if (myToken != token) return@launch
            if (line == null) return@launch

            AppLog.i("VM", "选中直播线路：${line.protocol}/${line.format}/${line.codec} qn=${line.qn}")
            codecLabel = graph.getString(R.string.player_live_codec, line.codec.uppercase())
            player.playLive(line.urls.first(), line.isHls)
            player.exo.playWhenReady = resumePlaying
            living = true
            startTicker(myToken)
            startLiveDanmaku(myToken)
        }
    }

    /**
     * 取一条能播的直播线路。**取不到就把原因写成用户能看懂的一句话**。
     *
     * @return null 表示已经设好 [error]，调用方不要再往下走。
     */
    private suspend fun fetchLiveLine(): top.bilitv.data.model.LiveStreamLine? {
        val play = graph.api.livePlayInfo(liveRoomId, requestedLiveQuality)
        if (play == null) {
            error = PlayError(
                graph.getString(R.string.player_live_no_url),
                graph.getString(R.string.player_live_no_url_desc),
            )
            return null
        }
        if (play.liveStatus != LiveStatus.LIVING) {
            /*
             * `live_status` 2 = 轮播（在放录像），0 = 未开播。
             *
             * 这两种都不是"播放失败"，所以**不能**复用那句错误文案 ——
             * 用户该做的事是"晚点再来"，而不是"点重试看日志"。
             *
             * ★ 判据写 `!= LIVING` 而不是 `== OFFLINE`：万一哪天接口不给这个
             * 字段（拿到 UNKNOWN），行为退化成"不播 + 说没开播"，
             * 而不是"当作在播、然后黑屏"。
             */
            error = PlayError(
                if (play.liveStatus == LiveStatus.RERUN) graph.getString(R.string.player_live_rerun) else graph.getString(R.string.player_live_offline),
                graph.getString(R.string.player_live_offline_desc),
            )
            return null
        }
        val line = play.preferredLine()
        if (line == null) {
            error = PlayError(
                graph.getString(R.string.player_live_no_lines),
                graph.getString(R.string.player_live_no_lines_desc, play.lines.size),
            )
            return null
        }
        livePlayInfo = play; pendingLiveLine = line
        return line
    }

    // ------------------------------------------------------------ 续播与观看记录

    /**
     * 看看这个视频上次看到哪了，接着播。
     *
     * ## 为什么判断在 [History.resumeTargetMs] 里，而不是这里写 if
     *
     * 那三个边界（没记录 / 只看了几秒 / 已经看到片尾）都是**会出错的地方**，
     * 而这里是 Composable 之外的 ViewModel，`History` 那边是纯函数 ——
     * 放过去就能被 JVM 单测钉住。本项目的教训是：这类判断不写测试，
     * 早晚会以一个"看起来像别的毛病"的形式冒出来（对比 `PositionClock` 那个洞）。
     *
     * ## 续播算不算"用户拖动"
     *
     * 算。跳转之后要告诉广告调度器"位置是被人为改过的"，
     * 否则它会拿着旧位置上的判断结果往新位置上套。
     */
    private fun resumeIfNeeded(key: String, totalMs: Long, ask: Boolean) {
        val saved = history.get(key)
        val target = History.resumeTargetMs(saved, totalMs)
        if (target == null) {
            AppLog.i(
                "VM",
                "无可续播位置（上次进度 ${saved?.progressMs ?: -1L}ms / 时长 ${totalMs}ms）",
            )
            return
        }
        if (ask) {
            resumeChoiceMs = target
            player.exo.pause()
            return
        }
        applyResume(target)
    }

    fun chooseResume(fromStart: Boolean) {
        val saved = resumeChoiceMs ?: return
        resumeChoiceMs = null
        applyResume(if (fromStart) 0L else saved)
        if (fromStart) persistProgress(force = true)
        if (error == null) player.exo.play()
    }

    private fun applyResume(target: Long) {
        player.exo.seekTo(target)
        // 立刻采样：否则在下一个 tick 之前（最多 250ms）进度条和弹幕还停在 0
        samplePosition(target)
        planner.onUserSeek()
        AppLog.i("VM", "续播到 ${target}ms")
        showNotice(if (target == 0L) graph.getString(R.string.player_resumed_start) else graph.getString(R.string.player_resumed_from, formatDuration((target / 1000).toInt())))
    }

    /**
     * 把当前进度写进观看记录。
     *
     * ## 为什么用 `positionMs` 而不是现读 `player.exo.currentPosition`
     *
     * 播放器在过渡态（刚 seek 完还在重缓冲 / 时间线未就绪）时，`currentPosition`
     * 返回的是 `C.TIME_UNSET`，一个**极小的负数**。
     * 拿它去写记录，历史列表上会出现一条"看到 -9223372036854775808 秒"的记录，
     * 而且它会把本来正确的续播点覆盖掉。
     *
     * `positionMs` 是采样来的，[samplePosition] 已经把负数全部丢弃。
     *
     * @param force 离开播放页时用。定时落盘是 5 秒一次，"看了 4 秒就退出"这一段
     *   本来会丢，用户会觉得"没记住"。
     */
    fun persistProgress(force: Boolean = false) {
        /*
         * ★ 直播**不写观看记录**。
         *
         * 两个理由，第二个才是必须的：
         * 1. "上次看到 12 分 30 秒"对直播没有意义 —— 下次进直播间是从**当前**开始看；
         * 2. 真写进去的话，历史列表会多出一条点了就跳转到**已经播完的时间点**的记录，
         *    而那个位置在一场新的直播里根本不存在。用户会看到一个永远黑屏的历史条目。
         */
        if (live || resumeChoiceMs != null) return
        val key = loadedKey ?: return
        val pos = positionMs
        if (pos < 0L || (pos == 0L && !force)) return
        if (!force && pos == lastSavedMs) return
        lastSavedMs = pos

        history.upsert(
            HistoryEntry(
                key = key,
                bvid = bvid,
                cid = cid,
                epId = epId,
                seasonId = currentSeasonId,
                // PGC 的标题在 titleHint 里（title 也是它，但兜一层更稳）
                title = title.ifBlank { titleHint },
                cover = cover.ifBlank { coverHint },
                owner = ownerName,
                badge = videoBadge,
                progressMs = pos,
                durationMs = top.bilitv.data.model.PlaybackPreview.duration(previewActive, durationMs, durationHintMs, playInfo?.previewLimitMs),
                updatedAtSec = System.currentTimeMillis() / 1000,
            )
        )
    }

    // ------------------------------------------------------------ 选流与起播

    /**
     * 选一组流。
     *
     * @param onlyAvc 只挑 AVC —— HEVC 在 Media3 1.5.x 上解析会崩时的逃生路（`docs/08` §8）。
     *   它会和设置页的「只用 AVC（兼容模式）」**取或** —— 用户主动打开的开关
     *   必须一次生效，不能"先崩一次、自动降级、下次才记住"。
     */
    private var enteredPreferredQuality = graph.settings.preferredQuality
    private var enteredPreferredAudio = graph.settings.preferredAudioQuality.id

    private fun pickSelection(play: PlayInfo, onlyAvc: Boolean = false): StreamSelector.Selection? =
        StreamSelector.select(
            play = play,
            canVideo = {
                DecoderSupport.canDecodeVideo(videoMimeOf(it.codecs), it.width, it.height)
            },
            canAudio = { DecoderSupport.canDecodeAudio(audioMimeOf(it.codecs)) },
            qualityId = adaptiveQuality ?: enteredPreferredQuality.takeIf { q -> QualityOptions.isExplicit(q) },
            preferHevc = graph.settings.preferHevc,
            onlyAvc = onlyAvc || graph.settings.forceAvc,
            audioQualityId = enteredPreferredAudio,
        )

    /** 起播并把"在播什么"记到界面上 —— 降级换编码后这里会跟着变，一眼能看出走到哪一级 */
    private fun startPlayback(sel: StreamSelector.Selection) {
        codecLabel = "${codecFamily(sel.video.codecs)} ${compactQualityLabel(sel.video.qualityId, playInfo?.qualityLabels?.get(sel.video.qualityId).orEmpty(), sel.video.height)}"
        // 线路策略开关在**每次起播时**读一次：改完设置不用重启应用，下一个视频就生效
        applyEndAction()
        player.play(
            sel,
            skipP2p = graph.settings.skipP2p,
            cdnPreference = graph.settings.cdnPreference,
            previewEndMs = playInfo?.previewLimitMs.takeIf { previewActive },
        )
        /*
         * 应用倍速（2026-09-29）。
         *
         * ★ 必须在 `play()` **之后**调 —— `setMediaSource` 之后 ExoPlayer 才是
         * 一个"有媒体"的状态。虽然 `setPlaybackSpeed` 在 IDLE 也接受（值会被记住），
         * 但放在这里语义更清楚：这是"这次起播用的倍速"。
         *
         * `speedIndex` 已在 `load()` 里重置为设置默认值，所以这里读的是
         * "设置默认值 或 用户在当前页临时调过的值"（后者在自动连播时会带着走，
         * 见 `continueToNext` —— 这是刻意的）。
         */
        player.setSpeed(PlaybackTuning.speedOf(speedIndex))
        if (resumeChoiceMs != null) player.exo.pause()
    }

    private fun lowerQualityIfNeeded(dropped: Int, elapsedMs: Long, fps: Float) {
        if (live || !playing || !graph.settings.autoLowerQuality) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdaptiveChange < 20_000L || !StreamSelector.shouldLowerQuality(dropped, elapsedMs, fps)) return
        val play = playInfo ?: return
        val current = player.currentQuality() ?: return
        val lower = StreamSelector.lowerResolution(play.videos, current) {
            (!(graph.settings.forceAvc || triedAvcFallback) || it.isAvc) && DecoderSupport.canDecodeVideo(videoMimeOf(it.codecs), it.width, it.height)
        } ?: return
        val previous = adaptiveQuality
        adaptiveQuality = lower.qualityId
        val selection = pickSelection(play, onlyAvc = triedAvcFallback)
        if (selection == null) { adaptiveQuality = previous; return }
        val position = player.exo.currentPosition.coerceAtLeast(0L)
        lastAdaptiveChange = now
        autoQualityFrom = current.qualityId
        AppLog.i("PlaybackPerf", "持续掉帧，自动降档 ${current.width}x${current.height} -> ${selection.video.width}x${selection.video.height}；保留进度=${position}ms")
        startPlayback(selection)
        player.exo.seekTo(position)
        samplePosition(position)
        showNotice(graph.getString(R.string.player_quality_adjusting))
    }

    /**
     * 换 AVC 重播 —— 降级链最后一环，抽出来是因为现在有**两个入口**：
     *  1. 视频路"数据到手但解析不了"（原本的路径）
     *  2. 「去掉 P2P 节点」那条退路失败之后接着走
     *
     * 抽之前这段逻辑内联在 `onUnrecoverable` 里，加第二个入口时如果复制一遍，
     * `triedAvcFallback` 这个防重复标志迟早会有一处忘写 —— 表现是**无限换编码循环**。
     */
    private fun fallbackToAvc(e: androidx.media3.common.PlaybackException) {
        val current = player.currentVideoCodecs
        val cached = playInfo?.let { pickSelection(it, onlyAvc = true) }
        val fromApp = playInfo?.source == top.bilitv.data.settings.VideoApiSource.APP
        if (cached == null && !fromApp) {
            error = PlayError(
                title = graph.getString(R.string.player_play_failed, e.errorCodeName),
                detail = describeThrows(e) + "\n\n" + player.describeState(),
            )
            return
        }
        triedAvcFallback = true
        val position = positionMs.coerceAtLeast(0)
        val wasPlaying = player.exo.playWhenReady
        AppLog.w("VM", "视频路走不通（当前 $current），换 AVC；缺少 App 兼容流时只请求 Web 一次")
        showNotice(graph.getString(R.string.player_switching_avc))
        error = null
        // 先把错误态清干净（此时在主线程，可以直呼）
        player.exo.stop()

        val myToken = token
        viewModelScope.launch {
            // 和播放器层同样的理由：让人从错误里彻底收敛，别贴着失败换源
            delay(FALLBACK_DELAY_MS)
            if (myToken != token) {
                AppLog.w("VM", "换编码期间已经切了视频，放弃这次重播")
                return@launch
            }
            var fallback = cached
            if (fallback == null && fromApp) {
                val web = if (epId > 0) graph.api.pgcPlayInfo(epId, cid,
                    source = top.bilitv.data.settings.VideoApiSource.WEB)
                else graph.api.playInfo(bvid, cid, aid, source = top.bilitv.data.settings.VideoApiSource.WEB)
                if (myToken != token) return@launch
                fallback = web?.let { pickSelection(it, onlyAvc = true) }
                if (fallback != null) {
                    val bounded = web!!.copy(isPreview = previewActive || web.isPreview,
                        previewLimitMs = if (previewActive) playInfo?.previewLimitMs else web.previewLimitMs)
                    top.bilitv.data.model.PlaybackPreview.requireBound(bounded)
                    playInfo = bounded; previewActive = bounded.isPreview
                    durationHintMs = top.bilitv.data.model.PlaybackPreview.duration(previewActive, 0, bounded.durationMs, bounded.previewLimitMs)
                }
            }
            if (fallback == null) {
                error = PlayError(graph.getString(R.string.player_no_avc), describeThrows(e) + "\n\n" + player.describeState())
                return@launch
            }
            showNotice(graph.getString(R.string.player_avc_fallback, codecFamily(current)))
            startPlayback(fallback)
            player.exo.seekTo(position.coerceAtMost(durationHintMs.takeIf { it > 0 } ?: position))
            if (!wasPlaying) player.exo.pause()
        }
    }

    /**
     * 退路 0：把 P2P 节点全部去掉、原地重开。**没得换时自动接着走换编码那条**。
     *
     * 这两步串成一条链（而不是各自独立触发），是因为它们都由**同一次失败**引发：
     * 播放器已经报了错，用户看到的还是黑屏，此时应该一口气把两条路试完，
     * 而不是"试一条、等它再报一次错、再试下一条" —— 那等于让用户多等一个超时。
     */
    private fun tryP2pRetry(e: androidx.media3.common.PlaybackException) {
        triedP2pRetry = true
        AppLog.w("VM", "网络类失败（${e.errorCodeName}）-> 先试一次「去掉 P2P 节点」")
        val myToken = token
        viewModelScope.launch {
            delay(FALLBACK_DELAY_MS)
            if (myToken != token) return@launch
            val ok = player.retryWithoutP2p(
                skipP2p = true,
                cdnPreference = graph.settings.cdnPreference,
            )
            if (ok) {
                error = null
                showNotice(graph.getString(R.string.player_retry_sources))
            } else {
                // 候选表里本来就没有 P2P 节点（或者已经滤过一轮）→ 这条退路等于没有
                AppLog.w("VM", "没有可换的线路，直接走换编码那条")
                continueToAvcOrFail(e)
            }
        }
    }

    /**
     * 降级链的最后一段：**能换 AVC 就换，不能就报错**。
     *
     * 单独抽出来是因为三个地方都要用（解析类失败 / 退路 0 失败 / 本来就没退路），
     * 而这三处如果各写一遍 `if (!triedAvcFallback && …)`，迟早有一处漏掉那个防重复标志。
     */
    private fun continueToAvcOrFail(e: androidx.media3.common.PlaybackException) {
        val current = player.currentVideoCodecs
        if (!triedAvcFallback && !current.startsWith("avc", true)) {
            fallbackToAvc(e)
        } else {
            error = PlayError(
                title = graph.getString(R.string.player_play_failed, e.errorCodeName),
                detail = describeThrows(e) + "\n\n" + player.describeState(),
            )
        }
    }

    /** 一次性提示，几秒后自己消失 */
    internal fun showNotice(msg: String) {
        notice = msg
        viewModelScope.launch {
            delay(NOTICE_MS)
            if (notice == msg) notice = null
        }
    }

    private var aid = 0L
    private var copyright = 0
    private var socialGeneration = 0
    val canInteract: Boolean get() = !live && epId == 0L && aid > 0L
    var relation by mutableStateOf<VideoRelation?>(null)
        private set
    var actionBusy by mutableStateOf(false)
        private set
    var favoriteFolders by mutableStateOf<List<FavFolder>?>(null)
        private set
    var qualityDialog by mutableStateOf(false)
    var liveLineDialog by mutableStateOf(false)
    var livePlayInfo by mutableStateOf<top.bilitv.data.model.LivePlayInfo?>(null)
        private set
    var activeLiveLine by mutableStateOf<top.bilitv.data.model.LiveStreamLine?>(null)
        private set
    private var pendingLiveLine: top.bilitv.data.model.LiveStreamLine? = null
    private var requestedLiveQuality = 10000
    private var liveChanging = false
    private var liveDmJob: Job? = null
    var activeQuality by mutableIntStateOf(0)
        private set
    private fun compactQualityLabel(id: Int, sourceLabel: String = "", height: Int = 0): String =
        if (id == 126) graph.getString(R.string.quality_dolby)
        else QualityOptions.compactLabel(id, sourceLabel, height)
    val qualityLabel: String get() = if (live) activeLiveLine?.let { livePlayInfo?.qualities?.get(it.qn)?.substringBefore(' ') ?: "${it.qn}" } ?: graph.getString(R.string.action_loading)
        else compactQualityLabel(activeQuality, playInfo?.qualityLabels?.get(activeQuality).orEmpty())
    private data class PendingQuality(val selection: StreamSelector.Selection, val preference: Int?, val position: Long, val playing: Boolean)
    private var pendingQuality: PendingQuality? = null
    val availableQualities: List<Pair<Int, String>> get() {
        if (live) return livePlayInfo?.qualities?.entries?.sortedByDescending { it.key }?.map { it.key to it.value }.orEmpty()
        val info = playInfo ?: return emptyList()
        return info.videos.filter { DecoderSupport.canDecodeVideo(videoMimeOf(it.codecs), it.width, it.height) &&
            (!(graph.settings.forceAvc || triedAvcFallback) || it.isAvc) }
            .distinctBy { it.qualityId }.sortedByDescending { it.qualityId }.map { video ->
                video.qualityId to (info.qualityLabels[video.qualityId]
                    ?: qualityOptionLabelRes(video.qualityId)?.let { graph.getString(it) }
                    ?: QualityOptions.ALL.firstOrNull { it.id == video.qualityId }?.label
                    ?: "${video.height}P")
            }
    }
    fun openQuality() {
        if (availableQualities.isEmpty()) showNotice(graph.getString(R.string.player_quality_pending)) else qualityDialog = true
    }
    fun changeQuality(id: Int) {
        if (live) { changeLiveQuality(id); return }
        val info = playInfo ?: return
        if (pendingQuality != null) { showNotice(graph.getString(R.string.player_quality_switching)); return }
        if (id == activeQuality || availableQualities.none { it.first == id }) return
        val previousSelection = player.currentSelection() ?: return
        autoQualityFrom = null
        val previous = adaptiveQuality
        adaptiveQuality = id
        val selection = pickSelection(info, onlyAvc = triedAvcFallback)
        if (selection == null || selection.video.qualityId != id) {
            adaptiveQuality = previous
            showNotice(graph.getString(R.string.player_quality_unsupported))
            return
        }
        val position = player.exo.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.exo.playWhenReady
        pendingQuality = PendingQuality(previousSelection, previous, position, wasPlaying)
        lastAdaptiveChange = SystemClock.elapsedRealtime()
        startPlayback(selection)
        player.exo.seekTo(position)
        player.exo.playWhenReady = wasPlaying
        samplePosition(position)
        showNotice(graph.getString(R.string.player_quality_switching_now))
    }

    private fun restorePreviousQuality() {
        val previous = pendingQuality ?: return
        pendingQuality = null
        adaptiveQuality = previous.preference
        error = null
        player.exo.stop()
        val myToken = token
        AppLog.w("Player", "画质切换失败，恢复 q=${previous.selection.video.qualityId}；进度=${previous.position}ms 暂停=${!previous.playing}")
        viewModelScope.launch {
            delay(FALLBACK_DELAY_MS)
            if (myToken != token) return@launch
            startPlayback(previous.selection)
            player.exo.seekTo(previous.position)
            player.exo.playWhenReady = previous.playing
            samplePosition(previous.position)
            showNotice(graph.getString(R.string.player_quality_failed, compactQualityLabel(previous.selection.video.qualityId)))
        }
    }

    private fun beginAction(): Boolean {
        if (actionBusy) { showNotice(graph.getString(R.string.player_busy)); return false }
        if (!canInteract) { showNotice(graph.getString(R.string.player_action_unsupported)); return false }
        if (!graph.api.canWriteVideoActions()) { showNotice(graph.getString(R.string.player_sign_in_first)); return false }
        actionBusy = true
        socialGeneration++
        return true
    }
    private fun socialAction(action: String, block: suspend (Long, VideoRelation) -> String) {
        if (!beginAction()) return
        AppLog.i("Interaction", "触发$action（读取服务器状态后操作，不自动重发）")
        val myToken = token
        val target = aid
        viewModelScope.launch {
            try {
                val before = graph.api.videoRelation(target)
                if (myToken != token) return@launch
                relation = before
                val message = block(target, before)
                if (myToken != token) return@launch
                val after = runCatching { graph.api.videoRelation(target) }
                    .onFailure { if (it is CancellationException) throw it }.getOrNull()
                if (myToken != token) return@launch
                relation = after
                showNotice(message + if (after == null) graph.getString(R.string.player_state_stale) else "")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (myToken == token) {
                    relation = null
                    showNotice(if (e is IllegalStateException || e is IllegalArgumentException) e.message ?: graph.getString(R.string.player_action_failed) else graph.getString(R.string.player_network_error))
                }
            } finally {
                if (myToken == token) actionBusy = false
                AppLog.i("Interaction", "$action 请求结束")
            }
        }
    }
    fun toggleLike() = socialAction("点赞") { target, before ->
        graph.api.setVideoLike(target, !before.liked)
        if (before.liked) graph.getString(R.string.player_like_removed) else graph.getString(R.string.player_liked)
    }
    fun coin() = socialAction("投币") { target, before ->
        val count = coinsToAdd(before.coins, copyright)
        if (count == 0) graph.getString(R.string.player_coin_full) else {
            graph.api.addVideoCoins(target, count)
            graph.getString(R.string.player_coin_added, count)
        }
    }
    fun triple() = socialAction("三连") { target, _ ->
        val result = graph.api.tripleVideo(target)
        val labels = listOf("like" to graph.getString(R.string.remote_like), "coin" to graph.getString(R.string.player_coin), "fav" to graph.getString(R.string.remote_favorite))
        labels.joinToString(" · ") { (key, label) -> if (result.getBoolean(key)) graph.getString(R.string.player_triple_ok, label) else graph.getString(R.string.player_triple_new, label) }
    }
    fun openFavorites() {
        if (!beginAction()) return
        val myToken = token
        val target = aid
        viewModelScope.launch {
            try {
                val folders = graph.api.videoFavoriteFolders(target)
                if (myToken == token) favoriteFolders = folders
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (myToken == token) showNotice(if (e is IllegalStateException) e.message ?: graph.getString(R.string.player_fav_folders_failed) else graph.getString(R.string.player_fav_folders_failed_retry)) }
            finally { if (myToken == token) actionBusy = false }
        }
    }
    fun closeFavorites() { favoriteFolders = null }
    fun saveFavorites(selected: Set<Long>) {
        val folders = favoriteFolders ?: return
        val myToken = token
        if (!selected.all { id -> folders.any { it.id == id } }) return
        val original = folders.filter { it.favored == true }.map { it.id }.toSet()
        socialAction("收藏") { target, _ ->
            graph.api.setVideoFavorites(target, selected - original, original - selected)
            if (myToken == token) favoriteFolders = null
            graph.getString(R.string.player_fav_saved)
        }
    }

    /** Re-fetch signed URLs while preserving this viewing session's choices, not new defaults. */
    fun refreshStream() {
        if (loadJob?.isActive == true) { showNotice(graph.getString(R.string.player_loading_wait)); return }
        finishTouchSeek(cancel = true); endTouchBoost()
        val wasPlaying = player.exo.playWhenReady
        val position = player.exo.currentPosition.coerceAtLeast(0)
        persistProgress(force = true)
        if (live) {
            val quality = activeLiveLine?.qn ?: requestedLiveQuality
            loadedKey = null
            loadLive(liveRoomId, title, cover, ownerName, resumePlaying = wasPlaying, qualityHint = quality)
        } else if (cid > 0 && (bvid.isNotBlank() || epId > 0)) {
            load(bvid, cid, epId, title, cover, currentSeasonId, keepSpeed = true,
                refreshAtMs = position, refreshPaused = !wasPlaying)
        } else showNotice(graph.getString(R.string.player_info_not_ready))
    }

    /** 手动切换和播完连播共用真实列表顺序，绝不以UP推荐冒充播放列表。 */
    private fun adjacentTarget(step: Int): NextTarget? {
        if (epId > 0) return cataloguePgc?.let { d -> NextEpisode.nextPgc(d.episodes, epId, step)?.let {
            NextTarget("", d.playbackTitle(it), it.cid, it.epId, it.cover, d.seasonId)
        } } ?: nextTarget.takeIf { step == 1 }
        NextEpisode.nextPage(catalogueDetail?.pages.orEmpty(), cid, step)?.let {
            return NextTarget(bvid, it.title, it.cid, 0, cover)
        }
        if (playlistId > 0) return playlistQueue.takeIf { it.folderId == playlistId }?.adjacent(bvid, step)?.sideTarget()
        return NextEpisode.adjacent(catalogueDetail?.collection.orEmpty(), step) { it.bvid == bvid }?.let {
            NextTarget(it.bvid, it.title, it.cid, 0, it.cover)
        }
    }

    private fun playAdjacentTarget(target: NextTarget, manual: Boolean) {
        adjacentJob = null
        persistProgress(force = true); closeSidebar()
        if (!manual) showNotice(graph.getString(R.string.player_playing_next, target.title))
        load(target.bvid, target.cid, target.epId, target.title, target.cover,
            target.seasonId.takeIf { it > 0 } ?: currentSeasonId, keepSpeed = true)
    }

    fun playAdjacent(step: Int, manual: Boolean = true) {
        adjacentJob?.cancel(); adjacentJob = null
        if (step != -1 && step != 1) return
        if (live) { if (manual) showNotice(graph.getString(R.string.player_live_no_next)); return }
        adjacentTarget(step)?.let { playAdjacentTarget(it, manual); return }
        val knownPlaylist = playlistId > 0 && playlistQueue.folderId == playlistId && playlistQueue.contains(bvid)
        if (step != 1 || !knownPlaylist || !playlistQueue.hasMore) {
            if (manual) showNotice(when {
                playlistId > 0 && !knownPlaylist -> graph.getString(R.string.player_playlist_open_first)
                step < 0 && knownPlaylist && playlistQueue.truncated -> graph.getString(R.string.player_playlist_truncated)
                step < 0 -> graph.getString(R.string.player_no_previous)
                else -> graph.getString(R.string.player_no_next)
            })
            return
        }
        closeSidebar()
        val requestedFolder = playlistId; val requestedBvid = bvid
        val myToken = token; val generation = sideGeneration
        if (manual) showNotice(graph.getString(R.string.player_playlist_loading_next))
        adjacentJob = viewModelScope.launch {
            try {
                val item = playlistQueue.next(requestedBvid) { page ->
                    val result = graph.api.favResourcePage(requestedFolder, page) ?: error(graph.getString(R.string.player_playlist_page_failed))
                    if (myToken != token || generation != sideGeneration || requestedFolder != playlistId ||
                        (!manual && !graph.settings.autoNext)) throw CancellationException()
                    result
                }
                if (myToken != token || generation != sideGeneration) return@launch
                if (item != null) playAdjacentTarget(item.sideTarget(), manual)
                else if (playlistQueue.hasMore) showNotice(graph.getString(R.string.player_playlist_no_more))
                else if (manual) showNotice(graph.getString(R.string.player_no_next))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (myToken == token && generation == sideGeneration) showNotice(e.message ?: graph.getString(R.string.player_playlist_failed)) }
            finally { if (myToken == token && generation == sideGeneration) adjacentJob = null }
        }
    }

    fun togglePlay() {
        adjacentJob?.cancel(); adjacentJob = null
        if (resumeChoiceMs != null) return
        if (previewEnded) { replayPreview(); return }
        // 出错状态下这个按钮变成「重试」：整体重走一遍取流流程。
        // 手机上测的时候不用退出去再进来，一步就能重试。
        if (error != null) { refreshStream(); return }
        val exo = player.exo
        exo.playWhenReady = !exo.playWhenReady
        AppLog.i("Player", "切换播放状态：playWhenReady=${exo.playWhenReady}")
    }

    private var touchOrigin: Long? = null
    private var touchTarget = 0L
    private var touchWasPlaying = false
    private var previewAt = 0L
    private var touchPreviewCount = 0
    private var boostSpeed by mutableStateOf<Float?>(null)
    fun beginTouchSeek() {
        if (live || touchOrigin != null || durationMs <= 0) return
        touchOrigin = player.exo.currentPosition.coerceAtLeast(0)
        touchTarget = touchOrigin!!; touchWasPlaying = player.exo.playWhenReady
        player.exo.pause(); previewAt = 0; touchPreviewCount = 0
    }
    fun previewTouchSeek(target: Long) {
        if (touchOrigin == null) return
        touchTarget = target.coerceIn(0, durationMs.coerceAtLeast(0))
        val now = SystemClock.elapsedRealtime()
        // ponytail: reuse the decoder, max two previews/sec; no extra thumbnail player or requests.
        if (now - previewAt >= 500 && !buffering && touchTarget <= player.exo.bufferedPosition && touchTarget >= touchOrigin!!) {
            player.exo.seekTo(touchTarget); previewAt = now; touchPreviewCount++
        }
    }
    fun finishTouchSeek(cancel: Boolean = false) {
        val origin = touchOrigin ?: return
        touchOrigin = null
        seekTo(if (cancel) origin else touchTarget)
        player.exo.playWhenReady = touchWasPlaying
        AppLog.i("Player", "触摸进度：目标=$touchTarget 预览=$touchPreviewCount 取消=$cancel")
    }
    fun beginTouchBoost(): Boolean {
        if (live || !player.exo.playWhenReady || boostSpeed != null || touchOrigin != null) return false
        boostSpeed = player.exo.playbackParameters.speed
        player.setSpeed(2f); AppLog.i("Player", "触摸临时倍速=2.0 原速=$boostSpeed"); return true
    }
    fun endTouchBoost() {
        val previous = boostSpeed ?: return
        boostSpeed = null; player.setSpeed(previous); AppLog.i("Player", "触摸倍速恢复=$previous")
    }

    fun seekTo(targetMs: Long) {
        if (live || durationMs <= 0L) return
        val exo = player.exo
        val target = targetMs.coerceIn(0L, durationMs)
        exo.seekTo(target)
        // 立刻重采样，否则下一次 tick 之前（最多 250ms）弹幕和进度条还停在旧位置
        samplePosition(target)
        planner.onUserSeek()
        skipHint = null
    }

    fun pause() {
        player.exo.pause()
    }

    fun cancelSkip() {
        planner.cancelPending()
        skipHint = null
    }

    fun setDanmakuOn(v: Boolean) {
        dmOn = v
        graph.settings.danmakuEnabled = v
        if (live) { if (v) startLiveDanmaku(token) else { liveDmJob?.cancel(); liveDmJob = null; danmaku = emptyList() } }
        else loadDanmakuMetadata(token)
    }

    val liveLines: List<Pair<top.bilitv.data.model.LiveStreamLine, Int>> get() = livePlayInfo?.lines.orEmpty()
        .flatMap { line -> line.urls.indices.map { line to it } }
    fun openLiveLines() {
        if (liveLines.isEmpty()) showNotice(graph.getString(R.string.player_live_no_lines_yet)) else liveLineDialog = true
    }
    fun useLiveLine(line: top.bilitv.data.model.LiveStreamLine, index: Int) {
        if (!live || index !in line.urls.indices) return
        error = null; triedLiveRefetch = false; pendingLiveLine = line
        player.playLive(line.urls[index], line.isHls)
    }
    private fun changeLiveQuality(id: Int) {
        if (liveChanging || availableQualities.none { it.first == id }) return
        liveChanging = true
        val myToken = token
        viewModelScope.launch {
            try {
                val result = graph.api.livePlayInfo(liveRoomId, id)
                if (myToken != token) return@launch
                val line = result?.preferredLine()
                if (line == null || result.liveStatus != LiveStatus.LIVING) { showNotice(graph.getString(R.string.player_quality_not_obtained)); return@launch }
                livePlayInfo = result; requestedLiveQuality = id
                useLiveLine(line, 0)
                if (line.qn != id) showNotice(graph.getString(R.string.player_quality_server_lower))
            } finally { liveChanging = false }
        }
    }
    private fun startLiveDanmaku(myToken: Int) {
        if (!live || !dmOn || liveDmJob?.isActive == true) return
        val room = liveRoomId
        liveDmJob = viewModelScope.launch {
            repeat(3) { attempt ->
                if (myToken != token || !dmOn) return@launch
                try {
                    var received = false
                    graph.api.liveDanmaku(room) { batch ->
                        if (myToken == token && dmOn && playing) {
                            val position = danmakuPosition()
                            val filtered = top.bilitv.data.danmaku.filterDanmaku(batch, dmFilter, localRules = localDmRules)
                            // ponytail: 最多保留 512 条／90 秒，超高峰丢旧消息；需要更密时先测量渲染容量。
                            danmaku = (danmaku.filter { it.timeMs >= position - 90000 } + filtered.map { it.copy(timeMs = position + 150) }).takeLast(512)
                            if (!received) { received = true; AppLog.i("LiveDanmaku", "房间 $room 收到并过滤真实弹幕") }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { AppLog.w("LiveDanmaku", "连接中断 ${e.javaClass.simpleName}，重试${attempt + 1}/3") }
                delay((attempt + 1) * 2000L)
            }
            showNotice(graph.getString(R.string.player_live_danmaku_failed))
        }
    }

    // ------------------------------------------------------------ 三连增强（2026-09-29）

    /**
     * 切下一档倍速（循环）。
     *
     * ## 为什么这里只改内存、不写设置
     *
     * 播放页的倍速是**临时状态**：用户这一集想 1.5x，不代表下一集还想。
     * 设置页里的那个才是"以后默认用几倍"，两者刻意分开。
     * 混成一个的后果是"我这次临时加速，从此所有视频都 1.5x"，很难联想到原因。
     *
     * 对直播：**允许**。有人就是听不清解说想慢放，或想快点跳过广告时段。
     * `setPlaybackSpeed` 对 HLS 同样有效。
     */
    fun cycleSpeed() {
        speedIndex = PlaybackTuning.nextSpeedIndex(speedIndex)
        if (!live) graph.settings.rememberSpeedForUp(ownerMid, speedIndex)
        val v = PlaybackTuning.speedOf(speedIndex)
        player.setSpeed(v)
        showNotice(graph.getString(R.string.player_speed_label, PlaybackTuning.formatSpeed(v)))
        AppLog.i("VM", "倍速 -> ${PlaybackTuning.formatSpeed(v)}")
    }

    /**
     * 切下一档画面比例（循环）。
     *
     * 它**不落盘**的理由同倍速：这是"这一条视频"的观看偏好
     * （老 4:3 片源才想拉伸，不看老片的人不希望被记住）。
     * 设置页里的 `aspectMode` 管的是默认值。
     */
    fun setAspect(id: String) { aspectId = PlaybackTuning.aspectOf(id).id }

    fun cycleAspect() {
        aspectId = PlaybackTuning.nextAspectId(aspectId)
        val label = graph.getString(aspectLabelRes(aspectId))
        showNotice(graph.getString(R.string.player_aspect_toast, label))
        AppLog.i("VM", "画面比例 -> $aspectId ($label)")
    }

    /**
     * 自动连播：播完切下一个。
     *
     * 触发点是 `Player.Listener.onPlaybackStateChanged(STATE_ENDED)`，
     * 那是"真的播完了"的唯一可靠信号（别用 `position >= duration` 去猜，
     * 拖到最后几秒也会满足，会提前跳）。
     *
     * ## 三道闸门（缺一不可）
     *
     * 1. **设置开着**（`autoNext`）—— 用户可以在设置页关掉
     * 2. **不是直播** —— 直播没有"播完"这回事，但防御性挡一下
     * 3. 按已知分P/分集/合集/收藏顺序解析目标；最终末尾停留，未知身份不猜首项
     */
    fun continueToNext(manual: Boolean = false) {
        if (!manual && previewActive) {
            finishTouchSeek(cancel = true); endTouchBoost(); player.exo.pause()
            closeSidebar(); previewEnded = true
            AppLog.i("VM", "接口试看片段结束；不自动循环或连播")
            return
        }
        if (!manual && !graph.settings.autoNext) return
        playAdjacent(1, manual)
    }

    fun replayPreview() {
        if (!previewActive || !previewEnded) return
        previewEnded = false
        player.exo.seekTo(0); samplePosition(0); player.exo.play()
    }

    /**
     * 位置采样循环。
     *
     * 250ms 一次**不是"够用"，是故意定得慢**：这个值的三个消费者
     * （进度文字精确到秒 / 广告判断 / 弹幕分段预取）都只需要这个量级。
     *
     * 弹幕要的 60fps 流畅感**不从这里来** —— 见 [danmakuPosition]。
     * 所以也别为了弹幕把这个值调小：那会把上面三件事一并按帧重算，白烧电。
     */
    private fun startTicker(myToken: Int) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (myToken != token) return@launch

                if (resumeChoiceMs != null) continue
                val exo = player.exo
                samplePosition(exo.currentPosition)
                durationMs = top.bilitv.data.model.PlaybackPreview.duration(previewActive, exo.duration, durationHintMs, playInfo?.previewLimitMs)
                if (!live && subtitlesEnabled) subtitleText = subtitleTimeline?.textAt(positionMs).orEmpty()

                tickSponsor()
                tickDanmaku(myToken)

                /*
                 * 定时落盘观看进度。
                 *
                 * 挂在 250ms 的 ticker 上而不是另起一个协程：省一个协程，
                 * 而且这个位置本来就是"每 250ms 更新一次"的，用它计数最自然。
                 * 5 秒一次 —— 比这勤会让 SharedPreferences 一直在写盘，
                 * 比这慢则"看到一半直接关机"会丢得多。
                 */
                sinceSaveMs += TICK_MS
                if (sinceSaveMs >= History.SAVE_INTERVAL_MS) {
                    sinceSaveMs = 0L
                    persistProgress()
                }
            }
        }
    }

    /**
     * 记一次位置采样。低频值给进度文字等用，连续的给弹幕用。
     *
     * ★ **负数一律丢弃**。播放器在过渡态（刚换源 / 还在缓冲 / 时间线未就绪）时
     * `currentPosition` 返回的是 `C.TIME_UNSET` —— 一个极小的负数。
     * 让它进来会一次连坏三件事：进度条显示 `--:--`、弹幕引擎判定"进度突变"整屏清空重排、
     * 广告调度器按一个错误的位置去判断该不该跳过。
     */
    private fun samplePosition(ms: Long) {
        if (ms < 0L) return
        posClock.sample(ms)
        positionMs = ms
    }

    /**
     * 弹幕专用的播放位置 —— **每一帧都会被调用**。
     *
     * 为什么要单独搞一个、为什么不直接把 ticker 提到 60Hz，见 [PositionClock] 的说明。
     * 一句话：`positionMs` 250ms 才动一次，拿它画 60fps 的弹幕就是"卡顿"的根。
     */
    fun danmakuPosition(): Long = posClock.read(playing)

    private fun tickSponsor() {
        if (live || !playing || buffering || previewEnded || resumeChoiceMs != null) return
        when (val decision = planner.onPosition(positionMs, durationMs, SystemClock.elapsedRealtime())) {
            is SkipPlanner.Decision.Armed ->
                skipHint = graph.getString(R.string.player_skip_hint, graph.getString(decision.segment.category.labelRes()))

            is SkipPlanner.Decision.Skip -> {
                skipHint = null
                player.exo.seekTo(decision.toMs)
            }

            else -> if (planner.pendingSegment == null) skipHint = null
        }
    }

    /** 分段预取：当前段 + 下一段。B 站本来就是 6 分钟一段，提前拿到才不断档 */
    private fun tickDanmaku(myToken: Int) {
        // 直播弹幕走 WebSocket，没有"分段"这回事，而且 cid 是 0（请求会打到一个不存在的段）
        if (live) return
        val current = danmakuSegmentIndex(positionMs)
        loadSegment(current, myToken)
        loadSegment(current + 1, myToken)
    }

    private fun loadSegment(index: Int, myToken: Int) {
        if (!graph.settings.danmakuEnabled) return
        if (index < 1) return
        if (index > danmakuSegmentCount(durationHintMs)) return
        if (!loadedSegments.add(index)) return

        viewModelScope.launch {
            val list = graph.api.danmakuSegment(cid, index)
            if (myToken != token) return@launch
            if (list.isEmpty()) {
                // 空段不重试，但把预取标记留着，避免反复打接口
                return@launch
            }
            danmakuMergeMutex.withLock {
                val merged = withContext(Dispatchers.Default) { mergeDanmaku(rawDanmaku, list) }
                if (myToken != token) return@withLock
                rawDanmaku = merged
                applyDanmakuFilter(myToken)
            }
            // 排查弹幕问题时第一条要看的数据：到底拿到了多少条
            AppLog.i("Danmaku", "第 $index 段 +${list.size} 条，累计 ${danmaku.size} 条")
        }
    }

    private val danmakuMergeMutex = Mutex()

    private fun refreshSkipSegments(force: Boolean = false) {
        val settings = graph.settings
        val policy = Triple(settings.skipOfficialIntroOutro, settings.sponsorEnabled, settings.sponsorCategories)
        if (!force && policy == skipPolicy) return
        skipPolicy = policy
        if (!policy.second) {
            sponsorJob?.cancel(); sponsorJob = null; sponsorRequested = false
        }
        val clips = if (policy.first && !live && epId > 0) playInfo?.officialClips.orEmpty() else emptyList()
        val official = clips.filter { it.endMs <= durationHintMs }.map { clip ->
            top.bilitv.data.sponsor.SponsorSegment(clip.startMs, clip.endMs,
                if (clip.intro) top.bilitv.data.sponsor.SponsorCategory.INTRO else top.bilitv.data.sponsor.SponsorCategory.OUTRO,
                "skip", "official:${clip.intro}:${clip.startMs}-${clip.endMs}", cid)
        }
        val community = if (policy.second) communitySkipSegments.filter { it.category in policy.third } else emptyList()
        val segments = official + community
        planner.setSegments(segments, segments.map { it.category }.toSet())
        skipHint = null
        if (renderedFirstFrame) loadSponsor()
    }

    private fun loadSponsor() {
        // Official PGC markers already come with the play response; only UGC queries the community service.
        if (live || epId > 0 || bvid.isBlank() || sponsorRequested || cid == 0L || !graph.settings.sponsorEnabled) return
        sponsorRequested = true

        val myToken = token
        sponsorJob = viewModelScope.launch {
            val segments = graph.sponsor.fetchSegments(bvid, cid)
            coroutineContext.ensureActive()
            if (myToken != token) return@launch
            communitySkipSegments = segments
            refreshSkipSegments(force = true)
        }
    }

    override fun onCleared() {
        stopSkipSettingsObserver()
        sponsorJob?.cancel()
        loadJob?.cancel()
        subtitleMetadataJob?.cancel(); subtitleJob?.cancel()
        ticker?.cancel()
        liveDmJob?.cancel()
        /*
         * 最后再落一次盘。
         *
         * `DisposableEffect` 的 `onDispose` 已经会落一次，但那是在"离开播放页"时；
         * 而 `onCleared` 覆盖的是"整个 Activity 被系统收走 / 用户直接杀进程"之前
         * 的最后一次机会。两处都写，成本是一次很小的字符串比较（`persistProgress`
         * 里 `pos == lastSavedMs` 会直接返回）。
         */
        runCatching { persistProgress(force = true) }
        player.release()
    }

    private companion object {
        const val TICK_MS = 250L

        /**
         * 换编码重播前等这么久。
         *
         * 理由和 `BiliPlayer.RETRY_DELAY_MS` 一样：绝不在失败还没收尾时动播放器。
         * 这里比那边稍长一点，因为中间还夹了一次 `stop()`。
         */
        const val FALLBACK_DELAY_MS = 300L

        /** 一次性提示在屏幕上停留多久 */
        const val NOTICE_MS = 3_500L
    }
}
