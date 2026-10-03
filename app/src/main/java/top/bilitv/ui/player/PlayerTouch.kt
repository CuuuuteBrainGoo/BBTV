package top.bilitv.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import top.bilitv.ui.components.formatDuration
import top.bilitv.ui.theme.AppTheme
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun touchSeekTarget(origin: Long, dx: Float, width: Float, duration: Long): Long =
    if (duration <= 0 || width <= 0) origin else (origin + dx / width * duration.coerceAtMost(120_000L)).toLong().coerceIn(0, duration)

/** Wait for a clear axis instead of changing brightness on a diagonal seek. */
internal fun touchDragAxis(dx: Float, dy: Float): Int = when {
    abs(dx) > abs(dy) * 1.3f -> 1
    abs(dy) > abs(dx) * 1.3f -> 2
    else -> 0
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Separate touch layer: gestures never synthesize remote-control key events. */
@Composable
internal fun PlayerTouch(vm: PlayerViewModel, modifier: Modifier, tap: () -> Unit,
    target: (Long?) -> Unit, dragging: (Boolean) -> Unit, enabled: Boolean = true) {
    val view = LocalView.current
    val activity = LocalContext.current.activity()
    val audio = LocalContext.current.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val originalBrightness = remember(activity) { activity?.window?.attributes?.screenBrightness }
    var hint by remember { mutableStateOf<String?>(null) }
    var levelHint by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var verticalDrag by remember { mutableStateOf(false) }
    DisposableEffect(vm) {
        onDispose {
            vm.finishTouchSeek(cancel = true); vm.endTouchBoost()
            activity?.window?.let { window -> window.attributes = window.attributes.apply {
                screenBrightness = originalBrightness ?: -1f
            } }
        }
    }
    LaunchedEffect(hint) { if (hint != null) { kotlinx.coroutines.delay(1200); hint = null } }
    LaunchedEffect(levelHint, verticalDrag) {
        if (levelHint != null && !verticalDrag) { kotlinx.coroutines.delay(1000); levelHint = null }
    }
    val latestTap by rememberUpdatedState(tap)
    Box(modifier
        .pointerInput(vm, enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(onTap = { latestTap() }, onDoubleTap = if (vm.danmakuSettings.touchDoubleTap) ({ vm.togglePlay() }) else null,
                onLongPress = {
                    if (vm.danmakuSettings.touchBoost && vm.beginTouchBoost()) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        hint = "临时 2 倍速"
                    }
                }, onPress = {
                    try { awaitRelease() } finally { vm.endTouchBoost() }
                })
        }
        .pointerInput(vm, enabled) {
            if (!enabled) return@pointerInput
            var origin = 0L; var start = Offset.Zero; var delta = Offset.Zero
            var mode = 0; var volume = 0; var appliedVolume = 0; var brightness = .5f
            detectDragGestures(onDragStart = {
                start = it; delta = Offset.Zero; mode = 0; origin = vm.positionMs; levelHint = null
                vm.endTouchBoost()
                // Leave native back/navigation edge swipes alone.
                val edge = 16.dp.toPx()
                if (it.x < edge || it.x > size.width - edge || it.y < edge || it.y > size.height - edge) mode = -1
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                appliedVolume = volume
                brightness = activity?.window?.attributes?.screenBrightness?.takeIf { b -> b >= 0 }
                    ?: (Settings.System.getInt(view.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(.01f, 1f)
            }, onDrag = { change, amount ->
                if (mode == -1) return@detectDragGestures
                change.consume(); delta += amount
                if (mode == 0) {
                    val axis = touchDragAxis(delta.x, delta.y)
                    if (axis == 0) return@detectDragGestures
                    mode = if (axis == 1) 1 else if (start.x < size.width / 2f) 2 else 3
                    val settings = vm.danmakuSettings
                    if ((mode == 1 && !settings.touchSeek) || (mode == 2 && !settings.touchBrightness) ||
                        (mode == 3 && !settings.touchVolume)) { mode = -1; return@detectDragGestures }
                    verticalDrag = mode != 1
                    if (mode == 1 && !vm.live) { vm.beginTouchSeek(); dragging(true) }
                }
                when (mode) {
                    1 -> if (!vm.live) {
                        val next = touchSeekTarget(origin, delta.x, size.width.toFloat(), vm.durationMs)
                        target(next); vm.previewTouchSeek(next)
                    }
                    2 -> {
                        val next = (brightness - delta.y / size.height.coerceAtLeast(1)).coerceIn(.01f, 1f)
                        activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = next } }
                        levelHint = "亮度" to next
                    }
                    3 -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val next = (volume - delta.y / size.height.coerceAtLeast(1) * max).roundToInt().coerceIn(0, max)
                        if (next != appliedVolume) {
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0); appliedVolume = next
                        }
                        levelHint = "音量" to (next.toFloat() / max.coerceAtLeast(1))
                    }
                }
            }, onDragEnd = { vm.finishTouchSeek(); dragging(false); verticalDrag = false },
                onDragCancel = { vm.finishTouchSeek(cancel = true); target(null); dragging(false); verticalDrag = false; levelHint = null })
        }) {
        hint?.let { Text(it, color = AppTheme.current.primary, modifier = Modifier.padding(24.dp)) }
        levelHint?.let { (label, value) ->
            val theme = AppTheme.current
            Column(Modifier.align(Alignment.Center).zIndex(2f).width(200.dp)
                .background(theme.surface.copy(alpha = .9f), RoundedCornerShape(12.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, color = theme.textPrimary)
                    Text("${(value * 100).roundToInt()}%", color = theme.primary)
                }
                LinearProgressIndicator(progress = { value }, color = theme.primary,
                    trackColor = theme.surfaceHigh, modifier = Modifier.fillMaxWidth().height(6.dp))
            }
        }
    }
}

@Composable
internal fun TouchProgress(vm: PlayerViewModel) {
    var target by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("${formatDuration(((target ?: vm.positionMs) / 1000).toInt())} / ${formatDuration((vm.durationMs / 1000).toInt())}",
            color = AppTheme.current.textPrimary)
        Slider(value = progressFraction(target ?: vm.positionMs, vm.durationMs),
            onValueChange = {
                if (target == null) vm.beginTouchSeek()
                target = (it * vm.durationMs).toLong(); vm.previewTouchSeek(target!!)
            }, onValueChangeFinished = { vm.finishTouchSeek(); target = null },
            enabled = vm.durationMs > 0, modifier = Modifier.fillMaxWidth().height(36.dp).onPreviewKeyEvent { event ->
                if (event.key !in listOf(Key.DirectionLeft, Key.DirectionRight)) false
                else {
                    if (event.type == KeyEventType.KeyDown) {
                        target = ((target ?: vm.positionMs) + if (event.key == Key.DirectionLeft) -vm.seekStepMs else vm.seekStepMs)
                            .coerceIn(0, vm.durationMs.coerceAtLeast(0))
                    } else if (event.type == KeyEventType.KeyUp) {
                        target?.let(vm::seekTo); target = null
                    }
                    true
                }
            })
    }
    DisposableEffect(vm) { onDispose { vm.finishTouchSeek(cancel = true) } }
}
