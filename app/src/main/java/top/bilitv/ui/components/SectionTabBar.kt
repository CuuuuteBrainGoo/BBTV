package top.bilitv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.bilitv.ui.theme.AppTheme
import top.bilitv.data.settings.StartupFocus

/**
 * 内容区顶部的子标签栏（推荐 / 热门 / 每周必看 ……）。
 *
 * ## 为什么要这个东西
 *
 * 之前"热门"是侧栏里的一个一级入口。照 BT 的做法，**一级入口是"内容板块"
 * （首页 / 影视 / 直播 / 我的），板块内部的"内容种类"是子标签**。
 * 好处是很实在的：
 * - 侧栏只有 6~9 项，不用为了加一个内容源就往侧栏塞一行；
 * - 子标签之间切换**不用换页**，内容区原地换数据，焦点也不会被重置；
 * - 这正好对上 B 站的接口结构 —— 首页下面的推荐/热门/每周必看本来就是同一层的。
 *
 * ## 三个状态不能混
 *
 * - **选中**：`navSelectedFill` 底 + `navSelectedText` 字（这**一对**颜色由皮肤给，
 *   组件不自己拼 —— 影院皮肤是"灰底粉字"，经典皮肤是"粉底白字"）
 * - **焦点**：`focusRing` 描边 + `focusFill` 垫色（和卡片一致）
 * - **常态**：无底、`textSecondary` 字
 *
 * 选中和焦点会同时成立（焦点停在当前选中的标签上），这时两个视觉都要在：
 * 描边表示"你在这儿"，垫色和字色表示"这是选中的那个"。
 *
 * @param labels 标签文字，顺序即显示顺序
 * @param selectedIndex 当前选中项下标
 * @param onSelect 选中回调。**点当前项也会触发** —— 调用方自己区分"切页"和"刷新"。
 * @param firstFocusRequester 非空时挂到第一个标签上（给"进页面就把焦点放这儿"用）
 *
 * ## 2026-09-29 改成 LazyRow（原来是个固定 Row）
 *
 * 起因是直播页：它要显示「推荐 + 12 个大区」共 13 个标签，
 * 固定 Row 在 1080dp 逻辑宽度下会**溢出屏幕**，末尾几个标签直接看不见、也点不到。
 *
 * 换成 [LazyRow] 之后多出来一件很重要的行为：**焦点移到看不见的标签上时会自动滚过去**。
 * 这在电视上是刚需 —— 遥控器只会往右按，用户没法用别的方式把内容"拉"过来。
 * 对现有两个调用点（首页 3 个、影视 6 个）没有副作用：内容放得下就不滚。
 */
@Composable
fun SectionTabBar(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    firstFocusRequester: FocusRequester? = null,
    contentFocusRequester: FocusRequester? = null,
) {
    val theme = AppTheme.current
    val navigation = LocalNavigationFocus.current
    val startupRequester = remember { FocusRequester() }
    DisposableEffect(navigation, contentFocusRequester) {
        navigation?.contentTarget = contentFocusRequester
        onDispose { if (navigation?.contentTarget === contentFocusRequester) navigation?.contentTarget = null }
    }
    DisposableEffect(navigation) { onDispose { navigation?.tabsFocused = false } }
    val shape = RoundedCornerShape(CHIP_HEIGHT / 2)

    LazyRow(
        modifier = modifier.onFocusChanged { navigation?.tabsFocused = it.hasFocus }
            .height(theme.tabBarHeight * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)),
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(labels) { index, label ->
            val selected = index == selectedIndex
            var focused by remember(label) { mutableStateOf(false) }

            val background = when {
                focused -> theme.focusFill
                selected -> theme.navSelectedFill
                else -> Color.Transparent
            }
            val textColor = when {
                focused && selected -> theme.navSelectedText
                focused -> theme.textPrimary
                selected -> theme.navSelectedText
                else -> theme.textSecondary
            }

            Box(
                modifier = Modifier
                    .focusProperties {
                        if (contentFocusRequester != null) down = contentFocusRequester
                    }
                    .then(
                        if (selected) Modifier.focusRequester(startupRequester) else Modifier
                    )
                    .then(
                        if (index == 0 && firstFocusRequester != null) {
                            Modifier.focusRequester(firstFocusRequester)
                        } else Modifier
                    )
                    .observeFocus {
                        focused = it
                        if (it && navigation?.confirmTabs == false && !selected) onSelect(index)
                    }
                    .clip(shape)
                    .background(background)
                    .border(
                        width = if (focused) theme.focusBorderWidth else 0.dp,
                        color = if (focused) theme.focusRing else Color.Transparent,
                        shape = shape,
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = label + if (selected) "，当前选中" else ""
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        /*
                         * ★ 2026-09-30 改：**点当前项也回调**（原来这里写的是 `if (!selected)`）。
                         *
                         * 少爷要求「点当前选项卡 = 刷新（重新推荐一批）」——
                         * 而原来那句 `if (!selected)` 会把"点当前项"整个吞掉，
                         * 调用方**永远收不到这次点击**，刷新分支等于死代码。
                         *
                         * 这个 bug 的隐蔽之处：日志、编译、单测全都没有异常，
                         * 只有真按下去才发现没反应（`docs/99` §C 那类"假成功"的又一例）。
                         *
                         * 现在"要不要忽略重复点击"这件事交给调用方 ——
                         * 它更清楚这次点击的语义是"切页"还是"刷新"。
                         */
                        // 切换和刷新都保留标签焦点；内容区由 DOWN 显式进入。
                        onSelect(index)
                    }
                    .padding(horizontal = CHIP_PADDING_H),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = TextStyle(fontSize = TAB_TEXT_SIZE, fontWeight = FontWeight.Medium),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    RequestFocusOnAppear(startupRequester, navigation?.pendingStartup == StartupFocus.TABS,
        role = StartupFocus.TABS)
}

/** 标签胶囊高度。BT 实测 61px @2x ≈ 30.5dp。 */
private val CHIP_HEIGHT = 30.dp
private val CHIP_GAP = 6.dp
private val CHIP_PADDING_H = 14.dp

/** 标签字号。BT 实测 30px @2x = 15sp。 */
private val TAB_TEXT_SIZE = 15.sp
