package top.bilitv.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType

/** 只在可见一级页面按分钟更新；后台、播放中和关闭开关后均没有计时任务。 */
@Composable
internal fun InterfaceClock(modifier: Modifier) {
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val listener = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(listener)
        onDispose { owner.lifecycle.removeObserver(listener) }
    }
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var time by remember { mutableStateOf(formatter.format(Date())) }
    LaunchedEffect(resumed) {
        if (resumed) while (true) {
            time = formatter.format(Date())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    Text(time, color = AppTheme.current.textSecondary, style = TextStyle(fontSize = AppType.Meta), modifier = modifier)
}
