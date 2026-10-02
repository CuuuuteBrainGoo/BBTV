package top.bilitv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/**
 * 屏幕底部的**半透明提示条**（全项目共用一份）。
 *
 * 少爷 2026-09-30 原话：
 * > 在第一级页面上按一次返回不要直接退出 APP，可以做一个 **2 秒的半透明提示**
 * > 「再按一下返回退出 APP」。
 *
 * 这个样式原来是播放页的私有实现（`PlayerScreen` 里的 `skipHint` 用的那个），
 * 现在提到 `components` 里，播放页/一级页/将来别处共用一份 ——
 * **不要各写一遍**，否则"半透明到底是 0xCC 还是 0xB3"会随着改动漂移。
 */
@Composable
fun AppToast(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    alpha: Float = 1f,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xCC1A1A22)),
    ) {
        Text(
            text = text,
            color = color.copy(alpha = alpha),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

/**
 * 「再按一次才退出」的两秒确认状态。
 *
 * ## 为什么需要它（少爷反馈 4 / 5 / 6）
 *
 * 电视遥控器的返回键很容易连按两下，而"按错一下就退出播放/退出 App"的代价很高
 * （播放页退出要重新找进度、App 退出要重新冷启动 4.8 秒）。
 * 所以第一次按只**提示**，两秒内再按才真的执行。
 *
 * ## ⛔ 两秒的计时**必须在"再次置位时重新开始"**
 *
 * 用 [token] 而不是只看 [message] 是否为空：
 * 用户在 1.9 秒时又按了一下（`arm` 第二次），如果计时器不重启，
 * 新提示会在 0.1 秒后就被清掉 —— 看着像"提示一闪而过"。
 * 把 `token` 放进 `LaunchedEffect` 的 key，每次置位都会取消上一个计时器、重新计时。
 */
class BackConfirmState {
    var message by mutableStateOf<String?>(null)
        private set

    /** 每次置位都 +1，用作 `LaunchedEffect` 的 key（见类注释里那条坑）。 */
    var token by mutableIntStateOf(0)
        private set

    /** 显示提示并开始两秒倒计时。 */
    fun arm(text: String) {
        message = text
        token++
    }

    fun clear() {
        message = null
    }
}

/**
 * 记住一份 [BackConfirmState]，并自动在**两秒**后把提示清掉。
 *
 * 用法（`BackHandler` 里）：
 * ```kotlin
 * val confirm = rememberBackConfirm()
 * BackHandler {
 *     if (confirm.message != null) { 真的退出() }   // 两秒内第二次按
 *     else confirm.arm("再按一下返回退出 APP")        // 第一次按只提示
 * }
 * ```
 */
@Composable
fun rememberBackConfirm(): BackConfirmState {
    val state = remember { BackConfirmState() }
    val token = state.token
    val shown = state.message
    LaunchedEffect(token, shown) {
        if (shown != null) {
            delay(BACK_CONFIRM_MS)
            state.clear()
        }
    }
    return state
}

/** 确认窗口：少爷要求的「2 秒」。 */
const val BACK_CONFIRM_MS = 2000L
