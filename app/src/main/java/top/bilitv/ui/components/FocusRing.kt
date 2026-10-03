package top.bilitv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import top.bilitv.ui.theme.AppTheme

/**
 * 焦点基元 —— 全项目**唯一**的"焦点长什么样"的地方。
 *
 * ## 为什么有这个东西
 *
 * 2026-09-29 接手审查（`docs/audit/A9` §7.3）发现：「焦点态」这套修饰符链
 * （`onFocusChanged` → `clip` → `background` → `border` → `semantics` → `clickable`）
 * 在 **8 个文件里被手抄了 8 遍**，每遍略有出入：
 *
 * | 文件 | 差异 |
 * |---|---|
 * | `FeedCard` | 有 `zIndex`、有 `FOCUS_RING_INSET` 内缩、原本**不放大**（密排网格；2026-09-29 起改随皮肤 `focusScale`） |
 * | `DynamicScreen` | 垫色+描边，padding 8dp |
 * | `SearchScreen` | 垫色+描边，无 inset |
 * | `PgcScreen` 海报 | 垫色+描边 + `zIndex` + padding 3dp |
 * | `PgcDetailScreen` 剧集格 | 同上 |
 * | `FollowScreen` | 垫色+描边，padding vertical 10dp |
 * | `Controls.FilledActionButton` | **反过来**：底色提亮 + 亮色描边（本身是实心主色） |
 * | `AppShell.NavItem` | 只变色，不描边、不放大 |
 *
 * 抄 8 遍的代价不是"多打几个字"，是**改视觉要改 8 个地方，而且一定会改漏**。
 * 少爷要的"以后容易维护"，这一条是关键。
 *
 * ## 为什么是修饰符，不是组件
 *
 * 一度想抽一个 `Focusable { }` 容器组件收编全部 8 处。**否决了**：
 * 那 8 处的布局各不相同（有的是 `Column`、有的是 `Row`、有的是 `Box`，
 * 内容区还要各自的 `BoxScope` / `ColumnScope`），做成容器要么丢 scope、
 * 要么塞一堆参数，**抽象成本高于收益**。
 *
 * 改成修饰符扩展后，各处**继续写自己的布局**，只把这 7 行链换成一行 ——
 * 真正的重复被消掉了，各处的差异（内缩 / padding / zIndex / 放大）全部保留。
 *
 * ## 用法
 *
 * ```kotlin
 * Box(
 *     modifier = Modifier
 *         .then(if (req != null) Modifier.focusRequester(req) else Modifier)
 *         .focusRing(contentDescription = desc) { onClick() }
 *         .padding(FOCUS_RING_INSET),
 * ) { ... }
 * ```
 *
 * ## ⚠️ 三条硬约束（错了都不报错，只会静默失效）
 *
 * 1. **`focusRequester` 必须放在 `focusRing` 之前**。requester 认的是"它后面最近的
 *    那个焦点目标"，而焦点目标是 `focusRing` 里的 `clickable`。放后面就挂不上，
 *    `requestFocus()` 会一直抛异常（2026-09-29 `FeedCard` 真踩过）。
 * 2. **`onFocusChanged` 必须在最外层**（本函数已保证）。它观察的是"我所在节点及其
 *    子树有没有焦点"，放内层就看不见 `clickable` 自己的焦点。
 * 3. **`zIndex` 和放大默认关闭**。只有"放大"或"描边会压到邻居"时才开
 *    `elevateOnFocus`；密排网格里乱开会造成相邻卡片闪烁。
 *
 * @param contentDescription 语义名。给读屏和 `ui_probe` 排查用（`docs/26`）。
 *   传 null 就不挂语义节点。
 * @param shape 圆角。默认取当前皮肤的 `cardCorner`。
 * @param focusedFill 焦点态垫色。默认取皮肤 `focusFill`（半透明主色）。
 *   传 `Color.Transparent` 可以关掉垫色（`NavItem` 那种只要描边/变色的场景）。
 * @param restFill 未聚焦时的底色。默认透明（大多数卡片是"缩略图/内容直贴页面"）。
 *   少数控件常态就有一块底（`SearchScreen` 的热搜 chip 是 `surface`），传它。
 * @param focusedBorder 焦点态描边色。默认取皮肤 `focusRing`。
 * @param borderWidth 描边粗细。默认取皮肤 `focusBorderWidth`。
 * @param elevateOnFocus 焦点时抬到最上层。默认关闭。
 * @param scaleOnFocus 焦点时放大倍率。默认 `null` = **读皮肤 `focusScale`**
 *   （影院 / 经典均为 1.03，见 `docs/31` §5.1）。显式传 `1f` 可关掉放大。
 *   ⚠️ 2026-09-29 前这里写死 `1f`，与用 `TvCard` 的卡片（读皮肤）不一致；
 *   现在两者都读皮肤，全项目焦点放大只有一个出处。
 * @param onClick 点击 / 遥控器确定键的回调。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.focusRing(
    contentDescription: String? = null,
    shape: Shape? = null,
    focusedFill: Color? = null,
    restFill: Color = Color.Transparent,
    focusedBorder: Color? = null,
    borderWidth: Dp? = null,
    elevateOnFocus: Boolean = false,
    /**
     * 焦点时的放大倍率。
     *
     * 传 `null`（默认）= **用皮肤里的 [top.bilitv.ui.theme.BiliTheme.focusScale]**。
     *
     * 2026-09-29 改：原来这里写死 `1f`，等于"皮肤的 `focusScale` 字段没人读" ——
     * 那正是 `docs/13` 皮肤系统明令禁止的"数值散在多处"。
     * 现在跟着皮肤走，改一处（`Theme.kt`）= 全项目所有焦点态一起变。
     */
    scaleOnFocus: Float? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val theme = AppTheme.current
    val ringShape = shape ?: RoundedCornerShape(theme.cardCorner)
    val ringFill = focusedFill ?: theme.focusFill
    val ringBorder = focusedBorder ?: theme.focusRing
    val ringWidth = borderWidth ?: theme.focusBorderWidth
    val ringScale = scaleOnFocus ?: theme.focusScale

    var focused by remember { mutableStateOf(false) }

    val fill = if (focused) ringFill else restFill
    val border = if (focused) ringBorder else Color.Transparent

    var modifier = this
        .onFocusChanged { focused = it.isFocused }
        .clip(ringShape)
        .background(fill)
        .border(
            width = if (focused) ringWidth else 0.dp,
            color = border,
            shape = ringShape,
        )

    if (elevateOnFocus || ringScale != 1f) {
        modifier = modifier
            // 放大必须同时抬层，否则放大出去的那一圈会被相邻卡片盖住
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = if (focused) ringScale else 1f
                scaleY = if (focused) ringScale else 1f
            }
    }

    val interaction = remember { MutableInteractionSource() }
    val click = touchClearFocus(onClick)
    val view = LocalView.current
    val labelled = modifier.focusSemantics(contentDescription)
    return if (onLongClick == null) labelled.clickable(interactionSource = interaction, indication = null, onClick = click)
    else labelled.combinedClickable(interactionSource = interaction, indication = null, onClick = click, onLongClick = {
        if (view.isInTouchMode) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongClick()
    })
}

/**
 * 把 [onClick] 包一层：**触摸模式下点完把焦点清掉**。
 *
 * ## 为什么需要（少爷 2026-09-30 原话：「手机上点完卡片不需要焦点框了」）
 *
 * 遥控器和触摸是两套交互，但我们的卡片只有一套视觉：
 *
 * | 设备 | 用户怎么选 | 焦点框该不该出现 |
 * |---|---|---|
 * | 电视 | 遥控器方向键 | **必须**，那是"你现在在哪"的唯一指示 |
 * | 手机 | 手指直接点 | **不该**，手指点哪儿哪儿就是选中，再画个框是噪音 |
 *
 * 手机上点完留着框，看起来像"卡住了"或"指错了地方"。
 *
 * ## 为什么用 `isInTouchMode` 而不是记"是不是手机"
 *
 * `View.isInTouchMode()` 是 Android 的**全局交互模式**，不是设备类型：
 * 手机接上键盘/遥控、或按了实体方向键，它立刻变 false —— 焦点链自动恢复。
 * 用"设备型号/有没有触摸屏"来判断反而会把"手机接遥控器"这种情况判错。
 *
 * ## ⚠️ 它读的是**点击那一刻**的值
 *
 * `isInTouchMode` 不是 Compose 的可观察状态（`LocalView` 不提供回调），
 * 所以这里在 lambda **执行时**读，而不是在组合时读 —— 组合时读会把它冻住。
 */
@Composable
fun touchClearFocus(onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    val focusManager: FocusManager = LocalFocusManager.current
    return remember(onClick, view, focusManager) {
        {
            onClick()
            if (view.isInTouchMode) {
                // 卡片自己是链尾节点，force = true 才清得掉
                runCatching { focusManager.clearFocus(force = true) }
            }
        }
    }
}

/**
 * 只做"焦点视觉"，**不接管点击** —— 给已经有自己按键处理的地方用。
 *
 * 目前的唯一调用方是 `SearchScreen` 的搜索输入框：它必须在 `onPreviewKeyEvent` 里
 * 先接住确认键（否则会被 `BasicTextField` 内部吃掉当成"把光标移到点击处"），
 * 所以不能用 [focusRing] 的 `clickable`。
 *
 * 用法（注意 `observeFocus` 要放在 `onPreviewKeyEvent` 之前）：
 *
 * ```kotlin
 * var focused by remember { mutableStateOf(false) }
 * Modifier
 *     .observeFocus { focused = it }
 *     .focusVisuals(focused)
 *     .onPreviewKeyEvent { ... }
 * ```
 */
@Composable
fun Modifier.focusVisuals(
    focused: Boolean,
    shape: Shape? = null,
    focusedFill: Color? = null,
    restFill: Color = Color.Transparent,
    focusedBorder: Color? = null,
    borderWidth: Dp? = null,
): Modifier {
    val theme = AppTheme.current
    val ringShape = shape ?: RoundedCornerShape(theme.cardCorner)
    val ringFill = focusedFill ?: theme.focusFill
    val ringBorder = focusedBorder ?: theme.focusRing
    val ringWidth = borderWidth ?: theme.focusBorderWidth

    val fill = if (focused) ringFill else restFill
    val border = if (focused) ringBorder else Color.Transparent

    return this
        .clip(ringShape)
        .background(fill)
        .border(
            width = if (focused) ringWidth else 0.dp,
            color = border,
            shape = ringShape,
        )
}

/**
 * 把焦点状态同步出来。和 [focusRing] 里的 `onFocusChanged` 同一套机制，只是不自己持有状态。
 *
 * ⚠️ 必须放在 `focusVisuals` / 任何焦点目标**之前**。
 */
fun Modifier.observeFocus(onChange: (Boolean) -> Unit): Modifier =
    this.onFocusChanged { onChange(it.isFocused) }

/** 语义名。`mergeDescendants` 见 `Tv.kt` 里 `semanticLabel` 的说明。 */
internal fun Modifier.focusSemantics(label: String?): Modifier {
    if (label == null) return this
    val text = label
    return this.semantics(mergeDescendants = true) { contentDescription = text }
}
