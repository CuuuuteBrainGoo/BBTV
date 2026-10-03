package top.bilitv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * 「刷新当前页」的总线。
 *
 * ## 为什么需要它（而不是让 AppShell 直接调页面）
 *
 * 遥控器菜单键是在 [AppShell]（外壳）那一层截住的 —— 但"刷新"是**每个页面自己的事**
 * （首页要重新推荐一批、影视要重新拉列表、动态要翻新的）。外壳**不该知道**当前页怎么刷新，
 * 否则每加一个页面就要在外壳里加一个分支，那是典型的反向依赖。
 *
 * 所以外壳只负责"喊一声"，谁在被显示谁自己听着。
 *
 * ## 为什么它天然只影响当前页
 *
 * 一级页面之间是 `when (tab)` 互斥的 —— **没被显示的那一页根本不在组合里**，
 * 它的 `LaunchedEffect` 不会跑。所以"广播"实际只会打到当前那一页，不需要额外判断。
 *
 * ⚠️ **只在"一级页面"能被触发**：菜单键的拦截写在 [AppShell] 里，
 * 而二级页面（详情/播放/关注/登录）会把 AppShell **整个从组合里摘掉**，
 * 所以那时候按菜单键根本不会走到这里 —— 这正是"一级拦、二级放行"的实现方式。
 * 用 `tick` 而不是布尔：连续按两次菜单键要能刷新两次，布尔会被去重掉。
 */
object RefreshBus {

    /** 每次请求刷新就 +1。页面用 `LaunchedEffect(tick)` 观察它。 */
    var tick by mutableIntStateOf(0)
        private set

    /** 请求当前页刷新。 */
    fun request() {
        tick++
    }
}

/** Entering a page does not replay an earlier page's request. Only visible consumers run. */
@Composable
internal fun OnRefreshRequest(onRefresh: () -> Unit) {
    var handled by remember { mutableIntStateOf(RefreshBus.tick) }
    val latest by rememberUpdatedState(onRefresh)
    LaunchedEffect(RefreshBus.tick) {
        if (handled != RefreshBus.tick) { handled = RefreshBus.tick; latest() }
    }
}
