package top.bilitv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import top.bilitv.ui.NavTab
import top.bilitv.ui.home.HomeSection
import top.bilitv.ui.player.PlayerBarButton

/*
 * ════════════════════════════════════════════════════════════════════════════
 * 两个业务侧的选择器 —— 薄薄一层，只负责"把业务表翻译成 PickItem"
 * ════════════════════════════════════════════════════════════════════════════
 *
 * 交互、排序、显隐全部在 [OrderPicker] 里，这里**一行 UI 代码都不写**。
 * 这样"侧栏的胶囊"和"首页分区的胶囊"手感必然一致 ——
 * 不是靠自觉保持同步，而是**它们本来就是同一个东西**。
 */

/**
 * 首页分区选择器。
 *
 * 少爷 2026-09-30 凌晨的要求：
 * > 分区配置明显可以做小很多，**在设置页里的一个设置项下面就可以分多个长方圆角铺开**，
 * > **没必要单独做一页**。BT 做的就简单方便。
 *
 * 原来那版每一项占 108dp 高（一整条横杠），17 项要滚三屏 —— 那是在"把一个列表当页面做"。
 */
@Composable
fun HomeSectionPicker(
    enabledIds: List<String>,
    onChange: (List<String>) -> Unit,
    /** 见 [OrderPicker.pinTail]。**只有同 item 里最后那个选择器才传 true。** */
    pinTail: Boolean = false,
    firstFocus: FocusRequester? = null,
    lastFocus: FocusRequester? = null,
    headUp: FocusRequester? = null,
    tailDown: FocusRequester? = null,
) {
    OrderPicker(
        all = HomeSection.entries.map { PickItem(it.id, stringResource(it.labelRes), pinned = it == HomeSection.RECOMMEND || it == HomeSection.POPULAR) },
        enabledIds = HomeSection.parse(enabledIds).map { it.id },
        onChange = onChange,
        pinTail = pinTail,
        firstFocus = firstFocus, lastFocus = lastFocus, headUp = headUp, tailDown = tailDown,
        // 首页至少留一个分区，否则首页成了空白页（遥控器上等于死键）
        minEnabled = 1,
    )
}

/**
 * 侧栏选择器 —— 少爷第 8 条：「不只首页，侧栏也能配」。
 *
 * ## 为什么「首页」和「设置」是固定项
 *
 * 这两个**不能隐藏**，理由是具体的、不是洁癖：
 *
 * - **设置**：它是唯一能改回来这一项的地方。允许隐藏 = 用户点一下就把自己锁在门外，
 *   只能清数据重来。BT 的配置网格里"设置"同样是不参与排序的固定项。
 * - **首页**：返回键的语义是"非首页 Tab → 回首页"（见 `Nav.kt` 的三档收口）。
 *   首页被隐藏时那条路会把用户送到一个**不在侧栏里**的页面 ——
 *   侧栏没有任何一项是亮的，用户会以为界面坏了。
 *
 * 固定项里又分两档（少爷 2026-09-30 第 3 条）：
 * - **不许隐藏但可以挪**：搜索 / 我的；
 * - **钉死在位置上**：首页（必须是第 1 位）、设置（必须是最后一位）。
 *   选择器里按左右对它们**完全没反应** —— 不是"按了失败"，是**提前把这两个键关掉**。
 */
@Composable
fun NavTabPicker(
    enabledIds: List<String>,
    onChange: (List<String>) -> Unit,
    /** 见 [OrderPicker.pinTail]。**只有同 item 里最后那个选择器才传 true。** */
    pinTail: Boolean = false,
    firstFocus: FocusRequester? = null,
    lastFocus: FocusRequester? = null,
    headUp: FocusRequester? = null,
    tailDown: FocusRequester? = null,
) {
    OrderPicker(
        all = NavTab.entries.map {
            PickItem(
                id = it.name,
                label = stringResource(it.labelRes),
                // 不许隐藏：首页 / 搜索 / 我的 / 设置（判据见 `NavTab.PINNED`）
                pinned = it in NavTab.PINNED,
                // 不许移动：首页钉首位、设置钉末位（少爷第 3 条原话）
                locked = it == NavTab.HEAD || it == NavTab.TAIL,
            )
        },
        enabledIds = enabledIds,
        onChange = onChange,
        pinTail = pinTail,
        firstFocus = firstFocus, lastFocus = lastFocus, headUp = headUp, tailDown = tailDown,
        minEnabled = NavTab.PINNED.size,
    )
}

/**
 * 播放器控制栏按钮选择器 —— 少爷第 8 条的**第三处**（首页 ✅ / 侧栏 ✅ / 控制栏）。
 *
 * ## 它原来在「高级模式 → 播放细节」里，现在搬到「个性化」一节（和另两处并排）
 *
 * 搬家的原因**不是审美，是它在那儿会把页面锁死**：
 * 页中间的选择器走到末尾出不去（根因见 [OrderPicker.pinTail]），
 * 实测卡在它末尾之后，「线路策略 / 容错 / 存储与诊断 / 关于」全够不到。
 *
 * 三处并排还有个附带好处：**"哪些栏目 / 按钮显示"集中在一处**，
 * 用户不用跑进"高级模式"去找第三处。
 *
 * ## 固定项
 *
 * 「播放/暂停」不可隐藏，其余按钮可以排序和隐藏。
 */
@Composable
fun PlayerBarPicker(
    enabledIds: List<String>,
    onChange: (List<String>) -> Unit,
    /** 见 [OrderPicker.pinTail]。它在同一个 item 里是**最后一个**，所以传 true。 */
    pinTail: Boolean = false,
    firstFocus: FocusRequester? = null,
    lastFocus: FocusRequester? = null,
    headUp: FocusRequester? = null,
    tailDown: FocusRequester? = null,
) {
    OrderPicker(
        all = PlayerBarButton.entries.map { PickItem(it.id, stringResource(it.labelRes), pinned = it.pinned) },
        enabledIds = enabledIds,
        onChange = onChange,
        pinTail = pinTail,
        firstFocus = firstFocus, lastFocus = lastFocus, headUp = headUp, tailDown = tailDown,
        minEnabled = PlayerBarButton.entries.count { it.pinned },
    )
}
