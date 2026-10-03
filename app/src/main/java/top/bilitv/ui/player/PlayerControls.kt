package top.bilitv.ui.player

import top.bilitv.ui.components.scrollWithScrollbar
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick as accessibilityLongClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import android.view.HapticFeedbackConstants
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.theme.AppTheme
import top.bilitv.util.AppLog

/** 遥控器重复 DOWN 不重新计时，长按已触发后 UP 不再触发短按。 */
internal class OkHold {
    private var start: Long? = null
    private var fired = false
    fun down(now: Long): Boolean {
        if (start != null) return false
        start = now; fired = false
        return true
    }
    fun longPress(now: Long): Boolean {
        val began = start ?: return false
        if (fired || now - began < 1500L) return false
        fired = true
        return true
    }
    fun up(now: Long): Int {
        val began = start ?: return 0
        val action = if (fired) 0 else if (now - began >= 1500L) 2 else 1
        cancel()
        return action
    }
    fun cancel() { start = null; fired = false }
}

@Composable
internal fun PlayerIconButton(
    label: String, icon: ImageVector?, modifier: Modifier = Modifier, active: Boolean = false,
    buttonScale: Float = 1f, value: String? = null, danmakuGlyph: Boolean = false, disabledIcon: Boolean = false,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val configuration = LocalViewConfiguration.current
    val touchConfiguration = remember(configuration) { object : ViewConfiguration by configuration {
        override val longPressTimeoutMillis: Long = 1500L
    } }
    val hold = remember { OkHold() }
    var pending by remember { mutableStateOf<Job?>(null) }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    fun cancelHold() { pending?.cancel(); pending = null; hold.cancel() }
    DisposableEffect(Unit) { onDispose { cancelHold() } }
    CompositionLocalProvider(LocalViewConfiguration provides touchConfiguration) {
    TvCard(onClick = onClick, focusedScale = 1f, contentDescription = label,
        onFocused = { if (!it) cancelHold() else AppLog.i("Focus", "播放按钮：$label") },
        modifier = modifier.height((48.dp * buttonScale).coerceAtLeast(48.dp))
            .width(((if (value != null && !danmakuGlyph) 76.dp * fontScale else 48.dp) * buttonScale).coerceAtLeast(48.dp))
            .pointerInput(onLongClick != null) {
                if (onLongClick != null) detectTapGestures(onTap = { click() }, onLongPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); longClick?.invoke()
                })
            }.semantics {
            if (longClick != null) accessibilityLongClick(if (danmakuGlyph) "弹幕/字幕设置" else "一键三连") { longClick?.invoke(); true }
        }.onPreviewKeyEvent { event ->
            if (longClick == null || event.key !in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) false
            else {
                val now = SystemClock.elapsedRealtime()
                when (event.type) {
                    KeyEventType.KeyDown -> if (hold.down(now)) {
                        pending = scope.launch { delay(1500L); if (hold.longPress(SystemClock.elapsedRealtime())) longClick?.invoke() }
                    }
                    KeyEventType.KeyUp -> {
                        pending?.cancel(); pending = null
                        when (hold.up(now)) { 1 -> click(); 2 -> longClick?.invoke() }
                    }
                }
                true
            }
        },
    ) {
        val tint = if (active) AppTheme.current.primary else Color.White
        when {
            danmakuGlyph -> {
                val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                } }
                val bounds = remember { android.graphics.Rect() }
                Canvas(Modifier.size(30.dp * buttonScale).align(Alignment.Center)) {
                    paint.color = tint.toArgb()
                    paint.textSize = size.minDimension * .74f
                    paint.getTextBounds("弹", 0, 1, bounds)
                    drawContext.canvas.nativeCanvas.drawText("弹", (size.width-bounds.width())/2-bounds.left,
                        (size.height-bounds.height())/2-bounds.top, paint)
                    if (!active) {
                    drawLine(Color.Black, Offset(2.dp.toPx(), size.height-2.dp.toPx()), Offset(size.width-2.dp.toPx(), 2.dp.toPx()), 5.dp.toPx())
                    drawLine(tint, Offset(2.dp.toPx(), size.height-2.dp.toPx()), Offset(size.width-2.dp.toPx(), 2.dp.toPx()), 2.dp.toPx())
                    }
                }
            }
            value != null -> Text(value, color = tint, fontSize = 15.sp * buttonScale, fontWeight = FontWeight.SemiBold,
                maxLines = 1, modifier = Modifier.align(Alignment.Center))
            icon != null -> Box(Modifier.size(30.dp * buttonScale).align(Alignment.Center)) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp * buttonScale).align(Alignment.Center))
                if (disabledIcon) Canvas(Modifier.fillMaxSize()) {
                    val from = Offset(2.dp.toPx(), size.height - 2.dp.toPx())
                    val to = Offset(size.width - 2.dp.toPx(), 2.dp.toPx())
                    drawLine(Color.Black, from, to, 5.dp.toPx())
                    drawLine(tint, from, to, 2.dp.toPx())
                }
            }
        }
    }
    }
}

@Composable
internal fun PlayerActionDialogs(vm: PlayerViewModel, onExit: () -> Unit, onClosed: () -> Unit) {
    val theme = AppTheme.current
    if (vm.previewEnded) {
        val first = remember { FocusRequester() }
        Dialog(onDismissRequest = onExit) {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).background(theme.surface, RoundedCornerShape(12.dp))
                .scrollWithScrollbar().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("试看已结束", color = theme.textPrimary)
                Text("当前接口提供的试看片段已播放完。如需观看完整视频，请在哔哩哔哩确认账号观看权限。", color = theme.textSecondary)
                TvCard(onClick = { vm.replayPreview(); onClosed() }, modifier = Modifier.fillMaxWidth().focusRequester(first),
                    focusedScale = 1f, contentDescription = "重新试看") {
                    Text("重新试看", color = theme.primary, modifier = Modifier.padding(12.dp))
                }
                TvCard(onClick = onExit, modifier = Modifier.fillMaxWidth(), focusedScale = 1f, contentDescription = "返回视频列表") {
                    Text("返回视频列表", color = theme.textPrimary, modifier = Modifier.padding(12.dp))
                }
            }
            RequestFocusOnAppear(first, true)
        }
        return
    }
    vm.resumeChoiceMs?.let { target ->
        val first = remember { FocusRequester() }
        fun choose(fromStart: Boolean) { vm.chooseResume(fromStart); onClosed() }
        Dialog(onDismissRequest = { choose(false) }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).background(theme.surface, RoundedCornerShape(12.dp))
                .scrollWithScrollbar().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上次看到 ${top.bilitv.ui.components.formatDuration((target / 1000).toInt())}", color = theme.textPrimary)
                TvCard(onClick = { choose(false) }, modifier = Modifier.fillMaxWidth().focusRequester(first),
                    focusedScale = 1f, contentDescription = "继续播放") {
                    Text("继续播放", color = theme.primary, modifier = Modifier.padding(12.dp))
                }
                TvCard(onClick = { choose(true) }, modifier = Modifier.fillMaxWidth(),
                    focusedScale = 1f, contentDescription = "从头播放") {
                    Text("从头播放", color = theme.textPrimary, modifier = Modifier.padding(12.dp))
                }
            }
            RequestFocusOnAppear(first, true)
        }
    }
    val folders = vm.favoriteFolders
    if (folders != null) {
        var selected by remember(folders) { mutableStateOf(folders.filter { it.favored == true }.map { it.id }.toSet()) }
        val first = remember { FocusRequester() }
        Dialog(onDismissRequest = { vm.closeFavorites(); onClosed() }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).background(theme.surface, RoundedCornerShape(12.dp))
                .scrollWithScrollbar(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("收藏夹", color = theme.textPrimary)
                if (folders.isEmpty()) Text("暂无收藏夹，请在 B 站创建后重试", color = theme.textSecondary)
                folders.forEachIndexed { i, folder ->
                    TvCard(onClick = { if (!vm.actionBusy) selected = if (folder.id in selected) selected - folder.id else selected + folder.id },
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        focusedScale = 1f, contentDescription = folder.title + if (folder.id in selected) "，已选" else "，未选") {
                        Text((if (folder.id in selected) "✓  " else "    ") + folder.title,
                            color = theme.textPrimary, modifier = Modifier.padding(12.dp))
                    }
                }
                TvCard(onClick = { vm.saveFavorites(selected) }, focusedScale = 1f,
                    modifier = if (folders.isEmpty()) Modifier.focusRequester(first) else Modifier,
                    contentDescription = if (vm.actionBusy) "正在保存收藏" else "保存收藏") {
                    Text(if (vm.actionBusy) "正在保存…" else "保存", color = theme.primary, modifier = Modifier.padding(12.dp))
                }
            }
            RequestFocusOnAppear(first, true)
        }
    }
    if (vm.qualityDialog) {
        val first = remember { FocusRequester() }
        Dialog(onDismissRequest = { vm.qualityDialog = false; onClosed() }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).background(theme.surface, RoundedCornerShape(12.dp))
                .scrollWithScrollbar(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("画质", color = theme.textPrimary)
                vm.availableQualities.forEachIndexed { i, (id, label) ->
                    TvCard(onClick = { vm.changeQuality(id); vm.qualityDialog = false; onClosed() }, focusedScale = 1f,
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        contentDescription = label + if (id == if (vm.live) vm.activeLiveLine?.qn else vm.activeQuality) "，当前" else "") {
                        Text(label + if (id == if (vm.live) vm.activeLiveLine?.qn else vm.activeQuality) "  ✓" else "", color = theme.textPrimary, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            RequestFocusOnAppear(first, true)
        }
    }
    if (vm.liveLineDialog) {
        val first = remember { FocusRequester() }
        Dialog(onDismissRequest = { vm.liveLineDialog = false; onClosed() }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).background(theme.surface, RoundedCornerShape(12.dp))
                .scrollWithScrollbar(rememberScrollState()).padding(16.dp)) {
                Text("直播线路", color = theme.textPrimary)
                vm.liveLines.forEachIndexed { i, (line, index) ->
                    val label = "线路 ${i + 1} · ${line.format.uppercase()} · ${line.codec.uppercase()}"
                    TvCard(onClick = { vm.useLiveLine(line, index); vm.liveLineDialog = false; onClosed() }, focusedScale = 1f,
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier), contentDescription = label) {
                        Text(label, color = theme.textPrimary, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            RequestFocusOnAppear(first, true)
        }
    }
}
