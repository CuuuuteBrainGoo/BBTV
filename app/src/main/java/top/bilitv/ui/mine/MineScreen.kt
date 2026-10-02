package top.bilitv.ui.mine

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import top.bilitv.BiliTvApp
import top.bilitv.data.model.MyProfile
import top.bilitv.data.model.MyProfileResult
import top.bilitv.data.model.MyStat
import top.bilitv.ui.components.FilledActionButton
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.fixedScheme
import top.bilitv.ui.components.formatCount
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.util.AppLog
import top.bilitv.ui.components.focusRing
import top.bilitv.ui.components.RailIcons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.Coil
import androidx.compose.ui.graphics.Color

/**
 * 我的 —— 账号页。
 *
 * ```
 * 我的
 * 账号与资料
 * ────────────────────────────────────────────────
 *            ◯  （大头像）
 *            昵称          ← 只在接口真的给了才显示
 *            UID 12345
 *       关注 128   粉丝 56   动态 12      ← 取不到显示 --
 *
 *            [ 查看我的投稿 ]              ← 焦点落点
 * ```
 *
 * ## 和 BT 的「我的」比，这一页**故意少了那些入口**
 *
 * BT 的「我的」是一屏"什么都能进的入口列表"（历史 / 收藏 / 关注 / 设置…）。
 * 我们这个 App 把它们全放在**常驻左侧栏**里了，再在这一页列一遍会变成
 * **同一件事的第二个真相源** —— 以后加一个入口就要改两处，迟早漏一处。
 *
 * 所以这一页只放**侧栏里没有的**东西：账号本身（我是谁、有多少关注/粉丝）
 * 和「看我的投稿」（那是二级页面，侧栏进不去）。
 *
 * ## ⚠️ 不显示任何设备信息
 *
 * 少爷第 40 条第 2 点：「把这个 app 里我现在能看到的所有的设备参数给去掉」。
 * 这一页是最容易顺手把"机型 / 分辨率 / 解码器"列出来的地方，所以特意写在这里：
 * **一个都不放**。要看设备信息去设置页「存储与诊断」，那是他自己翻的地方。
 *
 * ## 三种状态，三句话（和关注页同一套规矩）
 *
 * | 情况 | 判据 | 说什么 | 按钮 |
 * |---|---|---|---|
 * | 没登录 | 本机没有凭证（**直接不发请求**） | 还没登录 | 「扫码登录」 |
 * | 凭证过期 | 本机有凭证，但服务端 `isLogin=false` | 登录已过期 | 「重新登录」 |
 * | 拿不到 | 接口结构变了 / 被风控 | 拿不到账号信息 | 「重新加载」 |
 *
 * 中间那一行是这一页独有的：`nav` 未登录时**既报 -101 又给一份 data**，
 * 所以"本机有凭证但服务端不认"这件事是**可判别的**，不必和"还没登录"混成一句。
 *
 * @param onNeedLogin 去扫码登录。
 * @param onOpenUp 打开我的主页（投稿列表，二级页面）。三个参数一起带过去 ——
 *   昵称和头像本来就在手上了，没必要让主页再问一次接口。
 */
@Composable
fun MineScreen(
    onNeedLogin: () -> Unit,
    onOpenUp: (Long, String, String) -> Unit,
    /**
     * 打开「关注」列表。
     *
     * ★ 2026-09-29：`关注` 从**侧栏一级项**搬到这一页（少爷要求）。
     * 它是二级页，进去后按返回回到这里。
     */
    onOpenFollow: () -> Unit,
    /** 打开「收藏」（二级页）。少爷要求它和「关注」一样放在这一页。 */
    onOpenFav: () -> Unit,
) {
    val vm: MineViewModel = viewModel()
    val theme = AppTheme.current

    // 侧栏切走再切回会重新进组合。带闸门，理由同 [FollowViewModel.load]。
    LaunchedEffect(Unit) { vm.load() }

    val action = remember { FocusRequester() }
    val profile = vm.profile

    // 退出确认弹窗（少爷反馈 3：「弹出确认弹窗，确认后清掉账号登陆信息」）
    var confirmLogout by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        MineHeader()

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                vm.state == MineState.LOADING ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                profile == null -> MineNotice(
                    state = vm.state,
                    onAction = when (vm.state) {
                        MineState.EXPIRED -> onNeedLogin
                        MineState.ERROR -> { { vm.reload() } }
                        else -> onNeedLogin
                    },
                    actionLabel = when (vm.state) {
                        MineState.EXPIRED -> "重新登录"
                        MineState.ERROR -> "重新加载"
                        else -> "扫码登录"
                    },
                    requester = action,
                )

                else -> MineProfileBody(
                    profile = profile,
                    stat = vm.stat,
                    onOpenUp = { onOpenUp(profile.mid, profile.name, profile.face) },
                    onLogout = { confirmLogout = true },
                    onOpenFollow = onOpenFollow,
                    onOpenFav = onOpenFav,
                    actionRequester = action,
                )
            }

            /*
             * ★ 2026-09-30 少爷反馈 3：
             * 「为什么要做"切换账号"？我觉得应该做成"退出登陆并清除缓存"就可以了，
             *  弹出确认弹窗，确认后清掉这个 APP 的账号登陆信息。」
             *
             * → 去掉"切换账号"这个说法；点按钮只**弹窗**，确认后才真清。
             *   挂在 Box 里才能盖住整页（页根是 Column，弹层放 Column 里只会被挤到下面）。
             */
            if (confirmLogout) {
                LogoutConfirmDialog(
                    onCancel = { confirmLogout = false },
                    onConfirm = {
                        confirmLogout = false
                        vm.logoutAndClear()
                    },
                )
            }
        }
    }

    /*
     * 焦点落点。`key = state`，因为**按钮是随状态换的**：
     * 从"扫码登录"变成"查看我的投稿"之后要重新请求一次，否则焦点还挂在
     * 那个已经被组合掉的旧按钮上（表现就是"按方向键没反应"）。
     *
     * LOADING 时传 null 跳过 —— 那一刻屏幕上还没有可落焦点的东西，
     * 请求它只会刷 30 条没意义的失败日志（见 [RequestFocusOnAppear] 的说明）。
     */
    RequestFocusOnAppear(action, if (vm.state == MineState.LOADING) null else vm.state)
}

@Composable
private fun MineHeader() {
    val theme = AppTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = theme.screenPadding,
                end = theme.screenPadding,
                top = 8.dp,
                bottom = 12.dp,
            ),
    ) {
        Text(
            text = "我的",
            style = TextStyle(fontSize = AppType.H1, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        Text(
            text = "账号与资料（观看历史、关注、设置在左边栏）",
            style = TextStyle(fontSize = AppType.Caption),
            color = theme.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 已登录时的正文：头像 + 昵称 + UID + 三个计数 + 一个动作。
 *
 * 布局用居中的一列，不用卡片网格 —— 这一页只有一个"对象"（我），
 * 网格是给"很多个同类东西"用的。
 */
@Composable
private fun MineProfileBody(
    profile: MyProfile,
    stat: MyStat?,
    onOpenUp: () -> Unit,
    onOpenFollow: () -> Unit,
    onOpenFav: () -> Unit,
    onLogout: () -> Unit,
    actionRequester: FocusRequester,
) {
    val theme = AppTheme.current
    val context = LocalContext.current

    Column(
        /*
         * ★ 2026-09-30 少爷（截图批注）：箭头指着头像区写「**移到左边**」。
         *
         * 原来是 `CenterHorizontally` + 左右各 140dp 内衬 = 整块**水平居中**，
         * 大屏上左右各空一大片，头像像浮在正中间。
         * 现在改成**靠左**（Start）、左边留一档内衬 —— 和别的一级页一样从左起排。
         *
         * 竖向仍然 `Center`：这一页内容不高（头像+资料+三个数字+四个入口），
         * 居中的话视线落点更稳；改成 Top 反而会让整块贴死在标题下面。
         */
        modifier = Modifier
            .fillMaxSize()
            .padding(start = theme.screenPadding + 48.dp, end = theme.screenPadding),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(profile.face.fixedScheme())
                // 按实际显示尺寸解码，绝不原图解码（低内存的生命线）
                .size(360, 360)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(AVATAR_SIZE)
                .clip(CircleShape)
                .background(theme.surfaceHigh),
        )

        Spacer(Modifier.height(16.dp))

        /*
         * 昵称。接口没给时**不留空也不编一个**，改说一句实话。
         * 显示"用户12345"这种编号看起来像真名，比空着更糟。
         */
        Text(
            text = profile.name.ifBlank { "已登录的账号" },
            style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
            maxLines = 1,
        )

        if (profile.mid > 0L || profile.level > 0 || profile.vip) {
            Text(
                text = buildString {
                    if (profile.mid > 0L) append("UID ${profile.mid}")
                    if (profile.level > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("LV${profile.level}")
                    }
                    if (profile.vip) {
                        if (isNotEmpty()) append(" · ")
                        append("大会员")
                    }
                },
                style = TextStyle(fontSize = AppType.Caption),
                color = theme.textTertiary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(22.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(52.dp)) {
            StatCell("关注", stat?.following)
            StatCell("粉丝", stat?.follower)
            /*
             * ★ 2026-09-30 少爷（截图批注原话）：
             * 「**不要抓动态这个字段了，抓用户硬币数量**」。
             * 所以第三格由「动态」改成「硬币」（数据来自 `nav` 的 `money`）。
             */
            StatCell("硬币", profile.coins.toLong())
        }

        /*
         * ★ 「我的内容」入口区。
         *
         * 2026-09-29 少爷要求：「`关注` 不要做在侧边栏，做到 `我的` 页面里」。
         * 所以侧栏少了这一项，改从这里进 —— 外层 Column 是水平居中的，
         * 所以这里用一个**包内容的圆角块**，不铺满整行，视觉上才和上面那几行对得齐。
         *
         * ★ 「收藏」也在这条要求里，**已经做了**（`397ac2c`：`FavScreen` + `FavViewModel`，
         * 路由见 `Nav.kt` 的 `Screen.Fav`），所以这里两个入口都在。
         *
         * （原来这里写的是"本轮没有做、不放入口"—— 那时确实没做，页面交付后这句就陈旧了，
         *  2026-09-30 自检时改正。留一句说明，免得下次有人照着旧注释把入口删掉。）
         */
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MineEntry(icon = RailIcons.Follow, label = "关注", onClick = onOpenFollow)
            MineEntry(icon = RailIcons.Star, label = "收藏", onClick = onOpenFav)

        }

        /*
         * ★ 2026-09-30 少爷反馈 2：
         * 「不要"查看我的投稿"这个交互按键和功能了；把"切换账号/退出登陆"这个交互按钮
         *  放到现在"查看我的投稿"的位置，并且变成粉色」。
         *
         * → 位置照搬（这颗 `FilledActionButton` 本来就是粉色的，那就是他要的粉色）；
         *   点击**不直接退**，只打开确认弹窗（反馈 3）。
         */
        FilledActionButton(
            text = "退出登录并清除缓存",
            onClick = onLogout,
            modifier = Modifier
                .focusRequester(actionRequester)
                .padding(top = 30.dp),
        )
    }
}

/**
 * 「我的」页里的一个入口块（图标 + 文字）。
 *
 * 为什么是"包内容的圆角块"而不是整行：这一页的排版是**水平居中**的，
 * 整行会把它顶到左边缘，和上面的头像/昵称/计数格格不入。
 */
@Composable
private fun MineEntry(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val theme = AppTheme.current
    Row(
        modifier = Modifier
            .focusRing(
                contentDescription = label,
                shape = RoundedCornerShape(theme.cardCorner),
                restFill = theme.surface,
                elevateOnFocus = true,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,   // 文字已经在旁边，别再让读屏念两遍
            tint = theme.textPrimary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = TextStyle(fontSize = AppType.Body3),
            color = theme.textPrimary,
        )
    }
}

/**
 * 一个计数格子。
 *
 * ★ **`null` 显示 `--`，不显示 `0`。**
 * 这一项取不到时（接口没给这个字段 / 那次请求失败）写 0 就是我们自己编的假数据，
 * 而"关注 0 人"对用户来说是个**具体结论**，会让他以为自己关注列表被清空了。
 */
@Composable
private fun StatCell(label: String, value: Long?) {
    val theme = AppTheme.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = statText(value),
            style = TextStyle(fontSize = AppType.H3, fontWeight = FontWeight.SemiBold),
            color = if (value == null) theme.textTertiary else theme.textPrimary,
        )
        Text(
            text = label,
            style = TextStyle(fontSize = AppType.Caption),
            color = theme.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 计数格子上的文字。
 *
 * 抽成纯函数**就是为了能单测**：这一行的 `?:` 一旦写成 `?: 0L`，
 * 屏幕上就会出现"关注 0"这种**我们自己编的结论**，而它编译过、运行不报错、
 * 界面上看着也完全正常（直播页的"未开播"角标就是这么骗了 30 张卡）。
 */
internal fun statText(value: Long?): String = value?.let { formatCount(it) } ?: "--"

/**
 * 「不方便显示正文」时的说明。
 *
 * 三句话对应三种原因，因为用户能做的事完全不同（见 [MineScreen] 的表格）。
 * 合成一句"加载失败"的话，用户唯一能做的就是反复重试 —— 那正是这个项目
 * 一直在治的病（`docs/15` §2.1）。
 */
@Composable
private fun MineNotice(
    state: MineState,
    actionLabel: String,
    onAction: () -> Unit,
    requester: FocusRequester,
) {
    val theme = AppTheme.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 未登录时也要有一个"头像位"。空着一块会让这一页看着像没加载完
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE)
                .clip(CircleShape)
                .background(theme.surfaceHigh)
                .border(1.dp, theme.divider, CircleShape),
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = when (state) {
                MineState.NEED_LOGIN -> "还没登录"
                MineState.EXPIRED -> "登录已过期"
                MineState.ERROR -> "拿不到账号信息"
                // 不该走到这里（调用方只在 profile 为空且不在加载中时才渲染这一屏）。
                // 留一句话而不是留空白：万一日后哪里改错了，屏幕上至少有一行字能指认现场。
                MineState.LOADING, MineState.READY -> "状态异常，回到侧栏再进一次这一页"
            },
            style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        Text(
            text = when (state) {
                MineState.NEED_LOGIN ->
                    "登录后才能看番剧/影视、1080P 以上画质、观看记录与关注。扫码登录，电视上不用打字。"
                MineState.EXPIRED ->
                    "这台设备上存的登录凭证服务端已经不认了（改了密码、或者在别处退过登录都会这样）。重新扫一次码就行。"
                MineState.ERROR ->
                    "接口可能变了或网络不通。已经登录了还是这样，就是服务端的问题，看日志能有线索。"
                MineState.LOADING, MineState.READY ->
                    "这一屏本来不该出现，麻烦把日志发我。"
            },
            style = TextStyle(fontSize = AppType.Body3, lineHeight = 22.sp),
            color = theme.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )

        /*
         * ★ 这个按钮不只是"方便"，它是**这一屏唯一的焦点落点**。
         * `AppShell` 把「我的」标成"自己管焦点"，侧栏不会代收 ——
         * 没有它，这一屏上按方向键毫无反应（历史页踩过一次，见 docs/11）。
         */
        FilledActionButton(
            text = actionLabel,
            onClick = onAction,
            modifier = Modifier
                .focusRequester(requester)
                .padding(top = 24.dp),
        )
    }
}

/** 头像直径。 */
private val AVATAR_SIZE = 120.dp

/** 「我的」页的四种状态。决定"没有正文时该说什么话、给什么按钮"。 */
enum class MineState { LOADING, NEED_LOGIN, EXPIRED, ERROR, READY }

/**
 * 「我的」页数据。
 *
 * ## 判据的顺序很要紧：**先看本机，再看服务端**
 *
 * 1. **本机没有凭证 → 直接下结论，一个请求都不发。**
 *    发了也是 `-101`，白等一个网络往返，日志里还多一条没信息量的错误。
 * 2. **本机有凭证，服务端 `isLogin=false` → 凭证过期。**
 *    这就是 [MineState.EXPIRED] 存在的理由：它和"还没登录"给用户的
 *    下一步动作其实一样（重新扫码），但**说的话不一样** ——
 *    一个人明明登录过，你告诉他"还没登录"，他会觉得这 App 记不住事。
 * 3. 拿不到结构 → [MineState.ERROR]。
 */
class MineViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp

    var profile by mutableStateOf<MyProfile?>(null)
        private set
    var stat by mutableStateOf<MyStat?>(null)
        private set
    var state by mutableStateOf(MineState.LOADING)
        private set

    private var inFlight = false

    /** 进页面时调用。带"已经取到过就别重取"的闸，但登录态变过就必须重取。 */
    fun load() {
        if (state == MineState.READY && graph.api.isLoggedIn()) return
        fetch()
    }

    /** 「重新加载」/「重新登录」后回来时用。**绕过闸门**，否则按了没反应。 */
    /**
     * 退出登录（少爷 2026-09-30 反馈 5：账号管理统一收到「我的」页）。
     *
     * 退出必须**真的清凭证**，然后重新拉一次 —— 让页面自己回落到 NEU_LOGIN 态。
     * 只把界面上的字改掉而凭证还在，是最危险的一种假动作。
     */
    /**
     * 退出登录 + 清缓存（少爷 2026-09-30 反馈 3：
     * 「应该做成"退出登陆并清除缓存"就可以了，弹出确认弹窗，
     *  确认后清掉这个 APP 的账号登陆信息」）。
     *
     * 两件事都要真的做，不能只改界面：
     * 1. `api.logout()` —— 清登录凭证（只改字不清凭证是最危险的假动作）
     * 2. 清 Coil 的内存 + 磁盘图片缓存（这就是"清除缓存"的实指）
     *
     * 清缓存失败**不影响退出**，所以包了 runCatching 只记日志 ——
     * 一个缓存目录删不掉，不该让用户退不出去。
     */
    fun logoutAndClear() {
        graph.api.logout()
        runCatching {
            val loader = coil.Coil.imageLoader(getApplication())
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }.onFailure { AppLog.w("Mine", "清图片缓存失败：${it.javaClass.simpleName}") }
        reload()
    }

    fun reload() {
        inFlight = false
        fetch()
    }

    private fun fetch() {
        if (inFlight) return
        inFlight = true

        /*
         * 未登录：**同步**给出结论。
         * 放进协程里会先闪一下转圈再变成"还没登录"，看着像刚失败。
         */
        if (!graph.api.isLoggedIn()) {
            profile = null
            stat = null
            state = MineState.NEED_LOGIN
            inFlight = false
            AppLog.i("Mine", "未登录，不发任何请求")
            return
        }

        state = MineState.LOADING
        viewModelScope.launch {
            when (val r = graph.api.myProfile()) {
                is MyProfileResult.Ok -> {
                    profile = r.profile
                    // 计数是**第二条**请求：它挂了不该连累整页（上面已经有人了），
                    // 所以失败就让它三个格子显示 --
                    stat = graph.api.myStat()
                    state = MineState.READY
                    AppLog.i(
                        "Mine",
                        "资料：${r.profile.name.ifBlank { "(无昵称)" }} uid=${r.profile.mid} " +
                            "关注=${stat?.following} 粉丝=${stat?.follower}",
                    )
                }

                MyProfileResult.NotLoggedIn -> {
                    profile = null
                    stat = null
                    state = MineState.EXPIRED
                    AppLog.w("Mine", "本机有凭证，但服务端说没登录（凭证过期？）")
                }

                MyProfileResult.Unsupported -> {
                    profile = null
                    stat = null
                    state = MineState.ERROR
                    AppLog.w("Mine", "账号资料拿不到（接口结构变了或被风控）")
                }
            }
            inFlight = false
        }
    }
}


/**
 * 「退出登录并清除缓存」的确认弹窗（少爷 2026-09-30 反馈 3）。
 *
 * ## 两条规矩
 *
 * 1. **默认焦点在「取消」上** —— 破坏性操作的默认选项必须是**不做**。
 *    遥控器上误按一次 OK 就直接确认，是少爷今天已经踩过的坑
 *    （原话：「怎么我摸了一下就把账号退掉了」）。
 * 2. 文案要**说清后果**：清的是登录凭证和图片缓存；收藏/关注/历史在账号里，不会丢。
 *    不说清，用户不敢按 —— 或者按完才发现要重新扫码，那更糟。
 */
@Composable
private fun LogoutConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val theme = AppTheme.current
    val cancelRequester = remember { FocusRequester() }
    RequestFocusOnAppear(cancelRequester, "logout-confirm")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(theme.surfaceHigh)
                .padding(horizontal = 40.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "退出登录并清除缓存？",
                style = TextStyle(fontSize = AppType.H3, fontWeight = FontWeight.SemiBold),
                color = theme.textPrimary,
            )
            Text(
                text = "会清掉本机的登录凭证和图片缓存，下次要重新扫码登录。\n" +
                        "收藏、关注、历史记录都存在账号里，不会丢。",
                style = TextStyle(fontSize = AppType.Body3),
                color = theme.textSecondary,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // ★ 默认焦点给「取消」—— 破坏性操作的默认选项是不做
                FilledActionButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.focusRequester(cancelRequester),
                )
                FilledActionButton(text = "退出并清除", onClick = onConfirm)
            }
        }
    }
}
