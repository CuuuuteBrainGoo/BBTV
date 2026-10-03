package top.bilitv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import top.bilitv.ui.theme.AppTheme
import top.bilitv.util.AppLog
import top.bilitv.data.settings.StartupFocus

/**
 * TV 可聚焦容器。
 *
 * 遥控器用户"看不见焦点"是电视应用最致命的交互问题（`docs/03` §2.4），
 * 所以焦点态必须同时给三个信号：**放大 + 描边 + 抬到最上层**，少一个都不明显。
 *
 * 用 `Modifier.clickable` 而不是 `focusable` —— 它自带焦点支持，按遥控器确定键即触发 click，
 * 少写一层手动的按键处理。
 *
 * **触摸反馈**：`clickable` 本身就能响应手指点击，所以手机上也能操作；
 * 但我们关掉了默认涟漪（`indication = null`），不补一个按下态的话，
 * 手指点下去屏幕上毫无变化，会被当成"点了没反应"。所以再加一个 `pressed → 缩小 + 亮边`。
 *
 * ## 颜色/形状/缩放全部来自当前皮肤
 *
 * 三个外观参数的默认值是 `null`，进函数体才去读 [AppTheme].current。
 * 不能写成默认参数直接赋值 —— Composable 的读取只能发生在 @Composable 函数体内。
 *
 * ⚠️ **网格/横滑行里的卡片不要传 `focusedScale`**：焦点缩放是皮肤的职责
 * （经典皮肤卡片小、间距窄，1.06 会让相邻卡片撞在一起，它必须用 1.04）。
 *
 * 但**孤立的按钮/控件可以传** —— 小控件需要更夸张的放大才看得出焦点，
 * 而且它旁边没有邻居可撞。播放器控制条上的按钮就是这种情况（用 1.1f）。
 *
 * @param contentDescription 语义名，给读屏和排查用。
 *   2026-09-28 加上：`ui_probe tree` 里我们的卡片一律显示 `(无文本)`，
 *   排查时少一条线索；而对面的 mytvb 兜底节点叫 `Show player controls`、
 *   chinasoul.bt 的 Tab 叫 `For You` —— 人家都给了。
 * @param onFocused 焦点变化的回调。用来记"上次焦点在哪"，
 *   以便从详情页返回时把焦点放回原处（见 `HomeScreen`）。
 */
@Composable
fun TvCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    background: Color? = null,
    focusedScale: Float? = null,
    contentDescription: String? = null,
    onFocused: ((Boolean) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val theme = AppTheme.current
    val cardShape = shape ?: RoundedCornerShape(theme.cardCorner)
    val cardBackground = background ?: theme.surface
    val scaleTarget = focusedScale ?: theme.focusScale

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    /*
     * ★ 焦点态走 `onFocusChanged`，**不用** `interaction.collectIsFocusedAsState()`。
     *
     * 2026-09-28 实测到的一个真缺陷：从播放页返回详情页后，
     * uiautomator 明确报 `focused=true bounds=[992,335][1260,449]`（就是「播放」按钮），
     * 但蓝色描边**没画出来** —— 焦点是真的，`interactionSource` 却没收到焦点事件。
     *
     * 后果比"焦点丢了"更阴险：探针说一切正常，用户看见的是一屏没有任何高亮，
     * 在电视上等同于"这 App 卡死了"。
     *
     * `onFocusChanged` 是焦点系统**直接回调**，不经过 interactionSource 这条
     * 间接链路，少一层可能不同步的地方。按下态仍走 interactionSource（那是它擅长的）。
     *
     * 位置放在**链首**：`onFocusChanged` 观察的是"我所在节点及其子树里有没有焦点"，
     * 放最外层能看到整棵子树（含外传进来的 focusRequester 和 clickable 内部的 focusable）。
     */
    var focused by remember { mutableStateOf(false) }


    val scaleValue = when { focused -> scaleTarget; pressed -> PRESSED_SCALE; else -> 1f }
    val scale = if (theme.animations) animateFloatAsState(scaleValue,
        animationSpec = spring(dampingRatio = .7f, stiffness = 700f), label = "tvCardScale").value else scaleValue
    val borderValue = when { focused -> theme.focusRing; pressed -> theme.focusRing.copy(alpha = .7f); else -> theme.divider }
    val border = if (theme.animations) animateColorAsState(borderValue, label = "tvCardBorder").value else borderValue
    val fillValue = if (focused) theme.focusSurface else cardBackground
    val fill = if (theme.animations) animateColorAsState(fillValue, label = "tvCardFill").value else fillValue

    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                onFocused?.invoke(it.isFocused)
            }
            // 放大后需要盖住邻居，否则会被相邻卡片裁掉
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(cardShape)
            .background(fill)
            .border(if (focused) theme.focusBorderWidth else 1.dp, border, cardShape)
            .semanticLabel(contentDescription)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        content = content,
    )
}

/**
 * 有 [label] 就挂上语义名，没有就返回原修饰符。
 *
 * 单独抽出来是因为在 `semantics { contentDescription = ... }` 里，
 * 右值会被解析成 `SemanticsPropertyReceiver` 自己的属性（自赋值），
 * 必须借一个中间变量把外层参数"抢"出来。
 *
 * `mergeDescendants = true` 是必须的：默认的 `semantics {}` 会生成一个
 * **独立的子语义节点**，`contentDescription` 落在它身上，可聚焦/可点那个节点
 * 依然是空的 —— `ui_probe tree` 便什么都读不到（2026-09-28 实测）。
 */
private fun Modifier.semanticLabel(label: String?): Modifier {
    if (label == null) return this
    val text = label
    return this.semantics(mergeDescendants = true) { contentDescription = text }
}

/** 手指按下时的缩小比例：比焦点放大更"缩"，一按就有触感上的回应 */
private const val PRESSED_SCALE = 0.95f

/**
 * 一级导航持有焦点时，异步刷新和重试不得把焦点移入内容区。
 * 直接依据实际焦点状态，不依赖网络耗时，也不在第一次请求后解除保护。
 */
class NavigationFocus(initialFocus: StartupFocus? = null) {
    var pendingStartup by mutableStateOf(initialFocus)
    var confirmTabs by mutableStateOf(true)
    var contentTarget by mutableStateOf<FocusRequester?>(null)
    var rightToCards by mutableStateOf(false)
    var railFocused by mutableStateOf(false)
    var tabsFocused by mutableStateOf(false)
    val blocksAutoFocus: Boolean get() = pendingStartup != null || railFocused || tabsFocused
    fun allowsRequest(role: StartupFocus): Boolean =
        pendingStartup?.let { it == role } ?: !blocksAutoFocus
}

/** 仅一级外壳内共享，详情／播放器的新页面不继承旧导航状态。 */
val LocalNavigationFocus = staticCompositionLocalOf<NavigationFocus?> { null }

/**
 * 内容出现后，把焦点自动送到 [requester] 上。
 *
 * ## 为什么必须有这个
 *
 * 2026-09-28 在真实电视上实测发现：**每个页面进来时都没有焦点**，
 * 必须先用遥控器按一下方向键，焦点才会出现在第一张卡片/按钮上。
 *
 * 这在电视上是致命的 —— 用户看到一屏内容但没有任何高亮，
 * 第一反应是"App 卡死了"，而不是"我该按一下方向键"。
 * 电脑上用鼠标点击测是**测不出来的**（鼠标点哪儿哪儿有反应）。
 *
 * 根因：我们的页面栈是 `when (stack.last())`（`Nav.kt`），切页会把上一个
 * 页面**整个销毁**，返回时焦点状态全丢；而新页面第一次组合时没人给焦点。
 *
 * ## 为什么要等一帧
 *
 * 内容刚 compose 出来时节点还没 attach 到窗口，这时 `requestFocus()`
 * 会抛 `IllegalStateException`。`withFrameNanos { }` 等一帧就稳了。
 *
 * ## ★ 为什么等一帧还不够（2026-09-29 实测修正）
 *
 * 模拟器冷启动复现：**等一帧之后 requestFocus() 仍然没让界面出现高亮**，
 * 按一下方向键才有。也就是说这一次请求实际上没落地，而它**既没抛异常、
 * 也没返回值可以查**（`requestFocus()` 返回 `Unit`）—— 失败完全静默。
 * 原来那句"`requestFocus()` 失败会返回 false"是**错的**，它只在
 * requester 没 attach 时抛异常，其余情况一律无声。
 *
 * 所以不能再靠"一次请求 + 看有没有抛异常"。改成两件事：
 *
 * 1. **等窗口拿到焦点再请求**。冷启动时 Activity 窗口自己还没拿到焦点，
 *    这时候往里面塞焦点请求会被悄悄丢掉。把 `windowInfo.isWindowFocused`
 *    也放进 key：窗口一拿到焦点，effect 会再跑一次，这次才真正生效。
 * 2. **连试若干帧**（[attempts] 次，一帧 + [RETRY_GAP_MS] 一次的节奏）。
 *    "数据到位"和"列表测量完、item 节点 attach"不是同一时刻，
 *    单次请求会踩在空档上（踩中就是抛异常，我们靠重试糊过去）。
 *
 * ## ★ 为什么重试预算要给到 3 秒（2026-09-29 冷启动实测修正）
 *
 * 日志留下了确凿证据：
 *
 * ```
 * 18:19:59.757 I/Home: 推荐 12 条          ← 数据到位
 * 18:20:00.865 W/Focus: 请求焦点失败（连试 10 次，key=true）
 * ```
 *
 * 连试 10 次**全部抛异常**，也就是那 0.5 秒里卡片节点**根本没挂到窗口上**。
 * （`requestFocus()` 只在 requester 未 attach 时抛异常；attached 但拿不到焦点
 * 是静默的 —— 见上面的注释。所以"抛异常"明确等于"还没挂上"。）
 *
 * 冷启动时窗口拿到焦点本身就晚了 1 秒多（数据 59.7s 到，重试 60.4s 才开始），
 * 而首页要等网格测量完、第一项组合出来才有可落焦点的节点。
 * 原来 10 次 × 50ms ≈ 0.5 秒的预算，正好卡在这个空档上。
 *
 * 所以预算放宽到 **30 次 × 100ms ≈ 3 秒**。
 * 代价只在"一直失败"时才会付 —— 一旦成功立刻返回，正常情况还是第 1 次就中。
 * 副作用很小：用户极少会在冷启动的头 3 秒里按遥控器，而那时屏幕上还在加载封面。
 *
 * @param key 传"内容是否就绪"。数据是异步来的（走了 false → true），
 *   只有把它当 key，才会在数据到位后重新请求一次焦点。
 *   `null` / `false` 直接跳过 —— 那两种状态本来就没有可落焦点的目标，
 *   请求它们只会刷一堆没意义的失败日志。
 */
@Composable
fun RequestFocusOnAppear(requester: FocusRequester, key: Any?, attempts: Int = 30,
    role: StartupFocus = StartupFocus.CONTENT) {
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val view = LocalView.current
    val navigation = LocalNavigationFocus.current

    LaunchedEffect(key, windowFocused) {
        if (key == null || key == false) return@LaunchedEffect
        if (!windowFocused) return@LaunchedEffect
        /*
         * ⛔ 2026-09-30 撤回「触摸模式下跳过抢焦点」这条。
         *
         * 它的本意是"手机上别凭空冒出一个焦点框"，但实测发现**副作用更大**：
         * 模拟器也是触摸模式（用鼠标点），而模拟器是我们**验收电视 UI 的唯一手段**
         * —— 于是进页面一个焦点框都不显示，看着像"焦点坏了"。
         * 日志原文：`I/Focus: 触摸模式，跳过自动抢焦点（key=0-0）`。
         *
         * 正确的分工是：
         * - **进页面照常抢焦点**（这条对所有设备一致，也是电视要的行为）
         * - **手指点完之后清掉焦点** —— 那才是少爷真正的诉求
         *   （原话是「手机上**点完卡片**不需要焦点框了」），由 [touchClearFocus] 负责。
         */
        repeat(attempts) {
            withFrameNanos { }
            // 异步数据与重试都不能抢走用户已经放在侧栏／标签上的焦点。
            if (navigation?.allowsRequest(role) == false) return@LaunchedEffect
            val reached = runCatching {
                // clickable 在触摸模式下不能接受方向键焦点；由原生接口切入键盘模式再请求。
                if (view.isInTouchMode) view.requestFocusFromTouch()
                requester.requestFocus()
                // 该版本 requestFocus 返回 Unit；捕获／释放确认目标实际拿到了焦点。
                requester.captureFocus().also { if (it) requester.freeFocus() }
            }.getOrDefault(false)
            if (reached) {
                if (navigation?.pendingStartup == role) navigation.pendingStartup = null
                AppLog.i("Focus", "请求焦点（key=$key，第 ${it + 1} 次）")
                return@LaunchedEffect
            }
            delay(RETRY_GAP_MS)
        }
        AppLog.w("Focus", "请求焦点失败（连试 $attempts 次，key=$key）")
    }
}

/** 两次焦点重试之间的间隔。30 × 100ms ≈ 最长 3 秒，够冷启动时的测量 + 组合。 */
private const val RETRY_GAP_MS = 100L
